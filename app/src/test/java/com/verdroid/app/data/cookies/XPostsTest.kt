package com.verdroid.app.data.cookies

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class XPostsTest {
    private fun arr(s: String) = Json.parseToJsonElement(s) as JsonArray

    @Test fun parsesAndFormats() {
        val p = XPosts.parse(arr("""[
          {"name":"NASA","handle":"@NASA","text":"Launch\ntoday!","time":"2026-10-15T06:00:00.000Z","rel":"1h","url":"https://x.com/NASA/status/123/analytics",
           "replies":"12 Replies. Reply","reposts":"1,234 reposts. Repost","likes":"5.6K Likes. Like"},
          {"name":"Bob","handle":"@bob","text":"so true","quoted":"original take","rel":"Oct 3","url":"https://x.com/bob/status/9","social":"Alice reposted","likes":"Like"},
          {"raw":"Follow\nSomeone\n·\n12\nAn ad-ish text"},
          {"name":"","handle":"","text":""}
        ]"""))
        assertEquals(3, p.size)
        assertEquals("https://x.com/NASA/status/123", p[0].url)
        val out = XPosts.format(p).lines()
        assertEquals("Post 1: @NASA (NASA) - Launch today! - 1h ago - 12 replies, 1,234 reposts, 5.6K likes - https://x.com/NASA/status/123", out[0])
        assertEquals("Post 2: [Alice reposted] @bob (Bob) - so true | quoting: original take - Oct 3 - https://x.com/bob/status/9", out[1])
        assertEquals("Post 3: (unknown author) - Someone An ad-ish text", out[2])
    }

    @Test fun countsAndNames() {
        assertEquals("", XPosts.count("Reply"))
        assertEquals("1.2M", XPosts.count("1.2M Likes. Like"))
        val taken = setOf("x_timeline_20261015_090000.txt", "x_timeline_20261015_090000_2.txt")
        assertEquals("x_timeline_20261015_090000_3.txt", XPosts.outputName("x_timeline", "20261015_090000") { it in taken })
    }

    @Test fun forXRehomesTwitterCookies() {
        val c = listOf(BrowserCookie("auth_token", "a", "twitter.com"), BrowserCookie("ct0", "c", ".x.com"), BrowserCookie("ct0", "old", ".twitter.com"),
            BrowserCookie("z", "1", "other.com"))
        val x = BrowserCookies.forX(c)
        assertEquals(setOf("auth_token", "ct0"), x.map { it.name }.toSet())
        assertTrue(x.all { it.domain == ".x.com" }); assertEquals("c", x.first { it.name == "ct0" }.value)
    }
}
