package com.verdroid.app.data.cookies

import com.verdroid.app.ui.cookies.CookieLoginBridge
import com.verdroid.app.ui.cookies.LoginDetector
import org.junit.Assert.*
import org.junit.Test

class RedditJsonTest {
    @Test fun loginNeedsRealSessionCookieNotGuestTokens() {
        val guest = listOf(BrowserCookie("token_v2", "g", ".reddit.com"), BrowserCookie("loid", "l", ".reddit.com"))
        assertFalse(RedditJson.loggedIn(guest))
        assertTrue(RedditJson.loggedIn(guest + BrowserCookie("reddit_session", "s", ".www.reddit.com")))
        assertFalse(RedditJson.loggedIn(listOf(BrowserCookie("reddit_session", "s", ".example.com"))))
        assertEquals(".reddit.com", RedditJson.forReddit(guest).first().domain)
        assertEquals("reddit_session", CookieLoginBridge.markerFor("reddit.com"))
    }

    @Test fun urls() {
        assertEquals("https://www.reddit.com/search/?q=pixel+10&type=posts&sort=top&t=week", RedditJson.searchUrl("pixel 10", null, "top", "week"))
        assertEquals("https://www.reddit.com/r/Android/search/?restrict_sr=1&q=pixel&type=posts&sort=relevance",
            RedditJson.searchUrl("pixel", "r/Android", "bogus", "bogus"))
        assertEquals("https://www.reddit.com/r/Android/hot/", RedditJson.searchUrl(null, "/r/Android/", null, null))
        assertEquals("https://www.reddit.com/r/a_b/top/?t=day", RedditJson.searchUrl("", "a_b", "top", "day"))
        assertNull(RedditJson.searchUrl("x", "bad name!", null, null))
        assertNull(RedditJson.searchUrl(" ", null, null, null))
        assertEquals("https://www.reddit.com/r/s/comments/abc/t/", RedditJson.readUrl("https://old.reddit.com/r/s/comments/abc/t/"))
        assertEquals("https://www.reddit.com/r/s/comments/abc/t/", RedditJson.readUrl("https://www.reddit.com/r/s/comments/abc/t.json"))
        assertEquals("https://www.reddit.com/comments/abc12/", RedditJson.readUrl("https://redd.it/abc12"))
        assertNull(RedditJson.readUrl("https://www.reddit.com/r/s/"))
        assertNull(RedditJson.readUrl("https://evilreddit.com/r/s/comments/a/b"))
    }

    @Test fun throttleSpacesCalls() = kotlinx.coroutines.test.runTest {
        var t = 10_000L
        RedditThrottle.await { t }
        val before = testScheduler.currentTime
        RedditThrottle.await { t }
        assertTrue(testScheduler.currentTime - before >= RedditThrottle.MIN_GAP_MS - 1)
    }

    @Test fun hintOncePerChat() {
        RedditHints.resetForTest()
        assertNotNull(RedditHints.once(7, "x")); assertNull(RedditHints.once(7, "x")); assertNotNull(RedditHints.once(8, "x"))
    }

    private fun jwt(payload: String) = "eyJhbGciOiJSUzI1NiJ9." +
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".sig"

    @Test fun tokenV2CountsOnlyWhenIssuedToAUser() {
        assertTrue(RedditJson.isUserToken(jwt("""{"sub":"user","lid":"t2_abc","exp":1}""")))
        assertTrue(RedditJson.isUserToken(jwt("""{"sub":"t2_abc"}""")))
        assertFalse(RedditJson.isUserToken(jwt("""{"sub":"loid","loid":"000"}""")))
        assertFalse(RedditJson.isUserToken("garbage")); assertFalse(RedditJson.isUserToken(null))
        assertTrue(RedditJson.loggedIn(listOf(BrowserCookie("token_v2", jwt("""{"sub":"user"}"""), ".reddit.com"))))
        assertFalse(RedditJson.loggedIn(listOf(BrowserCookie("token_v2", jwt("""{"sub":"loid"}"""), ".reddit.com"))))
        val names = RedditJson.loginNames(listOf("token_v2" to jwt("""{"sub":"user"}"""), "loid" to "x"))
        assertTrue(RedditJson.USER_TOKEN_MARK in names)
        assertFalse(RedditJson.isLoggedInNames(RedditJson.loginNames(listOf("token_v2" to jwt("""{"sub":"loid"}""")))))
    }

    @Test fun guestTokenWithT2LidIsNotALogin() {
        val guest = jwt("""{"sub":"loid","lid":"t2_guest1","aid":"t2_guest1","exp":1}""")
        assertFalse(RedditJson.isUserToken(guest))
        assertFalse(RedditJson.isUserToken(jwt("""{"lid":"t2_x"}""")))          // no sub = guest
        assertFalse(RedditJson.isUserToken(jwt("""{"sub":"","aid":"t2_x"}""")))
        assertTrue(RedditJson.isUserToken(jwt("""{"sub":"user","lid":"t2_me"}""")))
        val guestNames = RedditJson.loginNames(listOf("token_v2" to guest, "loid" to "x", "csv" to "2", "edgebucket" to "e"))
        assertFalse(RedditJson.isLoggedInNames(guestNames))
        assertFalse(RedditJson.loggedIn(listOf(BrowserCookie("token_v2", guest, ".reddit.com"), BrowserCookie("loid", "x", ".reddit.com"))))
    }

    @Test fun blankPageDetection() {
        assertTrue(LoginDetector.isBlank("\"0,4\""))
        assertFalse(LoginDetector.isBlank("\"0,300\"")) // shadow-DOM app: little innerText, many elements
        assertFalse(LoginDetector.isBlank("\"120,10\""))
        assertFalse(LoginDetector.isBlank(null)); assertFalse(LoginDetector.isBlank("null"))
        assertTrue(LoginDetector.blankHint("https://www.reddit.com/login/").contains("old.reddit.com"))
        assertTrue(CookieLoginBridge.isReddit("https://old.reddit.com/login")); assertFalse(CookieLoginBridge.isReddit("https://x.com/"))
    }
}
