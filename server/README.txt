Set env vars: APP_TOKEN (secret), ENGINE=openai (or echo for testing), OPENAI_API_KEY.
Local:   pip install -r requirements.txt && uvicorn server:app --host 0.0.0.0 --port 8000
Hosted:  deploy this folder with the Dockerfile on Render / Railway / Fly.io / any VPS,
         then put the https URL + APP_TOKEN into the app's settings (gear icon).

--- ChatGPT website engine (free account, no API credit) ---
pip install -r requirements.txt
python -m playwright install chromium        (or: set BROWSER_CHANNEL=chrome to use installed Chrome)
python login.py                              (log in once, press Enter; server must NOT be running)
set APP_TOKEN=mysecret
set ENGINE=chatgpt
python -m uvicorn server:app --host 0.0.0.0 --port 8000
Keep the browser window open. Works only on your own computer, not on cloud hosts.

Android v1.7 queue support: the /enhance endpoint accepts an optional request_id form field. Repeated requests with the same request_id and signed-in user reuse an existing processing/completed job while it remains available. Older v1.6 Android clients may omit request_id and continue to work.