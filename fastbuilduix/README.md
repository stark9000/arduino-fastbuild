# fastbuilduix

A Java Swing desktop UI for [fastbuild](.), a fast Arduino build tool built
on top of `arduino-cli`. This project doesn't reimplement fastbuild's build
logic - it's a thin GUI that assembles the same command-line flags fastbuild
itself accepts, runs it as a subprocess, and streams its output back into the
UI. Anything fastbuild can do from the terminal, this wraps.

Built as a NetBeans project, Java 8, package `fastbuilduix`.

## Requirements

- Java 8 (source/target level 1.8)
- NetBeans (or any IDE that can import an Ant-based NetBeans project)
- A working `fastbuild` executable and `arduino-cli` installation somewhere
  on disk - the UI points at both from App Settings, it doesn't bundle either.
  Starting from scratch (no `arduino-cli`, no board cores installed yet)?
  See `INSTALL_GUIDE.md` for that setup first.

## Dependencies

All bundled as jars under `src/lib/`, referenced directly (no Maven/Gradle):

- `jSerialComm-2.2.2.jar` - serial port listing and the Serial Monitor tab
- `rsyntaxtextarea-3.6.2.jar` - syntax-highlighted editing in the Explorer tab
- `jssc-2.9.2.jar`, `log4j-1.2.17.jar`, `slf4j-simple-1.6.1.jar` - transitive
  dependencies pulled in alongside the above; not directly referenced by any
  class in this project

## Building & running

Open `fastbuilduix/` as a NetBeans project and run it directly, or from the
command line:

```
ant -f fastbuilduix jar
java -jar fastbuilduix/dist/fastbuilduix.jar
```

Entry point is `fastbuilduix.Main`.

## What's in the UI

Everything below is fully wired to real fastbuild/arduino-cli subprocesses -
none of it is a stub or placeholder.

**Always-present tabs:**
- **Project** - sketch path, FQBN, cache root, and the rest of the
  required/recommended fields, plus Run Build / Upload Now / Cancel
- **Explorer** - a file tree for the current sketch's folder with closable,
  syntax-highlighted tabs (RSyntaxTextArea) for editing any file in it.
  Ctrl+S saves, Ctrl+B saves everything open then builds. A failed build
  automatically opens/highlights every file with a compile error or
  warning (parsed from the standard gcc diagnostic format), switches to
  this tab, and jumps to the first error.
- **Board Wizard** - picks a platform, board, and menu options and
  assembles an FQBN; **Apply to Project** commits it to the Project tab
  (selecting a board here alone does not change what actually gets built -
  see "Apply to Project" below)
- **Upload** - serial port selection (auto-refreshing) and upload controls
- **Build Log** - streamed output of the current build/upload, with two
  independent filters (hide the user's home folder; collapse repeated
  toolchain paths and repeated compiler/archiver invocations down to
  `Compiling: x.cpp -> x.cpp.o` / `Archiving: x.cpp.o`), a live "Selected
  board" label, a live elapsed-time counter (floating right at the top,
  ticking while a build/upload runs, printed to the log and cleared when it
  concludes), a compact best-effort progress bar (left of Clear Log - see
  "Build progress" below), an "Open Sketch Folder" button (visible only
  when "Export binary to sketch folder" is checked on Output & Export -
  opens that folder directly, handy for grabbing the exported binary right
  after a build), and Save Log As... / Copy to Clipboard / Clear Log

**Optional tabs** (Settings menu, closable):
- **App Settings** - `arduino-cli` executable, `arduino-cli.yaml`, the
  `fastbuild` executable, and a default cache root, shared by every other
  tab that needs them; auto-saved and reloaded on startup. Opens
  automatically on first run if unconfigured. The default cache root
  auto-fills a sketch's Cache root field the first time it's opened (if
  that sketch doesn't have its own saved value yet) - each sketch can still
  override it individually afterward on the Project tab.
- **Cache & Dependencies** - persistent-cache and dependency-hashing knobs,
  plus one-shot force actions (Force Recompile Now, Clean & Rebuild Now,
  Force Rebuild Header Index Now), each behind a confirmation dialog. Also
  where "Pin platform version" lives - see its own section below.
- **Output & Export** - stats/logging/export/build-properties options
- **Hex Viewer** - standalone read-only hex/ASCII dump of any file. Its
  file picker defaults to the current sketch's folder whenever nothing's
  been picked yet this session (falls back to wherever the last-picked
  file's folder was, once something has).
- **Daemon** - `-daemon` (background build server) and `-connect` (send a
  build to an already-running daemon), each with its own log and
  Start/Stop control, fully independent of the main build runner
- **Watch** - `-watch` (rebuild automatically on change), same independent
  background-process pattern as Daemon; if "Send this build to a running
  daemon" is checked on the Daemon tab, Watch adds `-connect` automatically
  instead of building locally on every change
- **Dependency Viewer** - shows the `#include` tree for the current sketch
  using the same resolution order and platform/library roots fastbuild's
  own dependency scanner uses

An Activity Log panel (below the tabs) records a running history of
everything the app has done, independent of any one tab's own log.

A persistent status bar (below the tabs, above the Activity Log, visible
under every tab) shows four fields at a glance:

- **Sketch** - the current sketch's filename.
- **Board** - the board's friendly name (e.g. `NodeMCU 0.9 (ESP-12
  Module)`), resolved from the Board Wizard's board list when it matches
  what's currently applied, falling back to the raw FQBN otherwise (e.g. if
  it was typed in directly, or the Wizard hasn't loaded platforms yet).
- **Cache** - `HIT` if the last build reused a previous result without
  recompiling, `MISS` if it actually ran a fresh compile (including Force
  Recompile / Clean & Rebuild, which always miss on purpose), or `-` if no
  build has run yet this session, or the last one failed before fastbuild
  could report either way.
- **Flash/RAM** - program storage and dynamic memory usage from the last
  real compile, e.g. `Flash 255KB/1020KB (25%)  |  RAM 27KB/80KB (34%)`.
  Parsed directly from `arduino-cli`'s own compile output (the standard
  "Sketch uses X bytes... Global variables use Y bytes..." lines) -
  fastbuild itself has no concept of memory usage at all, and this is the
  only place in the whole app that surfaces it, rather than needing to
  scroll through the raw log to find it. Only ever printed on a real
  compile (a cache hit skips invoking `arduino-cli` entirely, so there's
  nothing to parse) - see "Flash/RAM persistence" below for how this is
  still shown on a cache hit.

## Flash/RAM persistence

Since a cache hit means fastbuild reused the *exact same binary* as before,
the flash/RAM figures from the last real compile are still 100% accurate on
a cache-hit run - arduino-cli just never gets invoked to reprint them.
Rather than blank the status bar out on every cache hit, these figures are
remembered (in the per-sketch cache - see "Persistence model" below) and
keep showing through subsequent cache hits, including across an app
restart. They only ever get refreshed when a new real compile reports new
numbers, and are cleared when applying a different board (a different
board's figures would be actively misleading to keep showing) or when
opening a sketch that's never been built.

## Why there's no "Build Summary" panel

This came up in review and is worth explaining rather than leaving as an
apparent gap: a post-build summary (success/fail, time, cache status,
source/dependency counts, output path) was considered, but almost every
piece of it is already surfaced somewhere - fastbuild's own `-stats`
summary already prints source/dependency counts and compile time in the
log, `export.go` already prints the output path when a binary is exported,
and Cache time are already lifted into the Build Log tab / status bar
above. Adding a whole separate panel to redisplay data that's already
visible in two places didn't seem worth the added UI surface. Flash/RAM
usage was the one genuinely missing piece - covered by the status bar's
Flash/RAM field above instead of a dedicated panel, in keeping with
"polish something that already exists" over "add another thing to look
at."

## Board Wizard prefetch

The Board Wizard normally fetches a board's menu options lazily, the moment
you select it. The "Prefetch mode" setting (ask / full / off) mirrors
fastbuild's own `-configure-board -wizard-prefetch` behavior: after a fresh
(non-cached) platform/board fetch, it can concurrently prefetch every
installed board's options up front instead, using a worker pool sized by
"Prefetch workers". This is a native Java implementation (not a shell-out to
`-configure-board`, which is an interactive console wizard with no clean way
to drive from a subprocess) - but it reads and writes the exact same cache
file/format fastbuild's own CLI uses, so prefetching from either side
benefits the other.

## Platform version pinning

"Pin platform version" (Cache & Dependencies tab) maps to fastbuild's
`-platform-version` / `platformVersion=` - pins an exact installed platform
version instead of auto-selecting the highest installed one, for
reproducible builds if multiple core versions are ever installed side by
side. The dropdown is auto-detected, not hand-typed: it lists whatever's
actually installed for the current board's `vendor:arch` (e.g.
`esp8266:esp8266`, `arduino:avr`), read straight from
`<data>/packages/<vendor>/hardware/<arch>/*` - the same directory
convention `resolvePlatformDir` uses on the Go side. Sorting is a direct
port of `compareVersions` from `deps.go` (numeric segment-by-segment
comparison, so `3.10.0` correctly sorts after `3.9.0`), so "highest
installed" in this dropdown always matches exactly what fastbuild itself
would pick when this is left blank. A caption above the dropdown shows
which platform the listed versions belong to. Refreshes automatically
whenever the FQBN changes (switching boards, applying a different one from
the Wizard), plus a manual Refresh button for when a platform gets
installed/updated without restarting the app. The dropdown is
selection-only (not editable) - a previously pinned version that isn't
among what's currently detected (e.g. after switching platforms, or if
that exact version was uninstalled) is kept in the list rather than
silently dropped, so a saved preference is never lost, just clearly
distinguishable from what's actually available.

## Menu bar

**File** - New / Open Config / Save / Save As / Export .bat File / Exit.
New/Open/Save/Save As are for the fastbuild `.config` file itself (the
CLI's own key=value format, separate from the per-sketch/project
persistence described below). Save As always ensures a `.config`
extension - if you type a name without one, `.config` is appended
automatically; a name that already ends with it (any case) is left alone.
You're still free to name the file whatever you want, just never left
with an extensionless file by accident. Export .bat File is covered in
its own section below.

**Settings** - opens each optional tab (see "What's in the UI" above).

**Help** - **Quick Help** shows a compact, scrollable reference (the same
"what do I click when" guidance as the cheat sheet in `HOW_TO_GUIDE.md`,
plus a short status bar legend and the keyboard shortcuts below) without
leaving the app or needing the full guide open; **About** shows the app
name/version and a one-line description of what it does. Both are
self-contained dialogs built into the app - not links to the external
docs, so they still work if the `.md` files aren't sitting alongside the
jar.

## Keyboard shortcuts

- **Ctrl+B** - saves every open file in the Explorer tab with unsaved
  changes, then runs a build. Bound to the whole window
  (`WHEN_IN_FOCUSED_WINDOW`), so it works regardless of which tab has
  focus, not just while actively editing.
- **Ctrl+S** - saves the file currently being edited in the Explorer tab.
- **Enter** - in the Explorer tab's Find box, jumps to the next match
  (same as clicking Find Next).

## Export .bat File

Generates a standalone Windows batch script that reproduces the current
build settings without this app running at all - useful for a quick
rebuild (e.g. producing a `.hex`/`.bin`) without opening the full UI.
Saved as `<sketch-name>_build.bat` in the sketch's own folder; if that
file already exists, you're asked to confirm before it's replaced. The
generated script's content is also echoed into the Build Log after
saving, so you can see exactly what it contains without opening it in a
separate editor.

Mechanically, the script:
1. Writes its own temporary fastbuild config file (one `echo key=value`
   line per setting, since fastbuild only ever reads its config from a
   real file or stdin, never as a pile of individual CLI flags).
2. Invokes `fastbuild.exe` against that temp config, using the exact same
   flag-assembly logic (`buildFastbuildFlags`) the in-app build itself
   uses, so the two can never quietly drift apart.
3. Deletes the temp config and pauses on "Press Enter to close this
   window..." once finished, showing the exit code.

A few deliberate choices worth knowing about:
- **Verbose is forced on** in the exported config regardless of the
  current UI setting, so there's always live compiler output streaming -
  standing in for a progress indicator, which batch scripting has no
  clean way to show alongside a foreground process's own output without
  backgrounding it and losing that live streaming entirely.
- **Cancellation is Ctrl+C**, same as any command-line tool - confirmed
  in practice to correctly stop mid-build via cmd's own "Terminate batch
  job (Y/N)?" prompt. The script also prints fastbuild's own PID right at
  the start and a `taskkill /F /T /PID <pid>` fallback instruction, for
  the rare case a child process (fastbuild spawns arduino-cli as its own
  child) needs cleaning up from a second window - the same recursive-kill
  approach the in-app Cancel button already uses, just spelled out
  manually here since a plain Ctrl+C doesn't carry that same guarantee.
- **Values are escaped for cmd.exe's special characters** (parentheses,
  `&`, `|`, `<`, `>`, `^`, `%`) before being written into the script -
  this matters in practice, not just in theory, since a completely
  ordinary installation path like `C:\Program Files (x86)\...` contains
  literal parentheses that would otherwise confuse cmd's own
  block-grouping parser.
- **Upload/Force/Clean behavior comes from the temp config file itself**,
  not extra command-line flags - those settings are already part of what
  gets written into the config (matching how the in-app build works too),
  so whatever's currently configured carries over correctly without any
  special-casing for the exported script.

## Interactive prompts, replicated in-app

fastbuild has two places where it normally asks a yes/no question on stdin
if something needs a decision: a stale cached header index ("rebuild it
now?"), and an export destination that already exists ("overwrite it?").
Neither prompt ever actually reaches fastbuild when it's run from this UI,
since the config is always piped over stdin - fastbuild detects that
stdin isn't a real interactive terminal and silently falls back to a safe
default instead (keep the stale index; auto-rename rather than overwrite)
rather than hanging.

Both are replicated as real dialogs in this UI instead, so picking the
"ask" behavior for either one can actually ask, the same as running
fastbuild directly in a terminal would:
- **Stale header index** - offered when the cached index is older than
  "Header index max age" and none of `-refresh-deps-index` /
  `-assume-yes-stale-deps` / `-skip-stale-deps-refresh` already decided it
  for this run.
- **Export conflict** - offered when "Export binary to sketch folder" is
  checked, its conflict policy is "ask", and a file already sitting in the
  sketch's folder would collide. Since this UI can't reliably predict the
  exact output filename ahead of time (the extension depends on the
  board's own platform - `.hex` for AVR, `.bin` for ESP8266/ESP32, etc.),
  it checks for any existing file that starts with the sketch's name and
  ends in a common build-output extension, rather than guessing one
  platform-specific name and risking a false negative.

Either way, the answer becomes a one-shot CLI flag override for just that
run (`-assume-yes-stale-deps`/`-skip-stale-deps-refresh`, or
`-export-conflict=overwrite`/`rename`) - never written back into the saved
config, so the underlying setting stays whatever it was.

## Persistence model

Three separate JSON files, each with a different scope - worth understanding
since they save at different times:

1. **App settings** (`AppSettingsStore`) - the `arduino-cli` executable and
   yaml paths. Auto-saved whenever changed.
2. **Project settings** (`ProjectSettingsStore`, `fastbuild-ui-project-settings.json`) -
   one global "whatever was last open" snapshot of every Project/Cache/Output
   setting. Saved on sketch switch, on explicit Save Config, and on exit.
3. **Per-sketch cache** (`SketchSettingsStore`, one `fastbuild-ui-cache.json`
   per sketch folder) - remembers each individual sketch's own settings,
   Board Wizard selection, and last-known Flash/RAM usage (see "Flash/RAM
   persistence" above), restored automatically when that sketch is
   reopened. Saved after a successful build/upload, on Apply to Project, on
   switching away from that sketch, on explicit Save Config, and on exit -
   deliberately more trigger points than #2, since this is the one that
   actually determines what a given sketch remembers.

## Apply to Project

Selecting a board in the Board Wizard only updates the wizard's own
"Assembled FQBN" preview - it does **not** change the Project tab's actual
FQBN (what a build actually uses) until **Apply to Project** is clicked. A
warning label appears under that button whenever the two are out of sync, so
this is hard to miss - but it's worth knowing the wizard is a picker, not a
live-bound control.

## Build progress

fastbuild and arduino-cli don't print an overall percentage, so the Build
Log tab's progress bar is a best-effort heuristic from two sources, tried in
order for each streamed line:

1. **Upload** - parses esptool's own `(NN %)` output directly when present;
   this is a real, accurate number straight from the tool.
2. **Compile** - otherwise maps arduino-cli's well-known verbose-compile
   stage banners (`Detecting libraries used...`, `Compiling sketch...`,
   `Compiling core...`, `Linking everything together...`, etc.) to
   approximate milestones.

It only ever moves forward within a single run, resets to 0 at the start of
each build/upload, and reaches 100 when the process concludes (success,
failure, or cancel alike).

## Log filtering

Both hide-path checkboxes default to checked. "Hide repeating tool paths"
covers the `arduino-cli`/fastbuild executable paths, the cache root, the
sketch folder, and the entire installed-platforms `packages/` directory
(read from `arduino-cli.yaml` at runtime, so it isn't tied to any specific
platform - installing a new board package doesn't require any code change
here). The same setting also collapses consecutive lines that are "the same
tool invocation, only the file changed" (compiler and archiver calls alike),
detected generically by diffing tokens rather than recognizing specific
tools - so an unfamiliar toolchain's repeated invocations still collapse.

## Explorer tab error highlighting

Parsed from the standard gcc diagnostic format (`file:line[:col]:
error|warning: message`) against the raw build output, before the
hide-path/collapsing filters above run - those replace real paths with
placeholders, and this needs the actual path on disk to open the file.
Each match opens its file in the Explorer tab (if not already open) and
highlights the line - red for errors, orange for warnings - then switches
to the Explorer tab and jumps to the first error, only if something was
actually found (a clean build never yanks focus away). Highlights clear
at the start of every new build.

## Known limitations

- Windows-only process-kill path (`taskkill /F /T`) for stopping the
  daemon, watch, and a running build/upload - this assumes a Windows host
