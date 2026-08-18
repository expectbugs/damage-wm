"""Grab reddit post permalinks from a search/listing page (JS-rendered)."""
import sys
from playwright.sync_api import sync_playwright

url = sys.argv[1]
wait = int(sys.argv[2]) if len(sys.argv) > 2 else 5000

with sync_playwright() as p:
    b = p.chromium.launch(headless=True, args=["--no-sandbox"])
    pg = b.new_page(user_agent=(
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"))
    pg.goto(url, timeout=60000, wait_until="domcontentloaded")
    pg.wait_for_timeout(wait)
    seen = set()
    for a in pg.query_selector_all("a[href*='/comments/']"):
        h = a.get_attribute("href") or ""
        if "/comments/" not in h:
            continue
        if h.startswith("/"):
            h = "https://www.reddit.com" + h
        h = h.split("?")[0]
        if h not in seen:
            seen.add(h)
            print(h)
    b.close()
