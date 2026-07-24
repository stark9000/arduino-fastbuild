# fastbuild

A thin wrapper around `arduino-cli` that fixes the two things that make iterative
Arduino builds slow:

1. **Persistent build cache.** `arduino-cli` already caches compiled core/library
   objects, but only within a given `--build-path`. Left to its default it uses a
   fresh random temp directory every run, so that cache gets thrown away constantly.
   `fastbuild` points `--build-path` at a fixed, persistent folder keyed by
   (sketch path + FQBN), so `arduino-cli`'s own incremental logic actually survives
   across builds.
2. **Skip the compiler entirely when nothing changed.** `fastbuild` hashes the
   sketch's own source files, every library/core header it transitively depends on,
   build properties, the FQBN, and the installed toolchain version. If none of that
   changed since the last successful build, it skips invoking `arduino-cli` at all and
   reuses the existing binary.

No third-party dependencies anywhere - standard library only, in both Go and (for the
optional GUI) Java 8.

---

## Quick start

```
fastbuild <path-to-config>
```

Copy `fastbuild.config.example` to a real config per project/board and fill in the
values (see **Config file reference** below). Everything else works automatically
with no flags needed.

**`-` reads the config from stdin instead of a file** (the standard Unix convention,
same as `tar`/`cat`/etc.):

```
cat myproject.config | fastbuild -
```

Both routes - a named file, or `-` - go through the exact same parser and validation;
there's no separate "stdin config format" to keep in sync with the file format. Useful
for a caller (a GUI, a script) that would rather pipe config text directly than manage
a temporary file on disk.

---

## Command-line flags

Every flag below overrides whatever the config file says for that one run, without
editing the file. All are optional.

### Cache behavior

| Flag | Effect |
|---|---|
| `-force` | Bypass the skip-if-unchanged check for this run only and always ask `arduino-cli` to build. `arduino-cli`'s own per-file object cache still applies. One-shot: does not repeat every tick under `-watch`. |
| `-clean` | Wipe the persistent build folder (and cached state) before building, for this run only. A true from-scratch rebuild. One-shot, same as `-force`. |
| `-no-deps` | Disable library/core header dependency hashing for this run. |
| `-no-toolchain` | Disable toolchain version fingerprinting for this run. |
| `-deps-mode <mode>` | How to detect library/header dependencies: `regex` (default, `#include` scanner) or `depfile` (use the compiler's own generated `.d` dependency file - see **Dependency file mode** below). `gcc` still works as an alias for `depfile`. |
| `-gcc-inject-mmd` | With `-deps-mode=depfile`, manually add `-MMD` via `build.extra_flags` instead of relying on `arduino-cli`'s recipe already including it. Use this if a board's `.d` file never shows up on its own - see **Dependency file mode** below. |
| `-platform-version <ver>` | Pin an exact installed platform version (e.g. `3.0.2`) instead of auto-selecting the highest installed one. Errors clearly if that version isn't installed. |
| `-refresh-deps-index` | Force a full rebuild of the cached library header index (regex mode) right now, regardless of its age or signature. |
| `-assume-yes-stale-deps` | When the cached header index is stale by age, rebuild it automatically instead of asking. |
| `-skip-stale-deps-refresh` | When the cached header index is stale by age, keep using it without asking. |

### Output / after the build

| Flag | Effect |
|---|---|
| `-stats` | Print a "FastBuild Statistics" summary after the build. |
| `-upload` | Upload the resulting binary after building (or after a cache-hit skip) via `arduino-cli upload --input-dir`, which does **not** trigger a recompile. Requires `-port`. |
| `-port <name>` | Serial port to upload to, e.g. `COM3`. Required with `-upload`. |
| `-export` | Copy the resulting binary into the sketch's own folder after building or skipping. |
| `-export-conflict <mode>` | What to do if the export destination already exists: `ask` (default), `overwrite`, or `rename`. |

### Logging

| Flag | Effect |
|---|---|
| `-json` | Emit `fastbuild`'s own status lines as JSON Lines instead of plain text - see **JSON output** below. `arduino-cli`'s own compiler output stays plain-text passthrough either way. |
| `-save-log` | Save this run's output to a timestamped file. Defaults to `<project>/logs/<timestamp>.log` (or `.jsonl` if `-json` is also on). |
| `-log-dir <path>` | Where `-save-log` writes to, if not the default. |

### Board configuration

| Flag | Effect |
|---|---|
| `-configure-board` | Interactively build a full FQBN: pick an installed platform, then a board within it, then its menu options - see **Board configuration wizard** below. Doesn't need or use a config file. |
| `-arduino-cli <path>` | Path to `arduino-cli.exe`, for use with `-configure-board` only. |
| `-arduino-cli-yaml <path>` | Path to `arduino-cli.yaml`, for use with `-configure-board` only. |
| `-wizard-cache-dir <path>` | Where `-configure-board` caches its platform/board/board-options data. Default: `<home>/.arduino-fastbuild`, same root as the normal build cache. |
| `-refresh-wizard-cache` | Force `-configure-board` to ignore its cache and re-fetch everything from `arduino-cli`. |
| `-wizard-prefetch <mode>` | Whether to prefetch every board's menu options upfront when a fresh fetch is needed: `ask` (default, prompts once), `full` (always prefetch), or `off` (only cache boards as you pick them) - see **Caching** below. |
| `-wizard-prefetch-workers <n>` | How many `arduino-cli board details` calls run concurrently while prefetching (only relevant with `-wizard-prefetch=full` or after accepting the `ask` prompt). Default `8`. Raise cautiously - see **Caching** below. |

### Daemon mode

| Flag | Effect |
|---|---|
| `-daemon` | Run as a persistent build server instead of doing a single build. Blocks forever. |
| `-daemon-addr <host:port>` | Address the daemon listens on (with `-daemon`) or connects to (with `-connect`). Default `127.0.0.1:9876`. |
| `-daemon-stale-deps-policy <mode>` | What the daemon does about a stale header index, since it can **never** prompt (see daemon.go for why). `skip` (default) or `refresh`. Overrides any individual request's config. |
| `-connect <host:port>` | Send this build to an already-running daemon instead of building in this process. |

### Watch mode

| Flag | Effect |
|---|---|
| `-watch` | Rebuild automatically whenever changes are detected (build-on-save). Combine with `-connect` to have a daemon do the actual building. |
| `-watch-interval <duration>` | How often to check, e.g. `500ms`, `2s`. Default `1s`. |

---

## Dependency file mode (`-deps-mode=depfile`)

The default `regex` mode scans `#include` lines to guess a sketch's dependencies.
It's fast and needs nothing but the source files, but it's not a real C preprocessor -
it can't evaluate `#ifdef`/`#ifndef`, so it may hash headers that a given build
configuration doesn't actually use (a safe direction to be wrong in: an unnecessary
recompile now and then, never a missed dependency).

`depfile` mode instead uses compiler-generated dependency files - the compiler's own
post-preprocessor view of exactly which files mattered, correctly handling conditional
includes a regex fundamentally can't evaluate. No changes to the compile command are
needed in the common case; see **Implementation notes** below for why.

**Supported:** any GCC-family toolchain, which covers every standard Arduino board
family:

| Board family | Compiler |
|---|---|
| AVR (Uno, Nano, Mega, ...) | `avr-g++` |
| ESP8266 | `xtensa-lx106-elf-g++` |
| ESP32 | `xtensa-esp32-elf-g++` / `riscv32-esp-elf-g++` (chip-dependent) |
| SAMD, RP2040, STM32 | `arm-none-eabi-g++` |

Only ESP8266 has been directly confirmed (against a real verbose build log showing
`-MMD` in the actual compile invocation); the others are expected to work identically
since they're all GCC-family toolchains sharing the same dependency-file mechanism,
but haven't each been individually verified. If a board doesn't generate a `.d` file
for some reason, `-gcc-inject-mmd` (below) is the fix.

**Bootstrap:** the first build for a project (or right after `-clean`) has no `.d`
files yet, so it transparently falls back to the regex scanner for that one run. After
a successful compile, every `.d` file found under the sketch's build folder is parsed
and unioned together (covering local `.cpp` files with their own separate translation
units, not just the main `.ino`), then saved to `<cacheRoot>/<project>/gcc-deps.json`.
Every subsequent run uses that instead.

**If a board's `.d` file never appears:** pass `-gcc-inject-mmd` (or set
`gccInjectMMD=true`) to have `fastbuild` add `-MMD` itself via `build.extra_flags`,
merged with any existing value you've already set, not overwriting it.

**A separate, narrower limitation** (applies to both `regex` and `depfile` modes
equally): local `.cpp`/`.h` files sitting in a *subfolder* of the sketch directory
aren't tracked by either mode. Keep local files flat next to the `.ino`, matching
Arduino's own convention.

---

## Board configuration wizard (`-configure-board`)

Building a full FQBN by hand means either copying it from a verbose IDE build log or
assembling it manually from `boards.txt` - both tedious, and board menu keys can
change between core versions (this project got bitten by exactly that once - see
`nodemcu=80` → `nodemcu:xtal=80` in project history).

`-configure-board` walks through it interactively instead:

```
fastbuild -configure-board -arduino-cli "C:\arduino-cli\arduino-cli.exe"
```

1. Lists your installed platforms, grouped and sorted alphabetically (`arduino-cli
   core list`) - so picking from "which of my ~10 installed platforms" is a short
   list, not hunting through every board from every platform mixed together.
2. Lists the boards belonging to just the platform you picked (`arduino-cli board
   listall`, filtered client-side by FQBN prefix).
3. Loads that board's menu options (`arduino-cli board details --json`) and prompts
   for each one, defaulting to the board's own default selection - just press Enter
   to accept every default.

Prints the finished FQBN and copies it straight to the clipboard (via `clip.exe`), so
it's ready to paste into a config file's `fqbn=` line with no manual retyping.
Clipboard access is non-fatal if it fails (no desktop session, some Remote Desktop
setups, `clip.exe` blocked by policy) - the FQBN is already printed either way, you'd
just copy it by hand in that case. Doesn't touch or require any existing `fastbuild`
config - only needs `-arduino-cli` (and `-arduino-cli-yaml` if you use one). If a
board's menu options can't be loaded for any reason, degrades gracefully to just the
base FQBN rather than failing outright.

Each `arduino-cli` call (listing platforms, listing boards, loading a board's menu
options) can take a real, noticeable moment - an animated "please wait" indicator
shows while each one runs, so it's visibly working rather than looking stuck.

### Caching

`core list` and `board listall` are cached to `<wizard-cache-dir>/board-wizard-cache.json`,
and so is every board's menu options (`board details`) - either lazily, one board at a
time as you pick it (the original behavior), or **prefetched for every board across
every installed platform in one pass**, controlled by `-wizard-prefetch`:

- **`ask`** (default): the first time a fresh fetch is actually needed (first run, or
  the signature changed - see below), prompts once, right there, asking whether to
  prefetch everything now. Answering no falls back to lazy, one-board-at-a-time
  caching for this cache generation. If stdin isn't interactive (a scripted or
  automated invocation, nothing to answer with), silently skips the prompt and
  behaves like `off` - launching a burst of concurrent `arduino-cli` processes without
  anyone around to consent to it isn't a safe unattended default.
- **`full`**: always prefetches everything on a fresh fetch, no prompt.
- **`off`**: never prefetches. Only the boards you actually pick get cached, one at a
  time - fastest to get to your first board selection, at the cost of every
  not-yet-picked board staying slow until it's picked once.

With several hundred boards installed across a handful of platforms, lazy-only
caching (`off`, or declining the `ask` prompt) means most boards never happen to get
picked a first time and stay slow forever. Prefetching (`full`, or accepting the
`ask` prompt) trades that for one noticeably-slower upfront pass in exchange for
every board being instant from its very first selection onward, this run and every
run after - until something actually changes and a fresh fetch is triggered again.

The prefetch runs with up to 8 `board details` calls in flight at once by default
(rather than one at a time) to keep that one-time pass reasonably short, and shows
live progress (`N/<total> boards done`) so it's clear it's working rather than stuck.
`-wizard-prefetch-workers <n>` raises or lowers that concurrency - **raise cautiously**:
each worker is a full `arduino-cli` process spawn, and a large burst of them launching
at once is a real, sustained CPU/memory spike that has been observed to cause display
driver instability (monitor blackout-and-recovery) on at least one real machine. If
you see anything like that, lower `-wizard-prefetch-workers` instead - see
**Interrupted prefetches** below for what happens to progress already made when you
do. A board that fails to fetch during the prefetch (arduino-cli erroring for that
one FQBN, an unexpected JSON shape) is skipped with a warning rather than aborting the
whole prefetch - it simply falls back to a live fetch if you ever pick it.

The cache is invalidated automatically, not on a timer: it's keyed by a signature
computed from your installed platforms' version folders and the mtimes of each
platform's `boards.txt`/`platform.txt`, under the `arduino-cli.yaml` data directory.
Installing, removing, or upgrading a platform changes that signature, which silently
triggers a fresh fetch (subject to `-wizard-prefetch` above) and re-saves the cache -
no manual "clear cache" step needed. `-refresh-wizard-cache` forces this regardless,
if you ever want to bypass it explicitly. A board with no menu options is cached too
(as "has none," not just "never looked up"), so boards without a menu don't get
re-fetched either.

#### Interrupted prefetches

A prefetch that's killed, crashes, or gets Ctrl-C'd partway through - including as a
direct result of lowering `-wizard-prefetch-workers` after seeing the CPU/memory spike
above, since that's a real reason to want to abort one - doesn't lose its progress or
corrupt the cache:

- Every write to `board-wizard-cache.json` (including the checkpoints described below)
  goes through a temp file plus an atomic rename over the real cache file, never a
  direct in-place write. That means the on-disk file is always either the previous
  complete version or the new complete version - never a half-written one, no matter
  when the interruption happens.
- While a prefetch is running, progress is checkpointed to that file roughly every 2
  seconds (plus once more right when the prefetch finishes), so an interruption loses
  at most a couple of seconds of fetching, not the whole pass.
- A checkpoint saved mid-prefetch is marked internally as incomplete. The **next** run
  detects that, skips re-listing platforms and boards (already known and still good),
  and resumes by fetching only the boards that hadn't been reached yet - not starting
  over from zero.

Nothing extra needs doing to get this - a canceled or crashed prefetch, run again
later with `-configure-board -wizard-prefetch=full` (or by accepting the `ask`
prompt), just continues where it left off.

Confirmed working end-to-end against a real Windows install with real installed
platforms/boards/menu options (12 platforms, 27 AVR boards, a board with 3 processor
variants) - not just tested against a synthetic fake `arduino-cli`.


---

## JSON output (`-json`)

By default `fastbuild` prints human-readable prose. `-json` switches `fastbuild`'s own
status lines to JSON Lines (one JSON object per line, not one big document) - easier
for a GUI or script to parse reliably than matching against exact English wording, and
streamable/incremental rather than needing to wait for a complete file.

**Scope, deliberately:** only `fastbuild`'s own lines are wrapped. `arduino-cli`'s own
compiler output stays plain-text passthrough either way, in both modes. This is a
conscious boundary, not an oversight: `fastbuild` hands `arduino-cli`'s output
straight through via a direct OS-level pipe (`cmd.Stdout = os.Stdout`), which is *why*
it's simple and can't deadlock regardless of output volume. Wrapping `arduino-cli`'s
own lines too would mean reading its output ourselves line-by-line instead - a real
trade-off, not done without being a deliberate choice.

A complete stream, showing the shape end to end (a real fresh-compile run,
reformatted here for readability - each line is genuinely one JSON object, no
whitespace, when actually printed):

```json
{"type":"pid","pid":12345}
{"ts":"2026-07-20T01:17:41Z","source":"fastbuild","text":"Changes detected (or no previous successful build) - compiling..."}
{"ts":"2026-07-20T01:17:41Z","source":"fastbuild","text":"Build path: C:\\Users\\saliya\\AppData\\Local\\ArduinoFastBuild\\...\\build"}
{"ts":"2026-07-20T01:17:41Z","source":"fastbuild","text":"Running: C:\\...\\arduino-cli.exe compile --fqbn ... --build-path ..."}
{"ts":"2026-07-20T01:17:42Z","source":"fastbuild","text":"Build succeeded in 0.41 s."}
{"ts":"2026-07-20T01:17:42Z","source":"fastbuild","text":"Output: C:\\...\\sketch.ino.bin"}
{"type":"result","success":true,"cacheHit":false,"elapsedSeconds":0.41,"outputFile":"C:\\...\\sketch.ino.bin"}
```

A `pid` event (`{"type":"pid","pid":12345}`) is emitted once `-json` is on, in
addition to the always-present plain-text `fastbuild PID: 12345` startup line (printed
before config loading even happens, so it's visible even if that later fails - too
early for `-json`/`-save-log` to be known yet, which is why it's duplicated as a
structured event afterward rather than replaced).

A `result` event is always the last line of a run - `success`, `cacheHit` (true only
when the compile step was skipped entirely), `elapsedSeconds`, and `outputFile`
(empty on failure). Emitted on failure too (`success:false`), so a caller gets a
clean structured signal either way instead of inferring failure from a missing line.

---

## `-save-log`

Captures a run's output to a timestamped file, in whichever representation (plain or
JSON Lines) is active - saved content always exactly matches what was shown live.
Default location: `<cacheRoot>/<project>/logs/<timestamp>.log` (or `.jsonl` with
`-json`), timestamp format `20060102-150405`. Override with `-log-dir` or
`logDir=` in the config. A failure to create the log directory/file is non-fatal -
the build still runs, just without a saved copy, with a warning on stderr.

---

## Config file reference

Simple `key=value` lines, `#` for comments, `|`-delimited for list values.

```properties
# --- Required ---
arduinoCLI=E:/Program Files (x86)/arduinoCLI/arduino-cli.exe
sketch=C:/Users/saliya/Desktop/neopixel_scroll_text/neopixel_scroll_text.ino
fqbn=esp8266:esp8266:nodemcu:xtal=80,vt=flash,...,baud=115200

# --- Recommended ---
configFile=E:/Program Files (x86)/arduinoCLI/arduino-cli.yaml   # enables dependency-aware hashing
cacheRoot=C:/Users/saliya/AppData/Local/ArduinoFastBuild

# --- Optional ---
buildProps=                        # "|"-delimited key=value, each becomes --build-property
verbose=true
hashLibraryHeaders=true            # default true; requires configFile
hashToolchain=true                 # default true; requires configFile
depsMode=regex                     # regex | depfile (gcc still works as an alias)
gccInjectMMD=false                 # only relevant when depsMode=depfile - see Dependency file mode above
platformVersion=                   # pin an exact installed platform version (e.g. 3.0.2); blank auto-selects the highest installed one
depsIndexMaxAgeHours=24            # 0 disables the age check entirely (regex mode)
showStats=false
jsonOutput=false                   # see JSON output section
saveLog=false
logDir=                            # default <project>/logs if unset
force=false
clean=false
upload=false
port=                              # e.g. COM3
export=false
exportConflict=ask                 # ask | overwrite | rename
```

`fqbn` works for any board, not just ESP8266 - `fastbuild` only ever splits it on `:`
to get `package:arch` generically. Get a fresh FQBN from a verbose IDE Verify rather
than reusing an old one; board definitions can change menu keys between core versions
without a major version bump (this bit us once already with the ESP8266 core - see the
`nodemcu=80` → `nodemcu:xtal=80` fix in project history).

`configFile` should point at the same `arduino-cli.yaml` you use everywhere else,
with `directories.data` and `directories.user` pointed at your existing
`Arduino15`/sketchbook folders - not a fresh, separate arduino-cli data directory.

---

## Implementation notes

Deeper detail for maintainers/contributors, split out of the user-facing sections
above to keep those short.

**Why `depfile` mode needs no compile-command changes in the common case:**
`arduino-cli` passes `-MMD` as part of its standard `recipe.cpp.o.pattern` for the
board families in the support table above - confirmed directly by inspecting a real
verbose ESP8266 build log, where `-MMD` appears in the actual `xtensa-lx106-elf-g++`
invocation for the sketch's own compile step. Every GCC-family toolchain shares the
same dependency-file mechanism regardless of target architecture, so the same
assumption should hold for AVR/ESP32/SAMD/RP2040/STM32 without each needing separate
verification - though as noted above, only ESP8266 has actually been checked against
real output.

**Silent-degrade protection:** if more than half of a harvested dependency list's
paths fail to read back (e.g. a board/toolchain that emits relative paths instead of
absolute ones), `fastbuild` prints a loud warning to stderr rather than quietly hashing
almost nothing and behaving as if the cache were always valid.

**`Builder` and `DependencyProvider`:** both areas of the code that already branched
on a mode (local-vs-daemon building; regex-vs-depfile dependency detection) are behind
a small interface each - `Builder` (`builder.go`) and `DependencyProvider`
(`deps_provider.go`) - rather than each call site (main()'s dispatch, the watch loop,
`dependencyAwareHashComponent`) doing its own `if`/`switch` on the mode. The mode
decision now happens exactly once per concern - `newDependencyProvider(cfg)` for
dependencies, constructing a `localBuilder`/`daemonBuilder` in main() for building -
and everything downstream just calls the interface method without needing to know
which concrete implementation it's holding. Introduced on a reviewer's suggestion
(see `feedback.txt`-style review comments); both interfaces currently have exactly
the two implementations this project already needed (not speculative extras), and
the refactor was verified against a fake `arduino-cli` end-to-end for every existing
path: local build (compile then cache-hit), `-daemon`/`-connect` (same), regex-mode
dependency hashing (including a library header edit actually invalidating the cache),
and `-deps-mode=depfile`'s bootstrap-then-real-list handoff.

**Why the board wizard parses plain text, not `--json`, for `core list`/`board
listall`:** this project has been burned once already by assuming an unverified
`arduino-cli` JSON schema (the FQBN `nodemcu=80` → `nodemcu:xtal=80` incident). The
text-table formats for those two commands were directly observed against real
`arduino-cli` output earlier in this project, making them more trustworthy parsing
targets than a JSON structure that was never independently confirmed. `board details`
does use `--json` (its structure isn't practical to parse from text), but degrades to
"base FQBN, no menu options" rather than failing outright if the response doesn't
match the expected shape.

---

## Known limitations

- **Regex include scanning** doesn't evaluate `#ifdef`/`#ifndef` - see **Dependency
  file mode** above, which fixes this.
- **Platform version resolution** compares installed versions numerically (e.g.
  `3.10.0` correctly sorts after `3.9.0`, unlike a plain lexical string sort) and picks
  the highest one automatically. To use a specific installed version instead - e.g. if
  you deliberately keep two versions side by side and want to pin one - set
  `platformVersion=` in the config or pass `-platform-version <ver>`; an unrecognized
  pinned version errors clearly rather than silently falling back to a different one.
- **Local files in sketch subfolders** aren't tracked by either dependency mode - see
  above. Keep local `.cpp`/`.h` files flat next to the `.ino`.
- **`-watch` polls** rather than subscribing to filesystem events, a deliberate
  tradeoff to stay dependency-free (no `fsnotify`). A 1-second interval is
  imperceptible in practice thanks to the header-index/depfile cache.
- **Daemon mode has no auth/encryption** - a plain TCP JSON protocol intended for
  `127.0.0.1` only.
- **`isInteractiveStdin()` is platform-specific**, in `isinteractive_windows.go` /
  `isinteractive_other.go`. Windows uses `GetConsoleMode` (the correct native check -
  a real console succeeds, redirected/piped/absent stdin fails). An earlier version
  used a Unix-style `os.SameFile`-against-`/dev/null` check on all platforms; that
  approach was confirmed *broken* on real Windows (`Stat()` doesn't behave like a
  regular file on a console handle there), silently always reporting "not
  interactive" and defaulting every `-configure-board` prompt regardless of what was
  typed - caught and fixed via real user testing, not caught in this sandbox (which
  can only exercise the non-Windows fallback). The `os.SameFile` approach is kept as
  the non-Windows fallback (`isinteractive_other.go`) for building/testing outside
  Windows, since `GetConsoleMode` is Windows-only.
- **`-json` only wraps `fastbuild`'s own lines**, not `arduino-cli`'s compiler output -
  see **JSON output** above for why.

---

## Source layout

| File | Contents |
|---|---|
| `fastbuild.go` | Entry point, flag parsing, config loading, the core `run()` build/skip logic, upload |
| `deps.go` | Toolchain fingerprinting, the cached header index, stale-index handling, `promptYesNo`, `hashFileList` (shared hashing step for both dependency providers) |
| `deps_provider.go` | The `DependencyProvider` interface: `regexProvider` (`#include` scanning) and `depfileProvider` (compiler-generated `.d` files), selected once by `newDependencyProvider` based on `-deps-mode` |
| `isinteractive_windows.go` | `isInteractiveStdin` for Windows (`GetConsoleMode`) - the platform this project ships on |
| `isinteractive_other.go` | `isInteractiveStdin` fallback for building/testing outside Windows |
| `gcc_deps.go` | Dependency file mode: `.d` parsing, multi-translation-unit harvesting, `-gcc-inject-mmd` |
| `board_wizard.go` | `-configure-board`: the interactive FQBN-building wizard, clipboard copy, resumable prefetch |
| `board_wizard_cache.go` | The wizard's on-disk cache (platforms/boards/board-options), its disk-signature-based invalidation, and atomic (temp-file-plus-rename) saves |
| `logging.go` | `-json` and `-save-log`: the structured logger both features share |
| `export.go` | `-export`: copying the binary to the sketch folder with conflict resolution |
| `builder.go` | The `Builder` interface: `localBuilder` (compiles in-process) and `daemonBuilder` (forwards to a running `-daemon`), both driven identically by `runWatch` and main()'s dispatch |
| `daemon.go` | `-daemon`/`-connect`: the persistent build server and its client |
| `watch.go` | `-watch`: the build-on-save polling loop |
| `deps_test.go` | Version comparison/pinning (`resolvePlatformDir`), `hashFileList` |
| `gcc_deps_test.go` | `.d` file parsing (line continuations, escaped spaces, Windows paths), multi-translation-unit harvesting |
| `board_wizard_cache_test.go` | Signature computation and cache load/save round-tripping |
| `fastbuild_config_test.go` | Config parsing: required fields, the `gcc`→`depfile` alias, `exportConflict` strictness |

---

## Testing

```
go build ./...
go vet ./...
gofmt -l .        # should print nothing
go test ./...
```

No external modules to fetch - `go build` works offline from a clean clone. Most of
the existing test coverage exists because a real bug was found by hand first (a fake
`arduino-cli` script, a synthetic `.d` file, a pty-driven interactive session) and then
turned into something that runs in CI instead of only in a terminal once - see
`CONTRIBUTING.md` for more on that philosophy and what's expected in a PR.

---

## License

MIT - see `LICENSE`.

## Contributing

See `CONTRIBUTING.md`.
