package main

import (
	"bufio"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"hash"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// includePattern matches both #include "foo.h" and #include <foo.h>, capturing the
// opening delimiter (" or <) separately so callers can tell which form was used -
// quoted includes are resolved relative to the includer's own directory first
// (matching how a real C preprocessor treats "foo.h" vs <foo.h>), while angle-bracket
// includes always fall back to the global header index.
var includePattern = regexp.MustCompile(`(?m)^\s*#\s*include\s*([<"])([^>"]+)[>"]`)

// arduinoDirs holds the two paths we need out of arduino-cli.yaml.
type arduinoDirs struct {
	data string // e.g. C:\Users\saliya\AppData\Local\Arduino15
	user string // e.g. C:\Users\saliya\Documents\Arduino
}

// readArduinoDirs does a minimal line-scan of arduino-cli.yaml for the two directory
// values we need. We deliberately avoid pulling in a YAML library for this - the file
// has a known, simple shape (we generated it), so a couple of regexes are enough and
// keep fastbuild dependency-free.
func readArduinoDirs(configFile string) (*arduinoDirs, error) {
	f, err := os.Open(configFile)
	if err != nil {
		return nil, err
	}
	defer f.Close()

	dataPattern := regexp.MustCompile(`^\s*data:\s*"?([^"]+?)"?\s*$`)
	userPattern := regexp.MustCompile(`^\s*user:\s*"?([^"]+?)"?\s*$`)

	dirs := &arduinoDirs{}
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := scanner.Text()
		if m := dataPattern.FindStringSubmatch(line); m != nil {
			dirs.data = strings.TrimSpace(m[1])
		}
		if m := userPattern.FindStringSubmatch(line); m != nil {
			dirs.user = strings.TrimSpace(m[1])
		}
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}
	if dirs.data == "" {
		return nil, fmt.Errorf("directories.data not found in %s", configFile)
	}
	return dirs, nil
}

// resolvePlatformDir finds the installed platform folder for the FQBN's package:arch,
// e.g. Arduino15/packages/esp8266/hardware/esp8266/3.0.2.
//
// If pinnedVersion is non-empty (set via platformVersion= in the config, or
// -platform-version on the command line), that exact version is used directly.
// Returns a clear error if the pinned version isn't actually installed, rather than
// silently falling back to a different one.
//
// With no pin, multiple installed versions are compared with compareVersions (a real
// numeric-segment comparison, not a lexical string sort) and the highest one is used.
// Lexical sort alone gets "3.9.0" vs "3.10.0" wrong (treats "3.9.0" as newer, since
// '9' > '1' at that character position) - compareVersions fixes the common case
// correctly. It isn't a full semver implementation (no pre-release/build-metadata
// precedence rules), but Arduino core version strings are plain dotted-numeric in
// practice, so this covers what's actually out there.
func resolvePlatformDir(dataDir, fqbn, pinnedVersion string) (string, error) {
	parts := strings.SplitN(fqbn, ":", 3)
	if len(parts) < 2 {
		return "", fmt.Errorf("fqbn %q missing package:arch prefix", fqbn)
	}
	pkg, arch := parts[0], parts[1]

	hardwareDir := filepath.Join(dataDir, "packages", pkg, "hardware", arch)

	if pinnedVersion != "" {
		pinnedDir := filepath.Join(hardwareDir, pinnedVersion)
		if info, err := os.Stat(pinnedDir); err == nil && info.IsDir() {
			return pinnedDir, nil
		}
		installed, _ := filepath.Glob(filepath.Join(hardwareDir, "*"))
		var versions []string
		for _, p := range installed {
			versions = append(versions, filepath.Base(p))
		}
		return "", fmt.Errorf("platformVersion %q is not installed for %s:%s (installed: %s)", pinnedVersion, pkg, arch, strings.Join(versions, ", "))
	}

	matches, err := filepath.Glob(filepath.Join(hardwareDir, "*"))
	if err != nil {
		return "", err
	}
	if len(matches) == 0 {
		return "", fmt.Errorf("no installed platform found matching %s", filepath.Join(hardwareDir, "*"))
	}
	sort.Slice(matches, func(i, j int) bool {
		return compareVersions(filepath.Base(matches[i]), filepath.Base(matches[j])) < 0
	})
	return matches[len(matches)-1], nil
}

// compareVersions compares two dotted-numeric version strings (e.g. "3.0.2" vs
// "3.10.0") segment by segment, numerically - so "3.10.0" correctly sorts after
// "3.9.0", unlike a plain lexical string comparison. Returns <0, 0, or >0 the same
// way strings.Compare does. Non-numeric segments (rare for Arduino core versions, but
// not impossible - a "-rc1" suffix, say) fall back to a lexical comparison of that one
// segment rather than erroring, so this never fails on an unexpected format; it just
// stops being precisely numeric past that point.
func compareVersions(a, b string) int {
	aParts := strings.Split(a, ".")
	bParts := strings.Split(b, ".")
	for i := 0; i < len(aParts) || i < len(bParts); i++ {
		// A missing segment (one version string has fewer dot-separated parts than
		// the other) defaults to "0", not empty string - so "3.0" and "3.0.0"
		// correctly compare equal instead of "3.0" looking smaller merely because it
		// ran out of segments first.
		aSeg, bSeg := "0", "0"
		if i < len(aParts) {
			aSeg = aParts[i]
		}
		if i < len(bParts) {
			bSeg = bParts[i]
		}
		aNum, aErr := strconv.Atoi(aSeg)
		bNum, bErr := strconv.Atoi(bSeg)
		if aErr == nil && bErr == nil {
			if aNum != bNum {
				if aNum < bNum {
					return -1
				}
				return 1
			}
			continue
		}
		if aSeg != bSeg {
			return strings.Compare(aSeg, bSeg)
		}
	}
	return 0
}

// toolchainFingerprint returns the sorted list of installed tool version folder names
// under packages/<pkg>/tools/ (e.g. "xtensa-lx106-elf-gcc/3.0.4-gcc10.3-1757bed"). This
// is a cheap proxy for "did the compiler toolchain change" - we hash directory names,
// not the (large) binaries themselves, so a version bump changes the fingerprint
// without us having to read gigabytes of compiler binaries on every build.
func toolchainFingerprint(dataDir, fqbn string) ([]string, error) {
	parts := strings.SplitN(fqbn, ":", 3)
	if len(parts) < 2 {
		return nil, fmt.Errorf("fqbn %q missing package:arch prefix", fqbn)
	}
	pkg := parts[0]

	pattern := filepath.Join(dataDir, "packages", pkg, "tools", "*", "*")
	matches, err := filepath.Glob(pattern)
	if err != nil {
		return nil, err
	}
	var names []string
	for _, m := range matches {
		rel, err := filepath.Rel(filepath.Join(dataDir, "packages", pkg, "tools"), m)
		if err == nil {
			names = append(names, filepath.ToSlash(rel))
		}
	}
	sort.Strings(names)
	return names, nil
}

// platformCacheKey returns a filesystem-safe identifier for the FQBN's package:arch
// plus the actual resolved platform version (e.g. "esp8266_esp8266_3.0.2"), used to
// give each installed platform *version* its own header index cache file - not just
// each platform. Two things this guards against:
//   - Switching between projects that target different platforms (ESP8266 today,
//     ESP32 tomorrow) previously overwrote a single shared header-index.json with
//     whichever platform built most recently, forcing a full rebuild on every switch
//     back - keying on package:arch alone (as this used to) already fixed that.
//   - Keying on package:arch alone was NOT enough to catch a platform being upgraded
//     in place (`arduino-cli core upgrade esp8266:esp8266`, or picking a different
//     -platform-version) - core headers can and do change between versions, but the
//     old key would keep serving whatever was cached under the same filename
//     regardless of which version actually built it. Including the resolved version
//     closes that gap: each version gets its own cache entry, and an upgrade or a
//     -platform-version switch naturally lands on a fresh (or already-cached-for-that-
//     version) entry instead of silently reusing another version's index.
//
// version should be the version actually resolved by resolvePlatformDir (e.g.
// filepath.Base(platformDir)), not just cfg.platformVersion - the latter is blank
// under auto-select, which would collapse every auto-selected build back to the
// pre-fix behavior and miss the upgrade case above entirely.
//
// Changing this key's format means any header-index-*.json written under the old
// package:arch-only naming is simply never matched again - harmless (a fresh index
// gets built and cached under the new name the next time it's needed), just a few
// orphaned files safe to delete manually if you want to tidy up.
func platformCacheKey(fqbn, version string) string {
	parts := strings.SplitN(fqbn, ":", 3)
	pkg, arch := "unknown", "unknown"
	if len(parts) >= 1 && parts[0] != "" {
		pkg = parts[0]
	}
	if len(parts) >= 2 && parts[1] != "" {
		arch = parts[1]
	}
	if version == "" {
		version = "unknown"
	}
	sanitize := func(s string) string {
		return strings.Map(func(r rune) rune {
			switch r {
			case '/', '\\', ':', ' ', '\t':
				return '_'
			}
			return r
		}, s)
	}
	return sanitize(pkg) + "_" + sanitize(arch) + "_" + sanitize(version)
}

// headerIndexSignatureDepth bounds how many directory levels below each root
// libraryDirSignature descends. Most Arduino libraries put their headers either
// directly in the library folder or one level down in "src/" (occasionally "src/utility/"),
// so a depth of 4 comfortably covers real-world layouts without unbounded recursion
// into large "examples/" trees.
const headerIndexSignatureDepth = 4

// headerIndexMaxAge, when non-zero, is compared against a cached index's age to
// decide whether it counts as "stale by age" - see loadOrBuildHeaderIndex and
// staleIndexDecision for what happens when it does. A value of 0 disables the
// age-based check entirely, leaving libraryDirSignature as the only thing that can
// invalidate the cache. This lives on config (depsIndexMaxAgeHours) rather than as a
// constant so it can be turned off or tuned per project.

// libraryDirSignature is a cheap proxy for "did the set of files under the installed
// platform/library directories change" between runs, used to decide whether the
// cached header index is still valid. It recurses through directories (never
// individual files) up to headerIndexSignatureDepth levels below each root, folding
// each directory's own mtime into the hash. Restricting this to directories - not
// every file - is deliberate and safe: adding, removing, or renaming a file always
// changes its *parent directory's* mtime, so we don't need to stat the file itself to
// notice a new or deleted header; we only need to notice a changed directory.
// Editing an existing file's *content* in place is not caught here at all, but that's
// fine - it doesn't need to be, since hashDependencies always re-reads the actual file
// bytes fresh on every build regardless of whether the index came from cache.
//
// The known gap: an addition/removal deeper than headerIndexSignatureDepth levels
// below a root won't bubble up in time. headerIndexMaxAge (see dependencyAwareHashComponent)
// is the backstop for that - a full rebuild happens at least once a day no matter what,
// so a missed case can't go stale forever, and -refresh-deps-index forces one on demand.
func libraryDirSignature(roots ...string) (string, error) {
	h := sha256.New()
	for _, root := range roots {
		if root == "" {
			continue
		}
		hashDirTree(h, root, headerIndexSignatureDepth)
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// hashDirTree folds the mtime of every directory at or below dir (down to maxDepth
// additional levels) into h. Non-directory entries are skipped entirely - see the
// libraryDirSignature comment for why that's safe here. An unreadable subdirectory is
// treated as contributing nothing (best-effort, same spirit as the rest of this file's
// error handling) rather than aborting the whole signature.
func hashDirTree(h hash.Hash, dir string, maxDepth int) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].Name() < entries[j].Name() })
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		path := filepath.Join(dir, e.Name())
		fmt.Fprintf(h, "%s|%d\n", path, info.ModTime().UnixNano())
		if maxDepth > 0 {
			hashDirTree(h, path, maxDepth-1)
		}
	}
}

// staleIndexDecision controls what loadOrBuildHeaderIndex does when the cached header
// index passes the signature check but has exceeded maxAge.
type staleIndexDecision int

const (
	// staleIndexAsk prompts on stdin before rebuilding - the default, so a full
	// directory walk never happens without you knowing about it first.
	staleIndexAsk staleIndexDecision = iota
	// staleIndexAlwaysRefresh rebuilds immediately without asking (set via
	// -assume-yes-stale-deps, for scripted/CI use where no one's there to answer a
	// prompt).
	staleIndexAlwaysRefresh
	// staleIndexNeverRefresh keeps using the stale cache without asking (set via
	// -skip-stale-deps-refresh) - the "I'm in a hurry, don't stop me" option. The
	// signature check still applies underneath this, so an actual added/removed
	// library file is still caught; this only skips the belt-and-suspenders age check.
	staleIndexNeverRefresh
)

// promptYesNo asks question on stdout and reads a y/n answer from stdin. Anything
// other than a leading 'y'/'Y' (including a read error, e.g. stdin isn't a real
// terminal) is treated as "no" - a stale cache is always safe to keep using for one
// more build, so the safe default on an ambiguous or failed prompt is to not block.
//
// isInteractiveStdin is defined per-platform - see isinteractive_windows.go and
// isinteractive_other.go. The check that worked correctly on Unix-like systems
// (comparing file identity against the null device via os.SameFile) does not
// reliably detect a real Windows console: Stat() on a console handle doesn't behave
// like a regular file there, so that approach was silently reporting "not
// interactive" on every real Windows console - confirmed directly against a real
// run, where -configure-board always picked the default answer regardless of what
// was typed. Windows needs GetConsoleMode instead (see isinteractive_windows.go).

func promptYesNo(question string) bool {
	fmt.Print(question)
	reader := bufio.NewReader(os.Stdin)
	line, err := reader.ReadString('\n')
	if err != nil {
		return false
	}
	line = strings.TrimSpace(line)
	return len(line) > 0 && (line[0] == 'y' || line[0] == 'Y')
}

// headerIndexCache is the on-disk (and, in daemon mode, in-memory) form of a
// previously built header index, tagged with the libraryDirSignature that was current
// when it was built and the time it was built, so loadOrBuildHeaderIndex can also
// expire it on age.
type headerIndexCache struct {
	Signature string              `json:"signature"`
	BuiltAt   time.Time           `json:"builtAt"`
	Index     map[string][]string `json:"index"`
}

// useHeaderIndexMemCache and headerIndexMemCache back an optional in-process cache of
// decoded header indexes, keyed by cachePath. This only matters in daemon mode
// (daemon.go sets useHeaderIndexMemCache = true at startup): a normal one-shot fastbuild
// invocation has nothing to gain from an in-memory cache since the process exits right
// after anyway, but a long-running daemon serving many builds back-to-back can skip
// even the disk read + JSON decode once an index has been loaded once. In non-daemon
// mode this is simply unused.
var (
	useHeaderIndexMemCache bool
	headerIndexMemCache    sync.Map // cachePath -> headerIndexCache
)

// loadOrBuildHeaderIndex returns the header index for roots, reusing a cached copy
// when the roots' directory signature still matches what's cached, and reports
// whether the returned index came from a cache (disk or in-memory) or was freshly
// rebuilt via a full directory walk - callers use that for build statistics.
//
// forceRefresh (from -refresh-deps-index) always bypasses any cache unconditionally.
// Otherwise, if maxAge > 0 and a signature-valid cache is older than maxAge, what
// happens next depends on staleDecision: staleIndexAsk (the default) prompts before
// rebuilding so a directory walk is never sprung on you mid-build; staleIndexAlwaysRefresh
// rebuilds without asking; staleIndexNeverRefresh keeps using the stale cache without
// asking. Passing maxAge <= 0 disables the age check entirely - only the signature then
// decides whether the cache is valid.
func loadOrBuildHeaderIndex(cachePath string, forceRefresh bool, verbose bool, maxAge time.Duration, staleDecision staleIndexDecision, roots ...string) (map[string][]string, bool, error) {
	sig, err := libraryDirSignature(roots...)
	if err != nil {
		return nil, false, err
	}

	if !forceRefresh {
		// In-memory cache first (daemon mode only) - skips the disk read + JSON
		// decode entirely when a prior request in this same daemon process already
		// loaded a matching index.
		if useHeaderIndexMemCache {
			if v, ok := headerIndexMemCache.Load(cachePath); ok {
				cache := v.(headerIndexCache)
				if cache.Signature == sig {
					age := time.Since(cache.BuiltAt)
					if maxAge <= 0 || age < maxAge {
						if verbose {
							fmt.Println("Reusing in-memory cached header index (age:", age.Round(time.Second), ")")
						}
						return cache.Index, true, nil
					}
				}
			}
		}

		if data, err := os.ReadFile(cachePath); err == nil {
			var cache headerIndexCache
			if json.Unmarshal(data, &cache) == nil && cache.Signature == sig {
				age := time.Since(cache.BuiltAt)
				stale := maxAge > 0 && age >= maxAge
				if !stale {
					if verbose {
						fmt.Println("Reusing cached header index (age:", age.Round(time.Second), ") - pass -refresh-deps-index to force a rebuild.")
					}
					if useHeaderIndexMemCache {
						headerIndexMemCache.Store(cachePath, cache)
					}
					return cache.Index, true, nil
				}

				switch staleDecision {
				case staleIndexNeverRefresh:
					if verbose {
						fmt.Println("Cached header index is older than", maxAge, "but -skip-stale-deps-refresh is set - reusing it as-is.")
					}
					return cache.Index, true, nil
				case staleIndexAlwaysRefresh:
					if verbose {
						fmt.Println("Cached header index is older than", maxAge, "- rebuilding automatically (-assume-yes-stale-deps).")
					}
				default: // staleIndexAsk
					if !isInteractiveStdin() {
						// No one could actually answer a prompt here (redirected/absent
						// stdin - a scheduled task, an IDE build hook, anything
						// headless). Fall back to the same safe default as a failed
						// prompt: keep the stale cache rather than risk hanging on a
						// read that will never complete. Always print this, even with
						// verbose off, since it's a silent behavior change worth knowing.
						fmt.Println("Cached header index is stale by age, but stdin isn't an interactive terminal - keeping it as-is. Pass -assume-yes-stale-deps to refresh automatically in non-interactive contexts.")
						return cache.Index, true, nil
					}
					question := fmt.Sprintf(
						"Cached header index is older than %s (age: %s). Rebuild now? This walks the platform/library directories and may take a moment. [y/N]: ",
						maxAge, age.Round(time.Second),
					)
					if !promptYesNo(question) {
						fmt.Println("Keeping the existing cached index for this build. Pass -refresh-deps-index anytime to rebuild it explicitly.")
						return cache.Index, true, nil
					}
				}
			}
		}
	} else if verbose {
		fmt.Println("Rebuilding header index (forced via -refresh-deps-index).")
	}

	if verbose {
		fmt.Println("Walking platform/library directories to rebuild header index...")
	}
	index := buildHeaderIndex(roots...)

	cache := headerIndexCache{Signature: sig, BuiltAt: time.Now(), Index: index}
	if data, err := json.Marshal(cache); err == nil {
		// Best-effort write: a failed write just means the next run rebuilds the
		// index from scratch again, same as if the cache never existed.
		_ = os.WriteFile(cachePath, data, 0o644)
	}
	if useHeaderIndexMemCache {
		headerIndexMemCache.Store(cachePath, cache)
	}
	return index, false, nil
}

// buildHeaderIndex walks the given root directories once and returns a map from a
// header/source filename (basename only, e.g. "FastLED.h") to every full path found
// with that name. Multiple candidates are possible (e.g. same filename in two
// libraries) - we hash all of them rather than guessing which one actually applies,
// trading a little over-invalidation for not silently missing a real dependency.
func buildHeaderIndex(roots ...string) map[string][]string {
	index := make(map[string][]string)
	for _, root := range roots {
		if root == "" {
			continue
		}
		filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
			if err != nil || info.IsDir() {
				return nil
			}
			name := filepath.Base(path)
			index[name] = append(index[name], path)
			return nil
		})
	}
	return index
}

// resolveInclude picks the candidate path(s) for an include found while scanning
// fromFile. Quoted includes ("foo.h") are resolved relative to the includer's own
// directory first, since that's what a real preprocessor does and it's the common
// case that causes basename collisions (e.g. two unrelated libraries both shipping a
// "config.h" - a library's own files including each other via quotes should resolve
// to the sibling file, not get lumped in with every other config.h on the system).
// Angle-bracket includes (<foo.h>) always fall back to the global headerIndex, since
// those are meant to search library/core include paths rather than the local
// directory. If no same-directory match exists for a quoted include, we also fall
// back to headerIndex - better to over-hash than to silently miss a dependency.
func resolveInclude(fromFile, includedName string, quoted bool, headerIndex map[string][]string) []string {
	base := filepath.Base(includedName)
	if quoted {
		local := filepath.Join(filepath.Dir(fromFile), includedName)
		if _, err := os.Stat(local); err == nil {
			return []string{local}
		}
	}
	return headerIndex[base]
}

// hashDependencies scans sketchFiles (and, transitively, every header they include
// that we can resolve via headerIndex), writes each resolved file's contents into h,
// and returns how many files it hashed (for build statistics). This is what catches
// "you edited FastLED.h directly" - previously invisible to fastbuild's cache check,
// since it only ever looked at files sitting next to the sketch itself.
//
// This is a regex-based #include scan, not a real C preprocessor - it doesn't evaluate
// #ifdef/#ifndef guards around includes, so it may occasionally hash a header that a
// particular build configuration wouldn't actually use. That's a safe direction to be
// wrong in (an unnecessary recompile now and then) rather than the reverse (a missed
// dependency serving a stale binary).
//
// The include-graph traversal itself stays sequential (each step depends on what the
// previous step's file content revealed), but once the full set of files to hash is
// known, reading their contents is embarrassingly parallel - there's no dependency
// between reading file A and reading file B. That read (and only the read) is farmed
// out to a small worker pool; the actual hash.Write calls still happen sequentially
// afterwards, in sorted path order, so the resulting hash is exactly as deterministic
// as the original sequential version regardless of goroutine scheduling.
// resolveDependencyFiles performs a breadth-first walk over sketchFiles, following
// every #include line (via resolveInclude/headerIndex) to find every library/core
// header transitively pulled in, and returns every file successfully read along the
// way - sketch files plus every included header - deduplicated and sorted.
//
// This is the traversal half of what used to be a single combined
// resolve-and-hash function; the hashing half is now hashFileList, shared with the
// depfile dependency provider (see deps_provider.go) so there's exactly one hashing
// implementation (with one concurrent-read worker pool) instead of two mode-specific
// ones that had drifted into different behavior (this one used to hash concurrently;
// the depfile path used to hash sequentially, for no functional reason - just an
// accident of having been written separately).
func resolveDependencyFiles(sketchFiles []string, headerIndex map[string][]string) []string {
	visited := make(map[string]bool)
	queue := append([]string{}, sketchFiles...)

	var resolved []string

	for len(queue) > 0 {
		path := queue[0]
		queue = queue[1:]
		if visited[path] {
			continue
		}
		visited[path] = true

		data, err := os.ReadFile(path)
		if err != nil {
			// A resolved candidate that can't be read (permissions, race with an
			// editor save) shouldn't hard-fail the whole build - skip it.
			continue
		}
		resolved = append(resolved, path)

		for _, m := range includePattern.FindAllStringSubmatch(string(data), -1) {
			quoted := m[1] == `"`
			includedName := m[2]
			for _, candidate := range resolveInclude(path, includedName, quoted, headerIndex) {
				if !visited[candidate] {
					queue = append(queue, candidate)
				}
			}
		}
	}

	sort.Strings(resolved)
	return resolved
}

// dependencyAwareHashComponent computes the extra hash input covering library/core
// header dependencies and toolchain version, given the config's configFile points at
// a real arduino-cli.yaml. Returns an empty hash component (not an error) if
// configFile isn't set - deep dependency hashing is an enhancement on top of the base
// sketch hash, not a hard requirement, so fastbuild still works without it (with the
// previously-known limitation that library edits won't be detected). depStats is
// always populated (even on the early-return paths) so callers can print build
// statistics unconditionally.
type depHashStats struct {
	depFileCount       int    // number of library/core header files hashed this run
	headerIndexCached  bool   // (regex mode only) true if the header index came from cache, false if freshly rebuilt
	headerIndexEnabled bool   // true if dependency-aware hashing was actually attempted this run, in either mode
	depsSource         string // "depfile", "regex", or "regex (depfile bootstrap)" - which mechanism actually supplied the dependency list this run, for -stats
}

func dependencyAwareHashComponent(cfg *config, sketchFiles []string, projectDir string) (string, depHashStats, error) {
	var stats depHashStats
	if cfg.configFile == "" {
		return "", stats, nil
	}
	if !cfg.hashLibraryHeaders && !cfg.hashToolchain {
		// Both disabled - nothing to do, don't even bother reading arduino-cli.yaml.
		return "", stats, nil
	}

	dirs, err := readArduinoDirs(cfg.configFile)
	if err != nil {
		return "", stats, fmt.Errorf("reading arduino directories: %w", err)
	}

	h := sha256.New()

	if cfg.hashToolchain {
		platformDir, err := resolvePlatformDir(dirs.data, cfg.fqbn, cfg.platformVersion)
		if err != nil {
			return "", stats, fmt.Errorf("resolving platform dir: %w", err)
		}
		toolFingerprint, err := toolchainFingerprint(dirs.data, cfg.fqbn)
		if err != nil {
			return "", stats, fmt.Errorf("resolving toolchain fingerprint: %w", err)
		}
		h.Write([]byte("platformDir:" + platformDir))
		h.Write([]byte("toolchain:" + strings.Join(toolFingerprint, "|")))
	}

	if cfg.hashLibraryHeaders {
		provider := newDependencyProvider(cfg)
		paths, depStats, err := provider.Dependencies(cfg, dirs, sketchFiles, projectDir)
		if err != nil {
			return "", stats, err
		}
		stats.headerIndexCached = depStats.headerIndexCached
		stats.headerIndexEnabled = depStats.headerIndexEnabled
		stats.depsSource = depStats.depsSource

		depCount, err := hashFileList(h, paths)
		if err != nil {
			return "", stats, err
		}
		stats.depFileCount = depCount

		// Silent-degrade protection (see README): only meaningful for the depfile
		// provider, whose paths come from the compiler itself rather than being
		// resolved fresh every run - if most of THOSE specifically fail to read
		// back, dependency-aware hashing may be silently degrading to a near
		// no-op with no other visible symptom. The regex provider re-resolves
		// paths fresh each run against files it just confirmed exist, so an
		// equivalent mismatch there isn't the same kind of stale-data warning
		// sign and has never warned here.
		if stats.depsSource == "depfile" && depCount < len(paths)/2 {
			fmt.Fprintf(os.Stderr,
				"fastbuild: warning: only %d of %d GCC-reported dependency paths could be read - dependency-aware hashing may be unreliable this run. Check that gcc-deps.json's paths are absolute and still exist, or run with -refresh-deps-index / delete gcc-deps.json to re-bootstrap.\n",
				depCount, len(paths))
		}
	}

	return fmt.Sprintf("%x", h.Sum(nil)), stats, nil
}

// hashFileList reads and hashes an explicit list of paths, sorted first for a
// deterministic hash regardless of input order, with reads happening concurrently
// (bounded by GOMAXPROCS-ish worker count) since these are typically dozens to
// hundreds of small header files - one at a time would leave most of that I/O
// serialized for no benefit. Shared by both dependency providers (see
// deps_provider.go): regexProvider's resolved #include walk, and depfileProvider's
// compiler-reported .d-file list - one hashing implementation for both, rather than
// each mode having its own (previously true only for the regex path).
//
// A path that fails to read (removed, permissions, a race with an editor save, or -
// for the depfile path specifically - a compiler-reported path that's since moved)
// is silently skipped rather than failing the whole build; the returned count is how
// many were actually hashed, letting the caller notice if that count came back
// suspiciously low relative to len(paths).
func hashFileList(h hash.Hash, paths []string) (int, error) {
	sortedPaths := append([]string(nil), paths...)
	sort.Strings(sortedPaths)

	contents := make([][]byte, len(sortedPaths))
	var wg sync.WaitGroup
	workers := runtime.NumCPU()
	if workers < 1 {
		workers = 1
	}
	sem := make(chan struct{}, workers)
	for i, path := range sortedPaths {
		wg.Add(1)
		sem <- struct{}{}
		go func(i int, path string) {
			defer wg.Done()
			defer func() { <-sem }()
			data, err := os.ReadFile(path)
			if err != nil {
				return // leave contents[i] nil; skipped below
			}
			contents[i] = data
		}(i, path)
	}
	wg.Wait()

	count := 0
	for i, path := range sortedPaths {
		if contents[i] == nil {
			continue
		}
		h.Write([]byte(path))
		h.Write(contents[i])
		count++
	}
	return count, nil
}
