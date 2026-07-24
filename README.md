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
| Use `fastbuild` directly from the command line | [`fastbuild-tool/README.md`](fastbuild-tool/README.md) |
| Contribute to `fastbuild` itself | [`fastbuild-tool/CONTRIBUTING.md`](fastbuild-tool/CONTRIBUTING.md) |
| Build either one from source | See "Building from source" below |
| Wrap `fastbuilduix.jar` into a native `.exe` | [Wraptor](https://github.com/stark9000/wraptor) (separate, companion tool) |

If you're new here and just want a working setup as quickly as possible:
start with the install guide, then the UI how-to guide. Both assume no
prior `arduino-cli` experience.

## Quick start (command line only)

If you already have `arduino-cli` and a board core installed, and just
want to try `fastbuild` directly without the UI:

```
fastbuild path/to/your.config
```

See `fastbuild-tool/README.md` for the config file format and every
available flag - `-daemon`/`-connect` for a persistent build server,
`-watch` for build-on-save, `-configure-board` for an interactive board
picker, and more.

## Building from source

Only needed if you're not just using prebuilt releases. The two halves
have separate toolchains and can be built independently of each other.

### Building `fastbuild` (the Go tool)

- **Go 1.22.2 or later** - [go.dev/dl](https://go.dev/dl/). The exact
  minimum is pinned in `fastbuild-tool/go.mod`.
- From the `fastbuild-tool` folder, run `build.bat` (or `go build -o
  fastbuild.exe .` directly) - no external dependencies, the standard
  library only.

**Optional: embedding the exe icon.** Go doesn't support embedding exe
icons/resources natively, so this is a separate, one-time step, not part
of the normal build:
- **MinGW-w64** (provides `windres.exe`) -
  [msys2.org](https://www.msys2.org/) is the simplest way to get it. After
  installing, make sure its `bin` folder (containing `windres.exe`) is
  added to your Windows PATH - `where windres` from a fresh Command Prompt
  should find it if it's set up correctly.
- Place your icon as `fastbuild-tool/fastbuild.ico`, then run
  `add-icon.bat` once. This generates `rsrc_windows_amd64.syso` in that
  same folder - `go build`/`build.bat` picks it up and embeds it
  automatically from then on, no flags needed, and no need to re-run
  `add-icon.bat` again unless the icon itself changes.
- This step is entirely optional - `fastbuild.exe` builds and runs
  identically without it, just without an icon of its own in Explorer.

### Building `fastbuilduix` (the Java UI)

- **Java 8 JDK** (source/target level 1.8) - [Eclipse
  Temurin](https://adoptium.net/) is a good free, actively-maintained
  source for this, since Oracle's own JDK 8 builds are no longer freely
  distributed.
- **NetBeans** (or any IDE that can import an Ant-based NetBeans project)
  - [netbeans.apache.org](https://netbeans.apache.org/).
- Open the `fastbuilduix` folder as a NetBeans project and run it
  directly, or build from the command line:
  ```
  ant -f fastbuilduix jar
  java -jar fastbuilduix/dist/fastbuilduix.jar
  ```
  See `fastbuilduix/README.md` for the full dependency list (bundled jars
  under `fastbuilduix/src/lib/`) and project structure.

**Optional: wrapping the jar into a native `.exe`.** For a
double-clickable Windows executable instead of `java -jar ...` -
[Wraptor](https://github.com/stark9000/wraptor), a companion tool: a small
Java Swing GUI that patches a real icon, version info, and JRE-version
enforcement directly into a native launcher stub around your jar - no
Electron, no bundled 200MB runtime, just the jar plus a small native
launcher. Point it at `fastbuilduix.jar` (Wraptor auto-detects the main
jar and main class from the manifest), set an icon/version info if you
want them, and build. Only needs a JRE to run Wraptor itself and produce
the `.exe` - its own native launcher stub is prebuilt and only needs
rebuilding (via MSYS2/MinGW-w64) if you're modifying Wraptor's C source
directly, not for normal use.

## License

See [`fastbuild-tool/LICENSE`](fastbuild-tool/LICENSE).
