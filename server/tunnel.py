"""Public link for the phone app: starts a Cloudflare quick tunnel and publishes its (changing) URL
to a private ntfy.sh topic named by your pairing code. The phone app reads it from there, so it
always finds your PC, from anywhere, with no manual steps."""
import os, re, time, secrets, pathlib, threading, subprocess, urllib.request

HERE = pathlib.Path(__file__).parent
PORT = os.getenv("PORT", "8000")
EXE = HERE / ("cloudflared.exe" if os.name == "nt" else "cloudflared")
URL = {"url": ""}

def pair_code():
    if os.getenv("PAIR_CODE"):
        return os.getenv("PAIR_CODE").strip()
    f = HERE / "pair.txt"
    if not f.exists():
        f.write_text("aicam-" + secrets.token_hex(6))
    return f.read_text().strip()

def publish(url):
    try:
        urllib.request.urlopen(urllib.request.Request("https://ntfy.sh/" + pair_code(), data=url.encode(), method="POST"), timeout=20).read()
        print("[tunnel] published link for the phone app")
    except Exception as e:
        print("[tunnel] could not publish the link:", e)

def _tunnel():
    if not EXE.exists():
        print("[tunnel] cloudflared not found - start with startup.bat so it gets downloaded"); return
    while True:
        p = subprocess.Popen([str(EXE), "tunnel", "--url", "http://localhost:" + PORT, "--no-autoupdate"],
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        for line in p.stdout:
            m = re.search(r"https://[a-z0-9-]+\.trycloudflare\.com", line)
            if m and m.group(0) != URL["url"]:
                URL["url"] = m.group(0)
                print("[tunnel] public link:", URL["url"]); publish(URL["url"])
        time.sleep(5)                                    # tunnel ended -> start a new one

def _refresh():                                          # ntfy keeps messages ~12 h, so re-send every 30 min
    while True:
        time.sleep(1800)
        if URL["url"]: publish(URL["url"])

def start():
    print("=" * 52); print("  PHONE PAIRING CODE:  " + pair_code()); print("=" * 52)
    if os.getenv("TUNNEL") == "1":
        threading.Thread(target=_tunnel, daemon=True).start()
        threading.Thread(target=_refresh, daemon=True).start()