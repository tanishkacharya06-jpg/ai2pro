"""AI Camera server v1.3 - accounts, admin prompts, ChatGPT/OpenAI/echo engines.
Run:  set ENGINE=chatgpt & py -m uvicorn server:app --host 0.0.0.0 --port 8000
First login: admin / admin  (change the password after logging in)"""
import os, io, time, uuid, base64, pathlib, threading, sqlite3, hashlib, secrets
from fastapi import FastAPI, UploadFile, Form, HTTPException, BackgroundTasks, Request, Response
from fastapi.responses import FileResponse, HTMLResponse
from pydantic import BaseModel
from PIL import Image, ImageOps
import tunnel

VERSION = "1.7"
ENGINE = os.getenv("ENGINE", "echo")                       # chatgpt | openai | echo
LEGACY_TOKEN = os.getenv("APP_TOKEN")                      # optional: keeps the old Android app working
ALLOW_SIGNUP = os.getenv("ALLOW_SIGNUP", "1") == "1"       # set 0 to stop new accounts
TTL = 900
HERE = pathlib.Path(__file__).parent
JOBS = HERE / "jobs"; JOBS.mkdir(exist_ok=True)
DB = HERE / "data.db"
jobs = {}
app = FastAPI()

def db():
    c = sqlite3.connect(DB); c.row_factory = sqlite3.Row; return c

def hashpw(pw, salt):
    return hashlib.pbkdf2_hmac("sha256", pw.encode(), bytes.fromhex(salt), 120000).hex()

def init():
    with db() as c:
        c.executescript("""
        CREATE TABLE IF NOT EXISTS users(name TEXT PRIMARY KEY COLLATE NOCASE, salt TEXT, pw TEXT, admin INTEGER DEFAULT 0);
        CREATE TABLE IF NOT EXISTS sessions(tok TEXT PRIMARY KEY, name TEXT, t REAL);
        CREATE TABLE IF NOT EXISTS prompts(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, text TEXT);
        CREATE TABLE IF NOT EXISTS enhancement_requests(request_id TEXT, user_name TEXT, job_id TEXT,
            PRIMARY KEY(request_id, user_name));""")
        if not c.execute("SELECT 1 FROM users").fetchone():
            salt = secrets.token_hex(8)
            c.execute("INSERT INTO users VALUES('admin',?,?,1)", (salt, hashpw("admin", salt)))
        if not c.execute("SELECT 1 FROM prompts").fetchone():
            c.execute("INSERT INTO prompts(name,text) VALUES(?,?)",
                      ("High Quality Image", (HERE / "PROMPT.txt").read_text(encoding="utf-8")))
init()

def user_of(req: Request):
    tok = req.cookies.get("sid") or (req.headers.get("authorization") or "").replace("Bearer ", "").strip()
    if LEGACY_TOKEN and tok == LEGACY_TOKEN:
        return {"name": "app", "admin": 0}
    if tok:
        with db() as c:
            r = c.execute("SELECT u.name, u.admin FROM sessions s JOIN users u ON u.name=s.name WHERE s.tok=?", (tok,)).fetchone()
        if r: return {"name": r["name"], "admin": r["admin"]}
    raise HTTPException(401, "Please log in")

def admin_of(req: Request):
    u = user_of(req)
    if not u["admin"]: raise HTTPException(403, "Admins only")
    return u

class Cred(BaseModel):
    name: str
    password: str

class PwChange(BaseModel):
    old: str
    new: str

class PromptIn(BaseModel):
    name: str
    text: str

def start_session(name, resp):
    tok = secrets.token_urlsafe(32)
    with db() as c: c.execute("INSERT INTO sessions VALUES(?,?,?)", (tok, name, time.time()))
    resp.set_cookie("sid", tok, httponly=True, samesite="lax", max_age=30 * 86400)

@app.post("/api/signup")
def signup(c: Cred, resp: Response):
    if not ALLOW_SIGNUP: raise HTTPException(403, "Sign-up is disabled")
    name = c.name.strip()[:30]
    if len(name) < 3 or len(c.password) < 4:
        raise HTTPException(400, "Username needs 3+ characters and password 4+")
    salt = secrets.token_hex(8)
    try:
        with db() as d: d.execute("INSERT INTO users VALUES(?,?,?,0)", (name, salt, hashpw(c.password, salt)))
    except sqlite3.IntegrityError:
        raise HTTPException(409, "That username is taken")
    start_session(name, resp)
    return {"name": name, "admin": 0}

@app.post("/api/login")
def login(c: Cred, resp: Response):
    with db() as d: r = d.execute("SELECT * FROM users WHERE name=?", (c.name.strip(),)).fetchone()
    if not r or not secrets.compare_digest(r["pw"], hashpw(c.password, r["salt"])):
        raise HTTPException(401, "Wrong username or password")
    start_session(r["name"], resp)
    return {"name": r["name"], "admin": r["admin"]}

@app.post("/api/logout")
def logout(req: Request, resp: Response):
    with db() as c: c.execute("DELETE FROM sessions WHERE tok=?", (req.cookies.get("sid", ""),))
    resp.delete_cookie("sid"); return {"ok": True}

@app.get("/api/me")
def me(req: Request):
    return user_of(req)

@app.post("/api/password")
def password(p: PwChange, req: Request):
    u = user_of(req)
    with db() as d:
        r = d.execute("SELECT * FROM users WHERE name=?", (u["name"],)).fetchone()
        if not r or hashpw(p.old, r["salt"]) != r["pw"]: raise HTTPException(401, "Old password is wrong")
        if len(p.new) < 4: raise HTTPException(400, "New password needs 4+ characters")
        salt = secrets.token_hex(8)
        d.execute("UPDATE users SET salt=?, pw=? WHERE name=?", (salt, hashpw(p.new, salt), u["name"]))
    return {"ok": True}

@app.get("/api/pair")
def pair(req: Request):
    user_of(req)
    return {"code": tunnel.pair_code(), "url": tunnel.URL["url"]}

@app.get("/api/prompts")
def prompts(req: Request):
    user_of(req)
    with db() as c: return [dict(r) for r in c.execute("SELECT id, name, text FROM prompts ORDER BY id")]

@app.post("/api/prompts")
def prompt_add(p: PromptIn, req: Request):
    admin_of(req)
    if not p.name.strip() or not p.text.strip(): raise HTTPException(400, "Name and prompt are required")
    with db() as c: c.execute("INSERT INTO prompts(name,text) VALUES(?,?)", (p.name.strip()[:40], p.text.strip()))
    return {"ok": True}

@app.put("/api/prompts/{pid}")
def prompt_edit(pid: int, p: PromptIn, req: Request):
    admin_of(req)
    if not p.name.strip() or not p.text.strip(): raise HTTPException(400, "Name and prompt are required")
    with db() as c: c.execute("UPDATE prompts SET name=?, text=? WHERE id=?", (p.name.strip()[:40], p.text.strip(), pid))
    return {"ok": True}

@app.delete("/api/prompts/{pid}")
def prompt_del(pid: int, req: Request):
    admin_of(req)
    with db() as c:
        if c.execute("SELECT COUNT(*) FROM prompts").fetchone()[0] <= 1:
            raise HTTPException(400, "Keep at least one prompt")
        c.execute("DELETE FROM prompts WHERE id=?", (pid,))
    return {"ok": True}

def wipe(jid):
    jobs.pop(jid, None)
    with db() as c:
        c.execute("DELETE FROM enhancement_requests WHERE job_id=?", (jid,))
    for p in JOBS.glob(f"{jid}*"): p.unlink(missing_ok=True)

def sweeper():
    while True:
        time.sleep(60)
        for jid, j in list(jobs.items()):
            if time.time() - j["t"] > TTL: wipe(jid)
threading.Thread(target=sweeper, daemon=True).start()

@app.on_event("startup")
def _startup():
    if ENGINE == "chatgpt":
        import chatgpt; chatgpt.start()
    tunnel.start()

def work(jid):
    src, out = JOBS / f"{jid}.jpg", JOBS / f"{jid}_out.png"
    prog = lambda s: jobs[jid].__setitem__("stage", s)
    prompt = jobs[jid]["prompt"]
    try:
        if ENGINE == "openai":
            from openai import OpenAI
            with open(src, "rb") as f:
                r = OpenAI().images.edit(model="gpt-image-1", image=f, prompt=prompt, input_fidelity="high", size="auto")
            out.write_bytes(base64.b64decode(r.data[0].b64_json))
        elif ENGINE == "chatgpt":
            import chatgpt; chatgpt.enhance(src, out, prompt, prog)
        else:
            time.sleep(3); Image.open(src).save(out)
        jobs[jid]["count"] = len(list(JOBS.glob(f"{jid}_out*.png")))
        jobs[jid]["status"] = "done"
    except Exception as e:
        jobs[jid].update(status="error", error=str(e))

def job_of(jid, req):
    u = user_of(req)
    j = jobs.get(jid)
    if not j or j["user"] != u["name"]: raise HTTPException(404, "Job not found")
    return j

@app.get("/")
def home():
    return HTMLResponse((HERE / "page.html").read_text(encoding="utf-8"))

@app.get("/version")
def version():
    return {"version": VERSION, "engine": ENGINE}

@app.post("/enhance")
async def enhance(req: Request, photo: UploadFile, bg: BackgroundTasks, prompt_id: int = Form(0), request_id: str = Form("")):
    u = user_of(req)
    request_id = request_id.strip()[:128]
    # WorkManager retries reuse this key. Return the original job while it is still available.
    if request_id:
        with db() as d:
            prior = d.execute("SELECT job_id FROM enhancement_requests WHERE request_id=? AND user_name=?",
                              (request_id, u["name"])).fetchone()
        if prior:
            previous = jobs.get(prior["job_id"])
            if previous and previous.get("user") == u["name"] and previous.get("status") in ("processing", "done"):
                return {"job_id": prior["job_id"]}
    with db() as d:
        r = (d.execute("SELECT text FROM prompts WHERE id=?", (prompt_id,)).fetchone()
             or d.execute("SELECT text FROM prompts ORDER BY id LIMIT 1").fetchone())
    img = ImageOps.exif_transpose(Image.open(io.BytesIO(await photo.read()))).convert("RGB")
    img.thumbnail((2048, 2048))
    jid = uuid.uuid4().hex
    img.save(JOBS / f"{jid}.jpg", quality=95)
    jobs[jid] = {"status": "processing", "t": time.time(), "stage": "Queued", "user": u["name"], "prompt": r["text"]}
    if request_id:
        with db() as d:
            d.execute("INSERT OR REPLACE INTO enhancement_requests(request_id,user_name,job_id) VALUES(?,?,?)",
                      (request_id, u["name"], jid))
    bg.add_task(work, jid)
    return {"job_id": jid}

@app.get("/status/{jid}")
def status(jid: str, req: Request):
    j = job_of(jid, req)
    return {k: j.get(k) for k in ("status", "stage", "error", "count")}

@app.get("/result/{jid}")
@app.get("/result/{jid}/{n}")
def result(jid: str, req: Request, n: int = 1):
    job_of(jid, req)
    p = JOBS / (f"{jid}_out.png" if n == 1 else f"{jid}_out{n}.png")
    if not p.exists(): raise HTTPException(404)
    return FileResponse(p, media_type="image/png")

@app.delete("/job/{jid}")
def delete(jid: str, req: Request):
    job_of(jid, req); wipe(jid); return {"ok": True}