// daemon.go implements a minimal "fastbuildd"-style persistent build server.
//
// Every normal fastbuild invocation pays Go process startup (small, but non-zero) and
// starts from a completely cold in-process state: the header index cache is re-read
// from disk and JSON-decoded on every single run, even though it's usually unchanged.
// -daemon starts a long-running process that keeps that state warm in memory across
// many builds, and -connect sends a build request to it instead of spawning a new
// fastbuild process each time.
//
// What this does NOT do (yet): arduino-cli itself is still spawned fresh for every
// real compile - that's where the actual compile time goes, and the daemon can't
// avoid it. It also doesn't watch the filesystem and trigger builds on save; a caller
// (Java, a Makefile, an editor plugin) still decides when to ask for a build. Those are
// the natural next steps mentioned in the feedback (build daemon -> watch mode), but
// this is the foundational piece: the process that can stay warm and serve them.
package main

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"time"
)

// daemonRequest is what a client (-connect) sends: the path to a fastbuild config file,
// same as the positional argument to a normal build, plus whether the caller wants
// "no changes" noise suppressed (set by a client running in -watch mode).
type daemonRequest struct {
	ConfigPath string `json:"configPath"`
	Quiet      bool   `json:"quiet"`
}

// daemonResponse is what the daemon sends back after running (or failing to run) the
// requested build. The actual compile log still goes to the daemon process's own
// stdout/stderr (like a build server's console) - the response is just the outcome.
type daemonResponse struct {
	OK         bool   `json:"ok"`
	Message    string `json:"message,omitempty"`
	DurationMs int64  `json:"durationMs"`
	DidBuild   bool   `json:"didBuild"` // true if an actual compile ran; false on a cache hit or a suppressed repeat-failure
}

// runDaemon starts the persistent build server on addr (e.g. "127.0.0.1:9876") and
// blocks forever, handling one build per accepted connection. It never returns except
// on a listen error.
//
// stalePolicy overrides whatever any individual request's config file says about
// stale-header-index handling - the daemon NEVER uses staleIndexAsk, even if stdin
// happens to be a real terminal (e.g. someone ran "fastbuild -daemon" directly in a
// foreground shell to watch its log). Two concurrent connections both hitting a stale
// index at once would otherwise interleave two prompts on one shared stdin with no way
// to tell which question belongs to which build - a policy decided once at daemon
// startup avoids that entirely rather than relying on isInteractiveStdin() per request.
func runDaemon(addr string, stalePolicy staleIndexDecision) error {
	useHeaderIndexMemCache = true // see deps.go: keeps decoded header indexes warm across requests

	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return fmt.Errorf("listening on %s: %w", addr, err)
	}
	defer ln.Close()

	fmt.Println("fastbuild daemon listening on", addr)
	fmt.Println("Send builds to it with: fastbuild -connect", addr, "<path-to-config>")
	if stalePolicy == staleIndexAlwaysRefresh {
		fmt.Println("Stale header index policy: always refresh automatically (never prompt).")
	} else {
		fmt.Println("Stale header index policy: keep using stale cache, never prompt (default). Restart with -daemon-stale-deps-policy=refresh to change this.")
	}

	for {
		conn, err := ln.Accept()
		if err != nil {
			fmt.Fprintln(os.Stderr, "fastbuild daemon: accept error:", err)
			continue // one bad accept shouldn't kill a long-running daemon
		}
		go handleDaemonConn(conn, stalePolicy)
	}
}

// handleDaemonConn services exactly one build request on conn, then closes it. A
// fresh connection per build keeps the protocol trivial (no request framing/pipelining
// to worry about); the expensive per-request cost this saves (warm header index cache,
// no new OS process) doesn't depend on keeping the TCP connection itself alive.
func handleDaemonConn(conn net.Conn, stalePolicy staleIndexDecision) {
	defer conn.Close()

	dec := json.NewDecoder(conn)
	enc := json.NewEncoder(conn)

	var req daemonRequest
	if err := dec.Decode(&req); err != nil {
		enc.Encode(daemonResponse{OK: false, Message: "bad request: " + err.Error()})
		return
	}

	cfg, err := loadConfig(req.ConfigPath)
	if err != nil {
		enc.Encode(daemonResponse{OK: false, Message: "loading config: " + err.Error()})
		return
	}
	// Always override whatever the config file says here - see the comment on
	// runDaemon for why the daemon can never safely use staleIndexAsk.
	cfg.staleDepsIndexDecision = stalePolicy

	if !req.Quiet {
		fmt.Println()
		fmt.Println("=== build request:", req.ConfigPath, "===")
	}

	start := time.Now()
	didBuild, buildErr := run(cfg, req.Quiet)
	resp := daemonResponse{OK: buildErr == nil, DurationMs: time.Since(start).Milliseconds(), DidBuild: didBuild}
	if buildErr != nil {
		resp.Message = buildErr.Error()
		fmt.Fprintln(os.Stderr, "fastbuild daemon: build failed:", buildErr)
	}
	enc.Encode(resp)
}

// sendBuildToDaemon (-connect) sends one build request to an already-running daemon
// and reports its outcome, returning whether an actual compile ran (for the -watch
// loop's benefit) alongside any error. When quiet is true and nothing needed to
// build, this stays silent - no round-trip line, no "succeeded" message - matching
// how a local run(cfg, true) call behaves during polling. If nothing is listening at
// addr, this fails with a hint to start a daemon via -daemon.
func sendBuildToDaemon(addr, configPath string, quiet bool) (bool, error) {
	conn, err := net.Dial("tcp", addr)
	if err != nil {
		return false, fmt.Errorf("connecting to daemon at %s: %w (start one with: fastbuild -daemon -daemon-addr %s)", addr, err, addr)
	}
	defer conn.Close()

	enc := json.NewEncoder(conn)
	dec := json.NewDecoder(conn)

	if err := enc.Encode(daemonRequest{ConfigPath: configPath, Quiet: quiet}); err != nil {
		return false, fmt.Errorf("sending build request: %w", err)
	}

	var resp daemonResponse
	if err := dec.Decode(&resp); err != nil {
		return false, fmt.Errorf("reading daemon response: %w", err)
	}

	if quiet && !resp.DidBuild && resp.OK {
		return false, nil // nothing changed - stay silent, same as a local quiet cache hit
	}

	fmt.Printf("Daemon round-trip: %d ms\n", resp.DurationMs)
	if !resp.OK {
		return resp.DidBuild, fmt.Errorf("daemon reported build failure: %s", resp.Message)
	}
	fmt.Println("Build succeeded (see the daemon's own console for the full compile log).")
	return resp.DidBuild, nil
}
