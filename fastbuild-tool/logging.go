// logging.go implements two related features:
//
//  1. -json: emits fastbuild's own status lines (not arduino-cli's passthrough
//     output, which stays plain-text - see the comment on run()'s use of
//     cmd.Stdout = os.Stdout for why that boundary exists) as JSON Lines instead of
//     plain text, so a GUI or other tool can parse them reliably instead of matching
//     against exact English wording.
//
//  2. -save-log: captures this run's output to a timestamped file, in whichever
//     representation (plain or JSON Lines) is active - a natural fit for a future
//     log analyzer, since JSON Lines can be read incrementally rather than requiring
//     a complete, valid document.
//
// Both share one buildLogger so a line only needs to be formatted once regardless of
// how many destinations it's going to.
package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"time"
)

// logEvent is one line of fastbuild's own output in JSON mode.
type logEvent struct {
	Ts     string `json:"ts"`
	Source string `json:"source"` // always "fastbuild" currently - reserved for future use if arduino-cli's own output is ever wrapped too
	Text   string `json:"text"`
}

// pidEvent reports fastbuild's own process ID - the same value the unconditional
// startup line prints before config loading (so it's visible even if config loading
// later fails), re-emitted here as a structured event once the logger exists, for
// JSON-mode/save-log consumers that only see logger-routed output.
type pidEvent struct {
	Type string `json:"type"`
	Pid  int    `json:"pid"`
}

// resultEvent is the final structured summary of a run, giving a GUI everything it
// needs without parsing prose: whether it succeeded, whether anything was actually
// compiled, timing, and the output path.
type resultEvent struct {
	Type           string  `json:"type"`
	Success        bool    `json:"success"`
	CacheHit       bool    `json:"cacheHit"`
	ElapsedSeconds float64 `json:"elapsedSeconds"`
	OutputFile     string  `json:"outputFile,omitempty"`
}

type buildLogger struct {
	jsonMode bool
	saveFile *os.File
}

// newBuildLogger sets up logging per cfg. Save-log failures are non-fatal - a build
// that can't write its own log file should still complete, not abort, so this returns
// a usable logger even if file creation failed (falling back to stdout-only).
func newBuildLogger(cfg *config, projectDir string) *buildLogger {
	l := &buildLogger{jsonMode: cfg.jsonOutput}

	if !cfg.saveLog {
		return l
	}

	logDir := cfg.logDir
	if logDir == "" {
		logDir = filepath.Join(projectDir, "logs")
	}
	if err := os.MkdirAll(logDir, 0o755); err != nil {
		fmt.Fprintln(os.Stderr, "fastbuild: could not create log directory, continuing without -save-log:", err)
		return l
	}

	ext := ".log"
	if cfg.jsonOutput {
		ext = ".jsonl"
	}
	timestamp := time.Now().Format("20060102-150405")
	logPath := filepath.Join(logDir, timestamp+ext)

	f, err := os.Create(logPath)
	if err != nil {
		fmt.Fprintln(os.Stderr, "fastbuild: could not create log file, continuing without -save-log:", err)
		return l
	}
	l.saveFile = f
	// This line always goes to stdout in plain text, even in -json mode, since it's
	// telling the operator (not a parser) where their transcript went.
	fmt.Println("Saving log to:", logPath)
	return l
}

// Println writes one line of fastbuild's own output, formatted per jsonMode, to
// stdout and (if -save-log is active) to the saved log file - both destinations get
// the exact same representation, so a saved log matches what was seen live.
func (l *buildLogger) Println(text string) {
	line := l.format(text)
	fmt.Println(line)
	l.writeToFile(line)
}

func (l *buildLogger) format(text string) string {
	if !l.jsonMode {
		return text
	}
	data, err := json.Marshal(logEvent{
		Ts:     time.Now().UTC().Format(time.RFC3339),
		Source: "fastbuild",
		Text:   text,
	})
	if err != nil {
		// Should be unreachable (a plain string always marshals cleanly), but never
		// let a logging failure take down the build - fall back to plain text.
		return text
	}
	return string(data)
}

// PID re-emits fastbuild's own process ID as a structured event, for JSON-mode/
// save-log consumers - the unconditional plain-text PID line at program startup
// (printed before config loading, so it's visible even if that later fails) happens
// too early for jsonMode/saveLog to be known yet.
func (l *buildLogger) PID(pid int) {
	if !l.jsonMode {
		return // the plain-text startup line already covered this
	}
	data, err := json.Marshal(pidEvent{Type: "pid", Pid: pid})
	if err != nil {
		return
	}
	line := string(data)
	fmt.Println(line)
	l.writeToFile(line)
}

// Result emits the final structured summary, in JSON mode only - in plain-text mode
// the existing "Build succeeded in ..."/"Done in ... (cache hit)" lines already cover
// this for a human reader, so this would just be redundant noise.
func (l *buildLogger) Result(success, cacheHit bool, elapsedSeconds float64, outputFile string) {
	if !l.jsonMode {
		return
	}
	data, err := json.Marshal(resultEvent{
		Type:           "result",
		Success:        success,
		CacheHit:       cacheHit,
		ElapsedSeconds: elapsedSeconds,
		OutputFile:     outputFile,
	})
	if err != nil {
		return
	}
	line := string(data)
	fmt.Println(line)
	l.writeToFile(line)
}

func (l *buildLogger) writeToFile(line string) {
	if l.saveFile == nil {
		return
	}
	fmt.Fprintln(l.saveFile, line)
}

func (l *buildLogger) Close() {
	if l.saveFile != nil {
		l.saveFile.Close()
	}
}
