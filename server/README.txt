AI CAMERA BACKEND v1.8 - HOSTING GUIDE

WHAT THIS SERVER DOES
- Provides the image-enhancement API used by the Android camera app.
- Creates an anonymous per-installation device profile. The app can open straight to
  the camera; a website sign-in or four-digit website code is NOT required to launch it.
- Provides /health for the app's Online / Offline indicator in Settings.
- Keeps enhancement jobs, account/device records, and prompt configuration in SQLite.
- The website UI is available at / if you also want it; it is optional for camera launch.

RECOMMENDED HOSTING (Docker)
1. Upload this entire backend folder to your Docker-capable host.
2. Build the Dockerfile in this folder.
3. Set these environment variables:
   DATA_DIR=/data
   ENGINE=openai
   OPENAI_API_KEY=<your OpenAI API key>
   PORT=8000 (or let the host provide PORT)
4. Attach persistent storage at /data. This is important: without a persistent volume,
   SQLite user/device data and job files may be lost when the container is replaced.
5. Deploy and open https://YOUR-HOST/health. A healthy server returns JSON with
   status "online", version and engine.
6. In the Android app, open Settings and tap Refresh beside Server status.

LOCAL RUN (Python)
   cd server
   pip install -r requirements.txt
   set DATA_DIR=./data
   set ENGINE=openai
   set OPENAI_API_KEY=<your OpenAI API key>
   python -m uvicorn server:app --host 0.0.0.0 --port 8000

ENGINE OPTIONS
- openai: real image editing; requires OPENAI_API_KEY and API access.
- echo: test mode only; sends the source image back unchanged and is NOT real enhancement.
- chatgpt: runs the Playwright browser flow on a computer you control. It is not
  recommended for container/cloud hosting.

DOCKER COMPOSE
From this folder, copy .env.example to .env, fill in OPENAI_API_KEY, then run:
   docker compose up -d --build
Compose stores the SQLite database and job files in the named volume mounted at /data.

ANDROID APP BEHAVIOUR
- The app opens directly to the camera. Website login/pairing is not needed just to open it.
- When a server can be discovered, the app automatically creates/restores its guest
  device profile, fetches enhancement presets, and shows Online · v1.8 in Settings.
- If the server is offline, local camera capture and local gallery remain available;
  enhancement will require the server to return online.
- The website UI remains available as an optional separate interface.

BACKUP
Back up the persistent /data volume regularly. It contains data.db and jobs/.
Do not publish your .env file or share your API key.
