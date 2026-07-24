# fastbuild + fastbuilduix

A fast, cache-aware Arduino build tool (`fastbuild`), and a Windows desktop
UI for it (`fastbuilduix`). Together, they're built to make the
edit-build-upload cycle faster and less annoying than the stock Arduino
IDE or a plain `arduino-cli` invocation - without trying to replace either
one.

- **`fastbuild`** wraps `arduino-cli` with a persistent, content-addressed
  build cache, so repeat builds of the same sketch skip recompiling
  entirely when nothing's actually changed - the same core idea as `ccache`,
  applied to Arduino sketches.
- **`fastbuilduix`** is a Swing desktop UI for `fastbuild` - a friendlier
  board picker, richer build/serial logging, a lightweight sketch editor,
  and a few quality-of-life touches (exportable standalone build scripts,
  Explorer-tab error highlighting, a persistent status bar) on top of the
  same underlying tool.

Neither is trying to be a full IDE replacement. If you're comfortable with
`arduino-cli` on the command line already, `fastbuild` alone gets you
faster repeat builds with no UI at all. If you'd rather have a GUI for
picking boards, watching build output, and editing sketch files without
leaving the tool, `fastbuilduix` wraps that same engine.

## Where to go next

| I want to... | See |
|---|---|
| Set up `arduino-cli`, board cores, and drivers from scratch | [`fastbuilduix/INSTALL_GUIDE.md`](fastbuilduix/INSTALL_GUIDE.md) |
| Learn to use the desktop UI | [`fastbuilduix/HOW_TO_GUIDE.md`](fastbuilduix/HOW_TO_GUIDE.md) |
| Understand how the UI is built, tab by tab | [`fastbuilduix/README.md`](fastbuilduix/README.md) |
| Use `fastbuild` directly from the command line | [`fastbuild/README.md`](fastbuild/README.md) |
| Contribute to `fastbuild` itself | [`fastbuild/CONTRIBUTING.md`](fastbuild/CONTRIBUTING.md) |

If you're new here and just want a working setup as quickly as possible:
start with the install guide, then the UI how-to guide. Both assume no
prior `arduino-cli` experience.

## Quick start (command line only)

If you already have `arduino-cli` and a board core installed, and just
want to try `fastbuild` directly without the UI:

```
fastbuild path/to/your.config
```

See `fastbuild/README.md` for the config file format and every available
flag - `-daemon`/`-connect` for a persistent build server, `-watch` for
build-on-save, `-configure-board` for an interactive board picker, and more.

## License

See [`fastbuild/LICENSE`](fastbuild/LICENSE).
