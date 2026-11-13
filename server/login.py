"""Run ONCE with the server stopped:  py login.py   (in cmd, not by double-click)"""
import traceback
try:
    from playwright.sync_api import sync_playwright
    from chatgpt import launch
    with sync_playwright() as p:
        ctx = launch(p, False)
        page = ctx.pages[0] if ctx.pages else ctx.new_page()
        page.goto("https://chatgpt.com/")
        input("Log in to ChatGPT in the browser window until you see the chat screen, then press Enter here... ")
        ctx.close()
    print("Login saved.")
except Exception:
    traceback.print_exc()
input("Press Enter to close...")