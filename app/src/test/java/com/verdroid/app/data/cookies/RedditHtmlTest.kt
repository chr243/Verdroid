package com.verdroid.app.data.cookies

import org.junit.Assert.*
import org.junit.Test

/** Fixtures modelled on Reddit's rendered shreddit markup (light DOM attributes + slots, as in outerHTML). */
class RedditHtmlTest {
    private val listing = """<html><head><title>pixel 10 - Reddit Search!</title></head><body>
        <shreddit-post post-title="I regret buying Pixel 10" permalink="/r/PixelUsersIndia/comments/1abcd/i_regret_buying_pixel_10/"
            subreddit-prefixed-name="r/PixelUsersIndia" author="someone" score="48" comment-count="48"
            created-timestamp="2026-09-12T10:00:00.000000+0000" content-href="https://www.reddit.com/r/PixelUsersIndia/comments/1abcd/i_regret_buying_pixel_10/" post-type="text">
          <a slot="full-post-link" href="/r/PixelUsersIndia/comments/1abcd/i_regret_buying_pixel_10/">I regret buying Pixel 10</a>
          <div slot="text-body"><p>Battery is   bad.</p><p>Camera ok.</p></div>
        </shreddit-post>
        <shreddit-post post-title="Pixel 10 after almost a year" permalink="/r/PixelUsersIndia/comments/2efgh/pixel_10_after/"
            subreddit-prefixed-name="r/PixelUsersIndia" author="other" score="1.2k" comment-count="107" content-href="https://example.com/review"></shreddit-post>
        <shreddit-post post-title="dup" permalink="/r/PixelUsersIndia/comments/1abcd/i_regret_buying_pixel_10/" subreddit-prefixed-name="r/PixelUsersIndia"></shreddit-post>
        </body></html>"""

    /** Search page variant without shreddit-post: result cards with anchors and "N votes · N comments". */
    private val searchCards = """<html><body><main>
        <div class="card"><div><a href="/r/PixelUsersIndia/">r/PixelUsersIndia</a> · <faceplate-timeago ts="2026-09-01T00:00:00Z">1mo ago</faceplate-timeago></div>
          <a data-testid="post-title" href="/r/PixelUsersIndia/comments/1abcd/i_regret_buying_pixel_10/">I regret buying Pixel 10</a>
          <div><faceplate-number number="48">48</faceplate-number> votes · <faceplate-number number="48">48</faceplate-number> comments</div></div>
        <div class="card"><div><a href="/r/Android/">r/Android</a> · 8d ago</div>
          <a href="/r/Android/comments/3zz99/pixel_10_review/">Pixel 10 review: great</a>
          <div>2.5k votes · 312 comments</div>
          <a href="/r/Android/comments/3zz99/pixel_10_review/">312 comments</a></div>
        </main></body></html>"""

    private val thread = """<html><body>
        <shreddit-post post-title="Q?" permalink="/r/s/comments/x1/q/" subreddit-prefixed-name="r/s" author="op" score="10" comment-count="3">
          <div slot="text-body">Body text</div></shreddit-post>
        <shreddit-comment-tree>
          <shreddit-comment author="a" score="9" depth="0" thingid="t1_1"><div slot="comment"><p>top</p></div>
            <shreddit-comment author="b" score="2" depth="1" thingid="t1_2"><div slot="comment"><p>reply</p></div></shreddit-comment>
          </shreddit-comment>
          <shreddit-comment author="" score="1" depth="0" thingid="t1_3"><div slot="comment"><p>second</p></div></shreddit-comment>
          <shreddit-comment author="x" score="1" depth="0"><div slot="comment"> </div></shreddit-comment>
        </shreddit-comment-tree></body></html>"""

    @Test fun shredditPostsAreParsedFromAttributes() {
        val posts = RedditHtml.parsePosts(listing, 10)
        assertEquals(2, posts.size)
        val p = posts[0]
        assertEquals("I regret buying Pixel 10", p.title); assertEquals("PixelUsersIndia", p.sub); assertEquals("someone", p.author)
        assertEquals(48, p.score); assertEquals(48, p.comments); assertEquals("2026-09-12", p.age)
        assertEquals("https://www.reddit.com/r/PixelUsersIndia/comments/1abcd/i_regret_buying_pixel_10/", p.permalink)
        assertTrue(p.text, p.text.contains("Battery is bad.")); assertEquals("", p.link)
        assertEquals(1200, posts[1].score); assertEquals("https://example.com/review", posts[1].link)
        val text = RedditJson.formatPosts(posts)
        assertTrue(text, text.startsWith("Post 1: r/PixelUsersIndia · u/someone · 48 points · 48 comments · 2026-09-12\nI regret buying Pixel 10"))
        assertEquals(1, RedditHtml.parsePosts(listing, 1).size)
    }

    @Test fun searchCardsFallBackToAnchors() {
        val posts = RedditHtml.parsePosts(searchCards, 10)
        assertEquals(listOf("I regret buying Pixel 10", "Pixel 10 review: great"), posts.map { it.title })
        assertEquals(48, posts[0].score); assertEquals(48, posts[0].comments); assertEquals("2026-09-01", posts[0].age)
        assertEquals("Android", posts[1].sub); assertEquals(2500, posts[1].score); assertEquals(312, posts[1].comments); assertEquals("8d ago", posts[1].age)
        assertEquals("https://www.reddit.com/r/Android/comments/3zz99/pixel_10_review/", posts[1].permalink)
        assertFalse(RedditJson.formatPosts(posts).contains("u/ ·"))
    }

    @Test fun threadWithNestedComments() {
        val (p, comments) = RedditHtml.parseThread(thread, 10)
        assertEquals("Q?", p!!.title); assertEquals("Body text", p.text)
        assertEquals(listOf(Triple("a", "top", 0), Triple("b", "reply", 1), Triple("[deleted]", "second", 0)), comments.map { Triple(it.author, it.body, it.depth) })
        val t = RedditJson.formatThread(p, comments)
        assertTrue(t, t.contains("- u/a (9): top\n  - u/b (2): reply\n- u/[deleted] (1): second"))
        assertEquals(1, RedditHtml.parseThread(thread, 1).second.size)
    }

    @Test fun emptyShellHasNoPosts() {
        assertTrue(RedditHtml.parsePosts("<html><body><div id='app'></div></body></html>", 5).isEmpty())
        assertNull(RedditHtml.parseThread("<html></html>", 5).first)
    }

    @Test fun triageCodes() {
        assertNull(RedditHtml.triage("ready", "q")); assertNull(RedditHtml.triage("empty", "q"))
        assertEquals("CHALLENGE", RedditHtml.triage("challenge", null)!!.first)
        assertEquals("IP_BLOCKED", RedditHtml.triage("ip_blocked", null)!!.first)
        assertEquals("RATE_LIMITED", RedditHtml.triage("rate_limited", null)!!.first)
        assertEquals("LOGIN_GATED", RedditHtml.triage("login_gated", null)!!.first)
        assertEquals("TIMEOUT", RedditHtml.triage("timeout", null)!!.first)
        assertEquals(1200, RedditHtml.num("1.2k")); assertEquals(0, RedditHtml.num("")); assertEquals(1234, RedditHtml.num("1,234"))
    }
}
