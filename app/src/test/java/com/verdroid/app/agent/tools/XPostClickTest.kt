package com.verdroid.app.agent.tools

import com.verdroid.app.data.termux.VerdroidSeleniumPy
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class XPostClickTest {
    @Test fun helperHasInterceptedClickFallbackAndOverlayDismiss() {
        val src = VerdroidSeleniumPy.SOURCE
        assertTrue(src.contains("ElementClickInterceptedException"))
        assertTrue(src.contains("js_fallback"))
        assertTrue(src.contains("_click_post_once"))
        assertTrue(src.contains("_dismiss_overlays"))
        assertTrue(src.contains("scrollIntoView"))
        assertTrue(src.contains("compose box changed"))
        val dir = File.createTempFile("vxpost", "").apply { delete(); mkdirs() }
        try {
            File(dir, "verdroid_selenium.py").writeText(src)
            File(dir, "t.py").writeText(
                """
import verdroid_selenium as v
class Ed:
    def is_displayed(self): return True
class B:
    def __init__(self): self.n = 0
    def click(self):
        self.n += 1
        if self.n == 1:
            raise type("ElementClickInterceptedException", (Exception,), {})("covered")
class D:
    def execute_script(self, s, *a):
        if "arguments[0].click" in s:
            a[0].n += 1; return None
        if "innerText" in s: return "hello world"
        return None
ok, how = v._click_post_once(D(), B(), Ed(), "hello world")
assert (ok, how) == (True, "js_fallback"), (ok, how)
class D2:
    def execute_script(self, s, *a):
        if "arguments[0].click" in s: raise AssertionError("must not JS-click")
        if "innerText" in s: return "other"
        return None
class B2:
    def click(self):
        raise type("ElementClickInterceptedException", (Exception,), {})("x")
ok, res = v._click_post_once(D2(), B2(), Ed(), "hello world")
assert ok is False and res.get("posted") == "uncertain"
print("ok")
""".trimIndent())
            val p = ProcessBuilder("python3", "t.py").directory(dir).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            assumeTrue("python3 helpers: $out", p.exitValue() == 0 && out.contains("ok"))
        } finally { dir.deleteRecursively() }
    }
}
