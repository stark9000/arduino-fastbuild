
@echo off
setlocal EnableDelayedExpansion
title FastBuild - Board Configuration Wizard

REM ============================================================================
REM  test_fqbn_wizard.bat
REM  Runs fastbuild's -configure-board wizard to interactively build an FQBN.
REM  No fastbuild.config is needed for this mode - only arduino-cli's own
REM  paths are required.
REM
REM  Edit the two arduino-cli paths below, then double-click this file (or run
REM  it from a terminal) to test the wizard.
REM
REM  NOTE: this uses "goto" instead of "if (...) ( ... )" blocks throughout, on
REM  purpose. Paths like "Program Files (x86)" contain a stray ")" that breaks
REM  cmd's block-parenthesis parsing if a variable holding such a path gets
REM  expanded inside an if-block - that's what was causing the window to flash
REM  and close instantly before. Every "if" below is a single-line goto, never
REM  a ( ... ) block, so this is safe regardless of what's in the paths.
REM ============================================================================

REM --- EDIT THESE ---------------------------------------------------------
REM %~dp0 = the folder this .bat file lives in (trailing backslash included) -
REM this assumes fastbuild.exe sits right next to this .bat file.
set "FASTBUILD_EXE=%~dp0fastbuild.exe"
set "ARDUINO_CLI_EXE=E:\Program Files (x86)\arduinoCLI\arduino-cli.exe"
set "ARDUINO_CLI_YAML=E:\Program Files (x86)\arduinoCLI\arduino-cli.yaml"

REM How -configure-board handles prefetching every board's menu options:
REM   ask  = prompts once, right when a fresh fetch is needed (default)
REM   full = always prefetch everything, no prompt
REM   off  = never prefetch - only cache boards as you pick them
set "PREFETCH_MODE=ask"

REM How many arduino-cli processes the prefetch runs at once, if it runs at all.
REM Keep this low (4) if you've seen display/monitor issues during a prefetch -
REM raise it only if 4 is stable and you want it to finish faster.
set "PREFETCH_WORKERS=4"
REM -------------------------------------------------------------------------

cls
echo ============================================================================
echo   FastBuild - Interactive Board Configuration Wizard
echo ============================================================================
echo.
echo This will ask arduino-cli what platforms and boards you have installed,
echo then walk you through building a full FQBN string step by step.
echo.
echo Press Enter to start the pre-flight checks...
pause >nul

echo.
echo ----------------------------------------------------------------------
echo [1/3] fastbuild.exe
echo       %FASTBUILD_EXE%
if not exist "%FASTBUILD_EXE%" goto :no_fastbuild
echo       FOUND.
echo.
echo [2/3] arduino-cli.exe
echo       %ARDUINO_CLI_EXE%
if not exist "%ARDUINO_CLI_EXE%" goto :no_arduino_cli
echo       FOUND.
echo.
echo [3/3] arduino-cli.yaml
echo       %ARDUINO_CLI_YAML%
if not exist "%ARDUINO_CLI_YAML%" goto :no_yaml
echo       FOUND.
echo ----------------------------------------------------------------------
echo.
echo All three checks passed. About to run:
echo.
echo   "%FASTBUILD_EXE%"
echo       -configure-board
echo       -arduino-cli "%ARDUINO_CLI_EXE%"
echo       -arduino-cli-yaml "%ARDUINO_CLI_YAML%"
echo       -wizard-prefetch %PREFETCH_MODE%
echo       -wizard-prefetch-workers %PREFETCH_WORKERS%
echo.
echo Press Enter to launch the wizard (or close this window to cancel)...
pause >nul
goto :run_with_yaml

:run_with_yaml
echo.
echo ----------------------------------------------------------------------
echo Launching wizard - answer the prompts below as they appear.
echo ----------------------------------------------------------------------
echo.
"%FASTBUILD_EXE%" -configure-board -arduino-cli "%ARDUINO_CLI_EXE%" -arduino-cli-yaml "%ARDUINO_CLI_YAML%" -wizard-prefetch %PREFETCH_MODE% -wizard-prefetch-workers %PREFETCH_WORKERS%
set "WIZARD_EXIT=%ERRORLEVEL%"
goto :done

:no_fastbuild
echo       NOT FOUND.
echo.
echo [ERROR] fastbuild.exe not found at: %FASTBUILD_EXE%
echo         Edit FASTBUILD_EXE at the top of this .bat file.
goto :fail

:no_arduino_cli
echo       NOT FOUND.
echo.
echo [ERROR] arduino-cli.exe not found at: %ARDUINO_CLI_EXE%
echo         Edit ARDUINO_CLI_EXE at the top of this .bat file.
goto :fail

:no_yaml
echo       NOT FOUND.
echo.
echo [WARN] arduino-cli.yaml not found at: %ARDUINO_CLI_YAML%
echo        Continuing without -arduino-cli-yaml - the wizard will fall
echo        back to arduino-cli's own default config resolution.
echo.
echo About to run:
echo.
echo   "%FASTBUILD_EXE%" -configure-board -arduino-cli "%ARDUINO_CLI_EXE%" -wizard-prefetch %PREFETCH_MODE% -wizard-prefetch-workers %PREFETCH_WORKERS%
echo.
echo Press Enter to continue anyway (or close this window to cancel)...
pause >nul
echo.
echo ----------------------------------------------------------------------
echo Launching wizard - answer the prompts below as they appear.
echo ----------------------------------------------------------------------
echo.
"%FASTBUILD_EXE%" -configure-board -arduino-cli "%ARDUINO_CLI_EXE%" -wizard-prefetch %PREFETCH_MODE% -wizard-prefetch-workers %PREFETCH_WORKERS%
set "WIZARD_EXIT=%ERRORLEVEL%"
goto :done

:done
echo.
echo ----------------------------------------------------------------------
if "%WIZARD_EXIT%"=="0" goto :report_success
goto :report_failure

:report_success
echo Wizard completed successfully (exit code 0).
echo Copy the FQBN printed above into your config file's fqbn= line.
goto :final_pause

:report_failure
echo Wizard exited with code %WIZARD_EXIT%.
echo   This usually means: no platforms installed, arduino-cli call failed,
echo   or a required path was wrong - see the message above for details.
goto :final_pause

:final_pause
echo ----------------------------------------------------------------------
echo.
echo Press Enter to close this window...
pause >nul
exit /b 0

:fail
echo.
echo Press Enter to close this window...
pause >nul
exit /b 1