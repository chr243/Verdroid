package com.verdroid.app.data.termux

import java.util.Base64

/**
 * The Python helper Verdroid drops into Termux at `~/.verdroid/verdroid_selenium.py` before every selenium_* / termux_python
 * call (headless Chromium through chromedriver). It is importable from the agent's own scrapers
 * (`from verdroid_selenium import make_driver`) and is also the CLI behind the selenium_* tools; it prints one
 * `VERDROID_JSON=<json>` line.
 */
object VerdroidSeleniumPy {
    /** Absolute (quoted "~/…" arguments are not tilde-expanded). */
    const val DIR = TermuxManager.TERMUX_HOME + "/.verdroid"
    const val FILE = "$DIR/verdroid_selenium.py"
    const val MARKER = "VERDROID_JSON="

    val SOURCE = """
# Verdroid headless Chromium helper (written by the Verdroid app; changes are overwritten).
import argparse
import re, json, os, shutil, sys, time


def make_driver(width=1366, height=900, user_agent=None):
    # Headless Chromium (Termux TUR package) via chromedriver; English UI.
    from selenium import webdriver
    from selenium.webdriver.chrome.service import Service
    opts = webdriver.ChromeOptions()
    binary = shutil.which("chromium-browser") or shutil.which("chromium")
    if binary:
        opts.binary_location = binary
    for a in ("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu",
              "--window-size=%d,%d" % (width, height), "--lang=en-US", "--mute-audio",
              "--disable-blink-features=AutomationControlled"):
        opts.add_argument(a)
    if user_agent:
        opts.add_argument("--user-agent=" + user_agent)
    opts.add_experimental_option("prefs", {"intl.accept_languages": "en-US,en"})
    opts.add_experimental_option("excludeSwitches", ["enable-automation"])
    opts.add_experimental_option("useAutomationExtension", False)
    driver_path = shutil.which("chromedriver")
    service = Service(driver_path) if driver_path else Service()
    drv = webdriver.Chrome(service=service, options=opts)
    drv.set_page_load_timeout(60)
    _unheadless(drv, user_agent)
    return drv


def normal_user_agent(ua):
    # "HeadlessChrome/129.0" -> "Chrome/129.0": sites like x.com answer 403 to the headless token.
    return (ua or "").replace("HeadlessChrome", "Chrome")


def _unheadless(drv, user_agent=None):
    try:
        ua = user_agent or normal_user_agent(drv.execute_script("return navigator.userAgent") or "")
        if ua:
            drv.execute_cdp_cmd("Network.setUserAgentOverride", {"userAgent": ua, "acceptLanguage": "en-US,en;q=0.9", "platform": "Linux"})
        drv.execute_cdp_cmd("Page.addScriptToEvaluateOnNewDocument", {"source":
            "Object.defineProperty(navigator,'webdriver',{get:()=>undefined});"
            "Object.defineProperty(navigator,'languages',{get:()=>['en-US','en']});"})
    except Exception:
        pass


def load(drv, url, wait=0.0, wait_for=None):
    drv.get(url)
    if wait_for:
        from selenium.webdriver.common.by import By
        from selenium.webdriver.support import expected_conditions as EC
        from selenium.webdriver.support.ui import WebDriverWait
        WebDriverWait(drv, max(wait, 1.0)).until(EC.presence_of_element_located((By.CSS_SELECTOR, wait_for)))
    elif wait > 0:
        time.sleep(wait)


def _read_cookies(path):
    # Reads and immediately deletes the private cookie file Verdroid wrote (values are never printed).
    if not path:
        return None
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    finally:
        try:
            os.remove(path)
        except OSError:
            pass


def cdp_cookie(c):
    # Selenium-style cookie -> CDP Network.setCookie params (domain kept, e.g. ".x.com").
    d = {"name": c["name"], "value": c.get("value", ""), "domain": c["domain"], "path": c.get("path") or "/",
         "secure": bool(c.get("secure")), "httpOnly": bool(c.get("httpOnly"))}
    if c.get("expiry"):
        d["expires"] = float(c["expiry"])
    if c.get("sameSite") in ("Strict", "Lax", "None"):
        d["sameSite"] = c["sameSite"]
    return d


def inject_cookies(drv, url, cookies):
    # Prefer CDP (no navigation needed, any domain); fall back to add_cookie per host. Counts only, never values.
    added, failed = 0, []
    try:
        drv.execute_cdp_cmd("Network.enable", {})
        drv.execute_cdp_cmd("Network.setCookies", {"cookies": [cdp_cookie(c) for c in cookies]})
        return {"added": len(cookies), "method": "cdp", "failed": []}
    except Exception as e:
        failed.append({"name": "*", "error": "cdp:" + type(e).__name__})
    from urllib.parse import urlsplit
    scheme = urlsplit(url).scheme or "https"
    by_host = {}
    for c in cookies:
        by_host.setdefault(c["domain"].lstrip("."), []).append(c)
    for host, group in by_host.items():
        try:
            drv.get("%s://%s/robots.txt" % (scheme, host))
        except Exception:
            pass
        for c in group:
            c = {k: v for k, v in c.items() if k in ("name", "value", "domain", "path", "secure", "httpOnly", "expiry", "sameSite")}
            try:
                drv.add_cookie(c)
                added += 1
            except Exception:
                c.pop("domain", None)
                try:
                    drv.add_cookie(c)
                    added += 1
                except Exception as e2:
                    failed.append({"name": c.get("name"), "error": type(e2).__name__})
    return {"added": added, "method": "add_cookie", "failed": failed[:20]}


X_JS = r'''
return Array.from(document.querySelectorAll('article[data-testid="tweet"]')).map(function (a) {
  function q(s) { return a.querySelector(s); }
  function lab(id) { var e = q('[data-testid="' + id + '"]'); return e ? (e.getAttribute('aria-label') || '') : ''; }
  var un = q('[data-testid="User-Name"]'), name = '', handle = '';
  if (un) {
    var parts = Array.from(un.querySelectorAll('span')).map(function (s) { return (s.innerText || '').trim(); }).filter(Boolean);
    name = parts[0] || '';
    handle = parts.filter(function (s) { return s.charAt(0) === '@'; })[0] || '';
  }
  var t = q('time'), link = '';
  if (t && t.closest('a')) link = t.closest('a').href;
  else { var l = q('a[href*="/status/"]'); if (l) link = l.href; }
  var texts = Array.from(a.querySelectorAll('[data-testid="tweetText"]')).map(function (e) { return e.innerText || ''; });
  var sc = q('[data-testid="socialContext"]');
  return { name: name, handle: handle, text: texts[0] || '', quoted: texts.length > 1 ? texts[1] : '',
    time: t ? (t.getAttribute('datetime') || '') : '', rel: t ? (t.innerText || '') : '', url: link,
    social: sc ? (sc.innerText || '') : '', replies: lab('reply'), reposts: lab('retweet'), likes: lab('like') || lab('unlike'),
    raw: (texts.length || un) ? '' : (a.innerText || '').slice(0, 1500) };
});
'''


def _status_id(url):
    m = re.search(r"/status/(\d+)", url or "")
    return m.group(1) if m else None


def scrape_x(drv, count, max_idle):
    # Posts from an X page via stable data-testid attributes (no HTML regex); scrolls and dedupes by status id.
    from selenium.webdriver.common.by import By
    from selenium.webdriver.support import expected_conditions as EC
    from selenium.webdriver.support.ui import WebDriverWait
    try:
        WebDriverWait(drv, 25).until(EC.presence_of_element_located((By.CSS_SELECTOR, 'article[data-testid="tweet"]')))
    except Exception:
        cur = drv.current_url or ""
        if "/login" in cur or "/i/flow/" in cur:
            return {"ok": False, "error": "NOT_LOGGED_IN: X shows the login page (saved login missing or expired). Call cookie_login once, then retry this same tool.", "url": cur}
        body = (drv.execute_script("return document.body ? document.body.innerText : '';") or "")[:1500]
        return {"ok": False, "error": "No posts appeared on the page within 25 s.", "page_text": body}
    seen, posts, idle = set(), [], 0
    while len(posts) < count and idle < max_idle:
        before = len(posts)
        for p in drv.execute_script(X_JS) or []:
            key = _status_id(p.get("url")) or (p.get("handle", "") + "|" + (p.get("text") or p.get("raw") or "")[:80])
            if key in seen:
                continue
            seen.add(key)
            p["id"] = _status_id(p.get("url")) or ""
            posts.append(p)
        if len(posts) >= count:
            break
        idle = idle + 1 if len(posts) == before else 0
        n = len(drv.find_elements(By.CSS_SELECTOR, 'article[data-testid="tweet"]'))
        drv.execute_script("window.scrollBy(0, Math.floor(window.innerHeight * 0.9));")
        end = time.time() + 3
        while time.time() < end:
            time.sleep(0.3)
            last = drv.execute_script(
                "var a=document.querySelectorAll('article[data-testid=\"tweet\"]');"
                "var l=a[a.length-1]; var t=l&&l.querySelector('time'); return t&&t.closest('a')?t.closest('a').href:'';")
            if _status_id(last) and _status_id(last) not in seen:
                break
            if len(drv.find_elements(By.CSS_SELECTOR, 'article[data-testid="tweet"]')) != n:
                break
    return {"posts": posts[:count], "post_count": min(len(posts), count)}


def _save(path, data, binary=False):
    d = os.path.dirname(path)
    if d:
        os.makedirs(d, exist_ok=True)
    with open(path, "wb" if binary else "w", **({} if binary else {"encoding": "utf-8"})) as f:
        f.write(data)
    return os.path.getsize(path)


def main(argv=None):
    ap = argparse.ArgumentParser(prog="verdroid_selenium")
    ap.add_argument("cmd", choices=["open", "source", "shot", "xposts"])
    ap.add_argument("url")
    ap.add_argument("--wait", type=float, default=2.0)
    ap.add_argument("--wait-for", default=None)
    ap.add_argument("--max-chars", type=int, default=12000)
    ap.add_argument("--max-links", type=int, default=60)
    ap.add_argument("--save", default=None)
    ap.add_argument("--width", type=int, default=1366)
    ap.add_argument("--height", type=int, default=900)
    ap.add_argument("--full", action="store_true")
    ap.add_argument("--cookies", default=None)
    ap.add_argument("--count", type=int, default=20)
    ap.add_argument("--max-idle", type=int, default=4)
    a = ap.parse_args(argv)
    out = {"ok": True}
    drv = None
    try:
        cookies = _read_cookies(a.cookies)
        drv = make_driver(a.width, a.height)
        if cookies is not None:
            out["cookies"] = inject_cookies(drv, a.url, cookies)
        load(drv, a.url, a.wait, a.wait_for)
        out["url"] = drv.current_url
        out["title"] = drv.title
        if a.cmd == "open":
            text = drv.execute_script("return document.body ? document.body.innerText : '';") or ""
            out["text_chars"] = len(text)
            out["truncated"] = len(text) > a.max_chars
            out["text"] = text[: a.max_chars]
            links = drv.execute_script(
                "return Array.from(document.querySelectorAll('a[href]')).slice(0, 1000)"
                ".map(function(e){return [(e.innerText||'').trim().slice(0,120), e.href];});") or []
            seen, kept = set(), []
            for t, h in links:
                if h and h.startswith("http") and h not in seen:
                    seen.add(h)
                    kept.append({"text": t, "url": h})
            out["links"] = kept[: a.max_links]
            if a.save:
                out["saved"] = a.save
                out["bytes"] = _save(a.save, drv.page_source)
        elif a.cmd == "xposts":
            out.update(scrape_x(drv, max(1, min(a.count, 100)), a.max_idle))
        elif a.cmd == "source":
            html = drv.page_source or ""
            out["html_chars"] = len(html)
            out["truncated"] = len(html) > a.max_chars
            out["html"] = html[: a.max_chars]
            if a.save:
                out["saved"] = a.save
                out["bytes"] = _save(a.save, html)
        else:
            if a.full:
                h = drv.execute_script("return Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);") or a.height
                drv.set_window_size(a.width, min(int(h), 12000))
                time.sleep(0.5)
            png = drv.get_screenshot_as_png()
            out["saved"] = a.save
            out["bytes"] = _save(a.save, png, binary=True)
    except ImportError as e:
        out = {"ok": False, "error": "Selenium is not installed in Termux (%s). Install 'chromium-selenium' in Verdroid Settings > Tools." % e}
    except Exception as e:
        out = {"ok": False, "error": ("%s: %s" % (type(e).__name__, e))[:2000]}
    finally:
        if drv is not None:
            try:
                drv.quit()
            except Exception:
                pass
    print("$MARKER" + json.dumps(out, ensure_ascii=False))
    return 0 if out.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main())
""".trimStart()

    /** Shell that (re)writes the helper into Termux. */
    fun installCommand(): String =
        "mkdir -p '$DIR' && echo " + Base64.getEncoder().encodeToString(SOURCE.toByteArray()) + " | base64 -d > '$FILE'"
}
