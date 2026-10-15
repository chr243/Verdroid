package com.verdroid.app.data.cookies

import kotlinx.serialization.json.*

/**
 * Beta: cookies injected into the headless Chromium (cookie_* tools) for reading logged-in pages. Cookies are secrets:
 * stored only in [CookieVault] (EncryptedSharedPreferences on the phone), never logged, never echoed unredacted.
 * Pure logic (parsing, matching, redaction) lives here so it is unit-tested.
 */
data class BrowserCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    /** Unix seconds; null = session cookie. */
    val expiry: Long? = null,
    /** "Strict", "Lax" or "None"; null = browser default. */
    val sameSite: String? = null,
) {
    /** Domain without a leading dot, lowercased. */
    val host: String get() = domain.trim().trimStart('.').lowercase()
    override fun toString() = "BrowserCookie(name=$name, value=${BrowserCookies.redact(value)}, domain=$domain)"
}

object BrowserCookies {
    /** Short masked form for echoes: never more than 2 characters of the value. */
    fun redact(value: String): String = when {
        value.isEmpty() -> "(empty)"
        value.length <= 6 -> "••••"
        else -> value.take(2) + "…(${value.length} chars, redacted)"
    }

    /** Parses a JSON array (DevTools / EditThisCookie / Cookie-Editor exports, or our own fields) or a Netscape cookies.txt. */
    fun parse(text: String, nowSec: Long = System.currentTimeMillis() / 1000): List<BrowserCookie> {
        val t = text.trim().removePrefix("\uFEFF")
        val all = if (t.startsWith("[") || t.startsWith("{")) parseJson(t) else parseNetscape(t)
        return all.filter { it.name.isNotBlank() && it.host.isNotBlank() && (it.expiry == null || it.expiry > nowSec) }
    }

    fun parseJson(text: String): List<BrowserCookie> {
        val root = Json.parseToJsonElement(text)
        val arr = when (root) {
            is JsonArray -> root
            is JsonObject -> root["cookies"] as? JsonArray ?: throw IllegalArgumentException("JSON must be an array of cookies (or {\"cookies\": [...]})")
            else -> throw IllegalArgumentException("JSON must be an array of cookies")
        }
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(vararg k: String) = k.firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.contentOrNull }
            fun b(vararg k: String) = k.firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.booleanOrNull } ?: false
            val name = s("name") ?: return@mapNotNull null
            val domain = s("domain", "host") ?: return@mapNotNull null
            val session = b("session")
            val exp = if (session) null else s("expiry", "expirationDate", "expires")?.toDoubleOrNull()?.toLong()?.takeIf { it > 0 }
            BrowserCookie(name, s("value") ?: "", domain, s("path") ?: "/", b("secure"), b("httpOnly", "httponly"),
                exp, sameSite(s("sameSite", "samesite")))
        }
    }

    fun parseNetscape(text: String): List<BrowserCookie> = text.lineSequence().mapNotNull { raw ->
        var line = raw.trimEnd('\r')
        var httpOnly = false
        if (line.startsWith("#HttpOnly_")) { httpOnly = true; line = line.removePrefix("#HttpOnly_") }
        if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
        val f = line.split('\t')
        if (f.size < 7) return@mapNotNull null
        BrowserCookie(f[5], f.subList(6, f.size).joinToString("\t"), f[0], f[2].ifBlank { "/" },
            f[3].equals("TRUE", true), httpOnly, f[4].toLongOrNull()?.takeIf { it > 0 })
    }.toList()

    private fun sameSite(v: String?): String? = when (v?.lowercase()) {
        "strict" -> "Strict"; "lax" -> "Lax"; "none", "no_restriction" -> "None"; else -> null
    }

    /** Cookies that apply to [host] (exact host or a parent domain). */
    fun forHost(cookies: List<BrowserCookie>, host: String): List<BrowserCookie> {
        val h = host.lowercase()
        return cookies.filter { h == it.host || h.endsWith("." + it.host) }
    }

    /** Merges [incoming] into [existing]; same (domain, path, name) is replaced. */
    fun merge(existing: List<BrowserCookie>, incoming: List<BrowserCookie>): List<BrowserCookie> {
        fun key(c: BrowserCookie) = Triple(c.host, c.path, c.name)
        val keys = incoming.map(::key).toSet()
        return existing.filter { key(it) !in keys } + incoming
    }

    /** Redacted summary safe for tool results and chat. */
    fun summary(cookies: List<BrowserCookie>): JsonArray = JsonArray(cookies.map { c ->
        buildJsonObject {
            put("name", c.name); put("domain", c.domain); put("path", c.path); put("value", redact(c.value))
            put("secure", c.secure); put("httpOnly", c.httpOnly); c.expiry?.let { put("expiry", it) }
        }
    })

    /** Removes any cookie value (length ≥ 4) that leaked into [text], e.g. in an error or page text. */
    fun scrub(text: String, cookies: List<BrowserCookie>): String =
        cookies.map { it.value }.filter { it.length >= 4 }.distinct().sortedByDescending { it.length }
            .fold(text) { acc, v -> acc.replace(v, "[redacted cookie]") }

    /** Selenium add_cookie payload (JSON) for the Termux helper. */
    fun toSeleniumJson(cookies: List<BrowserCookie>): String = JsonArray(cookies.map { c ->
        buildJsonObject {
            put("name", c.name); put("value", c.value); put("domain", c.domain); put("path", c.path)
            put("secure", c.secure); put("httpOnly", c.httpOnly)
            c.expiry?.let { put("expiry", it) }; c.sameSite?.let { put("sameSite", it) }
        }
    }).toString()

    fun encode(cookies: List<BrowserCookie>): String = toSeleniumJson(cookies)
    fun decode(stored: String?): List<BrowserCookie> = if (stored.isNullOrBlank()) emptyList() else runCatching { parseJson(stored) }.getOrDefault(emptyList())
}

/** Secret store for injected cookies. */
interface CookieVault {
    fun all(): List<BrowserCookie>
    fun save(cookies: List<BrowserCookie>)

    class InMemory(private var list: List<BrowserCookie> = emptyList()) : CookieVault {
        override fun all() = list
        override fun save(cookies: List<BrowserCookie>) { list = cookies }
    }
}
