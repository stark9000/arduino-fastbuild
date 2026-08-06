@echo off
REM add-icon.bat - embeds fastbuild.ico into fastbuild.exe.
REM
REM Go doesn't support embedding exe icons/resources natively, so this
REM compiles the icon into a Windows resource object (.syso) via windres -
REM go build then links that in AUTOMATICALLY, with no extra flags, since
REM Go picks up any *.syso file sitting in the same folder as the .go
REM source on its own. Run this once (or whenever the icon changes), then
REM run build.bat as normal - no changes needed to build.bat itself.
REM
REM Requires windres, which comes with MinGW-w64 (https://www.msys2.org/) -
REM if you don't already have a C toolchain installed for Go on Windows,
REM install that and make sure its bin folder (containing windres.exe) is
REM on PATH.

where windres >nul 2>nul
if errorlevel 1 (
    echo windres not found on PATH.
    echo Install MinGW-w64 ^(e.g. via https://www.msys2.org/^) and make sure
    echo its bin folder ^(containing windres.exe^) is on PATH, then re-run.
    pause
    exit /b 1
)

if not exist fastbuild.ico (
    echo fastbuild.ico not found in this folder.
    echo Place your icon file here first, named fastbuild.ico.
    pause
    exit /b 1
)

echo Generating resource script...
(
echo 1 ICON "fastbuild.ico"
) > fastbuild_icon.rc

echo Compiling resource with windres...
windres -O coff -o rsrc_windows_amd64.syso fastbuild_icon.rc

if errorlevel 1 (
    echo.
    echo windres failed - see errors above.
    del fastbuild_icon.rc >nul 2>nul
    pause
    exit /b 1
)

del fastbuild_icon.rc >nul 2>nul

echo.
echo Done - rsrc_windows_amd64.syso created in this folder.
echo Now run build.bat ^(or `go build`^) - the icon will be embedded
echo automatically, no extra flags needed.
pause
