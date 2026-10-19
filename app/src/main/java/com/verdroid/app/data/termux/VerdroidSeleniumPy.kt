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


def make_driver(width=1366, height=900, user_agent=None, cookies_for=None):
    # Headless Chromium (Termux TUR package) via chromedriver; English UI. Same launch for every tool and script.
    # cookies_for="reddit.com" (opt-in, termux_python with cookies_for): the app's stored cookies for that site are set over
    # CDP before the first get(), exactly like cookie_open. Values never leave the browser; drv.verdroid_cookies = names/counts.
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
    if cookies_for:
        try:
            drv.verdroid_cookies = inject_cookies(drv, "https://" + cookies_for.strip(".") + "/", app_cookies(cookies_for))
        except Exception:
            drv.quit()
            raise
    return drv


_APP_COOKIES = None


def app_cookies(site):
    # Stored cookies the app handed to this termux_python run (VERDROID_COOKIES: private 0600 file in ~/.verdroid, read
    # once into memory and deleted at once). Only the sites the tool call named are in it.
    global _APP_COOKIES
    if _APP_COOKIES is None:
        _APP_COOKIES = _read_cookies(os.environ.pop("VERDROID_COOKIES", None)) or []
    site = site.lower().strip(".")
    got = [c for c in _APP_COOKIES if c["domain"].lower().strip(".") == site or c["domain"].lower().strip(".").endswith("." + site)]
    if not got:
        raise RuntimeError("No app cookies for " + site + ": pass cookies_for=[\"" + site + "\"] to termux_python (needs a saved login).")
    return got


def open_session(drv, url, cookies, wait=0.0, wait_for=None):
    # THE logged-in page load shared by cookie_open (open/source/shot) and scripts: stored cookies over CDP
    # (Network.setCookies) before the first navigation, checked (names/counts only), then url + plain wait.
    # state "no_cookies" = the browser kept none of them (the page is then not loaded).
    if cookies is None:
        load(drv, url, wait, wait_for)
        return None
    info = inject_cookies(drv, url, cookies)
    info.update(cookie_report(drv, url))
    if cookies and info["present"] == 0:
        info["state"] = "no_cookies"
        return info
    load(drv, url, wait, wait_for)
    after = cookie_report(drv, url)["names"]
    info["present_after"] = len(after)
    info["kept_after_load"] = len(set(info["names"]) & set(after))  # injected cookies the site did not clear
    return info


CHALLENGE_JS = r'''
var t = (document.title || ''), b = document.body ? (document.body.innerText || '').slice(0, 3000) : '';
return {title: t, text: b, captcha: !!document.querySelector('.grecaptcha-badge, script[src*="recaptcha/enterprise.js"], iframe[src*="recaptcha"]')};
'''


def page_challenge(drv, url):
    # Bot-check interstitial instead of the page ("Prove your humanity"; on Reddit also any reCAPTCHA Enterprise marker,
    # which normal Reddit pages don't carry). Elsewhere a reCAPTCHA badge alone is normal (login forms), so only the text counts.
    try:
        st = drv.execute_script(CHALLENGE_JS) or {}
    except Exception:
        return False
    text = ((st.get("title") or "") + " " + (st.get("text") or "")).lower()
    if "prove your humanity" in text:
        return True
    return bool(st.get("captcha")) and "reddit.com" in (url or "").lower()


def cookie_report(drv, url):
    # Cookies the browser now holds for url (names only, never values).
    try:
        got = drv.execute_cdp_cmd("Network.getCookies", {"urls": [url]}) or {}
        names = sorted({c.get("name") for c in got.get("cookies", [])})
    except Exception:
        names = []
    return {"present": len(names), "names": names}


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


REDDIT_STATE_JS = r'''
var sel = arguments[0], b = document.body, t = (document.title || '');
var txt = b ? (b.innerText || '').slice(0, 1500) : '';
var n = 0; try { n = document.querySelectorAll(sel).length; } catch (e) {}
var cap = !!document.querySelector('.grecaptcha-badge, script[src*="recaptcha/enterprise.js"], iframe[src*="recaptcha"]');
return {found: n, title: t, text: txt, path: location.pathname, captcha: cap};
'''

REDDIT_CHALLENGE_MARKERS = ("prove your humanity", "recaptcha/enterprise.js", "grecaptcha-badge")


def reddit_state(st):
    # Classifies a Reddit page while it renders. Strings, not versions: Reddit changes these gates often.
    title = (st.get("title") or "").lower()
    text = (st.get("text") or "").lower()
    if st.get("found"):
        return "ready"
    if "prove your humanity" in title or "prove your humanity" in text or st.get("captcha"):
        return "challenge"
    if "blocked by network security" in text or "blocked by network security" in title:
        return "ip_blocked"
    if "too many requests" in text or "too many requests" in title or "429" in title:
        return "rate_limited"
    if (st.get("path") or "").startswith("/login"):
        return "login_gated"
    # A genuine zero-match search: the real search page rendered and says so (never a challenge page).
    if ("find any results" in text or "no results" in text) and "search" in (st.get("path") or "") + title:
        return "empty"
    return None


REDDIT_POSTS_JS = r'''
return Array.from(document.querySelectorAll('shreddit-post')).slice(0, arguments[0]).map(function (p) {
  function g(a) { return p.getAttribute(a) || ''; }
  return {title: g('post-title'), url: g('permalink') ? 'https://www.reddit.com' + g('permalink') : '', subreddit: g('subreddit-prefixed-name'),
    author: g('author'), score: g('score'), comments: g('comment-count'), created: g('created-timestamp'), link: g('content-href')};
});
'''


def reddit_posts(drv, url, wait=6.0, count=25, cap=15):
    # For termux_python sweeps: one driver (make_driver(cookies_for="reddit.com")), many pages. Polite: ~1 page/s at most.
    drv.get(url)
    time.sleep(max(1.0, float(wait)))
    r = reddit_page(drv, "shreddit-post", None, cap, count, 0)
    posts = drv.execute_script(REDDIT_POSTS_JS, count) or [] if r["state"] == "ready" else []
    return {"state": r["state"], "url": url, "posts": posts}


def reddit_page(drv, selector, settle_for, cap, count, max_chars):
    # Called after open_session's plain wait. Checks once a second (cap seconds) for the content selector, then returns the
    # rendered HTML. A challenge page can clear itself with a valid session, so it only counts if it is still there at the end.
    cap = max(3.0, min(float(cap or 15), 30.0))
    end, state, retried, last = time.time() + cap, None, False, None
    while time.time() < end:
        try:
            st = drv.execute_script(REDDIT_STATE_JS, selector) or {}
        except Exception:
            st = {}
        state = reddit_state(st)
        if state == "rate_limited" and not retried:
            retried = True  # back off once, then reload
            time.sleep(6)
            drv.refresh()
            end = max(end, time.time() + 8)
            state = None
            continue
        if state == "challenge":
            last = state
            state = None
        elif state is not None:
            break
        time.sleep(1.0)
    if state is None:
        state = last or "timeout"
    if state == "ready":
        if settle_for:  # e.g. comments render after the post
            end2 = time.time() + 4
            while time.time() < end2:
                try:
                    if drv.execute_script("return document.querySelectorAll(arguments[0]).length;", settle_for):
                        break
                except Exception:
                    pass
                time.sleep(0.25)
            time.sleep(0.5)
        for _ in range(4):  # scroll a little for more results (polite: max 4)
            try:
                if drv.execute_script("return document.querySelectorAll(arguments[0]).length;", selector) >= count:
                    break
                drv.execute_script("window.scrollTo(0, document.body.scrollHeight);")
            except Exception:
                break
            time.sleep(1.0)
    html = ""
    try:
        html = drv.execute_script("return document.documentElement.outerHTML;") or ""
    except Exception:
        pass
    if state in ("empty", "timeout") and any(m in html.lower() for m in REDDIT_CHALLENGE_MARKERS):
        state = "challenge"
    title = ""
    try:
        title = drv.title or ""
    except Exception:
        pass
    return {"state": state, "title": title, "html_chars": len(html), "truncated": len(html) > max_chars, "html": html[:max_chars]}


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


X_EDITORS = ['[data-testid="tweetTextarea_0"]', '[data-testid^="tweetTextarea"]', 'div[role="textbox"][contenteditable="true"]']


def _norm(t):
    return re.sub(r"\s+", " ", (t or "").replace("\u200b", "")).strip()


OVERLAY_JS = r'''
var n = 0;
var labels = ["Got it", "Not now", "Accept all cookies", "Refuse non-essential cookies", "Close", "Dismiss", "Maybe later", "Skip for now"];
Array.from(document.querySelectorAll('[data-testid="BottomBar"] [role="button"], [role="dialog"] [role="button"], [data-testid="sheetDialog"] [role="button"], [data-testid="app-bar-close"], [data-testid="xMigrationBottomBar"] [role="button"]')).forEach(function (b) {
  var t = (b.innerText || b.getAttribute("aria-label") || "").trim();
  if (labels.indexOf(t) >= 0 && !b.closest('[data-testid="tweetButton"]') && !b.closest('[aria-labelledby*="modal-header"] [data-testid^="tweetTextarea"]')) { try { b.click(); n++; } catch (e) {} }
});
Array.from(document.querySelectorAll('[data-testid="toast"], [data-testid="mask"]')).forEach(function (e) {
  if (e.getAttribute("data-testid") === "mask" && document.querySelector('[data-testid^="tweetTextarea"]') && e.parentElement && e.parentElement.querySelector('[data-testid^="tweetTextarea"]')) return;
  e.style.pointerEvents = "none"; n++;
});
return n;
'''


def _dismiss_overlays(drv):
    # Close known banners/sheets (cookie consent, "Got it"/"Not now") and make toasts/masks click-through; wait briefly for them to go.
    n = 0
    try:
        n = drv.execute_script(OVERLAY_JS) or 0
    except Exception:
        pass
    if n:
        time.sleep(0.8)
    return n


def _editor_still_has(drv, editor, text):
    try:
        return editor.is_displayed() and _norm(drv.execute_script("return arguments[0].innerText;", editor)) == _norm(text)
    except Exception:
        return False


def _click_post_once(drv, button, editor, text):
    # One post attempt. A native click that raises ElementClickInterceptedException never reached the button, so (after
    # checking the compose box still holds our text, i.e. nothing was posted) a single JS click on the same button is
    # still that one attempt. Returns (True, method) or (False, result_dict).
    try:
        button.click()
        return True, "native"
    except Exception as e:
        if type(e).__name__ != "ElementClickInterceptedException":
            return False, {"ok": False, "posted": "uncertain", "error": "Clicking Post failed (%s). Check X before trying again." % type(e).__name__}
    time.sleep(0.5)
    if not _editor_still_has(drv, editor, text):
        return False, {"ok": False, "posted": "uncertain", "error": "Post click was intercepted and the compose box changed. Check X before trying again."}
    try:
        drv.execute_script("arguments[0].click();", button)
        return True, "js_fallback"
    except Exception as e:
        return False, {"ok": False, "posted": False, "error": "Post button was covered and the JS click failed (%s); nothing was posted." % type(e).__name__}


def post_x(drv, text, url):
    # One session: open composer (or the status + Reply), insert text, verify, click Post ONCE, verify. Never re-clicks.
    from selenium.webdriver.common.by import By
    from selenium.webdriver.support import expected_conditions as EC
    from selenium.webdriver.support.ui import WebDriverWait
    cur = drv.current_url or ""
    if "/login" in cur or "/i/flow/" in cur:
        return {"ok": False, "error": "NOT_LOGGED_IN: X shows the login page (saved login missing or expired). Call cookie_login once, then retry this same tool."}
    reply = "/status/" in (url or "")
    if reply:
        try:
            btn = WebDriverWait(drv, 25).until(EC.element_to_be_clickable((By.CSS_SELECTOR, 'article[data-testid="tweet"] [data-testid="reply"]')))
            btn.click()
        except Exception:
            return {"ok": False, "posted": False, "error": "Could not find the Reply button on the post.", "url": drv.current_url}
    editor = None
    end = time.time() + 25
    while editor is None and time.time() < end:
        for sel in X_EDITORS:
            els = [e for e in drv.find_elements(By.CSS_SELECTOR, sel) if e.is_displayed()]
            if els:
                editor = els[-1] if reply else els[0]
                break
        if editor is None:
            time.sleep(0.4)
    if editor is None:
        cur = drv.current_url or ""
        if "/login" in cur or "/i/flow/" in cur:
            return {"ok": False, "error": "NOT_LOGGED_IN: X shows the login page (saved login missing or expired). Call cookie_login once, then retry this same tool."}
        return {"ok": False, "posted": False, "error": "Compose box did not appear within 25 s.", "url": cur}
    editor.click()
    drv.execute_script("arguments[0].focus();", editor)
    try:
        drv.execute_cdp_cmd("Input.insertText", {"text": text})
    except Exception:
        drv.execute_script("document.execCommand('insertText', false, arguments[0]);", text)
    time.sleep(0.6)
    got = drv.execute_script("return arguments[0].innerText;", editor) or ""
    if _norm(got) != _norm(text):
        return {"ok": False, "posted": False, "error": "Editor text did not match after typing; nothing was posted.", "editor_chars": len(got), "text_chars": len(text)}
    button = None
    end = time.time() + 15
    while button is None and time.time() < end:
        for sel in ('[data-testid="tweetButton"]', '[data-testid="tweetButtonInline"]'):
            for b in drv.find_elements(By.CSS_SELECTOR, sel):
                if b.is_displayed() and b.get_attribute("aria-disabled") != "true" and b.is_enabled():
                    button = b
                    break
            if button is not None:
                break
        if button is None:
            time.sleep(0.4)
    if button is None:
        return {"ok": False, "posted": False, "error": "Post button stayed disabled (too long, or X rejected the text). Nothing was posted."}
    _dismiss_overlays(drv)
    try:
        drv.execute_script("arguments[0].scrollIntoView({block: 'center', inline: 'center'});", button)
        time.sleep(0.3)
    except Exception:
        pass
    clicked, how = _click_post_once(drv, button, editor, text)
    if not clicked:
        return how
    out_click = {"click": how}
    link, closed = "", False
    end = time.time() + 15
    while time.time() < end:
        toast = drv.execute_script(
            "var t=document.querySelector('[data-testid=\"toast\"]');"
            "var a=t&&t.querySelector('a[href*=\"/status/\"]');return a?a.href:'';") or ""
        if toast:
            link = toast
            break
        if not [e for e in drv.find_elements(By.CSS_SELECTOR, X_EDITORS[0]) if e.is_displayed() and _norm(e.text)]:
            closed = True
        if closed and time.time() > end - 10:
            break
        time.sleep(0.5)
    if link:
        return dict(out_click, ok=True, posted=True, post_url=link)
    if closed:
        return dict(out_click, ok=True, posted=True, post_url="", note="Compose box closed (posted), but X did not show the post link. Check the profile for the URL.")
    return dict(out_click, ok=False, posted="uncertain", error="Could not confirm the post. Do NOT retry: check X first to avoid a double post.")


def _save(path, data, binary=False):
    d = os.path.dirname(path)
    if d:
        os.makedirs(d, exist_ok=True)
    with open(path, "wb" if binary else "w", **({} if binary else {"encoding": "utf-8"})) as f:
        f.write(data)
    return os.path.getsize(path)


def main(argv=None):
    ap = argparse.ArgumentParser(prog="verdroid_selenium")
    ap.add_argument("cmd", choices=["open", "source", "shot", "xposts", "xpost"])
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
    ap.add_argument("--text-b64", default=None)
    a = ap.parse_args(argv)
    out = {"ok": True}
    drv = None
    try:
        cookies = _read_cookies(a.cookies)
        drv = make_driver(a.width, a.height)
        info = open_session(drv, a.url, cookies, a.wait, a.wait_for)
        if info is not None:
            out["cookies"] = {k: info[k] for k in ("added", "present", "present_after", "kept_after_load", "names", "method", "failed") if k in info}
            if info.get("state") == "no_cookies":
                out.update(ok=False, code="COOKIES_NOT_SET", error="The browser kept none of the stored cookies, so the page was not loaded.")
                print("$MARKER" + json.dumps(out, ensure_ascii=False))
                return 0
            if page_challenge(drv, a.url):
                out.update(ok=False, code="CHALLENGE", url=drv.current_url, title=drv.title,
                           error="The site showed a bot check (\"Prove your humanity\" / reCAPTCHA) instead of the page: blocked, not empty.")
                print("$MARKER" + json.dumps(out, ensure_ascii=False))
                return 0
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
        elif a.cmd == "xpost":
            import base64
            out.update(post_x(drv, base64.b64decode(a.text_b64 or "").decode("utf-8"), a.url))
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
