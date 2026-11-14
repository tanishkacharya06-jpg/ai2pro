@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo.
echo ============================================================
echo                  AI CAMERA BACKEND v2.0
echo                 Automatic Windows Startup
echo ============================================================
echo.

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
if errorlevel 1 goto :failed

if not exist ".venv\Scripts\python.exe" (
    echo [2/5] Creating virtual environment...
    %PYTHON% -m venv .venv
    if errorlevel 1 goto :failed
) else (
    echo [2/5] Reusing virtual environment...
)
set "VENV_PY=%CD%\.venv\Scripts\python.exe"

echo [3/5] Installing Python packages...
"%VENV_PY%" -m pip install --upgrade pip
if errorlevel 1 goto :failed
"%VENV_PY%" -m pip install -r requirements.txt
if errorlevel 1 goto :failed

echo [4/5] Installing Playwright Chromium...
"%VENV_PY%" -m playwright install chromium
if errorlevel 1 goto :failed

if not exist "cloudflared.exe" (
    echo Downloading Cloudflare Tunnel...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe' -OutFile (Join-Path (Get-Location) 'cloudflared.exe')"
    if errorlevel 1 goto :failed
)
if not exist "cloudflared.exe" goto :failed
"cloudflared.exe" --version
if errorlevel 1 goto :failed

echo [5/5] Starting AI Camera...
echo.
echo ChatGPT website login opens in the host browser on first use.
echo Keep this window open. The Android app finds this server automatically.
echo.

set "ENGINE=chatgpt"
set "HEADLESS=0"
set "TUNNEL=1"
set "PORT=8000"
set "PAIR_CODE=aicam-dc66661a0788dd75"
set "ADMIN_CODE=0007"

REM Keep the default database/gallery location beside this server folder.
REM Do not delete data.db, cloud_gallery, or chatgpt_profile when upgrading.

"%VENV_PY%" -m uvicorn server:app --host 0.0.0.0 --port %PORT%
echo.
echo AI Camera backend stopped.
pause
exit /b 0

:no_python
echo Python 3.10 or newer is required. Install Python and enable Add Python to PATH.
goto :failed

:failed
echo.
echo Setup or server launch failed. Read the error above, fix it, and run startup.bat again.
pause
exit /b 1
