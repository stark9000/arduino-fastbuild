@echo off
REM build.bat - compiles fastbuild.exe from main.go
REM Run this from the same folder as main.go and go.mod.

where go >nul 2>nul
if errorlevel 1 (
    echo Go is not installed or not on PATH.
    echo Download it from https://go.dev/dl/ and install, then re-run this script.
    pause
    exit /b 1
)

echo Using Go:
go version
echo.

echo Building fastbuild.exe...
go build -o fastbuild.exe .

if errorlevel 1 (
    echo.
    echo Build failed - see errors above.
    pause
    exit /b 1
)

echo.
echo Build succeeded: fastbuild.exe
pause
