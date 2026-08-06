@echo off
REM check.bat - formats, vets, and builds fastbuild.exe. Run this after editing main.go
REM before trusting a build, to catch formatting issues and common bugs early.

where go >nul 2>nul
if errorlevel 1 (
    echo Go is not installed or not on PATH.
    echo Download it from https://go.dev/dl/ and install, then re-run this script.
    pause
    exit /b 1
)

echo Formatting...
gofmt -l .
if errorlevel 1 (
    echo gofmt reported an error - see above.
    pause
    exit /b 1
)

echo.
echo Vetting...
go vet ./...
if errorlevel 1 (
    echo go vet found issues - see above.
    pause
    exit /b 1
)

echo.
echo Building...
go build -o fastbuild.exe .
if errorlevel 1 (
    echo Build failed - see errors above.
    pause
    exit /b 1
)

echo.
echo All checks passed. fastbuild.exe built successfully.
pause
