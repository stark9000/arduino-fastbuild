// watch.go implements -watch: a build-on-save loop that polls for sketch/dependency
// changes and triggers a rebuild automatically, without needing an editor plugin or
// external file-watcher tool.
//
// This is deliberately a polling loop, not an OS-level filesystem-event subscription
// (inotify on Linux, ReadDirectoryChangesW on Windows, FSEvents on macOS). Wiring
// those up portably across all three from the Go standard library alone isn't
// possible without a third-party package (fsnotify or similar), and this project
// deliberately stays dependency-free. Polling costs one cache-key hash computation per
// tick, which - thanks to the header-index caching already built into fastbuild - is
// fast enough that a 1-second interval is imperceptible during normal edit-save-flash
// work. A true zero-latency file-event trigger would be the natural next step if the
// dependency-free constraint is ever relaxed.
package main

import (
	"fmt"
	"os"
	"os/signal"
	"syscall"
	"time"
)

// runWatch repeatedly invokes buildFunc on interval until interrupted (Ctrl+C /
// SIGTERM), printing full output on the first call and staying silent afterwards
// whenever buildFunc reports nothing needed building. buildFunc's signature matches
// Builder.Build (see builder.go) exactly, so callers just pass a Builder's Build
// method value directly - localBuilder{cfg}.Build for building locally,
// daemonBuilder{addr, configPath}.Build for building through a daemon - and this loop
// drives either one identically, with no need to know which kind it's holding.
func runWatch(description string, interval time.Duration, buildFunc func(quiet bool) (*Result, error)) error {
	if interval <= 0 {
		return fmt.Errorf("watch interval must be positive, got %s", interval)
	}

	fmt.Println("fastbuild watch:", description)
	fmt.Println("Checking every", interval, "- press Ctrl+C to stop.")

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)
	defer signal.Stop(sigCh)

	// Build once immediately, verbosely, so you get feedback right away instead of
	// waiting a full interval for the first check - and so a pre-existing compile
	// error is shown once up front rather than only surfacing silently-suppressed.
	if _, err := buildFunc(false); err != nil {
		fmt.Fprintln(os.Stderr, "fastbuild: build failed:", err)
	}

	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-sigCh:
			fmt.Println("\nfastbuild watch: stopping.")
			return nil
		case <-ticker.C:
			if _, err := buildFunc(true); err != nil {
				// A genuinely new error (not a repeat of the last known failure -
				// both Builder implementations already suppress those in quiet
				// mode) is still worth surfacing even during quiet polling.
				fmt.Fprintln(os.Stderr, "fastbuild: build failed:", err)
			}
		}
	}
}
