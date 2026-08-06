// board_wizard.go implements -configure-board: an interactive, terminal-based wizard
// that assembles a full FQBN (base board id plus every menu option, e.g.
// "esp8266:esp8266:nodemcu:xtal=80,vt=flash,...") without requiring it to be typed by
// hand or copied out of a verbose IDE build log.
//
// Three steps, each narrowing down from the last:
//  1. List installed platforms (arduino-cli core list), grouped by vendor
//     (esp8266, arduino, STMicroelectronics, ...) and shown alphabetically - so
//     "which of my ~10 installed platforms is this board" is a short list, not one
//     giant alphabetical dump of every board from every platform at once.
//  2. List boards belonging to the chosen platform (arduino-cli board listall),
//     narrowed to just that platform's boards.
//  3. Load that board's menu options (arduino-cli board details --json) and prompt
//     for each one, defaulting to the board's own default selection.
//
// Parsing choices, deliberately: steps 1 and 2 parse arduino-cli's plain TEXT output,
// not --format json. This project has been burned before by assuming an unverified
// JSON schema (the FQBN "xtal=80" incident) - the text formats for `core list` and
// `board listall` were directly observed against real arduino-cli output earlier in
// this project, so they're the more trustworthy parsing target here. Step 3 does use
// --json (board details' structure isn't practical to parse from text), but degrades
// gracefully to "just the base FQBN, no menu options" if the response doesn't have
// the shape expected, rather than crashing.
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type installedPlatform struct {
	ID   string // e.g. "esp8266:esp8266"
	Name string // e.g. "ESP8266 Boards (3.0.2)"
}

type boardEntry struct {
	Name string // e.g. "NodeMCU 1.0"
	FQBN string // e.g. "esp8266:esp8266:nodemcu"
}

// runBoardWizard drives the whole interactive flow and prints the final FQBN.
func runBoardWizard(arduinoCLI, configFile, cacheDir string, forceRefresh bool, prefetchMode string, prefetchWorkers int) error {
	cachePath := filepath.Join(cacheDir, "board-wizard-cache.json")

	cache, signature, fromCache, err := resolveWizardCache(arduinoCLI, configFile, cachePath, forceRefresh, prefetchMode, prefetchWorkers)
	if err != nil {
		return err
	}
	if len(cache.Platforms) == 0 {
		return fmt.Errorf("no platforms installed - install one first, e.g. `arduino-cli core install esp8266:esp8266`")
	}
	if fromCache {
		fmt.Println("Using cached platform/board list (unchanged since last check).")
	}
	platforms := cache.Platforms
	boards := cache.Boards
	sort.Slice(platforms, func(i, j int) bool {
		return strings.ToLower(platforms[i].Name) < strings.ToLower(platforms[j].Name)
	})

	reader := bufio.NewReader(os.Stdin)

	fmt.Println("Installed platforms:")
	for i, p := range platforms {
		fmt.Printf("  %2d) %s  (%s)\n", i+1, p.Name, p.ID)
	}
	platformIndex := promptChoiceIndex(reader, "Platform", len(platforms))
	platform := platforms[platformIndex]

	platformBoards := filterBoardsByPlatform(boards, platform.ID)
	if len(platformBoards) == 0 {
		return fmt.Errorf("no boards found for platform %s", platform.ID)
	}
	sort.Slice(platformBoards, func(i, j int) bool {
		return strings.ToLower(platformBoards[i].Name) < strings.ToLower(platformBoards[j].Name)
	})

	fmt.Println("\nBoards:")
	for i, b := range platformBoards {
		fmt.Printf("  %2d) %s\n", i+1, b.Name)
	}
	boardIndex := promptChoiceIndex(reader, "Board", len(platformBoards))
	board := platformBoards[boardIndex]

	finalFqbn, err := configureBoardOptions(reader, arduinoCLI, configFile, board.FQBN, cache, cachePath, signature)
	if err != nil {
		// Degrade gracefully - the base FQBN is still useful even without menu
		// options, and some boards genuinely have none.
		fmt.Fprintln(os.Stderr, "fastbuild: could not load menu options, using base FQBN only:", err)
		finalFqbn = board.FQBN
	}

	fmt.Println()
	fmt.Println("Final FQBN:")
	fmt.Println("  " + finalFqbn)
	fmt.Println()
	if err := copyToClipboard(finalFqbn); err != nil {
		// Non-fatal - clipboard access can fail in odd environments (no desktop
		// session, some Remote Desktop configurations, clip.exe missing/blocked by
		// policy). The FQBN is already printed above regardless, so this never
		// blocks the wizard from completing successfully.
		fmt.Fprintln(os.Stderr, "fastbuild: could not copy to clipboard:", err)
		fmt.Println("Paste this into your config file's fqbn= line.")
	} else {
		fmt.Println("Copied to clipboard.")
		fmt.Println("Paste this into your config file's fqbn= line.")
	}
	return nil
}

// resolveWizardCache is the cache-or-fetch orchestration for the wizard's slow
// arduino-cli calls: core list and board listall up front, plus - lazily, per board,
// from configureBoardOptions - board details. If a signature can be computed
// (requires configFile, so the installed-platforms directory is known) and a cached
// result exists whose signature still matches, the platform/board list is returned
// immediately with no arduino-cli calls at all - skipping both spinners entirely.
// Otherwise falls back to the live calls (same as before caching existed), and - if a
// signature is available - saves a fresh cache afterward so the next run can skip
// ahead.
//
// The returned *wizardCacheData is always non-nil with a non-nil BoardOptions map
// (even on a fresh/uncached run), so callers can read and add to it uniformly without
// a nil check - configureBoardOptions relies on this to cache step 3's result into the
// same struct/file.
//
// forceRefresh (-refresh-wizard-cache) always takes the live path and rewrites the
// cache, regardless of whether the existing one would otherwise still be considered
// valid - the same escape hatch this project already offers for the header-index
// cache (-refresh-deps-index), for consistency.
func resolveWizardCache(arduinoCLI, configFile, cachePath string, forceRefresh bool, prefetchMode string, prefetchWorkers int) (*wizardCacheData, string, bool, error) {
	var signature string
	if configFile != "" {
		if dirs, err := readArduinoDirs(configFile); err == nil {
			if sig, err := computeInstalledPlatformsSignature(dirs.data); err == nil {
				signature = sig
			}
			// A failure computing the signature (missing packages dir, permissions,
			// etc.) just means caching is unavailable this run - fall through to the
			// live path below, same as if configFile had never been set at all.
		}
	}

	var platforms []installedPlatform
	var boards []boardEntry
	boardOptions := make(map[string][]boardConfigOption)
	resuming := false

	if !forceRefresh && signature != "" {
		if cached, ok := loadWizardCache(cachePath, signature); ok {
			if cached.BoardOptions == nil {
				cached.BoardOptions = make(map[string][]boardConfigOption)
			}
			if cached.Complete {
				return cached, signature, true, nil
			}
			// Complete:false means a previous prefetch was interrupted (crash,
			// cancel, kill) partway through. The platform/board lists themselves
			// are never touched incrementally (they're written once, before
			// prefetching starts - see below), so if they're present at all
			// they're already trustworthy; reuse them and skip re-listing, then
			// resume the prefetch for only the boards still missing from
			// BoardOptions rather than starting over from zero.
			if len(cached.Platforms) > 0 && len(cached.Boards) > 0 {
				platforms = cached.Platforms
				boards = cached.Boards
				boardOptions = cached.BoardOptions
				resuming = true
			}
		}
	}

	if !resuming {
		var err error
		platforms, err = listInstalledPlatforms(arduinoCLI, configFile)
		if err != nil {
			return nil, "", false, fmt.Errorf("listing installed platforms: %w", err)
		}
		boards, err = listAllBoards(arduinoCLI, configFile)
		if err != nil {
			return nil, "", false, fmt.Errorf("listing boards: %w", err)
		}
	}

	// Whether to prefetch every board's menu options now, rather than only caching
	// each board lazily the first time it's actually picked, is controlled by
	// -wizard-prefetch:
	//  - "off": never prefetch. Only the boards actually picked get cached, one at a
	//    time - the original lazy-only behavior, before prefetching existed. Fastest
	//    to get to a board selection right now, at the cost of every board staying
	//    slow until it happens to be picked once.
	//  - "full": always prefetch every board on any fresh fetch, no prompt.
	//  - "ask" (default): prompts right here, once, only when a fresh fetch is
	//    actually needed (first run, or the signature changed) - not on every
	//    invocation, since a cache hit never reaches this code at all. Defaults to
	//    skipping the prefetch (same as "off") if stdin isn't interactive, since an
	//    unattended/scripted invocation has no one to answer and launching dozens of
	//    concurrent arduino-cli processes without consent isn't a safe default.
	//
	// Skipped entirely regardless of mode if there's no signature to cache against -
	// with nothing to persist to disk, spending the time would only benefit this one
	// process, which never re-reads a board it just fetched (the wizard only visits
	// one board per run), so there's nothing to gain.
	// Only boards not already sitting in BoardOptions need fetching - on a fresh
	// (non-resuming) run that's every board; when resuming an interrupted prefetch
	// it's just whatever didn't finish last time.
	var remaining []boardEntry
	for _, b := range boards {
		if _, ok := boardOptions[b.FQBN]; !ok {
			remaining = append(remaining, b)
		}
	}

	doPrefetch := false
	switch prefetchMode {
	case "off":
		doPrefetch = false
	case "full":
		doPrefetch = true
	default: // "ask", or anything unrecognized - fail toward the safer/faster option
		if len(remaining) == 0 {
			doPrefetch = false // resumed cache turned out to already be complete
		} else if !isInteractiveStdin() {
			fmt.Println("Not prefetching board options (stdin not interactive) - boards will be cached as you pick them. Pass -wizard-prefetch=full to always prefetch everything.")
			doPrefetch = false
		} else {
			verb := "Prefetch"
			if resuming {
				verb = "Resume prefetching"
			}
			doPrefetch = promptYesNo(fmt.Sprintf("%s menu options for %d board(s) now? This can take a while and briefly spawns several arduino-cli processes at once - after it's done, every board is instant on every future run. [y/N]: ", verb, len(remaining)))
			if !doPrefetch {
				fmt.Println("Skipping prefetch - boards will be cached as you pick them instead.")
			}
		}
	}

	data := &wizardCacheData{
		Signature:    signature,
		GeneratedAt:  time.Now(),
		Platforms:    platforms,
		Boards:       boards,
		BoardOptions: boardOptions,
		Complete:     false,
	}

	if signature != "" && doPrefetch && len(remaining) > 0 {
		// onProgress is called periodically (and once at the very end) with the
		// results gathered so far; each call atomically saves the cache with
		// Complete still false. If the process is killed, crashes, or the user
		// Ctrl-C's out partway through, whatever had been saved by the last
		// onProgress call is left on disk, untouched and fully valid (see
		// saveWizardCache's temp-file-plus-rename in board_wizard_cache.go) - the
		// next run resumes from there instead of re-fetching everything.
		onProgress := func(partial map[string][]boardConfigOption) {
			data.BoardOptions = partial
			saveWizardCache(cachePath, data)
		}
		data.BoardOptions = prefetchAllBoardOptions(arduinoCLI, configFile, remaining, prefetchWorkers, boardOptions, onProgress)
	}

	data.Complete = len(remaining) == 0 || doPrefetch
	if signature != "" {
		saveWizardCache(cachePath, data)
	}

	return data, signature, false, nil
}

// prefetchAllBoardOptions fetches board details for boards (already narrowed down by
// the caller to just the ones still missing - see resolveWizardCache), concurrently,
// and returns the full merged map keyed by FQBN: existing entries plus everything
// freshly fetched this call.
//
// workers bounds how many `arduino-cli board details` calls run at once (see
// -wizard-prefetch-workers). Sequential calls are what make prefetching hundreds of
// boards slow in the first place; arduino-cli's board details is a read-only query
// with no shared mutable state between invocations, so running several at once is
// safe. A non-positive value falls back to a conservative default rather than
// deadlocking on a zero-size worker pool.
//
// A single board's failure (arduino-cli erroring for that specific FQBN, or an
// unexpected JSON shape) is logged to stderr and that board is simply left out of the
// returned map, rather than aborting the whole prefetch - it'll fall back to being
// fetched live in configureBoardOptions the moment someone actually selects it, the
// same degrade-gracefully behavior this project already uses elsewhere for a single
// board's options.
//
// existing seeds the result with boards already fetched by an earlier, interrupted
// prefetch (resuming - see resolveWizardCache), so the map this returns always covers
// every board ever successfully fetched, not just this call's batch.
//
// onProgress, if non-nil, is called periodically (roughly every checkpointInterval,
// plus once unconditionally after the very last board finishes) with a snapshot of
// the merged results so far, so the caller can persist a resumable checkpoint to disk
// without waiting for the entire prefetch to complete. The periodic goroutine is
// always fully stopped before the final call fires (see tickerDone below), so
// onProgress is never invoked twice concurrently - safe for the caller to do
// blocking I/O in it without its own locking.
func prefetchAllBoardOptions(arduinoCLI, configFile string, boards []boardEntry, workers int, existing map[string][]boardConfigOption, onProgress func(map[string][]boardConfigOption)) map[string][]boardConfigOption {
	if workers <= 0 {
		workers = 8
	}
	total := len(boards)
	results := make(map[string][]boardConfigOption, len(existing)+total)
	for k, v := range existing {
		results[k] = v
	}
	if total == 0 {
		return results
	}

	fmt.Printf("Prefetching menu options for %d board(s) using %d workers (cached as they finish - safe to interrupt)...\n", total, workers)

	const checkpointInterval = 2 * time.Second

	var mu sync.Mutex // guards results
	var completed int32

	jobs := make(chan boardEntry)
	done := make(chan struct{})
	var wg sync.WaitGroup
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for b := range jobs {
				opts, err := fetchBoardConfigOptions(arduinoCLI, configFile, b.FQBN)
				n := atomic.AddInt32(&completed, 1)
				fmt.Printf("\r  %d/%d boards done%s", n, total, strings.Repeat(" ", 20))
				if err != nil {
					fmt.Fprintf(os.Stderr, "\nfastbuild: skipping %s in prefetch (will load live if selected): %v\n", b.FQBN, err)
					continue
				}
				mu.Lock()
				results[b.FQBN] = opts
				mu.Unlock()
			}
		}()
	}

	// Periodic checkpoint saves, so a crash/kill/Ctrl-C doesn't lose more than
	// ~checkpointInterval worth of progress. Runs on its own goroutine so it doesn't
	// block the job-feeding loop below; tickerDone is waited on before the final,
	// unconditional checkpoint further down so the two can never call onProgress
	// concurrently with each other (both write to the same cache file - see
	// saveWizardCache - so overlapping calls are never desirable even though each
	// individual write is itself atomic).
	tickerDone := make(chan struct{})
	if onProgress != nil {
		go func() {
			defer close(tickerDone)
			ticker := time.NewTicker(checkpointInterval)
			defer ticker.Stop()
			for {
				select {
				case <-ticker.C:
					mu.Lock()
					snapshot := make(map[string][]boardConfigOption, len(results))
					for k, v := range results {
						snapshot[k] = v
					}
					mu.Unlock()
					onProgress(snapshot)
				case <-done:
					return
				}
			}
		}()
	}

	for _, b := range boards {
		jobs <- b
	}
	close(jobs)
	wg.Wait()
	close(done)
	if onProgress != nil {
		<-tickerDone // wait for the periodic goroutine to fully stop first
	}

	fmt.Printf("\r  %d/%d boards done.%s\n", total, total, strings.Repeat(" ", 20))

	if onProgress != nil {
		// Final checkpoint, unconditional - guarantees the last-saved state always
		// reflects everything that finished, even if the ticker's last tick landed
		// just before the final board(s) completed.
		mu.Lock()
		snapshot := make(map[string][]boardConfigOption, len(results))
		for k, v := range results {
			snapshot[k] = v
		}
		mu.Unlock()
		onProgress(snapshot)
	}

	return results
}

// copyToClipboard copies text to the Windows clipboard via clip.exe, which ships
// with every Windows installation - no third-party dependency or raw Win32
// clipboard API calls needed, consistent with how this project already shells out
// to arduino-cli.exe and taskkill elsewhere rather than linking against their APIs
// directly.
func copyToClipboard(text string) error {
	cmd := exec.Command("clip")
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return fmt.Errorf("opening clip.exe stdin: %w", err)
	}
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("starting clip.exe: %w", err)
	}
	// clip.exe copies exactly what it receives on stdin, including a trailing
	// newline if one is written - so no newline is written here, to avoid leaving
	// one on the end of the pasted FQBN.
	if _, err := io.WriteString(stdin, text); err != nil {
		stdin.Close()
		return fmt.Errorf("writing to clip.exe: %w", err)
	}
	if err := stdin.Close(); err != nil {
		return fmt.Errorf("closing clip.exe stdin: %w", err)
	}
	if err := cmd.Wait(); err != nil {
		return fmt.Errorf("clip.exe exited with error: %w", err)
	}
	return nil
}

// promptChoiceIndex reads a 1-based numeric choice from reader and returns it as a
// 0-based index, clamped to a valid range. Defaults to 0 (the first item) with no
// prompt at all if stdin isn't interactive - same safe-default philosophy as
// isInteractiveStdin()'s other uses in this project.
func promptChoiceIndex(reader *bufio.Reader, label string, count int) int {
	if !isInteractiveStdin() {
		fmt.Printf("%s number (stdin not interactive - using #1): ", label)
		fmt.Println()
		return 0
	}
	fmt.Printf("%s number (Enter for #1): ", label)
	line, _ := reader.ReadString('\n')
	line = strings.TrimSpace(line)
	if line == "" {
		return 0
	}
	var n int
	if _, err := fmt.Sscanf(line, "%d", &n); err == nil && n >= 1 && n <= count {
		return n - 1
	}
	return 0
}

// listInstalledPlatforms parses `arduino-cli core list`'s plain-text table. Confirmed
// real format (from this project's own earlier testing):
//
//	ID                       Installed  Latest     Name
//	esp8266:esp8266          3.0.2      3.0.2      ESP8266 Boards (3.0.2)
func listInstalledPlatforms(arduinoCLI, configFile string) ([]installedPlatform, error) {
	output, err := runArduinoCLIText(arduinoCLI, configFile, "Checking installed platforms", "core", "list")
	if err != nil {
		return nil, err
	}

	// Columns: ID, Installed, Latest, then Name (which itself may contain spaces,
	// hence capturing everything remaining rather than splitting on whitespace
	// throughout).
	linePattern := regexp.MustCompile(`^(\S+)\s+(\S+)\s+(\S+)\s+(.+)$`)

	var platforms []installedPlatform
	scanner := bufio.NewScanner(strings.NewReader(output))
	firstLine := true
	for scanner.Scan() {
		line := strings.TrimRight(scanner.Text(), "\r")
		if firstLine {
			firstLine = false
			continue // header row
		}
		if strings.TrimSpace(line) == "" {
			continue
		}
		m := linePattern.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		platforms = append(platforms, installedPlatform{ID: m[1], Name: m[4]})
	}
	return platforms, nil
}

// listBoardsForPlatform parses `arduino-cli board listall`'s plain-text table and
// keeps only boards whose FQBN belongs to the given platform ID. Confirmed real
// format:
//
//	Board Name              FQBN
//	Arduino MKR FOX 1200    arduino:samd:mkrfox1200
//
// FQBNs never contain spaces, so the last whitespace-delimited token on each line is
// reliably the FQBN regardless of how many words are in the board name.
// listAllBoards fetches every board from every installed platform in one call -
// arduino-cli's `board listall` doesn't support filtering server-side, so this always
// returns everything; filterBoardsByPlatform narrows it down afterward, purely
// locally, no further arduino-cli calls needed. This split also means the result can
// be cached wholesale (see board_wizard_cache.go) and reused for any platform, not
// just the one the cache happened to be built while looking at.
func listAllBoards(arduinoCLI, configFile string) ([]boardEntry, error) {
	output, err := runArduinoCLIText(arduinoCLI, configFile, "Loading boards", "board", "listall")
	if err != nil {
		return nil, err
	}

	var boards []boardEntry
	scanner := bufio.NewScanner(strings.NewReader(output))
	firstLine := true
	for scanner.Scan() {
		line := strings.TrimRight(scanner.Text(), "\r")
		if firstLine {
			firstLine = false
			continue // header row
		}
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		fqbn := fields[len(fields)-1]
		name := strings.TrimSpace(strings.TrimSuffix(line, fqbn))
		boards = append(boards, boardEntry{Name: name, FQBN: fqbn})
	}
	return boards, nil
}

// filterBoardsByPlatform narrows an already-fetched board list down to just the ones
// belonging to platformID. Pure and local - no I/O, so it's free to call as many
// times as needed regardless of where the board list came from (a live fetch or the
// cache).
func filterBoardsByPlatform(all []boardEntry, platformID string) []boardEntry {
	prefix := platformID + ":"
	var boards []boardEntry
	for _, b := range all {
		if strings.HasPrefix(b.FQBN, prefix) {
			boards = append(boards, b)
		}
	}
	return boards
}

// configureBoardOptions calls `board details --json` and prompts for each menu
// option, defaulting to the board's own default selection. Returns an error (rather
// than a partial result) if the JSON doesn't have the shape expected, so the caller
// can decide how to degrade - this project has been wrong about an arduino-cli output
// format once before, so this step is written to fail cleanly rather than silently
// produce a wrong FQBN.
// withSpinner runs work while printing an animated "please wait" indicator to the
// console, so a slow arduino-cli call (core list / board listall / board details can
// each take a real, noticeable moment) visibly shows it's working rather than looking
// stuck. Clears itself from the line when work finishes, success or failure.
func withSpinner(label string, work func() (string, error)) (string, error) {
	done := make(chan struct{})
	var result string
	var workErr error

	go func() {
		result, workErr = work()
		close(done)
	}()

	frames := []string{".  ", ".. ", "..."}
	frameIndex := 0
	ticker := time.NewTicker(200 * time.Millisecond)
	defer ticker.Stop()

	fmt.Printf("%s%s", label, frames[0])
	for {
		select {
		case <-done:
			// Clear the line (overwrite with spaces, then return) so the next
			// output doesn't have leftover spinner text trailing it.
			fmt.Printf("\r%s\r", strings.Repeat(" ", len(label)+len(frames[0])))
			return result, workErr
		case <-ticker.C:
			frameIndex = (frameIndex + 1) % len(frames)
			fmt.Printf("\r%s%s", label, frames[frameIndex])
		}
	}
}

// fetchBoardConfigOptions runs `arduino-cli board details --fqbn <baseFqbn> --json`
// and parses its config_options - no prompting, just the fetch-and-parse step, shared
// between a single on-demand fetch (configureBoardOptions, wrapped in its own spinner)
// and the bulk prefetch (prefetchAllBoardOptions, which reports its own overall
// progress across many boards instead of a per-call spinner). Always returns a
// non-nil slice on success (empty, not nil, if the board genuinely has no menu
// options) so a cache hit can be distinguished from "never looked up" purely by map
// key presence.
func fetchBoardConfigOptions(arduinoCLI, configFile, baseFqbn string) ([]boardConfigOption, error) {
	args := []string{}
	if configFile != "" {
		args = append(args, "--config-file", configFile)
	}
	args = append(args, "board", "details", "--fqbn", baseFqbn, "--json")

	cmd := exec.Command(arduinoCLI, args...)
	out, err := cmd.Output()
	if err != nil {
		return nil, fmt.Errorf("running board details: %w", err)
	}

	var parsed struct {
		ConfigOptions []boardConfigOption `json:"config_options"`
	}
	if err := json.Unmarshal(out, &parsed); err != nil {
		return nil, fmt.Errorf("parsing board details JSON: %w", err)
	}
	if parsed.ConfigOptions == nil {
		parsed.ConfigOptions = []boardConfigOption{}
	}
	return parsed.ConfigOptions, nil
}

// configureBoardOptions prompts for baseFqbn's menu options, defaulting to the
// board's own default selection. cache.BoardOptions is checked first, keyed by
// baseFqbn: a hit (even an empty slice, meaning "this board has no menu options")
// skips the arduino-cli call and its spinner entirely - normally already satisfied by
// resolveWizardCache's upfront prefetch, but still checked here too as a fallback for
// a board the prefetch itself failed to fetch (see prefetchAllBoardOptions) or a cache
// with no signature available. A miss fetches live, then - if signature is non-empty,
// i.e. caching is available this run - stores the result back into cache and rewrites
// cachePath so the next -configure-board run for the same board hits the cache too.
func configureBoardOptions(reader *bufio.Reader, arduinoCLI, configFile, baseFqbn string, cache *wizardCacheData, cachePath, signature string) (string, error) {
	configOptions, ok := cache.BoardOptions[baseFqbn]
	if ok {
		fmt.Println("Using cached board options (unchanged since last check).")
	} else {
		var fetchErr error
		_, err := withSpinner("Loading board options", func() (string, error) {
			configOptions, fetchErr = fetchBoardConfigOptions(arduinoCLI, configFile, baseFqbn)
			return "", fetchErr
		})
		if err != nil {
			return "", err
		}

		if signature != "" {
			cache.BoardOptions[baseFqbn] = configOptions
			saveWizardCache(cachePath, cache)
		}
	}

	if len(configOptions) == 0 {
		return baseFqbn, nil // no menu options for this board - the base FQBN is complete as-is
	}

	var suffixParts []string
	for _, opt := range configOptions {
		if len(opt.Values) == 0 {
			continue
		}
		defaultIndex := 0
		fmt.Printf("\n%s (%s):\n", opt.OptionLabel, opt.Option)
		for i, v := range opt.Values {
			marker := " "
			if v.Selected {
				marker = "*"
				defaultIndex = i
			}
			fmt.Printf("  %s %d) %s\n", marker, i+1, v.ValueLabel)
		}

		choice := defaultIndex
		if !isInteractiveStdin() {
			fmt.Printf("Choice number (stdin not interactive - using #%d, marked with *): \n", defaultIndex+1)
		} else {
			fmt.Printf("Choice number (Enter for #%d, marked with *): ", defaultIndex+1)
			line, _ := reader.ReadString('\n')
			line = strings.TrimSpace(line)
			if line != "" {
				var n int
				if _, err := fmt.Sscanf(line, "%d", &n); err == nil && n >= 1 && n <= len(opt.Values) {
					choice = n - 1
				}
			}
		}
		suffixParts = append(suffixParts, opt.Option+"="+opt.Values[choice].Value)
	}

	if len(suffixParts) == 0 {
		return baseFqbn, nil
	}
	return baseFqbn + ":" + strings.Join(suffixParts, ","), nil
}

func runArduinoCLIText(arduinoCLI, configFile, spinnerLabel string, args ...string) (string, error) {
	fullArgs := []string{}
	if configFile != "" {
		fullArgs = append(fullArgs, "--config-file", configFile)
	}
	fullArgs = append(fullArgs, args...)

	return withSpinner(spinnerLabel, func() (string, error) {
		cmd := exec.Command(arduinoCLI, fullArgs...)
		data, err := cmd.Output()
		if err != nil {
			return "", err
		}
		return string(data), nil
	})
}
