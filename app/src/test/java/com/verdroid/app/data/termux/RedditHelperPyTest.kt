package com.verdroid.app.data.termux

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Runs the real Python helper's Reddit path against a fake Selenium driver (skipped when python3 isn't installed):
 * every stored cookie is set over CDP on .reddit.com BEFORE the first navigation, and a challenge page is CHALLENGE, not empty.
 */
class RedditHelperPyTest {
    private fun python(): String? = listOf("python3", "python").firstOrNull { p ->
        runCatching { ProcessBuilder(p, "--version").redirectErrorStream(true).start().waitFor() == 0 }.getOrDefault(false)
    }

    private fun run(harness: String): String {
        val py = python(); assumeTrue("python3 not available", py != null)
        val dir = Files.createTempDirectory("vd-py").toFile()
        val src = VerdroidSeleniumPy.SOURCE.replace("\$MARKER", VerdroidSeleniumPy.MARKER)
        File(dir, "verdroid_selenium.py").writeText(src)
        File(dir, "t.py").writeText(fake.trimIndent() + "\n" + harness.trimIndent())
        val p = ProcessBuilder(py, "t.py").directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor()
        assertEquals(out, 0, p.exitValue())
        return out
    }

    private val fake = """
        import time, verdroid_selenium as v
        time.sleep = lambda s: None
        class D:
            def __init__(self, page):
                self.log, self.jar, self.page, self.title = [], [], page, page.get("title", "")
            def execute_cdp_cmd(self, cmd, args):
                self.log.append(("cdp", cmd, args.get("name")))
                if cmd == "Network.setCookie":
                    self.jar.append(args); return {"success": True}
                if cmd == "Network.getCookies": return {"cookies": [{"name": c["name"]} for c in self.jar]}
                return {}
            def get(self, url): self.log.append(("get", url, None))
            def get_cookies(self): return [{"name": c["name"]} for c in self.jar]
            def refresh(self): pass
            def execute_script(self, js, *a):
                if "outerHTML" in js: return self.page.get("html", "")
                if "querySelectorAll(arguments[0]).length" in js and "return {" not in js: return self.page.get("found", 0)
                return {"found": self.page.get("found", 0), "title": self.title, "text": self.page.get("text", ""),
                        "path": self.page.get("path", "/search/"), "captcha": self.page.get("captcha", False)}
        cookies = [{"name": n, "value": "SECRET" + n, "domain": "reddit.com", "path": "/x", "secure": False, "httpOnly": n == "token_v2"}
                   for n in ["edgebucket", "loid", "csrf_token", "csv", "token_v2", "session_tracker", "eu_cookie_opted", "g_state"]]
    """

    @Test fun allCookiesAreSetBeforeTheFirstNavigation() {
        val out = run("""
            d = D({"found": 3, "html": "<shreddit-post></shreddit-post>"})
            r = v.reddit_fetch(d, "https://www.reddit.com/search/?q=x", cookies, "shreddit-post", None, 5, 1, 1000)
            first_get = next(i for i, e in enumerate(d.log) if e[0] == "get")
            sets = [i for i, e in enumerate(d.log) if e[1] == "Network.setCookie"]
            assert len(sets) == 8 and max(sets) < first_get, d.log
            assert d.log.index(("cdp", "Network.enable", None)) < min(sets)
            assert all(c["domain"] == ".reddit.com" and c["path"] == "/" and c["secure"] for c in d.jar)
            assert [c["httpOnly"] for c in d.jar if c["name"] == "token_v2"] == [True]
            assert r["cookies"]["added"] == 8 and r["cookies"]["present"] == 8 and r["state"] == "ready", r["cookies"]
            assert "SECRET" not in str(r["cookies"])
            print("OK")
        """)
        assertTrue(out, out.trim().endsWith("OK"))
    }

    @Test fun challengePageIsChallengeNotEmpty() {
        val out = run("""
            d = D({"found": 0, "title": "Reddit - Prove your humanity", "html": "<script src='https://www.google.com/recaptcha/enterprise.js'></script>"})
            assert v.reddit_fetch(d, "https://www.reddit.com/search/?q=x", cookies, "shreddit-post", None, 5, 1, 1000)["state"] == "challenge"
            d = D({"found": 0, "text": "Hm... we couldn't find any results", "html": "<div class='grecaptcha-badge'></div>"})
            assert v.reddit_fetch(d, "https://www.reddit.com/search/?q=x", cookies, "shreddit-post", None, 5, 1, 1000)["state"] == "challenge"
            d = D({"found": 0, "title": "zzqx - Reddit Search!", "text": "Hm... we couldn't find any results for zzqx", "html": "<main></main>"})
            assert v.reddit_fetch(d, "https://www.reddit.com/search/?q=zzqx", cookies, "shreddit-post", None, 5, 1, 1000)["state"] == "empty"
            assert v.reddit_state({"found": 0, "captcha": True}) == "challenge"
            print("OK")
        """)
        assertTrue(out, out.trim().endsWith("OK"))
    }

    @Test fun noCookiesSetMeansNoNavigation() {
        val out = run("""
            class Bad(D):
                def execute_cdp_cmd(self, cmd, args):
                    if cmd == "Network.setCookie": return {"success": False}
                    return D.execute_cdp_cmd(self, cmd, args)
            d = Bad({"found": 3})
            r = v.reddit_fetch(d, "https://www.reddit.com/", cookies, "shreddit-post", None, 5, 1, 1000)
            assert r["state"] == "no_cookies" and not any(e[0] == "get" for e in d.log), r
            print("OK")
        """)
        assertTrue(out, out.trim().endsWith("OK"))
    }
}
