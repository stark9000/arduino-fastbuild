# Changelog

Everything below is since the first public release, organized by area.
Most changes are in `fastbuilduix` (the UI); a couple touch `fastbuild-tool`
(the Go CLI) too - marked where relevant.

## Cache root - now a single, app-wide setting

Previously "Cache root" (Project tab) and "Wizard cache dir" (Board Wizard
tab) were two independently editable fields that could silently drift out
of sync - the exact cause of an earlier confusing bug where setting a
custom cache path only ever seemed to take effect in one place.

- **App Settings > Default cache root** is now the only place this is
  ever set. Prefills to `<home>\.arduino-fastbuild` (fastbuild's own
  default) on first run.
- **Project tab's Cache root** and **Board Wizard's Wizard cache dir**
  are now read-only, shown in each tab's "From App Settings" summary
  box, always mirroring the one App Settings value.
- Removed the now-pointless "Apply Default Cache Root to Current Sketch
  Now" button and the per-sketch cache-root override that used to cause
  the drift in the first place.

## Board Wizard prefetch - new controls, and several real bugs fixed

**New:**
- **Cancel Prefetch** button, shown only while a prefetch is running.
- **"Carry over previously cached board options on refresh"** checkbox
  (checked by default) - a Force Refresh no longer has to mean losing
  every previously prefetched board's options. Boards that still exist
  after the refresh keep their cached options; only new/changed boards
  actually get re-fetched.
- Layout reorganized into two rows: carry-over checkbox + Force Refresh
  button on one row, Load Platforms + Load Saved Selection + status on
  the next.
- A dedicated prefetch status label, separate from the general Board
  Wizard status label, so the two no longer overwrite each other.

**Fixed (all were real bugs, not stale-build issues):**
- Every control on the Board Wizard tab (platform/board selection, the
  filter field, Apply to Project, board option combos) is now correctly
  locked during a prefetch - previously only `boardCombo` was disabled,
  which didn't actually prevent the underlying race condition, since
  `populateBoardCombo()` calls `onBoardSelected()` unconditionally
  regardless of the combo's enabled state.
- A Windows input-method exception (`IllegalComponentStateException`)
  that could fire when disabling a text field that currently had
  keyboard focus - fixed by releasing focus first.
- **Cancel getting permanently stuck** - two separate causes, both fixed:
  1. `ArduinoCliRunner` blocks on a plain stream read *before* it ever
     reaches the interruptible `Process.waitFor()` - a plain
     `Thread.interrupt()` (what `ExecutorService.shutdownNow()` relies
     on) does nothing to unblock that. Fixed by tracking each in-flight
     subprocess and forcibly killing it directly on cancel.
  2. With hundreds of boards queued behind only a handful of workers,
     most tasks were still queued (never started) at the moment of
     cancel - fixed by explicitly cancelling every task's `Future`, not
     just relying on the thread pool's own shutdown behavior.
  3. A brief window where a worker's last progress update could still
     overwrite the final "cancelled" message - fixed by scheduling the
     final message to always run last.
- Force Refresh Wizard Cache's confirmation dialog now accurately
  reflects what will actually happen (whether prefetched data survives,
  based on the carry-over checkbox), rather than always claiming a full
  wipe. The `-refresh-wizard-cache` checkbox also now has a warning note
  explaining it silently forces every ordinary Load Platforms click to
  behave like a full refresh, for as long as it's left checked.

## Unapplied board changes are now caught before a build

Picking a different board/option in the Board Wizard only ever updates
a preview - it doesn't touch the Project tab's real settings until
"Apply to Project" is clicked. Forgetting that step and building anyway
used to silently build the *previous* board's firmware, with nothing
in the way to catch it.

- Clicking **Validate** or **Run Build** with a pending, unapplied
  Wizard selection now shows a confirmation first, in plain language
  ("Board changed from X to Y", or "Board options changed for X" when
  it's the same board with different settings) - not a raw FQBN dump.
- Three choices: apply the pending selection and continue, proceed with
  whatever's currently applied, or cancel.
- Reuses the same mismatch-detection the existing warning label on the
  Board Wizard tab already had - this just acts on it at the moment it
  actually matters, instead of relying on someone noticing the label.

## Editor and Explorer improvements

- **Reload from Disk** - right-click an open file tab to re-read its
  content from disk, discarding whatever's in the editor. Warns first
  if the tab has unsaved changes, so a reload can't silently throw
  away in-progress edits.
- **Refresh** - right-click the Explorer file tree to re-scan the
  sketch folder, picking up files added/removed/renamed on disk since
  it was last loaded (previously the tree only rebuilt when the sketch
  path itself changed).

## Activity Log and Build Log are now color-coded

Both logs were plain, single-color text before. Now colored by keyword,
consistently between the two:

- **Red** - errors, failures
- **Amber** - warnings
- **Green** - success, cache hits
- **Muted blue-gray** - general cache activity that isn't itself a
  cache-hit result

Fixed defaults.

Caught and fixed two false positives along the way from the first pass
at this: plain substring matching had `-Werror=return-type` (a normal
compiler flag present in every compile command) and the ESP8266 board
option `exception=disabled` (a config value, not a thrown exception)
both showing up red. Replaced with word-boundary-aware matching, and
dropped "exception" from the keyword list entirely - it's a legitimate,
common option name in this context, not a reliable error signal.

## New settings

- **Pin platform version** (Cache & Dependencies tab) - maps to
  fastbuild's `-platform-version` flag, added in a recent fastbuild-tool
  update. An editable-free dropdown, auto-detected from what's actually
  installed for the current board's platform, sorted so "highest
  installed" always matches what fastbuild itself would auto-select.
- **Export-conflict "ask" now actually asks.** Previously, selecting
  "ask" for what to do when an export destination already exists would
  silently behave like "rename" every time, since fastbuild can't show
  an interactive prompt when its config is piped over stdin (which this
  UI always does). Now replicated as a real in-app confirmation dialog,
  the same way the existing stale-header-index prompt already works.
- **"Always replace existing output file"** checkbox, next to the
  existing export-conflict dropdown - a one-click way to skip the
  ask/rename prompt entirely, unchecked by default so it changes
  nothing for anyone who doesn't touch it. Also fixed the
  export-conflict dropdown's own default, which was showing "ask" (its
  first list item) rather than the actual default of "rename" before
  any settings ever loaded, and fixed unchecking the new checkbox to
  properly revert to "rename" instead of getting stuck on "overwrite".

## Small quality-of-life additions

- **Hex Viewer's file picker** now defaults to the current sketch's
  folder when nothing's been picked yet this session.
- **"Open Sketch Folder" button** on the Build Log tab, visible only
  when "Export binary to sketch folder" is checked - opens that folder
  directly in the OS file explorer.
- Fixed a layout bug where a long file path in the Project tab's status
  label could visually overlap the Validate/Run Build buttons - now
  wraps onto multiple lines instead.
