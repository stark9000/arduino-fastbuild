# Getting Started - Installing Everything fastbuilduix Needs

This app doesn't compile Arduino sketches itself - it wraps `arduino-cli`
and `fastbuild`, which both need to be installed and set up first. This
guide walks through that setup, start to finish, before you ever open
fastbuilduix itself. If you've already got `arduino-cli` working (e.g. you
already use it from the command line, or through another tool), you can
likely skip straight to Step 4 (installing board cores) or Step 6
(pointing fastbuilduix at what you've got).

## Step 1: Install the Arduino IDE

Even though fastbuilduix doesn't use the IDE directly, install it first
anyway - it's the easiest way to get your board's USB drivers installed
(the genuine Arduino boards' own driver, or CH340/CP2102 for most clone
boards), and it gives you a simple way to confirm a board actually shows
up as a working COM port before troubleshooting anything else.

1. Download and install from [arduino.cc](https://www.arduino.cc/en/software).
2. Plug in your board.
3. Check Windows Device Manager under "Ports (COM & LPT)" - you should see
   a new COM port appear (e.g. `COM5`). If nothing shows up, the driver
   didn't install correctly - reinstalling the IDE, or installing the
   specific USB-to-serial chip's driver manually (CH340 is the most common
   one on cheap clone boards), usually fixes it.

## Step 2: Install arduino-cli

`arduino-cli` is **not** bundled with the IDE - it's a separate download,
a single standalone `.exe` with no installer.

1. Download it from
   [arduino.github.io/arduino-cli](https://arduino.github.io/arduino-cli/latest/installation/)
   (grab the Windows release).
2. Extract `arduino-cli.exe` somewhere permanent - not your Downloads
   folder, since that gets cleaned out eventually. Something like
   `C:\arduino-cli\` or alongside your other dev tools works fine.
3. Remember this path - you'll point fastbuilduix at it in Step 6.

## Step 3: Create the config file

Open a Command Prompt in the folder where you put `arduino-cli.exe` (or
add it to your PATH first) and run:

```
arduino-cli config init
```

This creates `arduino-cli.yaml`, usually at
`%USERPROFILE%\.arduino15\arduino-cli.yaml` - but the exact default
location has shifted slightly across CLI versions, so rather than assume,
confirm exactly where yours landed:

```
arduino-cli config dump
```

The output shows the config file's own contents, and running `config dump`
from the same folder will tell you which file it actually read. Note this
path down too - you'll need it in Step 6.

## Step 4: Install a board core (AVR - Uno, Nano, Mega, etc.)

A "core" is the actual compiler/toolchain support for a board family.
Nothing compiles until at least one is installed.

```
arduino-cli core update-index
arduino-cli core install arduino:avr
```

`arduino:avr` covers the classic boards - Uno, Nano, Mega, and similar.

## Step 5: Third-party platforms (ESP8266, ESP32)

Boards not made by Arduino itself (ESP8266, ESP32, and others) live in
separate package indexes that have to be added before `core install` can
find them.

**ESP8266:**
```
arduino-cli config add board_manager.additional_urls https://arduino.esp8266.com/stable/package_esp8266com_index.json
arduino-cli core update-index
arduino-cli core install esp8266:esp8266
```

**ESP32:**
```
arduino-cli config add board_manager.additional_urls https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json
arduino-cli core update-index
arduino-cli core install esp32:esp32
```

Both URLs can be added at the same time (each `config add` call appends
to the list rather than replacing it) - run `arduino-cli config dump`
afterward to confirm both are listed under `board_manager.additional_urls`.

## Step 6: Point fastbuilduix at all of this

Open fastbuilduix - the **App Settings** tab opens automatically on first
run. Fill in:

- **Arduino CLI executable** - the `arduino-cli.exe` path from Step 2.
- **arduino-cli.yaml** - the path confirmed in Step 3.
- **fastbuild executable** - wherever you've put `fastbuild.exe`.

From here, everything else is covered in `HOW_TO_GUIDE.md` - picking a
sketch, picking a board, and building.

## If something doesn't work

- **Board doesn't show up in the Board Wizard** - re-check Step 4/5; the
  Board Wizard only lists platforms that are actually installed via
  `arduino-cli core install`, not just ones with drivers installed.
- **"Sketch too big" or flash-related errors** - usually the wrong board
  selected (e.g. picking a board with a smaller flash size than your
  actual hardware) rather than a setup problem - double-check the exact
  board/variant in the Board Wizard.
- **Board Wizard shows an empty or stale list** - use "Force Refresh
  Wizard Cache & Reload" (see `HOW_TO_GUIDE.md`) after installing a new
  platform, since the wizard caches what it's seen before.
