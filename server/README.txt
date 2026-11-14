AI CAMERA BACKEND v2.0 — WINDOWS HOSTING

AI ENHANCEMENT ENGINE
This project uses the existing ChatGPT WEBSITE + Playwright browser integration by default.
It does not require an OpenAI API key in ENGINE=chatgpt mode. The host computer opens a
browser profile at server/chatgpt_profile; sign in to your ChatGPT account in that browser.
Keep that profile folder private and persistent. ChatGPT website UI changes may sometimes
require adjustments to the Playwright integration.

WINDOWS QUICK START
1. Install Python 3.10 or newer and enable "Add Python to PATH".
2. Extract the backend folder somewhere permanent.
3. Double-click startup.bat inside server/.
4. On first run, it creates .venv, installs requirements, installs Playwright Chromium,
   and downloads cloudflared.exe if it is not already in the folder.
5. If Google Chrome is installed, the launcher sets BROWSER_CHANNEL=chrome; otherwise
   Playwright uses its managed Chromium.
6. Sign in to ChatGPT in the browser that opens on the host PC.
7. Keep the terminal window and server computer running while using the Android app.

AUTOMATIC PHONE SERVER DISCOVERY
The app's built-in discovery topic is aicam-dc66661a0788dd75. startup.bat sets the same
PAIR_CODE and TUNNEL=1. tunnel.py starts Cloudflare Quick Tunnel and publishes its current
public URL to that ntfy topic. Do not change the topic unless you also change the Android APK.
The phone app remains camera-first and discovers the host URL automatically.

ACCOUNT CODES AND PHOTOS
- First app connection creates a USER<N> account and a permanent 4-digit account code.
- Settings > My Account > Show Code displays that code.
- Login Another Account requires confirmation that the old code was saved, then accepts
  only the other account's 4-digit code.
- Code 0007 is reserved for the built-in admin account. It provides admin-level access;
  do not expose the server publicly with this default unchanged.
- Admins can add, edit and delete enhancement prompts in the app's Settings.
- Account photos are stored in the cloud_gallery directory and metadata in data.db.

PERSISTENT DATA — DO NOT DELETE
Keep the following alongside the backend:
- data.db (accounts, codes, prompt definitions, photo metadata)
- cloud_gallery/ (uploaded image files)
- chatgpt_profile/ (host's ChatGPT browser session)
- pair.txt (if present; automatic phone discovery topic)

The default DATA_DIR is the server folder. If you previously set DATA_DIR elsewhere, keep
using that same folder. Back up it regularly before replacing backend files. Do not overwrite
it with an empty folder during upgrades.

MANUAL WINDOWS START (if you do not use startup.bat)
Open Command Prompt in server/ and run:
  py -m venv .venv
  .venv\Scripts\activate
  pip install -r requirements.txt
  py -m playwright install chromium
  set ENGINE=chatgpt
  set HEADLESS=0
  set TUNNEL=1
  set PORT=8000
  set PAIR_CODE=aicam-dc66661a0788dd75
  py -m uvicorn server:app --host 0.0.0.0 --port 8000

LINUX / DEBIAN
  python3 -m venv .venv
  source .venv/bin/activate
  pip install -r requirements.txt
  python -m playwright install chromium
Download the appropriate cloudflared binary into this server folder, then run:
  export ENGINE=chatgpt HEADLESS=0 TUNNEL=1 PORT=8000
  export PAIR_CODE=aicam-dc66661a0788dd75
  python -m uvicorn server:app --host 0.0.0.0 --port 8000
A GUI desktop session is needed for the visible ChatGPT browser sign-in.

HEALTH CHECK
After startup, the local health endpoint is:
  http://127.0.0.1:8000/health
The Android Settings page also shows Online / Offline status.

NO OPENAI API KEY
Do not add OPENAI_API_KEY for the website-based ChatGPT engine. The optional ENGINE=openai
mode is separate and is not used by the Android v2.0 default setup.
