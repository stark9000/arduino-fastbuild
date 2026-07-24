# Contributing to fastbuild

Thanks for considering a contribution. A few things that'll make this go smoothly.

## Project philosophy

- **No third-party dependencies.** Standard library only, in both the Go tool and the
  Java GUI. If a change seems to need an external package, that's usually a sign to
  find a different approach rather than reach for one - see `isinteractive_windows.go`
  for an example of solving a real problem (accurate TTY detection) with a raw syscall
  instead of pulling in a library.
- **Safe direction to be wrong.** Where there's a choice between "might do an
  unnecessary rebuild" and "might silently serve a stale binary," always pick the
  former. This shows up throughout the dependency-hashing code - read the comments in
  `deps.go` and `gcc_deps.go` for the reasoning.
- **Never silent about a degraded state.** If something falls back to a less precise
  or less efficient mode (regex scanning instead of `.d` files, a stale cache, an
  unreadable dependency path), say so - on stderr if it's actionable, in `-verbose`
  output otherwise. Nothing should quietly do less than it looks like it's doing.

## Getting set up

```
go build ./...
go vet ./...
gofmt -l .        # should print nothing
go test ./...
```

No `go.sum`/module dependencies to fetch - `go build` should work offline from a
clean clone.

## Before opening a PR

- **Run the existing tests** (`go test ./...`) and add new ones for anything you're
  fixing or adding. Look at the existing `*_test.go` files for the general style -
  most of this codebase's test coverage exists because a real bug was found by hand
  (a fake `arduino-cli` script, a synthetic `.d` file, a pty-driven interactive test)
  and then turned into something that runs in CI instead of only in a terminal once.
- **`gofmt -w` your changes.** CI (and reviewers) will reject anything `gofmt -l`
  flags.
- **Update `README.md`** if you're adding a flag, config key, or changing documented
  behavior. A flag that only exists in `-h` output and not the README is easy to miss
  and easy to forget to use correctly.
- **Keep the "why," not just the "what," in comments** for anything non-obvious -
  especially tradeoffs (e.g. "this could theoretically miss X, but that's rare and
  the alternative costs Y"). Future contributors (including future you) will thank
  you for not having to reverse-engineer a design decision from the diff alone.

## Reporting bugs

Include:
- What you ran (the exact command/flags)
- What you expected vs. what happened
- Your board/FQBN and whichever `-deps-mode` you were using, if relevant
- Whether it reproduces with `-verbose` on (the extra output often narrows things down
  immediately)

## Java GUI contributions

The GUI (`FastBuild.jar`) is a genuinely separate project from the Go engine - it
talks to `fastbuild.exe` the same way any script or IDE plugin would (writing a
config file, invoking the binary, reading its output), never by linking against Go
code directly. Changes to the GUI shouldn't need to touch the Go side at all unless
you're adding support for a config key or flag that doesn't exist yet.

## Questions

Open an issue - design questions and "is this the right approach before I write 500
lines" questions are always welcome before a PR, not just after.
