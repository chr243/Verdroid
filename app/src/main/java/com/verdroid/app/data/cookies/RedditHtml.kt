package com.verdroid.app.data.cookies

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Parses Reddit's rendered (shreddit) pages from the logged-in headless browser (pure, unit-tested with HTML fixtures).
 * Posts: `shreddit-post` attributes (post-title, permalink, subreddit-prefixed-name, author, score, comment-count,
 * created-timestamp, content-href), else result anchors `a[href*="/comments/"]` with nearby "N votes · N comments".
 * Comments: `shreddit-comment` (author, score, depth) with the `slot="comment"` body. Reddit changes markup often, so
 * everything is attribute/selector based with fallbacks.
 */
object RedditHtml {
    /** Content selectors the helper waits for. */
    const val LIST_SELECTOR = "shreddit-post, a[href*=\"/comments/\"]"
    const val THREAD_SELECTOR = "shreddit-post"
    const val COMMENT_SELECTOR = "shreddit-comment"

    private val PERMALINK = Regex("""/r/([A-Za-z0-9_]+)/comments/([a-z0-9]+)(?:/([^/?#]*))?""")

    fun parsePosts(html: String, max: Int): List<RedditJson.Post> {
        val doc = Jsoup.parse(html, "https://www.reddit.com/")
        val posts = doc.select("shreddit-post").mapNotNull(::fromShredditPost)
        val list = if (posts.isNotEmpty()) posts else fromAnchors(doc)
        return list.distinctBy { key(it.permalink) }.take(max.coerceAtLeast(1))
    }

    fun parseThread(html: String, maxComments: Int): Pair<RedditJson.Post?, List<RedditJson.Comment>> {
        val doc = Jsoup.parse(html, "https://www.reddit.com/")
        val post = doc.selectFirst("shreddit-post")?.let(::fromShredditPost) ?: fromAnchors(doc).firstOrNull()
        val comments = doc.select("shreddit-comment").asSequence().mapNotNull { c ->
            val body = c.children().firstOrNull { it.attr("slot") == "comment" }?.let(::text)
                ?: c.selectFirst("[slot=comment]")?.let(::text) ?: return@mapNotNull null
            if (body.isBlank()) return@mapNotNull null
            RedditJson.Comment(c.attr("author").ifBlank { "[deleted]" }, num(c.attr("score")), body,
                (c.attr("depth").toIntOrNull() ?: 0).coerceAtLeast(0))
        }.take(maxComments.coerceAtLeast(0)).toList()
        return post to comments
    }

    private fun key(permalink: String) = PERMALINK.find(permalink)?.groupValues?.get(2) ?: permalink

    private fun abs(href: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("/") -> "https://www.reddit.com$href"
        else -> href
    }

    private fun text(e: Element): String = e.wholeText().replace(Regex("[ \\t\\x0B\\f\\r]+"), " ").replace(Regex("\\s*\\n\\s*"), "\n").trim()

    private fun fromShredditPost(e: Element): RedditJson.Post? {
        val permalink = e.attr("permalink").ifBlank { e.selectFirst("a[href*=/comments/]")?.attr("href").orEmpty() }
        val title = e.attr("post-title").ifBlank { e.selectFirst("[slot=title]")?.text().orEmpty() }
        if (title.isBlank() && permalink.isBlank()) return null
        val sub = e.attr("subreddit-prefixed-name").removePrefix("r/").ifBlank { PERMALINK.find(permalink)?.groupValues?.get(1).orEmpty() }
        val body = e.selectFirst("[slot=text-body]")?.let(::text).orEmpty()
        val link = e.attr("content-href").takeIf { !it.contains("reddit.com/") && it.isNotBlank() }.orEmpty()
        return RedditJson.Post(title, sub, e.attr("author"), num(e.attr("score")), num(e.attr("comment-count")), abs(permalink), link, body,
            age = e.attr("created-timestamp").take(10))
    }

    /** Search results without shreddit-post: group anchors by post id, take the longest anchor text as the title. */
    private fun fromAnchors(doc: Document): List<RedditJson.Post> {
        val byId = LinkedHashMap<String, MutableList<Element>>()
        doc.select("a[href*=/comments/]").forEach { a ->
            val m = PERMALINK.find(a.attr("href")) ?: return@forEach
            if (a.attr("href").contains("/comment/")) return@forEach // a single comment's link
            byId.getOrPut(m.groupValues[2]) { mutableListOf() } += a
        }
        return byId.mapNotNull { (_, anchors) ->
            val m = PERMALINK.find(anchors.first().attr("href"))!!
            val title = anchors.map { it.text().trim() }.filter { t -> t.isNotEmpty() && !Regex("^\\d+[\\d.,kK]*\\s*(comments?|votes?)$").matches(t) }
                .maxByOrNull { it.length }.orEmpty().ifBlank { anchors.first().attr("aria-label") }
            if (title.isBlank()) return@mapNotNull null
            val box = container(anchors.first())
            val boxText = box?.text().orEmpty()
            val votes = Regex("""([\d.,]+\s*[kKmM]?)\s*(?:votes?|upvotes?|points?)""").find(boxText)?.groupValues?.get(1)
            val comments = Regex("""([\d.,]+\s*[kKmM]?)\s*comments?""").find(boxText)?.groupValues?.get(1)
            val age = box?.selectFirst("faceplate-timeago[ts]")?.attr("ts")?.take(10)
                ?: Regex("""\b(\d+\s*(?:mo|min|h|d|y|w|yr|hr)s?\.?\s*ago)\b""").find(boxText)?.groupValues?.get(1).orEmpty()
            val path = m.value.let { if (it.endsWith("/")) it else "$it/" }
            RedditJson.Post(title, m.groupValues[1], "", num(votes), num(comments), "https://www.reddit.com$path", "", "", age = age)
        }
    }

    /** Nearest ancestor (up to 8 levels) whose text mentions votes/comments: one result's card. */
    private fun container(a: Element): Element? {
        var e: Element? = a
        repeat(8) {
            e = e?.parent() ?: return null
            if (Regex("""\d\s*[kKmM]?\s*(votes?|comments?)""").containsMatchIn(e!!.text())) return e
        }
        return null
    }

    /** Reddit's challenge page (reCAPTCHA Enterprise interstitial), also when the helper's own check missed it. */
    fun isChallenge(html: String?, title: String? = null): Boolean {
        val h = html.orEmpty().lowercase(); val t = title.orEmpty().lowercase()
        return "prove your humanity" in t || "prove your humanity" in h || "recaptcha/enterprise.js" in h || "grecaptcha-badge" in h
    }

    /** "1.2k" → 1200, "48" → 48, "" → 0. */
    fun num(s: String?): Int {
        val t = s?.trim()?.replace(",", "")?.replace(" ", "")?.lowercase().orEmpty()
        if (t.isEmpty()) return 0
        val mult = when { t.endsWith("k") -> 1_000.0; t.endsWith("m") -> 1_000_000.0; else -> 1.0 }
        return ((t.trimEnd('k', 'm').toDoubleOrNull() ?: return 0) * mult).toInt()
    }

    /** Outcome codes for the agent (helper state → code, message). */
    fun triage(state: String?, query: String?): Pair<String, String>? = when (state) {
        "ready", "empty" -> null
        "challenge" -> "CHALLENGE" to "Reddit showed \"Prove your humanity\": the saved login isn't accepted (expired, or guest cookies). " +
            "The user should log in again (Settings → Tools → Logged-in sites → Log in to Reddit, or cookie_login url=https://www.reddit.com/login/)."
        "ip_blocked" -> "IP_BLOCKED" to "Reddit blocked this network (\"blocked by network security\"). Retrying won't help; another network may."
        "rate_limited" -> "RATE_LIMITED" to "Reddit is rate-limiting (too many requests), even after one back-off. Wait a minute or two before trying again."
        "login_gated" -> "LOGIN_GATED" to "Reddit redirected to its login page: the saved login isn't valid. The user should log in again."
        "no_cookies" -> "COOKIES_NOT_SET" to "The headless browser refused the saved Reddit cookies (none were set). The user should log in to Reddit again."
        "timeout" -> "TIMEOUT" to "The Reddit page didn't render any posts in time."
        else -> "FAILED" to "Unexpected Reddit page state: $state"
    }
}
