//go:build !windows

package main

import "os"

// isInteractiveStdin - non-Windows fallback. This project targets Windows, but this
// build tag keeps the source buildable and testable on other platforms too (e.g.
// during development/CI in a Linux environment). See isinteractive_windows.go for
// the platform this project actually ships on, and for why Windows needed a
// different, more reliable approach (GetConsoleMode) than this file-identity check.
//
// isInteractiveStdin reports whether stdin is a real terminal a person could actually
// answer a prompt on, as opposed to redirected, piped, or simply not attached (a
// scheduled task, an IDE's "external build tool" hook, a daemon started detached).
//
// The char-device check alone is NOT sufficient: /dev/null is also a character
// device, and is exactly what a detached/headless process typically has attached to
// stdin - so a naive ModeCharDevice check reports "interactive" for /dev/null too.
// Confirmed directly: redirecting stdin from /dev/null and checking
// info.Mode()&os.ModeCharDevice reports true, identically to a real terminal. Ruling
// out the null device explicitly (by identity, via os.SameFile against a freshly
// opened os.DevNull) closes that specific, demonstrated gap using only the standard
// library.
//
// This still isn't a complete isatty() - a handle to some other, unusual character
// device (e.g. an actual serial port dup'd onto stdin) would still pass as
// "interactive" - but that's a contrived edge case, whereas /dev/null on a headless
// process is the common one this exists to catch.
func isInteractiveStdin() bool {
	info, err := os.Stdin.Stat()
	if err != nil {
		return false
	}
	if info.Mode()&os.ModeCharDevice == 0 {
		return false // regular file, pipe, or socket - definitely not a terminal
	}

	nullFile, err := os.Open(os.DevNull)
	if err != nil {
		return true // can't check against the null device - trust the char-device bit
	}
	defer nullFile.Close()

	nullInfo, err := nullFile.Stat()
	if err != nil {
		return true
	}
	return !os.SameFile(info, nullInfo)
}
