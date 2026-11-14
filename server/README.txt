AI CAMERA BACKEND v2.0 — WINDOWS HOSTING

IMPORTANT
- The intended image enhancement engine is the existing ChatGPT website/Playwright integration.
- No OpenAI API key is required for ENGINE=chatgpt.
- The server uses the existing Cloudflare tunnel + ntfy discovery topic expected by the Android APK:
  aicam-dc66661a0788dd75
- Do not change server/tunnel.py, PAIR_CODE, or DEFAULT_LINK unless you also intentionally change the APK.

WINDOWS QUICK START
1. Install Python 3.10+ and make sure "Add Python to PATH" is enabled.
2. Extract this server folder to a permanent location.
3. Double-click startup.bat.
4. On first run it creates .venv, installs requirements, installs Playwright Chromium,
   and downloads cloudflared.exe if it is missing.
5. A browser opens on the host computer. Sign in to ChatGPT in that browser.
6. Leave the backend window running. The Android app opens straight to its camera and
   automatically discovers the public server URL via the existing ntfy topic.

ACCOUNT SYSTEM
- First successful app connection creates a USER<N> account and random 4-digit account code.
- Settings > My Account > Show Code displays that account's code.
- Login Another Account asks the user to confirm that the current code was saved, logs out,
  creates a fresh new USER<N> account/code, then opens a field to enter another account's code.
- The code-only login is protected by server-side rate limiting. Treat the code as a secret.
- Admin tools unlock using code 0007 by default (override with ADMIN_CODE environment variable).
- Existing website username/password login remains available and unchanged.

CLOUD GALLERY
- Photos are stored by account under DATA_DIR/cloud_gallery and indexed in data.db.
- The app syncs photos in the background when a network is available. Pending uploads/deletes
  are retried after connectivity returns.
- Keep DATA_DIR persistent. Never delete data.db or cloud_gallery when updating the server.
- Keep chatgpt_profile, because it stores the browser's ChatGPT login session.
- Back up DATA_DIR and chatgpt_profile regularly.

MANUAL WINDOWS START (if you do not use startup.bat)
Open Command Prompt in this folder and run:
  py -m venv .venv
  .venv\Scripts\activate
  py -m pip install -r requirements.txt
  py -m playwright install chromium
  set ENGINE=chatgpt
  set HEADLESS=0
  set TUNNEL=1
  set PORT=8000
  set PAIR_CODE=aicam-dc66661a0788dd75
  py -m uvicorn server:app --host 0.0.0.0 --port 8000

LINUX HOST
Install Python 3.10+, the requirements, Playwright Chromium, and the matching cloudflared
binary. Then run:
  export ENGINE=chatgpt HEADLESS=0 TUNNEL=1 PORT=8000
  export PAIR_CODE=aicam-dc66661a0788dd75
  python3 -m uvicorn server:app --host 0.0.0.0 --port 8000

CHECK SERVER
The local health endpoint is http://127.0.0.1:8000/health.
The public Cloudflare tunnel URL is published automatically to the unchanged ntfy topic.
Settings in the app shows the server's Online/Offline state.

ENGINE OPTIONS
- chatgpt: uses the signed-in ChatGPT website through the existing Playwright browser session.
- echo: connectivity/test mode only; returns the original photo unchanged.
- openai: optional separate integration requiring an API key. It is not the default.

SECURITY
- Do not share chatgpt_profile or your account code.
- The 4-digit code is short and has limited entropy; keep the server's login rate limit enabled.
- Protect your Windows host and do not expose the local port directly to the internet.
