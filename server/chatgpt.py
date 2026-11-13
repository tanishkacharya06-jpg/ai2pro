"""ChatGPT-website engine: drives a real browser logged in to YOUR ChatGPT account.
Fragile by nature: if chatgpt.com changes its layout, selectors below may need edits."""
import os, io, re, time, base64, queue, pathlib, threading
from playwright.sync_api import sync_playwright
from PIL import Image, ImageChops, ImageStat

HERE = pathlib.Path(__file__).parent
PROFILE = HERE / "chatgpt_profile"                                # keeps your login
HEADLESS = os.getenv("HEADLESS", "0") == "1"                      # keep 0: headless is often blocked
CHANNEL = os.getenv("BROWSER_CHANNEL") or None                    # set to "chrome" if Google Chrome is installed
TIMEOUT = 420                                                     # max seconds for the whole job
PREFIX = "Edit the attached photo exactly as instructed below and return the final result as an image.\n\n"
BUSY_SEL = '[data-testid="stop-button"], button[aria-label*="Stop" i]'
PREF_RE = re.compile(r"which (image|response)|you prefer|prefer this", re.I)   # "which image is better?" screen
PICK_RE = re.compile(r"prefer this|image 1|response 1|^\s*1\s*$", re.I)        # button that picks option 1

SKIP_CHECK = os.getenv("SKIP_ATTACH_CHECK") == "1"                  # escape hatch if the check misfires
_q, _lock, _started = queue.Queue(), threading.Lock(), False

def launch(p, headless=False):
    return p.chromium.launch_persistent_context(
        str(PROFILE), headless=headless, channel=CHANNEL,
        args=["--disable-blink-features=AutomationControlled"],
        ignore_default_args=["--enable-automation"], viewport={"width": 1280, "height": 900},
        accept_downloads=True)

def _gen_images(page):
    """Big images in ChatGPT's reply. Turn #1 is our own upload, so only turns after it count."""
    found, arts = [], page.locator("main article")
    for a in range(1, arts.count()):
        imgs = arts.nth(a).locator("img")
        for i in range(imgs.count()):
            try:
                if imgs.nth(i).evaluate("e => e.naturalWidth >= 512"):
                    found.append(imgs.nth(i))
            except Exception:
                pass
    if not found:                                       # fallback: images ChatGPT labels "Generated image"
        alts = page.locator('img[alt^="Generated"]')
        found = [alts.nth(i) for i in range(alts.count())]
    return found

def _sig(imgs):
    try:
        return tuple(i.get_attribute("src") for i in imgs)
    except Exception:
        return None

def _same_as_source(path, src):
    a = Image.open(path).convert("L").resize((64, 64))
    b = Image.open(src).convert("L").resize((64, 64))
    return ImageStat.Stat(ImageChops.difference(a, b)).mean[0] < 1.5

def _download(ctx, page, el, path):
    """Scroll to the image, click it, press ChatGPT's Download button, keep the downloaded file."""
    tmp = str(path) + ".dl"
    try:
        el.scroll_into_view_if_needed(); el.click()
        page.wait_for_timeout(1500)
        with page.expect_download(timeout=30000) as info:
            page.get_by_role("button", name=re.compile("download", re.I)).first.click()
        info.value.save_as(tmp)
        Image.open(tmp).save(path)                      # master copy as lossless PNG
        return
    except Exception:
        pass
    finally:
        try: page.keyboard.press("Escape")
        except Exception: pass
        try: os.remove(tmp)
        except OSError: pass
    try:                                                # fallback 1: fetch the image file directly
        Image.open(io.BytesIO(ctx.request.get(el.get_attribute("src")).body())).save(path)
    except Exception:                                   # fallback 2: screenshot of the image
        el.screenshot(path=str(path))

def _att_state(page):
    """Counts of things that appear in the chat box when a photo is attached (thumbnail / remove button)."""
    return (page.locator("form img").count(),
            page.locator('button[aria-label*="Remove" i]').count(),
            page.locator('form [data-testid*="attachment" i], form [data-testid*="file" i]').count())

def _attached(page, base):
    return SKIP_CHECK or any(n > b for n, b in zip(_att_state(page), base))

PASTE_JS = """async ({b64, name}) => {
  const bin = atob(b64), arr = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
  const dt = new DataTransfer(); dt.items.add(new File([arr], name, {type: 'image/jpeg'}));
  const el = document.querySelector('#prompt-textarea') || document.querySelector('div[contenteditable="true"]');
  el.focus();
  el.dispatchEvent(new ClipboardEvent('paste', {clipboardData: dt, bubbles: true, cancelable: true}));
}"""

def _attach(page, box, src, base):
    """Attach the user's photo. Tries 3 ways and CONFIRMS the thumbnail is in the box; raises if it never is."""
    data = base64.b64encode(pathlib.Path(src).read_bytes()).decode()
    def by_input():
        inputs = page.locator('input[type="file"]')
        for i in range(inputs.count()):
            inputs.nth(i).set_input_files(str(src))
            for _ in range(4):
                page.wait_for_timeout(1000)
                if _attached(page, base): return
    def by_chooser():
        page.locator('[data-testid="composer-plus-btn"], button[aria-label*="Attach" i], button[aria-label*="Add files" i], button[aria-label*="Add photos" i]').first.click(timeout=5000)
        with page.expect_file_chooser(timeout=8000) as fc:
            page.get_by_text(re.compile(r"add photos|upload|add files", re.I)).first.click(timeout=5000)
        fc.value.set_files(str(src))
    def by_paste():
        box.click()
        page.evaluate(PASTE_JS, {"b64": data, "name": pathlib.Path(src).name})
    for method in (by_input, by_chooser, by_paste):
        try: method()
        except Exception: pass
        for _ in range(15):                              # wait up to ~15 s for the thumbnail
            if _attached(page, base):
                page.wait_for_timeout(3000)              # let the upload finish
                return
            page.wait_for_timeout(1000)
    raise RuntimeError("Could not attach the photo to ChatGPT, so NOTHING was sent (see debug_last_error.png).")

def _sent(page, box, n0):
    try:
        empty = len(box.inner_text().strip()) < 5
    except Exception:
        empty = True
    return (empty or "/c/" in page.url or page.locator("article").count() > n0
            or page.locator(BUSY_SEL).count() > 0)

def _send(page, box):
    n0 = page.locator("article").count()
    btn = page.locator('button[data-testid="send-button"], button#composer-submit-button, button[aria-label*="Send" i]').first
    for attempt in range(4):
        try:
            if attempt < 2:
                btn.click(timeout=60000 if attempt == 0 else 5000)
            else:
                box.click(); page.keyboard.press("Enter")
        except Exception:
            try: box.click(); page.keyboard.press("Enter")
            except Exception: pass
        page.wait_for_timeout(4000)
        if _sent(page, box, n0):
            return

def _run(ctx, src, out, prompt, progress):
    out2 = out.with_name(out.name.replace("_out", "_out2"))
    page = ctx.pages[0] if ctx.pages else ctx.new_page()
    try:
        progress("Opening ChatGPT...")
        page.goto("https://chatgpt.com/", wait_until="domcontentloaded")
        box = page.locator('#prompt-textarea, div[contenteditable="true"]').first
        try:
            box.wait_for(timeout=60000)
        except Exception:
            raise RuntimeError("Chat box not found. Browser shows: '%s' at %s - see debug_last_error.png" % (page.title(), page.url))
        page.wait_for_timeout(1500)
        base = _att_state(page)
        progress("Attaching your photo...")
        _attach(page, box, src, base)
        box.click(); box.fill(PREFIX + prompt)
        for _ in range(3):                              # verify image AND text are both in the box before sending
            ok_img = _attached(page, base)
            try: ok_txt = len(box.inner_text().strip()) >= 20
            except Exception: ok_txt = False
            if ok_img and ok_txt: break
            progress("Photo or text missing in ChatGPT box - fixing...")
            if not ok_img: _attach(page, box, src, base)       # get the user's photo again
            if not ok_txt: box.click(); box.fill(PREFIX + prompt)
        else:
            raise RuntimeError("Not sent: photo + text were not both in the ChatGPT box (see debug_last_error.png).")
        progress("Photo + prompt confirmed - sending to ChatGPT...")
        _send(page, box)

        progress("ChatGPT is generating the image...")
        t0, stable, last, quiet = time.time(), 0, None, 0
        while time.time() - t0 < TIMEOUT:
            page.wait_for_timeout(2000)
            if page.locator(BUSY_SEL).count():          # ChatGPT still working
                stable, last, quiet = 0, None, 0
                continue
            imgs = _gen_images(page)
            sig = _sig(imgs)
            stable = stable + 1 if (imgs and sig == last) else 0
            last = sig
            if imgs and stable >= 4:                    # same image(s), not busy, for ~8 s = finished
                break
            quiet = 0 if imgs else quiet + 1
            if quiet >= 60:                             # 2 min with nothing -> ChatGPT replied with text only
                try: txt = page.locator("main").inner_text()[-300:]
                except Exception: txt = ""
                raise RuntimeError("ChatGPT returned no image: " + txt)
        else:
            raise TimeoutError("Timed out waiting for ChatGPT")

        progress("Downloading the result from ChatGPT...")
        n = 2 if len(imgs) >= 2 else 1                  # both images when ChatGPT shows two
        for k in range(n):
            els = _gen_images(page)
            _download(ctx, page, els[len(els) - n + k], out if k == 0 else out2)
        if _same_as_source(out, src):
            raise RuntimeError("ChatGPT gave back your original photo, no new image was generated. Look at the browser window.")
        if page.get_by_text(PREF_RE).count() > 0:       # "Which image do you prefer?" -> always pick 1
            try: page.get_by_role("button", name=PICK_RE).first.click(timeout=5000)
            except Exception: pass
    except Exception:
        page.screenshot(path=str(HERE / "debug_last_error.png"))
        raise

def _worker():
    try:
        ctx = launch(sync_playwright().start(), HEADLESS); err = None
    except Exception as e:
        ctx, err = None, str(e)
    if ctx:
        try:                                            # show chatgpt.com right away so login is visible
            (ctx.pages[0] if ctx.pages else ctx.new_page()).goto("https://chatgpt.com/")
        except Exception:
            pass
    while True:                                         # one job at a time, in this thread
        src, out, prompt, progress, box = _q.get()
        try:
            if err: raise RuntimeError("Browser failed to start: " + err)
            _run(ctx, src, out, prompt, progress); box["ok"] = True
        except Exception as e:
            box["err"] = str(e)
        box["ev"].set()

def start():
    global _started
    with _lock:
        if not _started:
            threading.Thread(target=_worker, daemon=True).start(); _started = True

def enhance(src, out, prompt, progress=lambda s: None):
    start()
    box = {"ev": threading.Event()}
    _q.put((src, out, prompt, progress, box))
    box["ev"].wait(TIMEOUT + 180)
    if "err" in box: raise RuntimeError(box["err"])
    if not box.get("ok"): raise TimeoutError("ChatGPT step timed out")