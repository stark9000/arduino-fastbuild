@echo off
REM run-fastbuild.bat - runs the fastbuild.exe Go CLI against the given config.
REM Usage: run-fastbuild.bat <path-to-config>
REM If no argument is given, defaults to fastbuild.config in this folder.

setlocal
set CONFIG=%~1
if "%CONFIG%"=="" set CONFIG=fastbuild.config

if not exist "%~dp0fastbuild.exe" (
    echo fastbuild.exe not found next to this script.
    pause
    exit /b 1
)

echo Running fastbuild with config: %CONFIG%
echo.
"%~dp0fastbuild.exe" "%CONFIG%"

echo.
echo ---------------------------------------
echo fastbuild finished with exit code %errorlevel%
pause
