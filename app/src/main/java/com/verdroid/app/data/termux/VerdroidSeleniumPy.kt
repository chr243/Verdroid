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
import argparse, json, os, shutil, sys, time


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


def _save(path, data, binary=False):
    d = os.path.dirname(path)
    if d:
        os.makedirs(d, exist_ok=True)
    with open(path, "wb" if binary else "w", **({} if binary else {"encoding": "utf-8"})) as f:
        f.write(data)
    return os.path.getsize(path)


def main(argv=None):
    ap = argparse.ArgumentParser(prog="verdroid_selenium")
    ap.add_argument("cmd", choices=["open", "source", "shot"])
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
