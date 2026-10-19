package com.verdroid.app.data.cookies

import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder

/**
 * Reddit reads for reddit_search / reddit_read (pure, unit-tested): builds www.reddit.com .json URLs and turns the
 * listing JSON the logged-in headless browser fetched into compact text (posts + top comments). Nothing is saved.
 */
object RedditJson {
    /** Logged-in marker: reddit_session is only set after a real login (token_v2 / loid / csv exist for guests too). */
    const val LOGIN_COOKIE = "reddit_session"
    val SORTS = setOf("relevance", "hot", "top", "new", "comments")
    val TIMES = setOf("hour", "day", "week", "month", "year", "all")
    private val SUB = Regex("^[A-Za-z0-9_]{2,21}$")

    fun forReddit(cookies: List<BrowserCookie>): List<BrowserCookie> =
        cookies.filter { BrowserCookies.siteOf(it.host) == "reddit.com" }.map { it.copy(domain = ".reddit.com") }.distinctBy { it.name to it.path }

    /** Synthetic cookie "name" the login screen adds when token_v2 belongs to a user (names-only login detection). */
    const val USER_TOKEN_MARK = "token_v2(user)"

    /**
     * Logged in = a reddit_session cookie, or a token_v2 JWT issued to a user (payload sub "user" or lid "t2_…"). Logged-out
     * visitors also get token_v2, but with sub "loid", so token_v2 alone is not enough.
     */
    fun loggedIn(cookies: List<BrowserCookie>): Boolean = forReddit(cookies).any {
        (it.name == LOGIN_COOKIE && it.value.isNotBlank()) || (it.name == "token_v2" && isUserToken(it.value))
    }

    /** Login detection from cookie (name, value) pairs; adds [USER_TOKEN_MARK] for a user token_v2. Values never leave here. */
    fun loginNames(pairs: List<Pair<String, String>>): Set<String> =
        pairs.map { it.first }.toSet() + (if (pairs.any { it.first == "token_v2" && isUserToken(it.second) }) setOf(USER_TOKEN_MARK) else emptySet())

    fun isLoggedInNames(names: Set<String>): Boolean = LOGIN_COOKIE in names || USER_TOKEN_MARK in names

    /** token_v2 JWT payload: sub == "user" or lid starting with t2_ (account id) = logged in; sub "loid" = guest. */
    fun isUserToken(jwt: String?): Boolean {
        val part = jwt?.split('.')?.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return false
        val json = runCatching { String(java.util.Base64.getUrlDecoder().decode(part.trimEnd('=').let { it + "=".repeat((4 - it.length % 4) % 4) })) }
            .getOrNull() ?: return false
        val o = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return false
        val sub = (o["sub"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val lid = (o["lid"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return sub == "user" || sub.startsWith("t2_") || lid.startsWith("t2_")
    }

    fun subreddit(raw: String?): String? = raw?.trim()?.removePrefix("/")?.removePrefix("r/")?.removePrefix("/")?.trimEnd('/')
        ?.takeIf { it.isNotEmpty() }?.takeIf { SUB.matches(it) }

    /** Search (or, with no query, a subreddit listing). Null = invalid input. */
    fun searchUrl(query: String?, subreddit: String?, sort: String?, time: String?, limit: Int): String? {
        val q = query?.trim().orEmpty()
        val sub = subreddit?.let { subreddit(it) ?: return null }
        val t = time?.trim()?.lowercase()?.takeIf { it in TIMES }
        val n = limit.coerceIn(1, 50)
        if (q.isEmpty()) {
            sub ?: return null
            val so = sort?.trim()?.lowercase()?.takeIf { it in setOf("hot", "new", "top", "rising") } ?: "hot"
            return "https://www.reddit.com/r/$sub/$so.json?limit=$n&raw_json=1" + (if (so == "top" && t != null) "&t=$t" else "")
        }
        val so = sort?.trim()?.lowercase()?.takeIf { it in SORTS } ?: "relevance"
        val base = if (sub != null) "https://www.reddit.com/r/$sub/search.json?restrict_sr=1&" else "https://www.reddit.com/search.json?"
        return base + "q=" + URLEncoder.encode(q, "UTF-8") + "&sort=$so" + (t?.let { "&t=$it" } ?: "") + "&limit=$n&raw_json=1&type=link"
    }

    /** A post permalink (reddit.com / old. / new. / m. / redd.it/<id>) → its .json URL; null = not a Reddit post link. */
    fun readUrl(url: String?, comments: Int): String? {
        val u = runCatching { URI(url?.trim().orEmpty()) }.getOrNull() ?: return null
        val host = u.host?.lowercase() ?: return null
        val n = comments.coerceIn(0, 100)
        val path: String = when {
            host == "redd.it" -> u.path.trim('/').takeIf { Regex("^[a-z0-9]{4,12}$").matches(it) }?.let { "/comments/$it" } ?: return null
            host == "reddit.com" || host.endsWith(".reddit.com") -> u.path.orEmpty().trimEnd('/').removeSuffix(".json")
                .takeIf { it.contains("/comments/") } ?: return null
            else -> return null
        }
        return "https://www.reddit.com$path.json?limit=$n&depth=2&sort=top&raw_json=1"
    }

    data class Post(val title: String, val sub: String, val author: String, val score: Int, val comments: Int,
                    val permalink: String, val link: String, val text: String, val created: Long)
    data class Comment(val author: String, val score: Int, val body: String, val depth: Int)

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 0
    private fun children(listing: JsonElement?): List<JsonObject> =
        ((listing as? JsonObject)?.get("data") as? JsonObject)?.get("children")?.let { it as? JsonArray }?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun post(d: JsonObject) = Post(d.s("title"), d.s("subreddit"), d.s("author"), d.i("score"), d.i("num_comments"),
        "https://www.reddit.com" + d.s("permalink"), d.s("url"), d.s("selftext"), d.i("created_utc").toLong())

    /** Posts of a search / subreddit listing. Throws on non-JSON (e.g. a block page). */
    fun parseListing(raw: String): List<Post> {
        val e = Json.parseToJsonElement(raw.trim()) as? JsonObject
        require(e?.get("data") is JsonObject) { "not a Reddit listing" }
        return children(e).filter { it.s("kind") == "t3" }.mapNotNull { it["data"] as? JsonObject }.map(::post)
    }

    /** (post, comments flattened depth-first, top-level first). Throws on non-JSON. */
    fun parseThread(raw: String, maxComments: Int): Pair<Post?, List<Comment>> {
        val arr = Json.parseToJsonElement(raw.trim()) as? JsonArray ?: error("not a thread")
        val p = children(arr.getOrNull(0)).firstOrNull { it.s("kind") == "t3" }?.get("data")?.let { it as? JsonObject }?.let(::post)
        val out = mutableListOf<Comment>()
        fun walk(list: List<JsonObject>, depth: Int) {
            for (c in list) {
                if (out.size >= maxComments) return
                if (c.s("kind") != "t1") continue
                val d = c["data"] as? JsonObject ?: continue
                out += Comment(d.s("author"), d.i("score"), d.s("body"), depth)
                if (depth < 2) walk(children(d["replies"]), depth + 1)
            }
        }
        walk(children(arr.getOrNull(1)), 0)
        return p to out
    }

    fun formatPosts(posts: List<Post>, snippet: Int = 300): String = posts.mapIndexed { i, p ->
        buildString {
            append("Post ${i + 1}: r/${p.sub} · u/${p.author} · ${p.score} points · ${p.comments} comments\n")
            append(p.title).append('\n')
            if (p.text.isNotBlank()) append(p.text.replace(Regex("\\s+"), " ").take(snippet)).append(if (p.text.length > snippet) "…\n" else "\n")
            append(p.permalink)
            if (p.link.isNotBlank() && !p.link.contains("reddit.com/") && p.link != p.permalink) append("\nLink: ").append(p.link)
        }
    }.joinToString("\n\n")

    fun formatThread(p: Post?, comments: List<Comment>, maxPostChars: Int = 6_000): String = buildString {
        if (p != null) {
            append("r/${p.sub} · u/${p.author} · ${p.score} points · ${p.comments} comments\n")
            append(p.title).append('\n')
            if (p.text.isNotBlank()) append(p.text.take(maxPostChars)).append('\n')
            if (p.link.isNotBlank() && !p.link.contains("reddit.com/")) append("Link: ").append(p.link).append('\n')
        }
        if (comments.isNotEmpty()) append("\nTop comments:\n")
        comments.forEach { c -> append("  ".repeat(c.depth)).append("- u/${c.author} (${c.score}): ").append(c.body.replace(Regex("\\s+"), " ").take(1_000)).append('\n') }
    }.trimEnd()

    /** Fallback the agent can use without the logged-in browser. */
    fun fallbackHint(query: String?, url: String?): String = buildString {
        append("Fall back to web_search (e.g. \"")
        append(query?.takeIf { it.isNotBlank() }?.let { "site:reddit.com $it" } ?: "site:reddit.com …")
        append("\") and web_fetch on old.reddit.com pages or their .json (e.g. ")
        append(url?.replace("https://www.reddit.com", "https://old.reddit.com") ?: "https://old.reddit.com/r/<sub>/search.json?q=…&restrict_sr=1")
        append("); Reddit often blocks these.")
    }
}

/** Tell the user at most once per chat (per app run) how to make Reddit reads work better. */
object RedditHints {
    private val told = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    const val LOGIN = "Log in to Reddit once in Verdroid (Settings → Tools → Logged-in sites → Log in to Reddit) so Reddit searches and threads are read reliably."
    const val CHROMIUM = "Install Termux and the chromium-selenium add-on (Settings → Tools → Available to install) so Verdroid can read Reddit with your login."

    /** The hint for [taskId] the first time (0 = always), else null. */
    fun once(taskId: Long, text: String): String? = if (taskId == 0L || told.add(taskId)) text else null
    fun resetForTest() = told.clear()
}
