// fastbuild is a thin wrapper around arduino-cli that fixes the two things that make
// iterative Arduino builds slow:
//
//  1. arduino-cli caches compiled core/library objects, but only within a given
//     --build-path. Left to its default it uses a fresh random temp directory every
//     run, so that cache gets thrown away constantly. fastbuild points --build-path at
//     a fixed, persistent folder keyed by (sketch path + FQBN), so arduino-cli's own
//     incremental logic actually survives across builds. (Older arduino-cli versions
//     had a separate --build-cache-path flag for this; it has since been deprecated
//     and folded into --build-path alone.)
//
//  2. fastbuild hashes the sketch's own source files (the .ino plus any local .cpp/.h
//     files) and skips invoking arduino-cli entirely if nothing changed since the last
//     successful build - the existing compiled output is still valid.
//
// Usage:
//
//	fastbuild <path-to-config>
//
// Config file format: simple key=value lines, "|"-delimited for list values. See
// fastbuild.config.example for the format.
//
// No third-party dependencies - standard library only.
package main

import (
	"bufio"
	"crypto/sha256"
	"encoding/hex"
	"flag"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

const stateFileName = "fastbuild-state.txt"

// config holds everything read from the user's config file.
type config struct {
	arduinoCLI             string                 // path to the arduino-cli binary
	configFile             string                 // optional path to an arduino-cli.yaml to pass via --config-file
	sketch                 string                 // path to the .ino file
	fqbn                   string                 // fully qualified board name
	cacheRoot              string                 // base folder for persistent per-project build/cache dirs
	verbose                bool                   // pass --verbose through to arduino-cli, to see per-file compile/skip lines
	hashLibraryHeaders     bool                   // scan #includes and hash resolved library/core headers into the cache key
	hashToolchain          bool                   // fold installed toolchain/tool version folder names into the cache key
	refreshDepsIndex       bool                   // force a full rebuild of the cached header index, bypassing signature/age checks entirely
	depsIndexMaxAge        time.Duration          // how old a signature-valid cached header index can get before it's considered stale; 0 disables this check
	staleDepsIndexDecision staleIndexDecision     // what to do when the cache is stale-by-age: ask (default), always refresh, or never refresh
	showStats              bool                   // print a FastBuild Statistics summary after each run
	force                  bool                   // bypass fastbuild's own skip-if-unchanged check, always ask arduino-cli to build
	clean                  bool                   // wipe the persistent build folder before building, for a full rebuild from scratch
	upload                 bool                   // upload the resulting binary after a successful build or cache-hit skip
	port                   string                 // serial port to upload to, e.g. COM3 (required if upload is true)
	export                 bool                   // copy the resulting binary into the sketch's own folder after building or skipping
	exportConflict         exportConflictDecision // what to do if a file already exists at the export destination
	depsMode               string                 // "regex" (default) or "depfile" (alias: "gcc") - see gcc_deps.go
	gccInjectMMD           bool                   // manually inject -MMD via build.extra_flags instead of relying on arduino-cli's recipe already including it - only relevant when depsMode="depfile"
	platformVersion        string                 // pin an exact installed platform version (e.g. "3.0.2") instead of auto-selecting the highest installed one - see resolvePlatformDir
	jsonOutput             bool                   // emit fastbuild's own status lines as JSON Lines instead of plain text - see logging.go
	saveLog                bool                   // save this run's output to a timestamped file - see logging.go
	logDir                 string                 // where -save-log writes to; default <project>/logs if unset
	buildProps             []string
}

// buildState is the small persisted record of the last successful build for a project.
type buildState struct {
	lastHash           string
	lastBuildOk        bool
	outputFile         string
	lastCompileSeconds float64 // wall-clock time the last real (non-cache-hit) compile took; used to estimate time saved on a cache hit
	lastAttemptedHash  string  // hash of the most recent attempt regardless of outcome; lets quiet/watch mode tell "still the same known failure" from "something new to try"
}

// buildStats collects the numbers shown by the "FastBuild Statistics" summary
// (-stats / showStats=true). Populated incrementally as run() progresses.
type buildStats struct {
	headerIndexEnabled bool          // true if dependency-aware hashing was actually attempted this run, in either mode
	headerIndexCached  bool          // (regex mode only) true if the index was reused from cache, false if rebuilt
	depsSource         string        // "depfile", "regex", or "regex (depfile bootstrap)" - see depHashStats in deps.go
	sourceFileCount    int           // number of sketch-local source files hashed
	depFileCount       int           // number of library/core header files hashed (0 if dependency hashing is off)
	hashDuration       time.Duration // time spent computing the cache-key hash
	cacheHit           bool          // true if the compile was skipped entirely
	compileDuration    time.Duration // wall-clock compile time (only set when a real compile ran)
	estimatedSaved     time.Duration // on a cache hit, the last known real compile duration - the time this run avoided spending
}

// print writes the "FastBuild Statistics" summary to stdout. People like seeing why a
// build was fast (or wasn't) - this makes the cache behavior visible instead of the
// build just silently being quick or slow.
//
// The dependency-source line used to always say "Header index: cached/rebuilt", even
// in -deps-mode=gcc where no header index is consulted at all (it uses the compiler's
// own harvested .d file instead) - that line now reflects which mechanism actually
// supplied the dependency list this run, rather than reusing regex-mode's wording for
// a conceptually different thing.
func (s buildStats) print() {
	fmt.Println()
	fmt.Println("FastBuild Statistics")
	switch {
	case !s.headerIndexEnabled:
		fmt.Println("  Dependencies:      disabled (hashLibraryHeaders=false or no configFile set)")
	case s.depsSource == "depfile":
		fmt.Println("  Dependencies:      depfile (.d file - compiler-verified, handles #ifdef correctly)")
	case s.depsSource == "regex (depfile bootstrap)":
		fmt.Println("  Dependencies:      regex scanner (deps-mode=depfile but no .d file harvested yet - bootstrapping)")
	case s.headerIndexCached:
		fmt.Println("  Dependencies:      regex scanner (header index cached)")
	default:
		fmt.Println("  Dependencies:      regex scanner (header index rebuilt)")
	}
	fmt.Println("  Source files:     ", s.sourceFileCount)
	fmt.Println("  Dependency files: ", s.depFileCount)
	fmt.Println("  Hash time:        ", s.hashDuration.Round(time.Millisecond))
	if s.cacheHit {
		fmt.Println("  Compile:           skipped (cache hit)")
		if s.estimatedSaved > 0 {
			fmt.Println("  Saved (est.):     ", s.estimatedSaved.Round(time.Millisecond))
		}
	} else {
		fmt.Println("  Compile:          ", s.compileDuration.Round(time.Millisecond))
	}
}

// printFlagHelp prints one flag's name and description with consistent alignment,
// used by the categorized flag.Usage function below instead of the flat,
// alphabetical listing flag.PrintDefaults() would otherwise produce.
func printFlagHelp(name, description string) {
	fmt.Printf("  -%-32s %s\n", name, description)
}

func main() {
	// Printed first, always, on its own stable line - a GUI (or any wrapper) driving
	// fastbuild as a subprocess can parse this to support "cancel mid-build" properly.
	// Killing fastbuild.exe alone does NOT kill its arduino-cli.exe child (or that
	// child's own compiler subprocesses) on Windows - a wrapper needs the PID to run
	// `taskkill /F /T /PID <pid>`, which kills the whole process tree, not just this
	// one process.
	fmt.Println("fastbuild PID:", os.Getpid())

	noDeps := flag.Bool("no-deps", false, "disable library/core header dependency hashing (overrides config)")
	noToolchain := flag.Bool("no-toolchain", false, "disable toolchain version fingerprinting (overrides config)")
	refreshDepsIndex := flag.Bool("refresh-deps-index", false, "force a full rebuild of the cached library header index right now, bypassing signature/age checks entirely")
	assumeYesStaleDeps := flag.Bool("assume-yes-stale-deps", false, "when the cached header index is stale by age, rebuild it automatically instead of asking")
	skipStaleDepsRefresh := flag.Bool("skip-stale-deps-refresh", false, "when the cached header index is stale by age, keep using it without asking - for when you're in a hurry and don't want to be prompted")
	showStats := flag.Bool("stats", false, "print a FastBuild Statistics summary after the build (overrides config)")
	force := flag.Bool("force", false, "bypass fastbuild's skip-if-unchanged check and always ask arduino-cli to build")
	clean := flag.Bool("clean", false, "wipe the persistent build folder first, for a full rebuild from scratch")
	upload := flag.Bool("upload", false, "upload the resulting binary after building (or after a cache-hit skip)")
	port := flag.String("port", "", "serial port to upload to, e.g. COM3 (overrides config, required with -upload)")
	export := flag.Bool("export", false, "copy the resulting binary into the sketch's own folder after building or skipping")
	exportConflict := flag.String("export-conflict", "", "what to do if the export destination already exists: 'ask' (default), 'overwrite', or 'rename'")
	depsMode := flag.String("deps-mode", "", "how to detect library/header dependencies: 'regex' (default, #include scanner) or 'depfile' (use the compiler's own generated .d dependency file - more accurate, handles #ifdef-guarded includes correctly, but needs one successful compile first to bootstrap; 'gcc' also accepted as an alias)")
	gccInjectMMD := flag.Bool("gcc-inject-mmd", false, "with -deps-mode=gcc, manually add -MMD via build.extra_flags instead of relying on arduino-cli's recipe already including it - use this if a board's .d file never shows up on its own (overrides config)")
	platformVersion := flag.String("platform-version", "", "pin an exact installed platform version (e.g. 3.0.2) instead of auto-selecting the highest installed one - error if that version isn't installed (overrides config)")
	jsonOutput := flag.Bool("json", false, "emit fastbuild's own status lines as JSON Lines instead of plain text (overrides config) - see README")
	configureBoard := flag.Bool("configure-board", false, "interactively build a full FQBN by picking a platform, board, and menu options - see -arduino-cli/-arduino-cli-yaml below")
	wizardArduinoCLI := flag.String("arduino-cli", "", "path to arduino-cli.exe, for use with -configure-board only")
	wizardArduinoYaml := flag.String("arduino-cli-yaml", "", "path to arduino-cli.yaml, for use with -configure-board only")
	wizardCacheDir := flag.String("wizard-cache-dir", "", "where -configure-board caches the platform/board list; default <home>/.arduino-fastbuild, same as the normal build cache")
	refreshWizardCache := flag.Bool("refresh-wizard-cache", false, "force -configure-board to re-fetch platforms/boards from arduino-cli, ignoring any cached list")
	wizardPrefetch := flag.String("wizard-prefetch", "ask", "whether -configure-board prefetches every board's menu options upfront when a fresh fetch is needed: 'ask' (default, prompts once), 'full' (always prefetch everything - can take minutes with hundreds of boards, and the resulting burst of concurrent arduino-cli processes can stress lower-end machines), or 'off' (skip it - only the boards you actually pick get cached, one at a time, same as before prefetching existed)")
	wizardPrefetchWorkers := flag.Int("wizard-prefetch-workers", 8, "how many arduino-cli 'board details' calls -configure-board runs at once while prefetching (only with -wizard-prefetch=full or after answering yes to the prompt); higher finishes faster but spawns more concurrent processes - raise cautiously, a big burst of simultaneous arduino-cli processes can stress weaker machines")
	saveLog := flag.Bool("save-log", false, "save this run's output to a timestamped file (overrides config)")
	logDir := flag.String("log-dir", "", "where -save-log writes to; default <project>/logs (overrides config)")
	daemonMode := flag.Bool("daemon", false, "run as a persistent build daemon instead of doing a single build - see -daemon-addr")
	daemonAddr := flag.String("daemon-addr", "127.0.0.1:9876", "address the daemon listens on (with -daemon) or connects to (with -connect)")
	daemonStalePolicy := flag.String("daemon-stale-deps-policy", "skip", "what the daemon does about a stale header index instead of prompting (it never prompts - see daemon.go): 'skip' keeps using the stale cache, 'refresh' rebuilds automatically")
	connectAddr := flag.String("connect", "", "send this build to an already-running daemon at this address instead of building in this process")
	watch := flag.Bool("watch", false, "keep running and rebuild automatically whenever sketch/dependency changes are detected (build-on-save); combine with -connect to have a daemon do the actual building")
	watchInterval := flag.Duration("watch-interval", time.Second, "how often -watch checks for changes, e.g. 500ms, 2s")
	flag.Usage = func() {
		fmt.Println("Usage: fastbuild [flags] <path-to-config>")
		fmt.Println("       fastbuild -watch [-watch-interval 1s] <path-to-config>")
		fmt.Println("       fastbuild -daemon [-daemon-addr host:port]")
		fmt.Println("       fastbuild -connect host:port [-watch] <path-to-config>")
		fmt.Println()
		fmt.Println("Cache behavior:")
		printFlagHelp("force", "bypass the skip-if-unchanged check for this run and always ask arduino-cli to build")
		printFlagHelp("clean", "wipe the persistent build folder before building, for a full rebuild from scratch")
		printFlagHelp("no-deps", "disable library/core header dependency hashing for this run")
		printFlagHelp("no-toolchain", "disable toolchain version fingerprinting for this run")
		printFlagHelp("deps-mode <mode>", "'regex' (default) or 'depfile' (alias: 'gcc') - see README")
		printFlagHelp("gcc-inject-mmd", "manually add -MMD via build.extra_flags (deps-mode=depfile only)")
		printFlagHelp("platform-version <ver>", "pin an exact installed platform version instead of auto-selecting the highest one")
		printFlagHelp("refresh-deps-index", "force a full rebuild of the cached header index right now")
		printFlagHelp("assume-yes-stale-deps", "rebuild a stale header index automatically instead of asking")
		printFlagHelp("skip-stale-deps-refresh", "keep using a stale header index without asking")
		fmt.Println()
		fmt.Println("Output / after the build:")
		printFlagHelp("stats", "print a FastBuild Statistics summary after the build")
		printFlagHelp("upload", "upload the resulting binary after building or skipping (requires -port)")
		printFlagHelp("port <name>", "serial port to upload to, e.g. COM3")
		printFlagHelp("export", "copy the resulting binary into the sketch's own folder")
		printFlagHelp("export-conflict <mode>", "'ask' (default), 'overwrite', or 'rename'")
		fmt.Println()
		fmt.Println("Logging:")
		printFlagHelp("json", "emit fastbuild's own status lines as JSON Lines instead of plain text - see README")
		printFlagHelp("save-log", "save this run's output to a timestamped file")
		printFlagHelp("log-dir <path>", "where -save-log writes to (default: <project>/logs)")
		fmt.Println()
		fmt.Println("Board configuration:")
		printFlagHelp("configure-board", "interactively build a full FQBN - pick a platform, a board, then its menu options")
		printFlagHelp("arduino-cli <path>", "path to arduino-cli.exe, for use with -configure-board only")
		printFlagHelp("arduino-cli-yaml <path>", "path to arduino-cli.yaml, for use with -configure-board only")
		printFlagHelp("wizard-cache-dir <path>", "where -configure-board caches its platform/board list (default: same as build cache)")
		printFlagHelp("refresh-wizard-cache", "force -configure-board to ignore its cache and re-fetch from arduino-cli")
		printFlagHelp("wizard-prefetch <mode>", "'ask' (default), 'full', or 'off' - see README")
		printFlagHelp("wizard-prefetch-workers <n>", "concurrent 'board details' calls while prefetching (default 8) - raise cautiously")
		fmt.Println()
		fmt.Println("Daemon mode:")
		printFlagHelp("daemon", "run as a persistent build server instead of doing a single build")
		printFlagHelp("daemon-addr <host:port>", "address to listen on or connect to (default 127.0.0.1:9876)")
		printFlagHelp("daemon-stale-deps-policy <mode>", "'skip' (default) or 'refresh' - the daemon never prompts")
		printFlagHelp("connect <host:port>", "send this build to an already-running daemon")
		fmt.Println()
		fmt.Println("Watch mode:")
		printFlagHelp("watch", "rebuild automatically whenever changes are detected")
		printFlagHelp("watch-interval <duration>", "how often to check, e.g. 500ms, 2s (default 1s)")
		fmt.Println()
		fmt.Println("Every flag overrides the config file for that one run only. See README.md for the full config file reference.")
	}
	flag.Parse()

	if *configureBoard {
		if *wizardArduinoCLI == "" {
			fmt.Fprintln(os.Stderr, "fastbuild: -configure-board requires -arduino-cli <path>")
			os.Exit(1)
		}
		cacheDir := *wizardCacheDir
		if cacheDir == "" {
			home, _ := os.UserHomeDir()
			cacheDir = filepath.Join(home, ".arduino-fastbuild") // same default as the normal build cache root
		}
		if err := runBoardWizard(*wizardArduinoCLI, *wizardArduinoYaml, cacheDir, *refreshWizardCache, *wizardPrefetch, *wizardPrefetchWorkers); err != nil {
			fmt.Fprintln(os.Stderr, "fastbuild: board wizard failed:", err)
			os.Exit(1)
		}
		return
	}

	if *daemonMode {
		policy := staleIndexNeverRefresh
		switch *daemonStalePolicy {
		case "skip":
			policy = staleIndexNeverRefresh
		case "refresh":
			policy = staleIndexAlwaysRefresh
		default:
			fmt.Fprintf(os.Stderr, "fastbuild: -daemon-stale-deps-policy must be 'skip' or 'refresh', got %q\n", *daemonStalePolicy)
			os.Exit(1)
		}
		if err := runDaemon(*daemonAddr, policy); err != nil {
			fmt.Fprintln(os.Stderr, "fastbuild: daemon failed:", err)
			os.Exit(1)
		}
		return
	}

	if flag.NArg() != 1 {
		flag.Usage()
		os.Exit(1)
	}
	configPath := flag.Arg(0)

	if *assumeYesStaleDeps && *skipStaleDepsRefresh {
		fmt.Fprintln(os.Stderr, "fastbuild: -assume-yes-stale-deps and -skip-stale-deps-refresh are mutually exclusive")
		os.Exit(1)
	}

	// -watch combined with -connect: don't build locally at all, just keep asking an
	// already-running daemon to build, on the same polling interval. The daemon still
	// does its own real cache-hit check per request; watch mode here just avoids
	// printing anything on the (common) tick where nothing changed.
	if *watch && *connectAddr != "" {
		description := fmt.Sprintf("via daemon at %s (config: %s)", *connectAddr, configPath)
		builder := daemonBuilder{addr: *connectAddr, configPath: configPath}
		if err := runWatch(description, *watchInterval, builder.Build); err != nil {
			fmt.Fprintln(os.Stderr, "fastbuild:", err)
			os.Exit(1)
		}
		return
	}

	if *connectAddr != "" {
		builder := daemonBuilder{addr: *connectAddr, configPath: configPath}
		if _, err := builder.Build(false); err != nil {
			fmt.Fprintln(os.Stderr, "fastbuild:", err)
			os.Exit(1)
		}
		return
	}

	cfg, err := loadConfig(configPath)
	if err != nil {
		fmt.Fprintln(os.Stderr, "fastbuild: failed to load config:", err)
		os.Exit(1)
	}

	// Command-line flags always win over whatever the config file says - a quick
	// one-off override without having to edit the file.
	if *noDeps {
		cfg.hashLibraryHeaders = false
	}
	if *noToolchain {
		cfg.hashToolchain = false
	}
	if *refreshDepsIndex {
		cfg.refreshDepsIndex = true
	}
	if *assumeYesStaleDeps {
		cfg.staleDepsIndexDecision = staleIndexAlwaysRefresh
	}
	if *skipStaleDepsRefresh {
		cfg.staleDepsIndexDecision = staleIndexNeverRefresh
	}
	if *showStats {
		cfg.showStats = true
	}
	if *force {
		cfg.force = true
	}
	if *clean {
		cfg.clean = true
	}
	if *upload {
		cfg.upload = true
	}
	if *port != "" {
		cfg.port = *port
	}
	if *export {
		cfg.export = true
	}
	if *exportConflict != "" {
		switch *exportConflict {
		case "ask":
			cfg.exportConflict = exportConflictAsk
		case "overwrite":
			cfg.exportConflict = exportConflictOverwrite
		case "rename":
			cfg.exportConflict = exportConflictRename
		default:
			fmt.Fprintf(os.Stderr, "fastbuild: -export-conflict must be 'ask', 'overwrite', or 'rename', got %q\n", *exportConflict)
			os.Exit(1)
		}
	}
	if *depsMode != "" {
		switch *depsMode {
		case "regex":
			cfg.depsMode = "regex"
		case "depfile", "gcc":
			// "gcc" is kept as a permanent alias, not deprecated - existing configs
			// and scripts that already say -deps-mode=gcc keep working forever.
			// "depfile" is the preferred name going forward: this mode reads
			// compiler-generated dependency files, which works with any
			// GCC-compatible toolchain (or, in principle, any future toolchain that
			// emits the same Makefile-style .d format) - "gcc" undersold that.
			cfg.depsMode = "depfile"
		default:
			fmt.Fprintf(os.Stderr, "fastbuild: -deps-mode must be 'regex' or 'depfile' (or its alias 'gcc'), got %q\n", *depsMode)
			os.Exit(1)
		}
	}
	if *gccInjectMMD {
		cfg.gccInjectMMD = true
	}
	if *platformVersion != "" {
		cfg.platformVersion = *platformVersion
	}
	if *jsonOutput {
		cfg.jsonOutput = true
	}
	if *saveLog {
		cfg.saveLog = true
	}
	if *logDir != "" {
		cfg.logDir = *logDir
	}

	builder := localBuilder{cfg: cfg}

	if *watch {
		description := fmt.Sprintf("%s (FQBN: %s)", cfg.sketch, cfg.fqbn)
		if err := runWatch(description, *watchInterval, builder.Build); err != nil {
			fmt.Fprintln(os.Stderr, "fastbuild:", err)
			os.Exit(1)
		}
		return
	}

	if _, err := builder.Build(false); err != nil {
		fmt.Fprintln(os.Stderr, "fastbuild: build failed:", err)
		os.Exit(1)
	}
}

// run performs one build attempt: skip the compile if the cache key hash is unchanged
// from the last successful build, otherwise invoke arduino-cli. It returns whether an
// actual compile ran (false on a cache hit or a suppressed repeat-failure - see below)
// so callers like the watch loop can decide whether there's anything worth logging.
//
// quiet suppresses the "no changes / cache hit" status lines (and stats) entirely, and
// also suppresses re-running (and re-printing) a compile that's known to fail against
// the exact same hash as last time - both exist for the watch loop's benefit: polling
// every second shouldn't print "no changes" every second, and shouldn't re-spam a
// compiler error on every poll while you're mid-edit fixing it. quiet=false (a normal
// single build, or the first build in a watch session) always shows full output.
func run(cfg *config, quiet bool) (bool, error) {
	startTime := time.Now()

	if _, err := os.Stat(cfg.sketch); err != nil {
		return false, fmt.Errorf("sketch not found: %s", cfg.sketch)
	}
	if cfg.upload && cfg.port == "" {
		return false, fmt.Errorf("-upload requires a port (set 'port=' in the config or pass -port)")
	}

	projectID := deriveProjectID(cfg.sketch, cfg.fqbn)
	projectDir := filepath.Join(cfg.cacheRoot, projectID)
	buildPath := filepath.Join(projectDir, "build")
	stateFile := filepath.Join(projectDir, stateFileName)

	logger := newBuildLogger(cfg, projectDir)
	defer logger.Close()
	logger.PID(os.Getpid())

	if cfg.clean {
		logger.Println("Cleaning: " + buildPath)
		if err := os.RemoveAll(buildPath); err != nil {
			return false, fmt.Errorf("removing build path: %w", err)
		}
		_ = os.Remove(stateFile) // best-effort; a missing state file is not an error
		// One-shot: a single fastbuild invocation only ever needs to clean once. In
		// -watch mode, run() is called repeatedly against this same *config for the
		// life of the process - without clearing this, every single poll tick would
		// wipe the persistent build folder (including arduino-cli's own incremental
		// object cache) and force a full from-scratch recompile forever, even with
		// zero source changes. Confirmed directly: -watch -clean with no edits at all
		// cleaned and recompiled on every tick (6 cleans / 6 recompiles across 3
		// idle seconds). Clearing the flag here means only the watch session's first
		// build is a clean one; every tick after that goes back to normal, efficient
		// change-detected building - which is the actual point of watch mode.
		cfg.clean = false
	}

	if err := os.MkdirAll(buildPath, 0o755); err != nil {
		return false, err
	}

	hashStart := time.Now()
	currentHash, stats, err := hashSketchSources(cfg, projectDir)
	stats.hashDuration = time.Since(hashStart)
	if err != nil {
		return false, fmt.Errorf("hashing sketch sources: %w", err)
	}

	state := loadState(stateFile)

	// One-shot, same reasoning as cfg.clean above: -force is meant to override the
	// skip-if-unchanged check for exactly one build. Without capturing and clearing it
	// here, a -watch session would re-force a full recompile on every poll tick
	// forever, defeating watch mode's entire point even though nothing changed.
	forceThisRun := cfg.force
	cfg.force = false

	if !forceThisRun && state.lastBuildOk && state.lastHash == currentHash && state.outputFile != "" {
		if _, err := os.Stat(state.outputFile); err == nil {
			if quiet {
				return false, nil
			}
			logger.Println("No changes detected in sketch sources - skipping compile.")
			logger.Println("Reusing previous build output: " + state.outputFile)
			elapsed := time.Since(startTime).Seconds()
			logger.Println(fmt.Sprintf("Done in %.2f s (cache hit).", elapsed))
			logger.Result(true, true, elapsed, state.outputFile)
			if cfg.showStats {
				stats.cacheHit = true
				if state.lastCompileSeconds > 0 {
					stats.estimatedSaved = time.Duration(state.lastCompileSeconds * float64(time.Second))
				}
				stats.print()
			}
			return false, afterBuild(cfg, buildPath, state.outputFile)
		}
	}

	// If the last attempt at this exact hash already failed and nothing has changed
	// since, quiet mode (watch polling) stays silent instead of re-running and
	// re-printing the same compiler error every tick. A non-quiet call (or any change
	// to the hash) always retries for real. -force always retries too, regardless.
	if !forceThisRun && quiet && !state.lastBuildOk && state.lastAttemptedHash == currentHash {
		return false, nil
	}
	state.lastAttemptedHash = currentHash

	logger.Println("Changes detected (or no previous successful build) - compiling...")
	logger.Println("Build path: " + buildPath)

	args := []string{}
	if cfg.configFile != "" {
		args = append(args, "--config-file", cfg.configFile)
	}
	args = append(args,
		"compile",
		"--fqbn", cfg.fqbn,
		"--build-path", buildPath,
	)
	if cfg.verbose {
		args = append(args, "--verbose")
	}
	if cfg.depsMode == "depfile" && cfg.gccInjectMMD {
		logger.Println("Manually injecting -MMD via build.extra_flags (gccInjectMMD=true).")
	}
	for _, prop := range effectiveBuildProps(cfg) {
		args = append(args, "--build-property", prop)
	}
	args = append(args, cfg.sketch)

	logger.Println("Running: " + cfg.arduinoCLI + " " + strings.Join(args, " "))

	cmd := exec.Command(cfg.arduinoCLI, args...)
	// os/exec lets us hand off the child's stdout/stderr directly - no manual pipe
	// draining or reader threads needed, unlike the Java ProcessBuilder version.
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr

	compileStart := time.Now()
	buildErr := cmd.Run()
	compileDuration := time.Since(compileStart)

	if buildErr != nil {
		state.lastBuildOk = false
		saveState(stateFile, state)
		logger.Result(false, false, time.Since(startTime).Seconds(), "")
		return false, fmt.Errorf("arduino-cli exited with error: %w", buildErr)
	}

	outputFile := findOutputFile(buildPath, cfg.sketch)

	if cfg.depsMode == "depfile" {
		dataDir := ""
		if cfg.configFile != "" {
			if dirs, err := readArduinoDirs(cfg.configFile); err == nil {
				dataDir = dirs.data
			}
		}
		harvestGccDeps(buildPath, projectDir, cfg.sketch, cfg.fqbn, dataDir, cfg.verbose)
	}

	state.lastHash = currentHash
	state.lastBuildOk = true
	state.outputFile = outputFile
	state.lastCompileSeconds = compileDuration.Seconds()
	saveState(stateFile, state)

	elapsedSeconds := time.Since(startTime).Seconds()
	logger.Println(fmt.Sprintf("Build succeeded in %.2f s.", elapsedSeconds))
	if outputFile != "" {
		logger.Println("Output: " + outputFile)
	}
	logger.Result(true, false, elapsedSeconds, outputFile)
	if cfg.showStats {
		stats.compileDuration = compileDuration
		stats.print()
	}
	return true, afterBuild(cfg, buildPath, outputFile)
}

// afterBuild runs whatever optional post-build actions were requested - upload,
// export, or both - after either a real compile or a cache-hit skip. Both call sites
// in run() funnel through here so -upload/-export behave identically regardless of
// whether a real compile actually happened this time.
func afterBuild(cfg *config, buildPath, outputFile string) error {
	if cfg.upload {
		if outputFile == "" {
			return fmt.Errorf("upload requested but no build output is available")
		}
		if err := uploadBinary(cfg, buildPath); err != nil {
			return fmt.Errorf("upload failed: %w", err)
		}
	}
	if cfg.export {
		dest, err := exportBinary(cfg, outputFile)
		if err != nil {
			return fmt.Errorf("export failed: %w", err)
		}
		fmt.Println("Exported:", dest)
	}
	return nil
}

// uploadBinary uploads the already-compiled binary in buildPath using arduino-cli's
// --input-dir flag, which uploads without triggering a recompile.
func uploadBinary(cfg *config, buildPath string) error {
	args := []string{}
	if cfg.configFile != "" {
		args = append(args, "--config-file", cfg.configFile)
	}
	args = append(args,
		"upload",
		"--fqbn", cfg.fqbn,
		"--input-dir", buildPath,
		"--port", cfg.port,
	)
	if cfg.verbose {
		args = append(args, "--verbose")
	}
	args = append(args, cfg.sketch)

	fmt.Println("Uploading:", cfg.arduinoCLI, strings.Join(args, " "))

	cmd := exec.Command(cfg.arduinoCLI, args...)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	return cmd.Run()
}

// deriveProjectID builds a stable, filesystem-safe identifier from the sketch's
// absolute path and FQBN, so different projects/boards get separate persistent caches.
func deriveProjectID(sketchPath, fqbn string) string {
	abs, err := filepath.Abs(sketchPath)
	if err != nil {
		abs = sketchPath
	}
	sum := sha256.Sum256([]byte(abs + "|" + fqbn))
	return hex.EncodeToString(sum[:])[:16]
}

// hashSketchSources hashes the .ino file plus any local .cpp/.h/.c/.hpp files sitting
// in the same directory, sorted by name so the combined hash is deterministic
// regardless of filesystem iteration order. The FQBN and build properties are folded
// in too - anything that changes the generated binary without touching a source file
// (an optimization flag, a define, a board option) must also invalidate the cache, or
// fastbuild would wrongly skip a compile that's actually needed. Alongside the hash it
// returns a buildStats populated with source/dependency file counts and header-index
// cache status, for the -stats summary; run() fills in the timing fields itself.
//
// Known limitation without a configFile set: dependency-aware hashing (library/core
// headers, toolchain version - see deps.go) is skipped, so editing a library file
// directly won't be detected. Set configFile in the config to enable it.
func hashSketchSources(cfg *config, projectDir string) (string, buildStats, error) {
	var stats buildStats

	dir := filepath.Dir(cfg.sketch)
	entries, err := os.ReadDir(dir)
	if err != nil {
		return "", stats, err
	}

	var relevant []string
	var relevantFullPaths []string
	extPattern := regexp.MustCompile(`(?i)\.(ino|cpp|h|c|hpp)$`)
	for _, e := range entries {
		if !e.IsDir() && extPattern.MatchString(e.Name()) {
			relevant = append(relevant, e.Name())
			relevantFullPaths = append(relevantFullPaths, filepath.Join(dir, e.Name()))
		}
	}
	sort.Strings(relevant)
	stats.sourceFileCount = len(relevant)

	h := sha256.New()
	for _, name := range relevant {
		data, err := os.ReadFile(filepath.Join(dir, name))
		if err != nil {
			return "", stats, err
		}
		h.Write([]byte(name))
		h.Write(data)
	}
	// Fold in anything else that affects the generated binary but isn't a source file.
	h.Write([]byte("fqbn:" + cfg.fqbn))
	h.Write([]byte("buildProps:" + strings.Join(cfg.buildProps, "|")))

	depComponent, depStats, err := dependencyAwareHashComponent(cfg, relevantFullPaths, projectDir)
	if err != nil {
		// Don't hard-fail the whole build over dependency hashing - fall back to the
		// base sketch-only hash (previous behavior) rather than blocking compilation.
		fmt.Fprintln(os.Stderr, "fastbuild: warning: dependency hashing skipped:", err)
	} else if depComponent != "" {
		h.Write([]byte("deps:" + depComponent))
	}
	stats.depFileCount = depStats.depFileCount
	stats.headerIndexCached = depStats.headerIndexCached
	stats.headerIndexEnabled = depStats.headerIndexEnabled
	stats.depsSource = depStats.depsSource

	return hex.EncodeToString(h.Sum(nil)), stats, nil
}

// findOutputFile looks for the compiled firmware artifact in the build path,
// preferring .bin, then .hex, then .elf.
func findOutputFile(buildPath, sketchPath string) string {
	sketchName := filepath.Base(sketchPath)
	for _, suffix := range []string{".bin", ".hex", ".elf"} {
		candidate := filepath.Join(buildPath, sketchName+suffix)
		if _, err := os.Stat(candidate); err == nil {
			abs, err := filepath.Abs(candidate)
			if err == nil {
				return abs
			}
			return candidate
		}
	}
	return ""
}

// loadConfig reads the simple key=value config file. Required keys: arduinoCLI,
// sketch, fqbn. Optional: cacheRoot (defaults under the user's home dir), buildProps
// ("|"-delimited key=value build properties).
// loadConfig opens path and parses it as a config file. As a special case, path == "-"
// (the standard Unix convention for "stdin instead of a file", same as tar/cat/etc.)
// reads from os.Stdin instead of opening a named file. Both routes call parseConfig
// with the resulting io.Reader, so there is exactly one parsing/validation
// implementation regardless of where the bytes came from - not two config formats to
// keep in sync, just two ways of supplying the same one.
func loadConfig(path string) (*config, error) {
	if path == "-" {
		return parseConfig(os.Stdin)
	}
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	return parseConfig(f)
}

// parseConfig reads key=value config text from r - see loadConfig for the two ways
// that text can arrive (a named file, or stdin via "-").
func parseConfig(r io.Reader) (*config, error) {
	values := map[string]string{}
	scanner := bufio.NewScanner(r)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		parts := strings.SplitN(line, "=", 2)
		if len(parts) != 2 {
			continue
		}
		values[strings.TrimSpace(parts[0])] = strings.TrimSpace(parts[1])
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}

	cfg := &config{
		arduinoCLI:         values["arduinoCLI"],
		configFile:         values["configFile"],
		sketch:             values["sketch"],
		fqbn:               values["fqbn"],
		cacheRoot:          values["cacheRoot"],
		verbose:            values["verbose"] == "true",
		hashLibraryHeaders: values["hashLibraryHeaders"] != "false", // default true
		hashToolchain:      values["hashToolchain"] != "false",      // default true
		showStats:          values["showStats"] == "true",
		force:              values["force"] == "true",
		clean:              values["clean"] == "true",
		upload:             values["upload"] == "true",
		port:               values["port"],
		export:             values["export"] == "true",
	}
	switch strings.ToLower(strings.TrimSpace(values["exportConflict"])) {
	case "", "ask":
		cfg.exportConflict = exportConflictAsk
	case "overwrite":
		cfg.exportConflict = exportConflictOverwrite
	case "rename":
		cfg.exportConflict = exportConflictRename
	default:
		// Previously any unrecognized value (including a plain typo like "overwrit")
		// silently fell through to "ask" with no warning at all - inconsistent with
		// how every other enum-like setting here (e.g. depsIndexMaxAgeHours) is
		// validated, and a typo could quietly change behavior without you noticing.
		// Now it's a hard error, same as the -export-conflict CLI flag already was.
		return nil, fmt.Errorf("exportConflict must be one of ask, overwrite, rename (got %q)", values["exportConflict"])
	}
	switch strings.ToLower(strings.TrimSpace(values["depsMode"])) {
	case "", "regex":
		cfg.depsMode = "regex"
	case "depfile", "gcc":
		// "gcc" is a permanent backward-compat alias - see the matching -deps-mode
		// CLI flag comment above for why.
		cfg.depsMode = "depfile"
	default:
		return nil, fmt.Errorf("depsMode must be 'regex' or 'depfile' (or its alias 'gcc') (got %q)", values["depsMode"])
	}
	cfg.gccInjectMMD = values["gccInjectMMD"] == "true"
	cfg.platformVersion = strings.TrimSpace(values["platformVersion"])
	cfg.jsonOutput = values["jsonOutput"] == "true"
	cfg.saveLog = values["saveLog"] == "true"
	cfg.logDir = values["logDir"]
	// depsIndexMaxAgeHours: how old a signature-valid cached header index can get
	// before it's treated as stale and (by default) you get asked whether to rebuild
	// it. Defaults to 24 hours. Set to 0 to disable this check entirely - the cached
	// index then only ever gets rebuilt when libraryDirSignature actually changes.
	cfg.depsIndexMaxAge = 24 * time.Hour
	if raw, ok := values["depsIndexMaxAgeHours"]; ok {
		hours, err := strconv.Atoi(strings.TrimSpace(raw))
		if err != nil {
			return nil, fmt.Errorf("depsIndexMaxAgeHours must be an integer: %w", err)
		}
		if hours <= 0 {
			cfg.depsIndexMaxAge = 0 // disabled - signature is the only check that applies
		} else {
			cfg.depsIndexMaxAge = time.Duration(hours) * time.Hour
		}
	}
	if cfg.arduinoCLI == "" || cfg.sketch == "" || cfg.fqbn == "" {
		return nil, fmt.Errorf("config must set arduinoCLI, sketch, and fqbn")
	}
	if cfg.cacheRoot == "" {
		home, _ := os.UserHomeDir()
		cfg.cacheRoot = filepath.Join(home, ".arduino-fastbuild")
	}
	if raw, ok := values["buildProps"]; ok && raw != "" {
		for _, p := range strings.Split(raw, "|") {
			p = strings.TrimSpace(p)
			if p != "" {
				cfg.buildProps = append(cfg.buildProps, p)
			}
		}
	}
	return cfg, nil
}

// loadState reads the persisted build state, if any. Missing/unreadable state is
// treated as "no prior successful build" rather than an error.
func loadState(path string) *buildState {
	state := &buildState{}
	f, err := os.Open(path)
	if err != nil {
		return state
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := scanner.Text()
		parts := strings.SplitN(line, "=", 2)
		if len(parts) != 2 {
			continue
		}
		switch parts[0] {
		case "lastHash":
			state.lastHash = parts[1]
		case "lastBuildOk":
			state.lastBuildOk = parts[1] == "true"
		case "outputFile":
			state.outputFile = parts[1]
		case "lastCompileSeconds":
			if v, err := strconv.ParseFloat(parts[1], 64); err == nil {
				state.lastCompileSeconds = v
			}
		case "lastAttemptedHash":
			state.lastAttemptedHash = parts[1]
		}
	}
	return state
}

func saveState(path string, state *buildState) {
	var sb strings.Builder
	sb.WriteString("lastHash=" + state.lastHash + "\n")
	sb.WriteString(fmt.Sprintf("lastBuildOk=%t\n", state.lastBuildOk))
	sb.WriteString("outputFile=" + state.outputFile + "\n")
	sb.WriteString(fmt.Sprintf("lastCompileSeconds=%f\n", state.lastCompileSeconds))
	sb.WriteString("lastAttemptedHash=" + state.lastAttemptedHash + "\n")
	// Best-effort write; a failure here just means the next run recompiles, which is safe.
	_ = os.WriteFile(path, []byte(sb.String()), 0o644)
}
