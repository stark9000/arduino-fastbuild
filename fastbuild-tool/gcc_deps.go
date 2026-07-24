// gcc_deps.go implements the "depfile" depsMode (alias: "gcc"): instead of a regex #include scanner
// guessing which headers a sketch depends on, this reads the .d dependency file GCC
// itself already generates during compilation - which correctly accounts for
// #ifdef/#ifndef conditional includes, macro-controlled includes, and everything else
// a real C preprocessor understands, none of which a regex can evaluate.
//
// This works for free, with no changes to the compile command: arduino-cli already
// passes -MMD to the compiler for every board family (AVR, ESP8266, ESP32, RP2040,
// STM32, ...) as part of its standard recipe.cpp.o.pattern - confirmed directly by
// checking a real verbose ESP8266 build log, which already shows "-MMD" on the
// sketch's own compile invocation. Every GCC-based toolchain a board might use shares
// the same frontend, so the .d file format doesn't vary by board.
//
// Bootstrap problem and how it's handled: on the very first build for a project (or
// right after -clean), no .d file exists yet from a previous run, so there's nothing
// to hash against for the skip-if-unchanged check. dependencyAwareHashComponent in
// deps.go falls back to the regex scanner for exactly that one run; after the compile
// that run triggers succeeds, harvestGccDeps reads the fresh .d file and stores it, so
// every subsequent run uses the accurate, compiler-verified list instead.
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

const gccDepsFileName = "gcc-deps.json"

// gccDepsCache is what gets persisted to disk after a successful compile in gcc mode.
// Storing metadata alongside the raw dependency list - not just the list itself -
// means a future fastbuild version can recognize and safely ignore or migrate an
// older cache file if the format or harvesting logic ever changes, rather than
// silently misinterpreting stale data. Compiler is recorded too (via the same
// toolchainFingerprint used for hashToolchain) so a stored cache is self-describing:
// if you ever open gcc-deps.json by hand to debug something, you can see exactly
// which compiler produced it without cross-referencing anything else.
type gccDepsCache struct {
	Mode        string    `json:"mode"` // "depfile" currently (older files may say "gcc" - not checked at load time, purely informational); reserved for future dependency-tracking modes
	GeneratedAt time.Time `json:"generatedAt"`
	Fqbn        string    `json:"fqbn"`
	Compiler    []string  `json:"compiler"` // toolchain fingerprint at harvest time (e.g. ["xtensa-lx106-elf-gcc/3.0.4-gcc10.3-1757bed"]) - see toolchainFingerprint in deps.go
	Deps        []string  `json:"deps"`
}

// findSketchDepFiles returns every ".d" file under buildPath/sketch/ - one per local
// translation unit belonging to the sketch itself. A sketch isn't always just the
// single .ino file: it can also have its own hand-written .cpp/.c files sitting
// alongside it (e.g. main.ino + foo.cpp + bar.cpp), and arduino-cli compiles each of
// those as its own separate translation unit, producing its own separate .d file
// (foo.cpp.d, bar.cpp.d, ...) under the same buildPath/sketch/ directory as the
// .ino-derived one.
//
// Harvesting only the main .ino's own .d file (as an earlier version of this code
// did) would miss any header reached ONLY through one of those secondary local .cpp
// files' own #include graph - foo.cpp doesn't get pulled into the .ino's own
// preprocessing, so its dependencies never show up in the .ino's .d file at all. This
// walks the whole sketch/ directory and returns every .d file found there, so the
// union covers every local translation unit's own dependency graph, not just the
// main one's.
func findSketchDepFiles(buildPath string) ([]string, error) {
	sketchDir := filepath.Join(buildPath, "sketch")
	var depFiles []string
	err := filepath.Walk(sketchDir, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return nil // best-effort - a walk error on one entry shouldn't abort the rest
		}
		if !info.IsDir() && strings.HasSuffix(path, ".d") {
			depFiles = append(depFiles, path)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	sort.Strings(depFiles)
	return depFiles, nil
}

// parseGccDepFile parses a Makefile-style .d dependency file into a flat list of
// dependency paths, dropping the target (the .o file) itself. Handles line
// continuations (trailing backslash) and GCC's backslash-escaped spaces in paths.
func parseGccDepFile(path string) ([]string, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()

	// Join continuation lines into one logical line first - a .d file is one
	// "target: dep dep dep \\\n dep dep \\\n dep" statement spread across many
	// physical lines for readability.
	var joined strings.Builder
	scanner := bufio.NewScanner(f)
	// .d files can have very long single lines (every transitively included header on
	// one continuation) - grow the scanner's buffer well past the default 64KB.
	buf := make([]byte, 0, 64*1024)
	scanner.Buffer(buf, 8*1024*1024)
	for scanner.Scan() {
		line := scanner.Text()
		trimmed := strings.TrimRight(line, "\r\n")
		if strings.HasSuffix(trimmed, "\\") {
			joined.WriteString(strings.TrimSuffix(trimmed, "\\"))
			joined.WriteString(" ")
		} else {
			joined.WriteString(trimmed)
			joined.WriteString(" ")
		}
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}

	full := joined.String()
	colonIdx := strings.Index(full, ":")
	if colonIdx < 0 {
		return nil, fmt.Errorf("no ':' found in dependency file %s - unexpected format", path)
	}
	depsPart := full[colonIdx+1:]

	// GCC escapes spaces inside paths as "\ " - protect those before splitting on
	// whitespace, then restore them afterward.
	const spacePlaceholder = "\x00"
	depsPart = strings.ReplaceAll(depsPart, `\ `, spacePlaceholder)

	var deps []string
	for _, tok := range strings.Fields(depsPart) {
		tok = strings.ReplaceAll(tok, spacePlaceholder, " ")
		if tok != "" {
			deps = append(deps, tok)
		}
	}
	return deps, nil
}

// harvestGccDeps reads every local translation unit's freshly-generated .d file after
// a successful compile (see findSketchDepFiles) and persists the union of their
// dependency lists to projectDir for use as the authoritative source on the next
// run's hash check. Failure here is deliberately non-fatal - a missing or unparsable
// .d file (an unusual board recipe that doesn't emit -MMD, or a GCC version with a
// slightly different format) just means the next run falls back to the regex scanner
// again, same as the bootstrap case. It should never block a build that otherwise
// succeeded.
func harvestGccDeps(buildPath, projectDir, sketchPath, fqbn string, dataDir string, verbose bool) {
	depFiles, err := findSketchDepFiles(buildPath)
	if err != nil {
		if verbose {
			fmt.Println("fastbuild: could not scan for GCC dependency files (falling back to regex scanning next run):", err)
		}
		return
	}
	if len(depFiles) == 0 {
		if verbose {
			fmt.Println("fastbuild: no GCC dependency (.d) files found under", filepath.Join(buildPath, "sketch"), "- falling back to regex scanning next run")
		}
		return
	}

	seen := make(map[string]bool)
	var deps []string
	for _, df := range depFiles {
		fileDeps, err := parseGccDepFile(df)
		if err != nil {
			if verbose {
				fmt.Println("fastbuild: could not parse GCC dependency file", df, "- skipping it:", err)
			}
			continue // one malformed .d file among several shouldn't discard the rest
		}
		for _, d := range fileDeps {
			if !seen[d] {
				seen[d] = true
				deps = append(deps, d)
			}
		}
	}
	if len(deps) == 0 {
		if verbose {
			fmt.Println("fastbuild: found .d files but none contained any dependencies - falling back to regex scanning next run")
		}
		return
	}
	sort.Strings(deps)

	// Best-effort: record the compiler that produced these .d files, so gcc-deps.json
	// is self-describing if you ever open it by hand. A failure here (e.g. the
	// platform directory can't be resolved for some reason) shouldn't block saving the
	// dependency list itself - the fingerprint is a nice-to-have, not load-bearing.
	var compiler []string
	if fp, err := toolchainFingerprint(dataDir, fqbn); err == nil {
		compiler = fp
	}

	cache := gccDepsCache{
		Mode:        "depfile",
		GeneratedAt: time.Now(),
		Fqbn:        fqbn,
		Compiler:    compiler,
		Deps:        deps,
	}
	data, err := json.MarshalIndent(cache, "", "  ")
	if err != nil {
		if verbose {
			fmt.Println("fastbuild: could not encode GCC dependency list:", err)
		}
		return
	}
	storedPath := filepath.Join(projectDir, gccDepsFileName)
	if err := os.WriteFile(storedPath, data, 0o644); err != nil {
		if verbose {
			fmt.Println("fastbuild: could not save GCC dependency list:", err)
		}
		return
	}
	if verbose {
		fmt.Printf("fastbuild: saved %d GCC-verified dependencies from %d translation unit(s) for next run\n", len(deps), len(depFiles))
	}
}

// loadStoredGccDeps returns the dependency list saved by a previous successful
// compile's harvestGccDeps call, or nil if none exists yet (bootstrap case - the
// caller should fall back to the regex scanner for this one run).
func loadStoredGccDeps(projectDir string) []string {
	storedPath := filepath.Join(projectDir, gccDepsFileName)
	data, err := os.ReadFile(storedPath)
	if err != nil {
		return nil
	}
	var cache gccDepsCache
	if err := json.Unmarshal(data, &cache); err != nil {
		// Unreadable/incompatible cache format (e.g. an older plain-text version of
		// this file, or a future format this build predates) - treat exactly like
		// "no cache yet" and fall back to the regex scanner for this run, rather than
		// failing the build over a harvested-dependency cache being stale.
		return nil
	}
	return cache.Deps
}

// effectiveBuildProps returns the --build-property list actually used for a compile,
// injecting "-MMD" into build.extra_flags when depsMode is "depfile" and gccInjectMMD is
// enabled.
//
// depsMode=gcc normally assumes arduino-cli's own recipe.cpp.o.pattern already passes
// -MMD to the compiler by default - true for the standard AVR/ESP8266/ESP32/SAMD cores
// checked directly against a verbose build log, but not something fastbuild can
// guarantee for every third-party board package out there. gccInjectMMD is the
// explicit override for when that assumption doesn't hold (or you just want to be
// certain rather than rely on it): fastbuild adds -MMD itself via build.extra_flags,
// which every standard Arduino platform.txt recipe already includes as a deliberate
// "put your own extra flags here" slot, so this doesn't require guessing at or
// clobbering a board-specific compiler.*.extra_flags value.
//
// If the config's own buildProps already sets build.extra_flags (e.g. for an
// unrelated reason like enabling a warning flag), -MMD is appended to that existing
// value instead of adding a second, conflicting --build-property for the same key -
// arduino-cli takes the last occurrence of a repeated --build-property key, so two
// separate entries would silently discard whichever came first rather than combining.
//
// Returns a fresh slice; cfg.buildProps itself is never mutated, since run() reuses
// the same *config across every -watch poll tick and a real compile could happen on
// any of them.
func effectiveBuildProps(cfg *config) []string {
	if cfg.depsMode != "depfile" || !cfg.gccInjectMMD {
		return cfg.buildProps
	}

	props := append([]string(nil), cfg.buildProps...)
	for i, p := range props {
		if strings.HasPrefix(p, "build.extra_flags=") {
			props[i] = p + " -MMD"
			return props
		}
	}
	return append(props, "build.extra_flags=-MMD")
}
