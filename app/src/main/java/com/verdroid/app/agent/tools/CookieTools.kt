package com.verdroid.app.agent.tools

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
        "(import them with cookie_import), so pages behind the user's login can be read. Read-only: never post, like, follow or change settings."
    override val parameters = schema(listOf("url"), URL_PROP, WAIT_PROP, WAIT_FOR_PROP,
        "max_chars" to prop("integer", "Max characters of visible text (default 12000, max 60000)"))

    override suspend fun execute(args: JsonObject): String {
        val url = url(args) ?: return errorJson("url (http/https) is required")
        val host = runCatching { URI(url).host }.getOrNull() ?: return errorJson("Bad url")
        val cookies = cookiesForHost(vault.all(), host)
        if (cookies.isEmpty()) return errorJson("No stored cookies for $host. Ask the user to export them to a file in Documents/Verdroid/Input and run cookie_import.")
        val (file, preamble) = cookiePreamble(cookies)
        val cli = listOf("open", url) + commonArgs(args) +
            listOf("--max-chars", (args.int("max_chars") ?: 12_000).coerceIn(500, 60_000).toString(), "--cookies", file)
        return BrowserCookies.scrub(runHelper(cli, 120, null, preamble), cookies)
    }
}

/**
 * Opens the in-app WebView login screen (Settings → Tools → Logged-in sites) and waits until the user taps Done or Cancel.
 * [launch] starts the screen and completes the returned deferred with (saved, sites, count); values never come back.
 */
class CookieLoginTool(
    private val launch: (String) -> kotlinx.coroutines.CompletableDeferred<com.verdroid.app.ui.cookies.CookieLoginResult>?,
    private val timeoutMs: Long = 10 * 60_000L,
) : AgentTool {
    override val name = "cookie_login"
    override val description = "$COOKIE_NOTE Fallback only: for X, call it only after x_timeline/x_user_posts/x_search report NOT_LOGGED_IN (never first or just in case); " +
        "for Reddit only when the user wants to log in (url=${com.verdroid.app.ui.cookies.CookieLoginBridge.REDDIT_URL}). Opens a full-screen in-app browser so the user logs in to a site themselves (default x.com), " +
        "then taps Done; the session cookies are saved encrypted for cookie_open. Waits up to 10 min. Tell the user to log in and tap Done. " +
        "Some sites (e.g. Google) block this kind of login."
    override val parameters = schema(emptyList(), "url" to prop("string", "Login page URL (default https://x.com/i/flow/login)"))

    override suspend fun execute(args: JsonObject): String {
        val url = args.str("url")?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: com.verdroid.app.ui.cookies.CookieLoginBridge.DEFAULT_URL
        val d = launch(url) ?: return errorJson("Could not open the login screen. Ask the user to use Settings → Tools → Logged-in sites → Log in.")
        val r = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { d.await() }
            ?: return errorJson("Login screen still open after ${timeoutMs / 60_000} min. Ask the user to tap Done when logged in, then call cookie_list.")
        return buildJsonObject {
            put("ok", r.saved); put("logged_in", r.loggedIn)
            put("sites", JsonArray(r.sites.map(::JsonPrimitive))); put("cookies_saved", r.count)
            put("cookie_names", JsonArray(r.names.map(::JsonPrimitive)))
            if (!r.saved) put("note", "Nothing saved (cancelled, or not logged in).")
        }.toString()
    }
}
