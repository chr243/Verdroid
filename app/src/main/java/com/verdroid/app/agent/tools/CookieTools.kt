package com.verdroid.app.agent.tools

import com.verdroid.app.data.cookies.BrowserCookies
import com.verdroid.app.data.cookies.CookieVault
import com.verdroid.app.data.storage.SharedFolder
import com.verdroid.app.data.termux.TermuxRunner
import kotlinx.serialization.json.*
import java.net.URI
import java.util.Base64

/**
 * Beta (Settings → Tools → "Browser cookies (beta)", off by default): inject the user's own cookies into the headless
 * Chromium to read logged-in pages. Values live only in [CookieVault] (encrypted, app-private); tool results show
 * names and redacted values only, and any value that shows up in helper output is scrubbed.
 */
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
    override val description = "$COOKIE_NOTE Remove stored browser cookies for a domain (or all with domain=\"*\"). Only when the user asks."
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
        val cookies = BrowserCookies.forHost(vault.all(), host)
        if (cookies.isEmpty()) return errorJson("No stored cookies for $host. Ask the user to export them to a file in Documents/Verdroid/Input and run cookie_import.")
        val file = "${com.verdroid.app.data.termux.VerdroidSeleniumPy.DIR}/ck-${System.nanoTime()}.json"
        val b64 = Base64.getEncoder().encodeToString(BrowserCookies.toSeleniumJson(cookies).toByteArray())
        val preamble = "rm -f ${com.verdroid.app.data.termux.VerdroidSeleniumPy.DIR}/ck-*.json; (umask 077; echo $b64 | base64 -d > ${TermuxRunTool.shellQuote(file)})\n"
        val cli = listOf("open", url) + commonArgs(args) +
            listOf("--max-chars", (args.int("max_chars") ?: 12_000).coerceIn(500, 60_000).toString(), "--cookies", file)
        return BrowserCookies.scrub(runHelper(cli, 120, null, preamble), cookies)
    }
}
