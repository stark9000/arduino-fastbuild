// deps_provider.go implements the reviewer-suggested DependencyProvider abstraction:
// one interface, two implementations (regex #include scanning vs compiler-generated
// .d files), picked once by newDependencyProvider based on cfg.depsMode, rather than
// dependencyAwareHashComponent branching on cfg.depsMode directly inline. Mirrors the
// original suggestion closely:
//
//	type DependencyProvider interface {
//	    Dependencies(cfg Config) ([]string, error)
//	}
//
// adapted for this project's actual shape: Dependencies also takes the already-loaded
// arduinoDirs (dependencyAwareHashComponent already reads arduino-cli.yaml once, for
// both toolchain and header hashing - no reason for the provider to re-read it) and
// projectDir (where a depfile provider's previously-harvested .d-derived list lives),
// and returns depHashStats alongside the path list so -stats reporting (which source
// actually supplied the list, whether the regex header index came from cache) keeps
// working without the caller needing to know which concrete provider ran.
package main

import (
	"fmt"
	"path/filepath"
)

// DependencyProvider resolves which files, beyond the sketch's own source files, this
// build depends on - the library/core headers that should be folded into fastbuild's
// change-detection hash. Two implementations: regexProvider (the #include scanner) and
// depfileProvider (the compiler's own generated dependency file, more accurate but
// needs one prior successful build to bootstrap - see depfileProvider below).
type DependencyProvider interface {
	// Dependencies returns the resolved dependency file paths for this run. An
	// empty/nil slice with a nil error is valid whenever there's nothing to add
	// (not expected in practice here, since dependencyAwareHashComponent only calls
	// this when cfg.hashLibraryHeaders is true, but implementations shouldn't treat
	// "found nothing" as inherently an error condition).
	Dependencies(cfg *config, dirs *arduinoDirs, sketchFiles []string, projectDir string) ([]string, depHashStats, error)
}

// newDependencyProvider is the one place cfg.depsMode gets switched on to pick a
// DependencyProvider - everywhere else (currently just dependencyAwareHashComponent)
// just calls provider.Dependencies without needing its own mode check.
func newDependencyProvider(cfg *config) DependencyProvider {
	if cfg.depsMode == "depfile" {
		return depfileProvider{}
	}
	return regexProvider{}
}

// regexProvider resolves dependencies by scanning #include lines, transitively,
// against the cached library/core header index. Doesn't evaluate #ifdef/#ifndef (see
// README's Known limitations) - a real C preprocessor concern depfileProvider sidesteps
// entirely by asking the compiler directly instead.
type regexProvider struct{}

func (regexProvider) Dependencies(cfg *config, dirs *arduinoDirs, sketchFiles []string, projectDir string) ([]string, depHashStats, error) {
	var stats depHashStats

	platformDir, err := resolvePlatformDir(dirs.data, cfg.fqbn, cfg.platformVersion)
	if err != nil {
		return nil, stats, fmt.Errorf("resolving platform dir: %w", err)
	}
	userLibrariesDir := filepath.Join(dirs.user, "libraries")
	// Cache file is keyed by platform + actual resolved version (see
	// platformCacheKey) - not shared across platforms (switching between e.g. ESP8266
	// and ESP32 projects never evicts the other platform's cached index) NOR across
	// versions of the same platform (an arduino-cli core upgrade, or a different
	// -platform-version, gets its own cache entry instead of silently reusing another
	// version's index). resolvedVersion comes from platformDir itself - the directory
	// resolvePlatformDir actually picked - not cfg.platformVersion directly, since
	// that's blank under auto-select and would otherwise miss the upgrade case.
	resolvedVersion := filepath.Base(platformDir)
	cachePath := filepath.Join(cfg.cacheRoot, fmt.Sprintf("header-index-%s.json", platformCacheKey(cfg.fqbn, resolvedVersion)))
	headerIndex, cached, err := loadOrBuildHeaderIndex(cachePath, cfg.refreshDepsIndex, cfg.verbose, cfg.depsIndexMaxAge, cfg.staleDepsIndexDecision, platformDir, userLibrariesDir)
	if err != nil {
		return nil, stats, fmt.Errorf("loading header index: %w", err)
	}
	stats.headerIndexCached = cached
	stats.headerIndexEnabled = true
	stats.depsSource = "regex"

	return resolveDependencyFiles(sketchFiles, headerIndex), stats, nil
}

// depfileProvider resolves dependencies from a previous build's compiler-generated
// dependency file (harvested by harvestGccDeps in gcc_deps.go, after a real compile) -
// the compiler's own post-preprocessor view of exactly which files mattered, correctly
// handling #ifdef-guarded includes a regex fundamentally can't evaluate. See README's
// Dependency file mode section.
type depfileProvider struct{}

func (depfileProvider) Dependencies(cfg *config, dirs *arduinoDirs, sketchFiles []string, projectDir string) ([]string, depHashStats, error) {
	var stats depHashStats

	if storedDeps := loadStoredGccDeps(projectDir); len(storedDeps) > 0 {
		stats.headerIndexEnabled = true
		stats.depsSource = "depfile"
		return storedDeps, stats, nil
	}

	// Bootstrap case: no .d file has been harvested yet (first build for this
	// project, or right after -clean). Fall back to the regex scanner for this one
	// run - harvestGccDeps will populate the stored list after this build succeeds,
	// so the next run uses depfileProvider's fast path instead.
	if cfg.verbose {
		fmt.Println("fastbuild: no GCC dependency file harvested yet - using regex scanner for this run (bootstrap)")
	}
	paths, stats, err := regexProvider{}.Dependencies(cfg, dirs, sketchFiles, projectDir)
	if err != nil {
		return nil, stats, err
	}
	stats.depsSource = "regex (depfile bootstrap)"
	return paths, stats, nil
}
