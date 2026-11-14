@echo off
setlocal EnableExtensions
title AI Camera Backend - v2.1
cd /d "%~dp0"

echo ==========================================
echo          AI CAMERA BACKEND v2.1
echo ==========================================
echo.

where py >nul 2>nul
if errorlevel 1 (
  echo Python Launcher was not found.
  echo Install Python 3.11 or newer from https://www.python.org/downloads/windows/
  echo During setup, enable "Add Python to PATH".
  pause
  exit /b 1
)

py -3 --version
if errorlevel 1 (
  echo Python 3 was not found. Install Python 3.11 or newer.
  pause
  exit /b 1
)

if not exist ".venv\Scripts\python.exe" (
  echo [1/5] Creating Python environment...
  py -3 -m venv .venv
  if errorlevel 1 goto :failed
)

call ".venv\Scripts\activate.bat"
echo [2/5] Installing backend packages...
python -m pip install --upgrade pip
if errorlevel 1 goto :failed
python -m pip install -r requirements.txt
if errorlevel 1 goto :failed

echo [3/5] Installing Playwright Chromium...
python -m playwright install chromium
if errorlevel 1 goto :failed

echo [4/5] Preparing Cloudflare Tunnel...
if not exist "cloudflared.exe" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe' -OutFile 'cloudflared.exe'"
  if errorlevel 1 goto :failed
)
if not exist "cloudflared.exe" (
  echo Could not download cloudflared.exe. Check your internet connection.
  goto :failed
)

if not exist "data" mkdir data

echo [5/5] Starting AI Camera...
echo.
echo Keep this window open while the server is running.
echo The first ChatGPT engine startup opens a browser window for login.
echo Your browser profile and account session stay in this backend folder.
echo.
set "ENGINE=chatgpt"
set "HEADLESS=0"
set "TUNNEL=1"
set "PORT=8000"
set "PAIR_CODE=aicam-dc66661a0788dd75"
set "DATA_DIR=%~dp0data"

python -m uvicorn server:app --host 0.0.0.0 --port 8000
echo.
echo AI Camera backend stopped.
pause
exit /b 0

:failed
echo.
echo Startup failed. Read the error above, check internet/Python, then run startup.bat again.
pause
exit /b 1
