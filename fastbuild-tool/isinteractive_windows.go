//go:build windows

package main

import (
	"os"
	"syscall"
)

// isInteractiveStdin reports whether stdin is a real console a person could actually
// answer a prompt on, as opposed to redirected, piped, or absent (a scheduled task,
// an IDE build hook, a daemon started detached).
//
// On Windows, GetConsoleMode is the correct, native way to check this: it succeeds
// only when the handle refers to a real console, and fails for anything else
// (redirected files, pipes, NUL). This is more reliable on Windows than checking file
// mode bits or comparing file identity against the null device (the approach used on
// other platforms - see isinteractive_other.go) - that approach was confirmed to
// silently report "not interactive" on every real Windows console during testing,
// since Stat() on a console handle doesn't behave like a regular file the way it does
// on Unix-like systems.
//
// syscall.GetConsoleMode is part of the Go standard library's syscall package for
// Windows (not a third-party dependency) - this project stays dependency-free.
func isInteractiveStdin() bool {
	var mode uint32
	err := syscall.GetConsoleMode(syscall.Handle(os.Stdin.Fd()), &mode)
	return err == nil
}
