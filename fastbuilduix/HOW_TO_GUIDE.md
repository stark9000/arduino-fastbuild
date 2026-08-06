# fastbuilduix - How to Use This App

This is a desktop app for building and uploading Arduino sketches, faster
than the normal Arduino IDE, using a tool called `fastbuild` behind the
scenes. This guide is split in two:

- **[Basic Guide](#basic-guide)** - everything explained in plain language,
  no technical background needed. Read this first.
- **[Advanced Guide](#advanced-guide)** - what's actually happening under
  the hood, for people who want to understand the mechanics or troubleshoot
  something unusual.

---

# Basic Guide

## What this app actually does

You give it a sketch (`.ino` file) and a board, and it compiles and
optionally uploads it - like the Arduino IDE's "Verify" and "Upload"
buttons, but it remembers what it already built last time, so repeat builds
of the same sketch are much faster.

## One-time setup

If you haven't installed the Arduino IDE, `arduino-cli`, or any board
cores (ESP8266, ESP32, AVR, etc.) yet, see `INSTALL_GUIDE.md` first - it
walks through all of that from scratch. This section assumes you already
have `arduino-cli` working.

Before anything else works, tell the app where a few things live, on the
**App Settings** tab (it opens automatically the first time):

1. **Arduino CLI executable** - the `arduino-cli.exe` file. If you already
   use the Arduino IDE, `arduino-cli` may already be installed alongside
   it; otherwise you'll need to install it separately.
2. **arduino-cli.yaml** - a small settings file that `arduino-cli` itself
   uses. If you're not sure where it is, running `arduino-cli config dump`
   in a terminal will show you.
3. **fastbuild executable** - the `fastbuild.exe` file itself.

Optionally, you can also set a **default cache root** - prefilled with
`<your home folder>\.arduino-fastbuild` (fastbuild's own standard default),
used to auto-fill the Cache root field the first time you open a sketch
that hasn't been built with this app before. Not required; each sketch can
still be pointed at a different cache folder individually if you ever
want that.

You only need to do this once - the app remembers it.

Forget any of this later? **Help > Quick Help** in the menu bar has a
condensed version of the cheat sheet below, without leaving the app.

## Everyday use

1. **Project tab** - browse to your sketch's `.ino` file.
2. **Board Wizard tab** - pick your board:
   - **Platform** - the *type* of board (e.g. "ESP8266 Boards", "Arduino
     AVR Boards"). This is basically the manufacturer/family.
   - **Board** - the specific model (e.g. "NodeMCU 1.0", "Arduino Uno").
   - **Board options** - extra settings some boards need (flash size, CPU
     speed, upload speed, etc). If you don't know what these mean, the
     defaults are usually fine.
   - Click **Apply to Project** when you're happy with your choice. This
     is an easy thing to forget - see the box below.

   > **Important:** picking a board here doesn't do anything by itself.
   > You have to click **Apply to Project** to actually use it. If you
   > forget, a warning message appears right under the button telling you
   > so - if you see that warning, click Apply to Project before building.

3. Back on the **Project tab**, click **Run Build**. Output streams into
   the **Build Log** tab.
4. To upload to a real board afterward, plug it in, go to the **Upload**
   tab, pick the right serial port (USB port), and click **Upload Now**.
   Or just tick "Upload" as part of the build so it happens automatically.

That's the whole everyday loop: pick sketch → pick board → build → upload.

**Keyboard shortcuts:**
- **Ctrl+B** - saves every open file in the Explorer tab that has unsaved
  changes, then runs a build. Works from anywhere in the window, so you
  don't need to click back into the Project tab first.
- **Ctrl+S** - saves the file you're currently editing in the Explorer tab.
- **Enter** (in the Explorer tab's Find box) - jumps to the next match,
  same as clicking Find Next.

## Quick rebuilds without opening the app: Export .bat File

**File > Export .bat File...** creates a standalone script (saved right
next to your sketch) that reproduces your current build settings and can
be run on its own, without the UI open at all. Handy for a quick rebuild
- e.g. producing a fresh `.hex`/`.bin` - without waiting for the whole app
to start up.

Double-click the generated `.bat` and a command window opens, showing the
same kind of live compiler output you'd see in the Build Log tab. It stays
open until you press Enter, so you can read the result before it closes.
If you need to stop it partway through, **Ctrl+C** works the normal way
any command-line tool responds to it - press it, then answer `Y` when
asked to confirm.

If you change something in the app afterward (a different board, a
different setting), just export again - it'll ask before replacing the
existing file.

## Understanding the Build Log tab

- The big text area is the actual compiler output. You don't need to read
  all of it - look for the last few lines, which say whether it succeeded
  or failed.
- Two checkboxes at the top clean this up for readability:
  - **Hide user folder path** - replaces your Windows username/folder with
    `<home>` so the log isn't full of your personal file paths.
  - **Hide repeating tool paths** - shortens the very long, repeated file
    paths to the compiler/toolchain, and collapses repeated "compiling
    this file, compiling that file" lines into short one-liners.
  Both are on by default; there's rarely a reason to turn them off unless
  you specifically need to see a full raw path.
- **Selected board** - shows which board is actually going to be used for
  the next build (see the Apply to Project warning above).
- The little bar in the top-right corner counts up while a build/upload is
  running, and the small progress bar (next to Clear Log) gives a rough
  sense of how far along it is - neither is exact down to the second, just
  a general sense of progress.
- **Open Sketch Folder** - appears next to the progress bar whenever
  "Export binary to sketch folder" is checked (Output & Export tab).
  Opens that folder directly in your file explorer - handy right after a
  build for grabbing the exported file.
- **If a build fails**, the Explorer tab automatically opens (and switches
  to) every file with a compile error, with the problem line highlighted
  directly in the editor - similar to how the Arduino IDE points at the
  line that failed. Jumps you straight to the first error. A successful
  build clears any highlights left over from a previous failed one.

There's also a thin status bar visible under every tab (not just Build
Log) showing your sketch, board, cache status, and flash/RAM usage - handy
for a quick glance without switching tabs.

**Cache: HIT or MISS** - this tells you whether the last build actually
recompiled anything:
- **HIT** means nothing relevant changed since last time, so it reused the
  previous result instead of recompiling - much faster.
- **MISS** means it actually compiled from scratch (your code changed, a
  setting changed, or you used a button like Force Recompile/Clean &
  Rebuild, which always recompile on purpose).
- **-** means no build has run yet this session, or the last one failed
  before getting far enough to tell either way.

Neither HIT nor MISS is "good" or "bad" by itself - a HIT after you just
made a change would be worth double-checking (did your edit actually get
saved?), while a MISS is completely normal after any real code change or
after Force Recompile/Clean & Rebuild.

**Flash/RAM** shows how much of your board's storage and memory your
sketch is using, e.g. `Flash 255KB/1020KB (25%)  |  RAM 27KB/80KB (34%)` -
useful for checking your sketch still fits on the board without digging
through the log. This only gets fresh numbers from an actual compile (a
cache hit doesn't recompile, so there's nothing new to read) - but since a
cache hit means the exact same result as before, the app keeps showing the
last real numbers instead of blanking them out, and remembers them between
sessions too. They only change again on the next real compile, or clear
out if you switch to a different board or open a sketch that's never been
built before.

## Plain-English guide to the settings tabs

You mostly don't need to touch anything beyond Project and Board Wizard.
The rest are for specific situations:

### Cache & Dependencies tab

This app keeps a cache so it doesn't recompile things that haven't
changed. Almost all the checkboxes here can be left on their defaults.
The three buttons at the bottom are the ones actually worth knowing:

- **Force Recompile Now** - "rebuild everything from scratch this one
  time, ignoring the cache." Use this if you think a build is using
  outdated code and you're not sure why.
- **Clean & Rebuild Now** - like Force Recompile, but also wipes the
  build folder first. Use this if something seems properly broken/stuck
  and a normal rebuild doesn't fix it.
- **Force Rebuild Header Index Now** - use this **after you install a new
  board type (platform) or add/update/remove a library**. The app keeps
  a list of where all your libraries' files live so it doesn't have to
  search for them every time; this button tells it to go look again,
  since your library setup just changed.

**Rule of thumb:** installed a new library or platform → Force Rebuild
Header Index Now. Build seems wrong/stuck → Force Recompile Now or Clean &
Rebuild Now.

There's also **"Pin platform version"** - a dropdown that defaults to
"(auto - highest installed)". Leave it there unless you specifically need
a particular version - it auto-lists whatever's actually installed for
your current board's platform, so you don't have to know or type version
numbers yourself. Useful mainly if you have more than one version of a
core installed side by side and want to make sure a build always uses a
specific one, rather than whichever happens to be newest at the time.

### Output & Export tab

Controls whether a copy of the finished `.bin`/`.hex` file gets saved
somewhere convenient after a successful build, and what to do if a file
with that name already exists (overwrite it, keep both, or ask each time).
Leave this on the defaults unless you specifically want the built file
saved somewhere for later (e.g. to re-upload without rebuilding, or to give
to someone else).

### Hex Viewer tab

A simple viewer for looking at the raw bytes of any file. Not part of the
normal build/upload flow - only open this if you specifically want to
inspect a file's contents. Its Browse button starts in your current
sketch's folder by default, which is usually where an exported
`.hex`/`.bin` file would be sitting.

### Dependency Viewer tab

Shows which files your sketch actually includes/depends on, as a tree.
Mostly useful if you're curious why a rebuild is slower/faster than
expected, or want to double check a library is actually being picked up.

### Daemon / Watch tabs

Both are optional, more advanced ways of working - see the Advanced Guide
below. You don't need either for normal day-to-day use.

## "What do I click when...?" cheat sheet

| Situation | What to do |
|---|---|
| First time using the app | Fill in App Settings, then Project + Board Wizard |
| Just want to build and check for errors | Run Build |
| Want to build and immediately flash the board | Tick Upload (or Upload Now after) |
| Just installed a new board type / library | Cache & Dependencies → Force Rebuild Header Index Now |
| Build seems to be using old/wrong code | Cache & Dependencies → Force Recompile Now |
| Something's properly broken and won't rebuild right | Cache & Dependencies → Clean & Rebuild Now |
| Picked a different board in the wizard | Click Apply to Project before building |
| Want to re-upload something already built, without rebuilding | Upload tab → Upload This File |
| Board Wizard is showing an old list of boards | Board Wizard → Force Refresh Wizard Cache & Reload |
| Log is cluttered with long file paths | Already handled - the two checkboxes at the top of Build Log are on by default |

---

# Advanced Guide

## Architecture in one paragraph

This app doesn't reimplement anything - it's a GUI wrapper that assembles
the exact same command-line flags the `fastbuild` binary accepts, runs it
as a subprocess (config piped over stdin, `-` convention), and streams its
stdout back into the relevant log area. Anything documented in fastbuild's
own `-help` output is reflected somewhere in this UI.

## FQBN and the Board Wizard

An FQBN (Fully Qualified Board Name) has the shape
`vendor:arch:board:option1=value1,option2=value2`. The Board Wizard's
Platform/Board/option combos are just a friendlier way of assembling this
string - **Assembled FQBN** shows the literal result. Selecting a board
only updates that preview field; the Project tab's actual `fqbn` field
(what a build actually uses) is a completely separate value that's only
overwritten when you click **Apply to Project**. A warning label appears
under that button whenever the two differ, generated by comparing the
preview field's text against the Project tab's field directly (via
document listeners on both), not by tracking button clicks.

The wizard's platform/board list and each board's menu options are cached
in a shared JSON file (default `<home>/.arduino-fastbuild/`), in the exact
same format `fastbuild -configure-board` itself uses on the CLI side - so
a cache warmed by one benefits the other. The cache is keyed by a signature
computed from the installed platforms' folder names/versions, so installing
or updating a platform invalidates it automatically on the next fetch.

**Prefetch mode** (ask/full/off) governs what happens right after a fresh
(non-cached) platform/board fetch: instead of only fetching a board's menu
options the moment you select it (the lazy default), it can concurrently
fetch every installed board's options up front, using a worker pool sized
by "Prefetch workers". This is a native, from-scratch Java implementation
(not a shell-out to `-configure-board`, which is an interactive console
wizard with no clean way to drive or decline via a subprocess) - it uses
the same stateless per-board fetch call the lazy path already relies on,
just run concurrently across a thread pool, with board-combo selection
disabled for the duration to avoid two different threads racing to write
the same cache map.

## Caching and dependency tracking

Two independent caching layers:

1. **Build cache** (`cacheRoot`, default `<home>/.arduino-fastbuild`) - a
   content-addressed cache keyed by a hash of everything that could affect
   the compiled output: the sketch's own source, resolved library/core
   headers (if `hashLibraryHeaders` is on and a `configFile` is set), and
   the installed toolchain's version folders (if `hashToolchain` is on). If
   the computed key matches a previous build's, that build's outputs are
   reused - "cache hit" in the Build Log / status bar. Since a cache hit
   reuses the exact same binary, `arduino-cli` never actually runs on a hit
   - which also means it never reprints its flash/RAM usage lines. The
   status bar's Flash/RAM field works around this by remembering the last
   real compile's figures (persisted per-sketch, see "Persistence" below)
   and continuing to show them through subsequent hits, since they're
   still accurate; it only refreshes them on the next real compile, and
   clears them when Apply to Project changes the board (see "Persistence
   - three separate files" below for where this is stored).
2. **Header index** - a separate cache of *where each `#include` in your
   sketch/libraries actually resolves to on disk* (regex-based scanning,
   `depsMode = regex`), so the app doesn't have to re-search your entire
   library folder on every single build. This is what goes stale when you
   install/update/remove a platform or library - hence "Force Rebuild
   Header Index Now" specifically, distinct from the build cache above.
   `depsIndexMaxAgeHours` controls how old a signature-valid index can get
   before it's considered stale by age alone (separate from the
   installed-library signature check); 0 disables the age check entirely.
   `assumeYesStaleDeps` / `skipStaleDepsRefresh` decide what happens when
   that staleness prompt would normally appear - since this app pipes its
   config over stdin (not a real interactive terminal), fastbuild itself
   can never actually see that prompt, so the UI reproduces the same
   question itself and passes the matching one-shot flag through. The
   export-destination-already-exists prompt (when "On export conflict" is
   "ask") gets the identical treatment, for the identical reason - see
   "Interactive prompts, replicated in-app" in the README for both.

The three force buttons on Cache & Dependencies map directly to CLI flags:
Force Recompile Now → `-force` (bypass the build cache once), Clean &
Rebuild Now → `-clean` (wipe the build folder first), Force Rebuild Header
Index Now → `-refresh-deps-index` (bypass the header index's signature/age
checks and rebuild it unconditionally).

**Pin platform version** maps to `-platform-version` / `platformVersion=`.
The dropdown is populated by scanning
`<data>/packages/<vendor>/hardware/<arch>/*` for the current FQBN's
`vendor:arch` - the same directory convention `resolvePlatformDir` uses on
the Go side - and sorted with a direct port of `compareVersions` from
`deps.go` (numeric, not lexical, so `3.10.0` sorts after `3.9.0`). Not
editable - a saved/pinned version that isn't among what's currently
detected stays in the list rather than being silently dropped, so
switching platforms or uninstalling a version never quietly loses a saved
preference.

## Daemon, Connect, and Watch

- **Daemon** (`-daemon`) runs fastbuild as a persistent background process
  that accepts build requests over a TCP address, so the toolchain/cache
  stays warm between builds instead of a fresh process paying startup cost
  each time. Its stale-deps policy can't prompt (there's no interactive
  caller to ask), so it takes a fixed policy instead (`skip` or `refresh`).
- **Connect** (`-connect`) sends a single build request to an
  already-running daemon instead of building in-process.
- **Watch** (`-watch`) reruns a build automatically whenever the sketch or
  its dependencies change, on a polling interval (`-watch-interval`). If
  Connect is also checked, Watch adds `-connect` too, so every detected
  change is sent to the daemon instead of building locally each time.

All three run as independent background processes (their own Start/Stop
button and log area), deliberately not sharing the main build-runner's
UI-locking state - a daemon or a watch session is meant to keep running
in the background while you keep using the rest of the app normally.

## Persistence - three separate files

- **App settings** - the `arduino-cli` executable/yaml paths only.
- **Project settings** (one global file) - a "whatever was last open"
  snapshot of every Project/Cache/Output setting, saved on sketch switch,
  explicit Save Config, and exit.
- **Per-sketch cache** (one file per sketch folder) - each sketch's own
  settings, Board Wizard selection, and last-known Flash/RAM usage,
  restored automatically when that sketch is reopened. Saved after a
  successful build/upload, on Apply to Project, on switching away from
  that sketch, on explicit Save Config, and on exit - deliberately more
  trigger points than the project-settings file, since this is the one
  that actually determines what a given sketch remembers between
  sessions.

## Log filtering and the progress heuristic

"Hide repeating tool paths" substitutes several known paths (the
`arduino-cli`/fastbuild executables, the cache root, the sketch folder,
and the entire installed-platforms `packages/` directory, read from
`arduino-cli.yaml` at runtime rather than hardcoded per platform) and also
collapses consecutive lines that are "the same tool invocation, only the
file argument changed" - detected generically by tokenizing each line and
diffing against the previous one, rather than recognizing specific
compiler/archiver names, so an unfamiliar toolchain's repeated invocations
still collapse correctly.

The progress bar has no real percentage to work with (neither fastbuild
nor arduino-cli print one), so it's a best-effort estimate: esptool's own
`(NN %)` output during upload is parsed directly (a real number), while
compilation falls back to recognizing arduino-cli's well-known verbose
stage banners (`Detecting libraries used...`, `Compiling core...`,
`Linking everything together...`, etc.) mapped to approximate milestones.
It only ever moves forward within one run.

## Explorer tab error highlighting

Parsed from the standard gcc diagnostic format every compiler in the
toolchain uses - `file:line[:col]: error|warning: message` - matched
against the raw, unfiltered build output (before the hide-path/collapsing
filters run, since those replace real paths with placeholders and this
needs the actual file path on disk to open it). Each match opens its file
in the Explorer tab if it isn't already open, highlights the line (red for
errors, orange for warnings), and the tab bar switches to Explorer and
jumps to the first error - only if something was actually highlighted, so
a clean build never yanks you away from wherever you were. Highlights
clear at the start of every new build, so a fixed error's old highlight
doesn't linger into the next run.

## Export .bat File - technical notes

Since fastbuild only ever reads its config from a real file or stdin
(never as individual CLI flags), the generated script writes its own temp
config file line-by-line via `echo`, invokes `fastbuild.exe` against it,
then deletes it. It reuses the exact same flag-assembly code the in-app
build uses, so the two can't drift apart. Verbose is forced on in the
exported config (there's no clean way to show a live progress indicator
alongside a foreground process's own streaming output in batch scripting
without backgrounding it and losing that output entirely - forcing
verbose output is the deliberate substitute). Every value written into
the script is escaped for cmd.exe's special characters first, since
ordinary paths like `C:\Program Files (x86)\...` contain literal
parentheses that would otherwise break cmd's block-grouping parser.

## Known limitations

- Process termination (`taskkill /F /T`) for stopping a running build,
  Daemon, or Watch session is Windows-specific.
