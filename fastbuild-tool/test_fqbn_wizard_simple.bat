@echo off
setlocal EnableDelayedExpansion
REM ============================================================================
REM  test_fqbn_wizard.bat
REM  Runs fastbuild's -configure-board wizard to interactively build an FQBN.
REM  No fastbuild.config is needed for this mode - only arduino-cli's own
REM  paths are required.
REM
REM  Edit the two arduino-cli paths below, then double-click this file (or run
REM  it from a terminal) to test the wizard.
REM
REM  NOTE: this uses "goto" instead of "if (...) ( ... )" blocks on purpose.
REM  Paths like "Program Files (x86)" contain a stray ")" that breaks cmd's
REM  block-parenthesis parsing if used inside an if-block - that's what was
REM  causing the window to flash and close instantly.
REM ============================================================================

REM --- EDIT THESE ---------------------------------------------------------
REM %~dp0 = the folder this .bat file lives in (trailing backslash included) -
REM this assumes fastbuild.exe sits right next to this .bat file.
set "FASTBUILD_EXE=%~dp0fastbuild.exe"
set "ARDUINO_CLI_EXE=E:\Program Files (x86)\arduinoCLI\arduino-cli.exe"
set "ARDUINO_CLI_YAML=E:\Program Files (x86)\arduinoCLI\arduino-cli.yaml"
REM -------------------------------------------------------------------------

if not exist "%FASTBUILD_EXE%" goto :no_fastbuild
if not exist "%ARDUINO_CLI_EXE%" goto :no_arduino_cli
if not exist "%ARDUINO_CLI_YAML%" goto :no_yaml

"%FASTBUILD_EXE%" -configure-board -arduino-cli "%ARDUINO_CLI_EXE%" -arduino-cli-yaml "%ARDUINO_CLI_YAML%"
goto :done

:no_fastbuild
echo [ERROR] fastbuild.exe not found at: %FASTBUILD_EXE%
echo         Edit FASTBUILD_EXE at the top of this .bat file.
goto :fail

:no_arduino_cli
echo [ERROR] arduino-cli.exe not found at: %ARDUINO_CLI_EXE%
echo         Edit ARDUINO_CLI_EXE at the top of this .bat file.
goto :fail

:no_yaml
echo [WARN] arduino-cli.yaml not found at: %ARDUINO_CLI_YAML%
echo        Continuing without -arduino-cli-yaml - the wizard will fall
echo        back to arduino-cli's own default config resolution.
echo.
"%FASTBUILD_EXE%" -configure-board -arduino-cli "%ARDUINO_CLI_EXE%"
goto :done

:done
echo.
echo ----------------------------------------------------------------------
echo Wizard exited with code %ERRORLEVEL%
echo   0 = wizard completed, FQBN printed above (copy it into your config's
echo       fqbn= line)
echo   1 = missing -arduino-cli path, or a step failed (no platforms
echo       installed, arduino-cli call failed, etc - see the message above)
echo ----------------------------------------------------------------------
pause
exit /b 0

:fail
echo.
pause
exit /b 1
