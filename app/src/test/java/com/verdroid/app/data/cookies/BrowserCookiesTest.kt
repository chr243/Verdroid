package com.verdroid.app.data.cookies

import org.junit.Assert.*
import org.junit.Test

class BrowserCookiesTest {
    private val now = 1_700_000_000L

    @Test fun parsesJsonArrayExport() {
        val json = """[{"name":"auth_token","value":"abcdef0123456789","domain":".x.com","path":"/","secure":true,"httpOnly":true,
            "expirationDate":1900000000.5,"sameSite":"no_restriction"},
            {"name":"ct0","value":"zzzz9999","domain":"x.com","session":true}]"""
        val c = BrowserCookies.parse(json, now)
        assertEquals(2, c.size)
        assertEquals("x.com", c[0].host); assertTrue(c[0].secure && c[0].httpOnly)
        assertEquals(1900000000L, c[0].expiry); assertEquals("None", c[0].sameSite)
        assertNull(c[1].expiry); assertEquals("/", c[1].path)
    }

    @Test fun parsesWrappedObjectAndDropsExpired() {
        val c = BrowserCookies.parse("""{"cookies":[{"name":"a","value":"1","domain":"e.com","expiry":10},{"name":"b","value":"2","domain":"e.com"}]}""", now)
        assertEquals(listOf("b"), c.map { it.name })
    }

    @Test fun parsesNetscapeIncludingHttpOnlyPrefix() {
        val txt = "# Netscape HTTP Cookie File\n\n#HttpOnly_.x.com\tTRUE\t/\tTRUE\t1900000000\tauth_token\tsecretvalue\r\n" +
            "x.com\tFALSE\t/i\tFALSE\t0\tlang\ten\n"
        val c = BrowserCookies.parse(txt, now)
        assertEquals(2, c.size)
        assertTrue(c[0].httpOnly); assertEquals("secretvalue", c[0].value); assertEquals(1900000000L, c[0].expiry)
        assertFalse(c[1].httpOnly); assertNull(c[1].expiry); assertEquals("/i", c[1].path)
    }

    @Test fun redactionNeverShowsValue() {
        val c = BrowserCookies.parse("""[{"name":"auth_token","value":"abcdef0123456789","domain":"x.com"},{"name":"s","value":"tiny","domain":"x.com"}]""", now)
        val s = BrowserCookies.summary(c).toString()
        assertFalse(s.contains("abcdef0123456789")); assertFalse(s.contains("tiny"))
        assertFalse(c[0].toString().contains("abcdef0123456789"))
        assertEquals("(empty)", BrowserCookies.redact(""))
    }

    @Test fun scrubRemovesLeakedValues() {
        val c = BrowserCookies.parse("""[{"name":"a","value":"supersecret","domain":"x.com"}]""", now)
        assertEquals("err: [redacted cookie]!", BrowserCookies.scrub("err: supersecret!", c))
    }

    @Test fun hostMatchingAndMerge() {
        val c = BrowserCookies.parse("""[{"name":"a","value":"1","domain":".x.com"},{"name":"b","value":"2","domain":"other.com"}]""", now)
        assertEquals(listOf("a"), BrowserCookies.forHost(c, "mobile.x.com").map { it.name })
        assertTrue(BrowserCookies.forHost(c, "notx.com").isEmpty())
        val merged = BrowserCookies.merge(c, listOf(BrowserCookie("a", "new", "x.com")))
        assertEquals(2, merged.size); assertEquals("new", merged.first { it.name == "a" }.value)
        assertEquals(merged, BrowserCookies.decode(BrowserCookies.encode(merged)))
    }
}
