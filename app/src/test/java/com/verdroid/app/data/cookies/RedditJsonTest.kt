package com.verdroid.app.data.cookies

import com.verdroid.app.ui.cookies.CookieLoginBridge
import com.verdroid.app.ui.cookies.LoginDetector
import org.junit.Assert.*
import org.junit.Test

class RedditJsonTest {
    private val listing = """{"kind":"Listing","data":{"children":[
        {"kind":"t3","data":{"title":"Pixel 10 review","subreddit":"Android","author":"bob","score":1234,"num_comments":56,
         "permalink":"/r/Android/comments/abc123/pixel_10_review/","url":"https://example.com/review","selftext":"Long   text\nhere","created_utc":1700000000.0}},
        {"kind":"t5","data":{"display_name":"notapost"}}]}}"""
    private val thread = """[{"kind":"Listing","data":{"children":[{"kind":"t3","data":{"title":"Q?","subreddit":"s","author":"op","score":10,
        "num_comments":3,"permalink":"/r/s/comments/x1/q/","url":"https://www.reddit.com/r/s/comments/x1/q/","selftext":"Body"}}]}},
        {"kind":"Listing","data":{"children":[
          {"kind":"t1","data":{"author":"a","score":9,"body":"top","replies":{"kind":"Listing","data":{"children":[
             {"kind":"t1","data":{"author":"b","score":2,"body":"reply","replies":""}}]}}}},
          {"kind":"more","data":{"count":5}},
          {"kind":"t1","data":{"author":"c","score":1,"body":"second","replies":""}}]}}]"""

    @Test fun loginNeedsRealSessionCookieNotGuestTokens() {
        val guest = listOf(BrowserCookie("token_v2", "g", ".reddit.com"), BrowserCookie("loid", "l", ".reddit.com"))
        assertFalse(RedditJson.loggedIn(guest))
        assertTrue(RedditJson.loggedIn(guest + BrowserCookie("reddit_session", "s", ".www.reddit.com")))
        assertFalse(RedditJson.loggedIn(listOf(BrowserCookie("reddit_session", "s", ".example.com"))))
        assertEquals(".reddit.com", RedditJson.forReddit(guest).first().domain)
        assertEquals("reddit_session", CookieLoginBridge.markerFor("reddit.com"))
    }

    @Test fun loginDetectorWaitsForRedditSession() {
        assertEquals(LoginDetector.Verdict.NOT_YET, LoginDetector.check("reddit.com", "https://www.reddit.com/", setOf("token_v2", "loid", "csv"), 0, 0))
        assertEquals(LoginDetector.Verdict.DONE, LoginDetector.check("reddit.com", "https://www.reddit.com/", setOf("token_v2", "reddit_session"), 0, 0))
        assertEquals(LoginDetector.Verdict.WAIT, LoginDetector.check("reddit.com", "https://www.reddit.com/login/", setOf("reddit_session"), 0, 0))
    }

    @Test fun urls() {
        assertEquals("https://www.reddit.com/search.json?q=pixel+10&sort=top&t=week&limit=15&raw_json=1&type=link",
            RedditJson.searchUrl("pixel 10", null, "top", "week", 15))
        assertEquals("https://www.reddit.com/r/Android/search.json?restrict_sr=1&q=pixel&sort=relevance&limit=50&raw_json=1&type=link",
            RedditJson.searchUrl("pixel", "r/Android", "bogus", "bogus", 500))
        assertEquals("https://www.reddit.com/r/Android/hot.json?limit=10&raw_json=1", RedditJson.searchUrl(null, "/r/Android/", null, null, 10))
        assertEquals("https://www.reddit.com/r/a_b/top.json?limit=5&raw_json=1&t=day", RedditJson.searchUrl("", "a_b", "top", "day", 5))
        assertNull(RedditJson.searchUrl("x", "bad name!", null, null, 5))
        assertNull(RedditJson.searchUrl(" ", null, null, null, 5))
        assertEquals("https://www.reddit.com/r/s/comments/abc/t.json?limit=30&depth=2&sort=top&raw_json=1",
            RedditJson.readUrl("https://old.reddit.com/r/s/comments/abc/t/", 30))
        assertEquals("https://www.reddit.com/comments/abc12.json?limit=0&depth=2&sort=top&raw_json=1", RedditJson.readUrl("https://redd.it/abc12", -3))
        assertNull(RedditJson.readUrl("https://www.reddit.com/r/s/", 10))
        assertNull(RedditJson.readUrl("https://evilreddit.com/r/s/comments/a/b", 10))
    }

    @Test fun listingAndThreadFormatting() {
        val posts = RedditJson.parseListing(listing)
        assertEquals(1, posts.size)
        val text = RedditJson.formatPosts(posts)
        assertTrue(text, text.startsWith("Post 1: r/Android · u/bob · 1234 points · 56 comments\nPixel 10 review\nLong text here\n"))
        assertTrue(text.contains("https://www.reddit.com/r/Android/comments/abc123/pixel_10_review/"))
        assertTrue(text.contains("Link: https://example.com/review"))
        val (p, comments) = RedditJson.parseThread(thread, 10)
        assertEquals("Q?", p!!.title)
        assertEquals(listOf("top" to 0, "reply" to 1, "second" to 0), comments.map { it.body to it.depth })
        val t = RedditJson.formatThread(p, comments)
        assertTrue(t, t.contains("- u/a (9): top\n  - u/b (2): reply\n- u/c (1): second"))
        assertFalse("self link not repeated", t.contains("Link:"))
        assertEquals(2, RedditJson.parseThread(thread, 2).second.size)
    }

    @Test(expected = Exception::class) fun blockPageIsNotJson() { RedditJson.parseListing("<html>blocked</html>") }

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
        assertEquals(LoginDetector.Verdict.DONE, LoginDetector.check("reddit.com", "https://www.reddit.com/", names, 0, 0))
        assertFalse(RedditJson.isLoggedInNames(RedditJson.loginNames(listOf("token_v2" to jwt("""{"sub":"loid"}""")))))
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
