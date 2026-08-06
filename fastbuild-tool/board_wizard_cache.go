// board_wizard_cache.go implements caching for -configure-board's two slow
// arduino-cli calls (core list, board listall). Both enumerate every installed
// platform's boards, which - especially with several platforms installed (AVR,
// ESP8266, ESP32, SAMD, ...) - is the actual source of the noticeable delay after the
// wizard's PID line, not something the spinner alone can fix.
//
// Caching strategy: rather than a time-based expiry (which would either go stale
// silently or force annoying manual refreshes), the cache is invalidated by a
// signature computed directly from disk - the installed platform version folders
// under <data>/packages/*/hardware/*/*, plus the modification times of each
// platform's boards.txt/platform.txt. Installing, removing, or upgrading a platform
// changes which version folders exist; hand-editing boards.txt (rare, but possible
// for a custom/local core) changes its mtime. Either changes the signature, which
// invalidates the cache automatically - no explicit "did you update your boards"
// step required.
//
// This signature computation is itself fast (a bounded glob plus a couple of stat
// calls per installed platform - a few dozen filesystem operations at most), far
// cheaper than spawning arduino-cli and having it enumerate everything, which is
// what makes caching worthwhile here at all.
package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"time"
)

// boardConfigValue and boardConfigOption mirror the shape of `arduino-cli board
// details --json`'s config_options - named (rather than left as an anonymous struct
// inside configureBoardOptions, as originally written) so the same type can be both
// parsed from arduino-cli's JSON output AND round-tripped through
// board-wizard-cache.json without duplicating the field list in two places.
type boardConfigValue struct {
	Value      string `json:"value"`
	ValueLabel string `json:"value_label"`
	Selected   bool   `json:"selected"`
}

type boardConfigOption struct {
	Option      string             `json:"option"`
	OptionLabel string             `json:"option_label"`
	Values      []boardConfigValue `json:"values"`
}

// wizardCacheData is what's persisted to board-wizard-cache.json.
type wizardCacheData struct {
	Signature   string              `json:"signature"`
	GeneratedAt time.Time           `json:"generatedAt"`
	Platforms   []installedPlatform `json:"platforms"`
	Boards      []boardEntry        `json:"boards"` // every board from every platform, unfiltered - see listAllBoards

	// BoardOptions caches step 3 (arduino-cli board details --json), keyed by base
	// FQBN (e.g. "esp8266:esp8266:nodemcu"). This is the per-board menu-options call,
	// separate from Boards above (which only holds names/FQBNs from step 2) - a board
	// with zero menu options is still cached, as an empty (non-nil) slice, so that
	// "never looked up" (key absent) and "looked up, has no options" (key present,
	// empty) stay distinguishable and neither one triggers a repeat arduino-cli call.
	BoardOptions map[string][]boardConfigOption `json:"boardOptions"`

	// Complete is false while a bulk prefetch (-wizard-prefetch) still has boards
	// left to fetch, and true once every board has been attempted. A file saved
	// mid-prefetch (see saveWizardCacheAtomic) is always internally consistent - it's
	// just missing some BoardOptions entries - so Complete is what tells the next run
	// "resume the remaining boards" instead of "treat this as the finished dataset".
	// Absent in cache files written before this field existed, which unmarshal it as
	// false - that's fine, it just means an old-format complete cache gets treated as
	// an incomplete one once, finds nothing left to fetch, and re-saves itself as
	// Complete:true - a harmless one-time upgrade.
	Complete bool `json:"complete"`
}

// computeInstalledPlatformsSignature fingerprints the installed platforms directly
// from disk: every matched <data>/packages/<pkg>/hardware/<arch>/<version> folder,
// plus the mtimes of that folder's boards.txt and platform.txt if present. Sorted and
// hashed for a stable, order-independent result.
func computeInstalledPlatformsSignature(dataDir string) (string, error) {
	pattern := filepath.Join(dataDir, "packages", "*", "hardware", "*", "*")
	matches, err := filepath.Glob(pattern)
	if err != nil {
		return "", err
	}
	if len(matches) == 0 {
		return "", fmt.Errorf("no installed platforms found under %s", pattern)
	}

	var entries []string
	for _, dir := range matches {
		rel, err := filepath.Rel(dataDir, dir)
		if err != nil {
			rel = dir
		}
		entry := filepath.ToSlash(rel)

		// boards.txt/platform.txt mtimes catch an in-place edit that doesn't change
		// the version folder name itself (e.g. a hand-modified local core) - the
		// parent directory's own mtime is NOT a reliable signal for this on most
		// filesystems (modifying a file inside a directory doesn't necessarily
		// update that directory's own mtime), so the files themselves are checked
		// directly instead.
		for _, fname := range []string{"boards.txt", "platform.txt"} {
			if info, err := os.Stat(filepath.Join(dir, fname)); err == nil {
				entry += "|" + fname + "@" + info.ModTime().UTC().Format(time.RFC3339)
			}
		}
		entries = append(entries, entry)
	}
	sort.Strings(entries)

	h := sha256.New()
	for _, e := range entries {
		h.Write([]byte(e))
		h.Write([]byte{0}) // separator, so "a"+"b" can't collide with "ab"
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// loadWizardCache reads cachePath and returns its contents only if they parse
// correctly AND the stored signature matches expectedSignature exactly. Any mismatch,
// missing file, or parse failure returns (nil, false) - always safe to fall through
// to a live arduino-cli fetch, never treated as an error worth surfacing to the user.
func loadWizardCache(cachePath, expectedSignature string) (*wizardCacheData, bool) {
	data, err := os.ReadFile(cachePath)
	if err != nil {
		return nil, false
	}
	var cache wizardCacheData
	if err := json.Unmarshal(data, &cache); err != nil {
		return nil, false
	}
	if cache.Signature != expectedSignature {
		return nil, false
	}
	if len(cache.Platforms) == 0 {
		return nil, false // an empty/corrupt-looking cache is treated as no cache
	}
	return &cache, true
}

// saveWizardCache writes the cache, best-effort. A failure here (permissions, disk
// full, cacheDir not creatable) is non-fatal and doesn't interrupt the wizard - the
// FQBN it just helped build is already in hand regardless of whether the cache for
// next time got saved successfully.
//
// Writes go to a temp file in the same directory first, then an atomic rename over
// cachePath - never a direct write to cachePath itself. That means a crash, kill, or
// power loss mid-write can only ever leave behind an inert, ignored .tmp file; the
// real cachePath is left completely untouched until the moment a fully-encoded,
// complete file is ready to replace it in one atomic step. os.Rename onto an existing
// destination is atomic on both POSIX and Windows, so a reader can never observe a
// half-written file - only the previous complete version or the new complete version.
//
// This matters most for prefetchAllBoardOptions, which calls this repeatedly with a
// growing-but-still-Complete:false BoardOptions map as boards finish - each of those
// intermediate saves is itself a perfectly valid, parseable cache file (just missing
// some board entries), so an interruption at any point leaves real, resumable
// progress on disk instead of nothing (the old single-write-at-the-very-end
// behavior) or a corrupted half-written file (what a naive in-place incremental write
// would risk).
func saveWizardCache(cachePath string, data *wizardCacheData) {
	dir := filepath.Dir(cachePath)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return
	}
	encoded, err := json.MarshalIndent(data, "", "  ")
	if err != nil {
		return
	}

	tmpPath := cachePath + ".tmp"
	if err := os.WriteFile(tmpPath, encoded, 0o644); err != nil {
		return
	}
	if err := os.Rename(tmpPath, cachePath); err != nil {
		// Best-effort, like the rest of this function - but don't leave a stray
		// .tmp lying around if the rename itself failed for some reason (e.g.
		// destination locked by another process). It's inert either way (never
		// read by loadWizardCache), so this is just tidiness, not correctness.
		_ = os.Remove(tmpPath)
	}
}
