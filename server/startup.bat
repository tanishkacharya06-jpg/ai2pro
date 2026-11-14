@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo.
echo ============================================================
echo                AI CAMERA BACKEND v2.0
echo                 Automatic Windows Setup
echo ============================================================
echo.

REM Locate Python 3.10+.
where py >nul 2>&1
if not errorlevel 1 (
    set "PYTHON=py -3"
) else (
    where python >nul 2>&1
    if errorlevel 1 goto :no_python
    set "PYTHON=python"
)

echo [1/5] Checking Python...
%PYTHON% --version
if errorlevel 1 goto :python_error

if not exist ".venv\Scripts\python.exe" (
    echo [2/5] Creating virtual environment...
    %PYTHON% -m venv .venv
    if errorlevel 1 goto :venv_error
) else (
    echo [2/5] Reusing existing virtual environment...
)
set "VENV_PY=%CD%\.venv\Scripts\python.exe"

echo [3/5] Installing Python dependencies...
"%VENV_PY%" -m pip install --upgrade pip
if errorlevel 1 goto :pip_error
"%VENV_PY%" -m pip install -r requirements.txt
if errorlevel 1 goto :pip_error

echo [4/5] Installing Playwright Chromium...
"%VENV_PY%" -m playwright install chromium
if errorlevel 1 goto :playwright_error

if not exist "cloudflared.exe" (
    echo Downloading Cloudflare Tunnel...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe' -OutFile (Join-Path (Get-Location) 'cloudflared.exe')"
    if errorlevel 1 goto :cloudflared_error
)
if not exist "cloudflared.exe" goto :cloudflared_error
"cloudflared.exe" --version
if errorlevel 1 goto :cloudflared_error

REM Use the installed Google Chrome if present; otherwise use Playwright Chromium.
set "BROWSER_CHANNEL="
if exist "%ProgramFiles%\Google\Chrome\Application\chrome.exe" set "BROWSER_CHANNEL=chrome"
if exist "%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe" set "BROWSER_CHANNEL=chrome"
if exist "%LocalAppData%\Google\Chrome\Application\chrome.exe" set "BROWSER_CHANNEL=chrome"
if defined BROWSER_CHANNEL (
    echo Google Chrome detected. Using Google Chrome for ChatGPT.
) else (
    echo Google Chrome not found. Using Playwright Chromium.
)

echo.
echo [5/5] Starting backend and automatic Cloudflare URL publishing...
echo Sign in to ChatGPT in the browser window that opens.
echo Keep this terminal open while AI Camera is being used.
echo.

set "ENGINE=chatgpt"
set "HEADLESS=0"
set "TUNNEL=1"
set "PORT=8000"
set "PAIR_CODE=aicam-dc66661a0788dd75"

REM Keep the default DATA_DIR beside server.py so data.db and cloud_gallery survive.
"%VENV_PY%" -m uvicorn server:app --host 0.0.0.0 --port %PORT%

echo.
echo AI Camera backend has stopped.
pause
exit /b 0

:no_python
echo ERROR: Python 3 was not found. Install Python 3.10+ and enable Add Python to PATH.
goto :failed
:python_error
echo ERROR: Python could not be started.
goto :failed
:venv_error
echo ERROR: Could not create .venv.
goto :failed
:pip_error
echo ERROR: Python dependency installation failed. Check internet access.
goto :failed
:playwright_error
echo ERROR: Playwright Chromium installation failed.
goto :failed
:cloudflared_error
echo ERROR: cloudflared.exe could not be downloaded or launched.
echo Download the official Windows AMD64 binary and save it beside server.py as cloudflared.exe.
goto :failed
:failed
echo.
echo Setup did not complete. Fix the error and run startup.bat again.
pause
exit /b 1
