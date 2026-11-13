AI Camera server v1.9

HOSTING
- Deploy the contents of this folder (including Dockerfile and requirements.txt) to Render, Railway, Fly.io, or a VPS.
- The app discovers the current Cloudflare quick-tunnel URL using the configured ntfy topic. For a fixed host, enter its HTTPS URL in the Android app's Server URL setting when that setting is available.
- The public GET /health endpoint returns online status and server version for the Android Settings screen.

LOCAL
  pip install -r requirements.txt
  python -m uvicorn server:app --host 0.0.0.0 --port 8000

IMAGE ENGINE
- ENGINE=echo is a connectivity/demo engine and returns an unchanged image.
- ENGINE=openai requires OPENAI_API_KEY and uses the OpenAI image editing API.
- ENGINE=chatgpt uses a locally authenticated Playwright browser. Run login.py once before starting the server; keep the browser window open. This mode is for your own computer, not ordinary cloud hosting.

ANDROID APP
- The Android app opens directly to the camera. No website login or pairing code is required.
- When this server is reachable, the app creates/restores a per-installation guest device profile automatically.
- If the server is offline, the camera and local gallery remain usable. Settings displays Online/Offline and has a Refresh button.
- Website account login and the website UI are retained as separate features; they are not part of Android camera startup.
- SQLite data.db is created automatically. Back it up before replacing server files on an existing installation.
