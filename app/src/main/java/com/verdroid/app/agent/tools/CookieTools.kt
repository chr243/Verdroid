package com.verdroid.app.agent.tools

import com.verdroid.app.data.cookies.RedditThrottle
import com.verdroid.app.data.cookies.RedditHints
import com.verdroid.app.data.cookies.RedditBlock
import com.verdroid.app.data.cookies.RedditJson
import com.verdroid.app.data.cookies.BrowserCookies
import com.verdroid.app.data.cookies.CookieVault
import com.verdroid.app.data.storage.SharedFolder
import com.verdroid.app.data.termux.TermuxRunner
import kotlinx.serialization.json.*
import java.net.URI
import java.util.Base64

/**
 * Beta (Settings → Tools → Web, cookie_* off by default; Logged-in sites section): inject the user's own cookies into the headless
 * Chromium to read logged-in pages. Values live only in [CookieVault] (encrypted, app-private); tool results show
 * names and redacted values only, and any value that shows up in helper output is scrubbed.
 */
/** Shell that writes [cookies] to a private (0600) temp file in Termux for the helper's --cookies; returns (path, shell). */
/** The stored cookies the browser gets for [host]: one rule for cookie_open, reddit_* and termux_python cookies_for. */
internal fun cookiesForHost(all: List<com.verdroid.app.data.cookies.BrowserCookie>, host: String) =
    if (BrowserCookies.siteOf(host) in setOf("x.com", "twitter.com")) BrowserCookies.forX(all) else BrowserCookies.forHost(all, host)

internal fun cookiePreamble(cookies: List<com.verdroid.app.data.cookies.BrowserCookie>): Pair<String, String> {
    val dir = com.verdroid.app.data.termux.VerdroidSeleniumPy.DIR
    val file = "$dir/ck-${System.nanoTime()}.json"
    val b64 = Base64.getEncoder().encodeToString(BrowserCookies.toSeleniumJson(cookies).toByteArray())
    return file to "rm -f $dir/ck-*.json; (umask 077; echo $b64 | base64 -d > ${TermuxRunTool.shellQuote(file)})\n"
}

internal const val COOKIE_NOTE_X = "Beta, for testing logged-in reads. Cookie values are secrets: never print, repeat or save them."
private const val COOKIE_NOTE = "Beta, for testing logged-in reads. Cookie values are secrets: never print, repeat or save them."

class CookieImportTool(private val folder: SharedFolder, private val vault: CookieVault) : AgentTool {
    override val name = "cookie_import"
    override val description = "$COOKIE_NOTE Import browser cookies from a file the user put in Documents/Verdroid (e.g. Input/x.com-cookies.txt): " +
        "a JSON array (Cookie-Editor / EditThisCookie / DevTools export: name, value, domain, path, secure, httpOnly, expiry) or a " +
        "Netscape cookies.txt. Stored encrypted in the app; same domain+path+name is replaced. Optional domain keeps only cookies for it. " +
        "Never ask the user to paste cookies into the chat."
    override val parameters = schema(listOf("path"),
        "path" to prop("string", "File path inside Documents/Verdroid, e.g. Input/x.com-cookies.json"),
        "domain" to prop("string", "Optional: only import cookies for this domain (e.g. x.com)"))

    override suspend fun execute(args: JsonObject): String = try { doImport(args) } catch (e: SecurityException) { errorJson(e.message ?: "Path not allowed") }

    private fun doImport(args: JsonObject): String {
        val rel = args.str("path")?.trim()?.takeIf { it.isNotEmpty() } ?: return errorJson("path is required")
        val f = SharedFolderSandbox(folder).resolve(rel)
        if (!f.isFile) return errorJson("File not found in Documents/Verdroid: $rel")
        if (f.length() > 2_000_000) return errorJson("File too large for a cookie export")
        var cookies = runCatching { BrowserCookies.parse(f.readText()) }.getOrElse { return errorJson("Could not parse cookies: ${it.javaClass.simpleName}") }
        args.str("domain")?.trim()?.removePrefix("https://")?.removePrefix("http://")?.substringBefore('/')?.takeIf { it.isNotEmpty() }?.let { d ->
            cookies = cookies.filter { d.lowercase() == it.host || d.lowercase().endsWith("." + it.host) || it.host.endsWith("." + d.lowercase()) }
        }
        if (cookies.isEmpty()) return errorJson("No valid (unexpired) cookies found in $rel")
        vault.save(BrowserCookies.merge(vault.all(), cookies))
        return buildJsonObject {
            put("ok", true); put("imported", cookies.size)
            put("domains", JsonArray(cookies.map { it.host }.distinct().map(::JsonPrimitive)))
            put("cookies", BrowserCookies.summary(cookies))
            put("note", "Stored encrypted in Verdroid. The file $rel still contains the cookies; suggest the user deletes it (ask first).")
        }.toString()
    }
}

class CookieListTool(private val vault: CookieVault) : AgentTool {
    override val name = "cookie_list"
    override val description = "$COOKIE_NOTE List stored browser cookies (names, domains, redacted values)."
    override val parameters = schema(emptyList(), "domain" to prop("string", "Optional domain filter"))
    override suspend fun execute(args: JsonObject): String {
        val all = vault.all()
        val list = args.str("domain")?.trim()?.takeIf { it.isNotEmpty() }?.let { BrowserCookies.forHost(all, it) } ?: all
        return buildJsonObject { put("count", list.size); put("cookies", BrowserCookies.summary(list)) }.toString()
    }
}

class CookieClearTool(private val vault: CookieVault) : AgentTool {
    override val name = "cookie_clear"
    override val description = "$COOKIE_NOTE Remove stored browser cookies for a domain (or all with domain=\"*\"). Only when the user explicitly asks; never to fix or retry an X tool."
    override val parameters = schema(listOf("domain"), "domain" to prop("string", "Domain such as x.com, or * for all"))
    override suspend fun execute(args: JsonObject): String {
        val d = args.str("domain")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return errorJson("domain is required")
        val all = vault.all()
        val keep = if (d == "*") emptyList() else all.filter { !(it.host == d || it.host.endsWith(".$d")) }
        vault.save(keep)
        return buildJsonObject { put("ok", true); put("removed", all.size - keep.size); put("remaining", keep.size) }.toString()
    }
}

class CookieOpenTool(termux: TermuxRunner, folder: SharedFolder, private val vault: CookieVault) : TermuxSeleniumBase(termux, folder) {
    override val name = "cookie_open"
    override val description = PREFIX + "$COOKIE_NOTE Like selenium_open, but first injects the stored cookies for the URL's site " +
        "(import them with cookie_import, or the user's saved login), so pages behind the user's login can be read. Read-only: never post, like, follow or change settings. " +
        "Also THE tool for Reddit (see REDDIT below). code CHALLENGE = the site showed a bot check (blocked, not empty); " +
        "COOKIES_NOT_SET = the browser refused the stored cookies. $REDDIT"
    override val parameters = schema(listOf("url"), URL_PROP, WAIT_PROP, WAIT_FOR_PROP,
        "max_chars" to prop("integer", "Max characters of visible text (default 12000, max 60000)"))

    override suspend fun execute(args: JsonObject): String = execute(args, ToolContext(0))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
        val raw = url(args) ?: return errorJson("url (http/https) is required")
        val rawHost = runCatching { URI(raw).host }.getOrNull() ?: return errorJson("Bad url")
        val reddit = BrowserCookies.siteOf(rawHost) == "reddit.com" || rawHost.lowercase() == "redd.it"
        // old.reddit / redd.it / .json thread links → the rendered www.reddit.com thread (the others are blocked or login-gated).
        val url = if (reddit) RedditJson.readUrl(raw) ?: raw else raw
        val host = URI(url).host
        if (reddit) {
            val noLogin = !RedditJson.loggedIn(vault.all())
            val noChromium = !termux.isInstalled() || !termux.hasRunCommandPermission()
            if (noLogin || noChromium) return redditFallback(if (noLogin) "NOT_LOGGED_IN" else "NO_CHROMIUM", noLogin, noChromium, url, ctx)
            RedditThrottle.await()
        }
        val cookies = cookiesForHost(vault.all(), host)
        if (cookies.isEmpty()) return errorJson("No stored cookies for $host. Ask the user to export them to a file in Documents/Verdroid/Input and run cookie_import.")
        val (file, preamble) = cookiePreamble(cookies)
        val wait = if (reddit && args.int("wait_seconds") == null) JsonObject(args + ("wait_seconds" to JsonPrimitive(REDDIT_WAIT_S))) else args
        val cli = listOf("open", url) + commonArgs(wait) +
            listOf("--max-chars", (args.int("max_chars") ?: 12_000).coerceIn(500, 60_000).toString(), "--cookies", file)
        val out = BrowserCookies.scrub(runHelper(cli, 120, null, preamble), cookies)
        return if (reddit) redditResult(out, url, ctx) else out
    }

    /** Reddit triage: blocked (CHALLENGE / COOKIES_NOT_SET) is never "empty"; failures carry the web_search fallback. */
    private fun redditResult(out: String, url: String, ctx: ToolContext): String {
        val o = runCatching { Json.parseToJsonElement(out).jsonObject }.getOrNull() ?: return out
        var code = o["code"]?.jsonPrimitive?.contentOrNull
        val ok = o["ok"]?.jsonPrimitive?.booleanOrNull != false
        if (ok && RedditJson.isChallenge(o["text"]?.jsonPrimitive?.contentOrNull?.take(3_000), o["title"]?.jsonPrimitive?.contentOrNull)) code = "CHALLENGE"
        if (!ok && code == null) {
            val err = o["error"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (Regex("not installed|python3 missing|Run commands|did not answer|selenium", RegexOption.IGNORE_CASE).containsMatchIn(err))
                return redditFallback("NO_CHROMIUM", false, true, url, ctx, err)
        }
        if (code == null) return out
        val ck = o["cookies"] as? JsonObject
        fun n(k: String) = (ck?.get(k) as? JsonPrimitive)?.intOrNull
        // Rejected cookies (none / few kept) → re-saving helps; bot check with the cookies accepted → the network/session is refused.
        val kind = if (code == "COOKIES_NOT_SET") RedditBlock.Kind.COOKIES_REJECTED else RedditBlock.classify(n("added"), n("present"), n("kept_after_load"))
        val rejected = kind == RedditBlock.Kind.COOKIES_REJECTED
        if (code == "CHALLENGE" && rejected) code = "COOKIES_REJECTED"
        val error = when (code) {
            "CHALLENGE" -> "Reddit's bot check (\"Prove your humanity\") refused this network/session; the saved Reddit cookies were accepted. Blocked, not empty."
            "COOKIES_REJECTED" -> "Reddit showed its bot check and did not keep the saved Reddit session cookies. Blocked, not empty."
            else -> o["error"]?.jsonPrimitive?.contentOrNull ?: "The browser refused the saved Reddit cookies."
        }
        val hint = if (rejected) RedditHints.RESAVE else RedditHints.BOT_WALL
        return JsonObject(o.filterKeys { it != "text" && it != "links" } + mapOf(
            "ok" to JsonPrimitive(false), "code" to JsonPrimitive(code), "error" to JsonPrimitive(error),
            "fallback" to JsonPrimitive(RedditJson.fallbackHint(null, url)),
        ) + (RedditHints.once(ctx.taskId, hint)?.let { mapOf("tell_user" to JsonPrimitive("In your reply, tell the user briefly (once): $it")) } ?: emptyMap())).toString()
    }

    private fun redditFallback(code: String, noLogin: Boolean, noChromium: Boolean, url: String, ctx: ToolContext, detail: String? = null): String {
        val hint = listOfNotNull(RedditHints.LOGIN.takeIf { noLogin }, RedditHints.CHROMIUM.takeIf { noChromium }).joinToString(" ")
        return buildJsonObject {
            put("ok", false); put("code", code)
            put("error", if (code == "NOT_LOGGED_IN") "No Reddit session saved."
                else "Headless Chromium isn't available (Termux / chromium-selenium add-on)." + (detail?.let { " $it".take(200) } ?: ""))
            put("fallback", RedditJson.fallbackHint(null, url))
            RedditHints.once(ctx.taskId, hint)?.let { put("tell_user", "In your reply, tell the user briefly (once): $it") }
        }.toString()
    }

    companion object {
        const val REDDIT_WAIT_S = 15
        /** How the agent reads Reddit (shared with the system prompt). */
        const val REDDIT = "REDDIT (read-only): for any Reddit search/read call cookie_open FIRST (not web_search), one query per call, " +
            "wait_seconds 15–20 (default 15 for reddit.com): search https://www.reddit.com/search/?q=<urlencoded>&type=posts " +
            "(optional &sort=new|top|comments&t=hour|day|week|month|year|all), one subreddit https://www.reddit.com/r/<sub>/search/?q=<urlencoded>&restrict_sr=1&type=posts, " +
            "a listing https://www.reddit.com/r/<sub>/<hot|new|top|rising>/, or the thread URL (old.reddit/redd.it links are converted). " +
            "On code CHALLENGE, COOKIES_REJECTED, COOKIES_NOT_SET, NOT_LOGGED_IN or NO_CHROMIUM use the fallback (web_search \"site:reddit.com …\"), say briefly why, " +
            "and pass on tell_user if present. Don't web_fetch reddit .json / old.reddit (blocked). No posting, voting or commenting. " +
            "For many queries in one go: termux_python with cookies_for=[\"reddit.com\"], one make_driver(cookies_for=\"reddit.com\") and " +
            "verdroid_selenium.reddit_posts(drv, url) per page (~1 page/s, driver.quit() at the end)."
    }
}

/**
 * Opens the in-app WebView login screen (Settings → Tools → Logged-in sites) and waits until the user taps Done or Cancel.
 * [launch] starts the screen and completes the returned deferred with (saved, sites, count); values never come back.
 */
class CookieLoginTool(
    private val launch: (String) -> kotlinx.coroutines.CompletableDeferred<com.verdroid.app.ui.cookies.CookieLoginResult>?,
    /** A bit longer than the screen's own 10-min timeout, which closes it and answers "timeout". */
    private val timeoutMs: Long = com.verdroid.app.ui.cookies.CookieLoginBridge.TIMEOUT_MS + 15_000L,
) : AgentTool {
    override val name = "cookie_login"
    override val description = "$COOKIE_NOTE Fallback only: for X, call it only after x_timeline/x_user_posts/x_search report NOT_LOGGED_IN (never first or just in case); " +
        "for Reddit only when the user wants to log in (url=${com.verdroid.app.ui.cookies.CookieLoginBridge.REDDIT_URL}). Opens a full-screen in-app browser so the user logs in to a site themselves (default x.com), " +
        "then taps Done; the session cookies are saved encrypted for cookie_open. The screen never closes by itself: only Done, Cancel, back or after 10 min. " +
        "Tell the user to log in and tap Done. logged_in is true only when a real login cookie was found (X auth_token, Reddit reddit_session / user token); " +
        "guest-only cookies on X/Reddit are not saved (the stored ones stay). " +
        "Some sites (e.g. Google) block this kind of login."
    override val parameters = schema(emptyList(), "url" to prop("string", "Login page URL (default https://x.com/i/flow/login)"))

    override suspend fun execute(args: JsonObject): String {
        val url = args.str("url")?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: com.verdroid.app.ui.cookies.CookieLoginBridge.DEFAULT_URL
        val d = launch(url) ?: return errorJson("Could not open the login screen. Ask the user to use Settings → Tools → Logged-in sites → Log in.")
        val r = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { d.await() }
            ?: return buildJsonObject {
                put("ok", false); put("logged_in", false); put("reason", "timeout")
                put("error", "Login screen still open after ${timeoutMs / 60_000} min; nothing was saved. Ask the user to finish logging in and tap Done, then call cookie_list.")
            }.toString()
        return buildJsonObject {
            // logged_in only from a real login check (X auth_token; Reddit reddit_session or a user token_v2), never assumed.
            put("ok", r.saved); put("logged_in", r.saved && r.loggedIn); put("reason", r.reason)
            put("sites", JsonArray(r.sites.map(::JsonPrimitive))); put("cookies_saved", r.count)
            if (r.saved) put("cookie_names", JsonArray(r.names.map(::JsonPrimitive)))
            when (r.reason) {
                "cancelled" -> put("note", "The user cancelled; nothing was saved and the stored cookies are unchanged.")
                "timeout" -> put("note", "The login screen was left open for 10 minutes and closed; nothing was saved.")
                "not_logged_in" -> put("note", "Only guest cookies were found (the user didn't log in); nothing was saved and the stored cookies are unchanged.")
                "no_cookies" -> put("note", "No cookies were found; nothing was saved.")
                "unchecked" -> put("note", "Cookies saved, but this site has no known login cookie, so the login could not be verified.")
            }
        }.toString()
    }
}
