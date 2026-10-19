package com.verdroid.app.data.cookies

import com.verdroid.app.ui.cookies.CookieLoginBridge
import org.junit.Assert.*
import org.junit.Test

class CookieHeaderTest {
    @Test fun parsesHeaderTolerantly() {
        assertEquals(listOf("a" to "1", "b" to "x=y", "flag" to ""), BrowserCookies.parseCookieHeader(" a=1; b=x=y ;; flag; =nameless"))
        assertTrue(BrowserCookies.parseCookieHeader(null).isEmpty())
    }

    @Test fun webViewCookiesAreSessionOnDotDomain() {
        val c = BrowserCookies.fromCookieHeader("x.com", "auth_token=SECRETVALUE99; ct0=abc; auth_token=dup")
        assertEquals(2, c.size)
        assertTrue(c.all { it.domain == ".x.com" && it.path == "/" && it.expiry == null && it.secure && !it.httpOnly })
        assertFalse(BrowserCookies.summary(c).toString().contains("SECRETVALUE99"))
    }

    @Test fun sitesAndSiblings() {
        assertEquals("x.com", BrowserCookies.siteOf("mobile.x.com"))
        assertEquals("example.org", BrowserCookies.siteOf("www.example.org"))
        assertEquals(listOf("x.com", "twitter.com"), BrowserCookies.relatedSites("x.com"))
        assertEquals(listOf("reddit.com"), BrowserCookies.relatedSites("old.reddit.com"))
    }

    @Test fun captureReplacesOldSiteCookiesOnly() {
        val vault = CookieVault.InMemory(listOf(BrowserCookie("old", "o", ".x.com"), BrowserCookie("keep", "k", "y.com")))
        val r = CookieLoginBridge.capture("https://x.com/login", vault) { u -> if ("x.com" in u && "twitter" !in u) "auth_token=T0KENVALUE; ct0=c" else null }
        assertTrue(r.saved); assertEquals(listOf("x.com"), r.sites); assertEquals(2, r.count)
        assertTrue(r.loggedIn); assertEquals(listOf("auth_token", "ct0"), r.names)
        assertEquals(setOf("keep", "auth_token", "ct0"), vault.all().map { it.name }.toSet())
        val none = CookieLoginBridge.capture("https://x.com/", CookieVault.InMemory()) { null }
        assertFalse(none.saved)
    }

    @Test fun webViewUserAgentBecomesChrome() {
        val wv = "Mozilla/5.0 (Linux; Android 14; 23078PND5G Build/UKQ1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/129.0.6668.100 Mobile Safari/537.36"
        assertEquals("Mozilla/5.0 (Linux; Android 14; 23078PND5G Build/UKQ1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.6668.100 Mobile Safari/537.36",
            CookieLoginBridge.chromeUserAgent(wv))
    }

    @Test fun captureMergesSubdomainsAndFlagsGuestOnly() {
        val vault = CookieVault.InMemory()
        val r = CookieLoginBridge.capture("https://x.com/i/flow/login", vault) { u ->
            when (u) { "https://x.com/" -> "guest_id=g; gt=1"; "https://api.x.com/" -> "gt=1; d_prefs=p"; else -> null }
        }
        assertEquals(listOf("d_prefs", "gt", "guest_id"), r.names)
        assertFalse(r.loggedIn); assertFalse(r.saved); assertEquals("not_logged_in", r.reason)
        assertTrue(vault.all().isEmpty())
    }

    private fun jwt(p: String) = "h." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(p.toByteArray()) + ".s"

    @Test fun guestRedditCookiesNeverOverwriteTheStoredOnes() {
        val stored = listOf(BrowserCookie("reddit_session", "OLDSESSION", ".reddit.com"), BrowserCookie("loid", "old", ".reddit.com"))
        val vault = CookieVault.InMemory(stored)
        val guest = "token_v2=" + jwt("""{"sub":"loid","lid":"t2_guest","aid":"t2_guest"}""") + "; loid=g; csv=2; edgebucket=e; session_tracker=s"
        val r = CookieLoginBridge.capture("https://www.reddit.com/login/", vault) { u -> if ("reddit.com" in u) guest else null }
        assertFalse(r.saved); assertFalse(r.loggedIn); assertEquals("not_logged_in", r.reason)
        assertEquals(stored, vault.all())
        val user = "token_v2=" + jwt("""{"sub":"user"}""") + "; loid=g"
        val ok = CookieLoginBridge.capture("https://www.reddit.com/login/", vault) { u -> if ("reddit.com" in u) user else null }
        assertTrue(ok.saved && ok.loggedIn); assertEquals("saved", ok.reason)
        // A site without a known login cookie saves, but is never reported as logged in.
        val other = CookieLoginBridge.capture("https://example.org/login", CookieVault.InMemory()) { u -> if ("example.org" in u) "sid=1" else null }
        assertTrue(other.saved); assertFalse(other.loggedIn); assertEquals("unchecked", other.reason)
    }
}
