package com.verdroid.app.data.termux

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Runs the real Python helper against a fake Selenium (skipped when python3 isn't installed): reddit_search/read (rhtml),
 * cookie_open (open) and make_driver(cookies_for=…) share one launch + CDP cookie injection before the first navigation,
 * and a challenge page is CHALLENGE, never empty.
 */
class RedditHelperPyTest {
    private fun python(): String? = listOf("python3", "python").firstOrNull { p ->
        runCatching { ProcessBuilder(p, "--version").redirectErrorStream(true).start().waitFor() == 0 }.getOrDefault(false)
    }

    private val fake = """
        import sys, types, json, os, time, tempfile
        LOG, SLEEPS = [], []
        time.sleep = lambda s: (LOG.append(("sleep", s, None)), SLEEPS.append(s))
        PAGE = {"found": 3, "html": "<shreddit-post></shreddit-post>"}
        class Opts:
            def __init__(self): self.args, self.exp, self.binary_location = [], {}, None
            def add_argument(self, a): self.args.append(a)
            def add_experimental_option(self, k, v): self.exp[k] = v
        class D:
            def __init__(self, service=None, options=None):
                LOG.append(("launch", sorted(options.args), json.dumps(options.exp, sort_keys=True)))
                self.jar, self.title, self.current_url = [], PAGE.get("title", ""), ""
            def set_page_load_timeout(self, t): pass
            def execute_cdp_cmd(self, cmd, args):
                LOG.append(("cdp", cmd, sorted(c["name"] for c in args.get("cookies", []))))
                if cmd == "Network.setCookies": self.jar += args["cookies"]
                if cmd == "Network.getCookies": return {"cookies": [{"name": c["name"]} for c in self.jar]}
                return {}
            def get(self, url): LOG.append(("get", url, None)); self.current_url = url
            def refresh(self): pass
            def quit(self): LOG.append(("quit", None, None))
            def get_cookies(self): return []
            def execute_script(self, js, *a):
                LOG.append(("js", None, None))
                if "navigator.userAgent" in js: return "Mozilla/5.0 HeadlessChrome/130.0"
                if "outerHTML" in js: return PAGE.get("html", "")
                if "shreddit-post')).slice" in js: return [{"title": "t"}]
                if "querySelectorAll(arguments[0]).length" in js and "return {" not in js: return PAGE.get("found", 0)
                if "innerText" in js and "return {" not in js: return "text"
                if "querySelectorAll('a[href]')" in js: return []
                return {"found": PAGE.get("found", 0), "title": PAGE.get("title", ""), "text": PAGE.get("text", ""),
                        "path": PAGE.get("path", "/search/"), "captcha": PAGE.get("captcha", False)}
        wd = types.ModuleType("selenium.webdriver"); wd.ChromeOptions = Opts; wd.Chrome = D
        svc = types.ModuleType("selenium.webdriver.chrome.service"); svc.Service = lambda *a, **k: None
        sel = types.ModuleType("selenium"); sel.webdriver = wd
        sys.modules.update({"selenium": sel, "selenium.webdriver": wd, "selenium.webdriver.chrome": types.ModuleType("c"),
                            "selenium.webdriver.chrome.service": svc})
        import verdroid_selenium as v
        NAMES = ["edgebucket", "loid", "csrf_token", "csv", "token_v2", "session_tracker", "eu_cookie_opted", "g_state"]
        COOKIES = [{"name": n, "value": "SECRET" + n, "domain": ".reddit.com", "path": "/", "secure": True, "httpOnly": n == "token_v2"} for n in NAMES]
        def ckfile():
            f = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False); json.dump(COOKIES, f); f.close(); return f.name
        def until_get():
            i = next(i for i, e in enumerate(LOG) if e[0] == "get")
            return LOG[:i]
        def run(argv):
            del LOG[:]
            import io, contextlib
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf): v.main(argv)
            line = [l for l in buf.getvalue().splitlines() if l.startswith(v.__dict__.get("MARK", "VERDROID_JSON="))][-1]
            return json.loads(line.split("=", 1)[1])
    """

    private fun run(body: String): String {
        val py = python(); assumeTrue("python3 not available", py != null)
        val dir = Files.createTempDirectory("vd-py").toFile()
        File(dir, "verdroid_selenium.py").writeText(VerdroidSeleniumPy.SOURCE.replace("\$MARKER", VerdroidSeleniumPy.MARKER))
        File(dir, "t.py").writeText(fake.trimIndent() + "\n" + body.trimIndent() + "\nprint('OK')\n")
        val p = ProcessBuilder(py, "t.py").directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor()
        assertEquals(out, 0, p.exitValue()); assertTrue(out, out.trim().endsWith("OK"))
        return out
    }

    @Test fun redditToolsUseCookieOpensExactLaunchAndCookiePath() {
        run("""
            f = ckfile(); o = run(["open", "https://www.reddit.com/search/?q=x", "--wait", "6", "--cookies", f]); a = until_get()
            assert not os.path.exists(f)
            f = ckfile(); r = run(["rhtml", "https://www.reddit.com/search/?q=x", "--wait", "6", "--cap", "15", "--cookies", f]); b = until_get()
            assert not os.path.exists(f)
            assert a == b, (a, b)
            assert [e for e in a if e[0] == "launch"] and ("cdp", "Network.setCookies", sorted(NAMES)) in a, a
            assert "--headless=new" in a[0][1] and "--disable-blink-features=AutomationControlled" in a[0][1]
            cdp = [e[1] for e in a if e[0] == "cdp"]
            assert "Network.setUserAgentOverride" in cdp and cdp.index("Network.enable") < cdp.index("Network.setCookies")
            assert r["cookies"]["added"] == 8 and r["cookies"]["present"] == 8 and r["state"] == "ready", r
            assert "SECRET" not in json.dumps(r["cookies"]) and "SECRET" not in json.dumps(o.get("cookies"))
        """)
    }

    @Test fun redditWaitsPassivelyBeforeTouchingThePage() {
        run("""
            run(["rhtml", "https://www.reddit.com/search/?q=x", "--wait", "6", "--cookies", ckfile()])
            g = next(i for i, e in enumerate(LOG) if e[0] == "get")
            assert LOG[g + 1] == ("sleep", 6.0, None), LOG[g:g + 3]
        """)
    }

    @Test fun challengePageIsChallengeNotEmpty() {
        run("""
            PAGE.clear(); PAGE.update({"found": 0, "title": "Reddit - Prove your humanity", "html": "<script src='https://www.google.com/recaptcha/enterprise.js'></script>"})
            assert run(["rhtml", "https://www.reddit.com/search/?q=x", "--cookies", ckfile()])["state"] == "challenge"
            PAGE.clear(); PAGE.update({"found": 0, "text": "Hm... we couldn't find any results", "html": "<div class='grecaptcha-badge'></div>"})
            assert run(["rhtml", "https://www.reddit.com/search/?q=x", "--cookies", ckfile()])["state"] == "challenge"
            PAGE.clear(); PAGE.update({"found": 0, "title": "zzqx - Reddit Search!", "text": "Hm... we couldn't find any results for zzqx", "html": "<main></main>"})
            assert run(["rhtml", "https://www.reddit.com/search/?q=zzqx", "--cookies", ckfile()])["state"] == "empty"
            assert v.reddit_state({"found": 0, "captcha": True}) == "challenge"
        """)
    }

    @Test fun makeDriverCookiesForInjectsStoredCookiesBeforeFirstGetAndReusesOneDriver() {
        run("""
            f = ckfile(); os.environ["VERDROID_COOKIES"] = f
            drv = v.make_driver(cookies_for="reddit.com")
            assert not os.path.exists(f) and "VERDROID_COOKIES" not in os.environ
            assert ("cdp", "Network.setCookies", sorted(NAMES)) in LOG and not any(e[0] == "get" for e in LOG)
            assert drv.verdroid_cookies["added"] == 8 and "SECRET" not in json.dumps(drv.verdroid_cookies)
            for q in ("a", "b"):
                r = v.reddit_posts(drv, "https://www.reddit.com/search/?q=" + q)
                assert r["state"] == "ready" and r["posts"] == [{"title": "t"}], r
            assert [e[0] for e in LOG].count("launch") == 1
            try:
                v.make_driver(cookies_for="x.com"); raise SystemExit("expected no cookies for x.com")
            except RuntimeError as e:
                assert "cookies_for" in str(e)
        """)
    }
}
