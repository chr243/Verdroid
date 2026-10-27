package com.verdroid.app.agent.research

import com.verdroid.app.agent.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder

/*
 * Deep research reliability (test6): source registry, tool-generated bibliography, quote grounding, lenient structured worker
 * output, fetch resilience (Wayback, engine circuit breaker, one query reformulation) and honest diagnostics. Everything here is
 * deterministic code: no model calls.
 */

/** One fetched (or failed) page. [id] is the stable marker the writer cites as [S<id>]. */
data class SourceEntry(
    val id: Int,
    val url: String,
    val finalUrl: String,
    val status: Int,
    val title: String,
    val h1: String,
    val text: String,
    val servedFrom: String = "live",
    val flags: Set<String> = emptySet(),
    /** False when [text] is only part of the page (first markdown page, or cut at [SourceRegistry.MAX_TEXT]). */
    val complete: Boolean = true,
    val disambigReason: String = "",
) {
    val ok: Boolean get() = flags.isEmpty()
    /** Cleaned, tokenized page text for quote grounding (built once per entry). */
    val index: PageIndex by lazy { PageIndex(text) }
    val label: String get() = title.ifBlank { h1 }.ifBlank { finalUrl }
}

object SourceChecks {
    const val DEAD = "dead"
    const val DISAMBIGUATION = "disambiguation"
    const val SEARCH_PAGE = "search_page"
    const val EMPTY = "empty"
    const val MIN_TEXT = 120

    private val DISAMBIG_TITLE = Regex("""\(disambiguation\)\s*(?:[-–—|:].*)?$""", RegexOption.IGNORE_CASE)
    private val MW_MARKERS = listOf("mw-disambig", "disambigbox", "category:disambiguation pages", "category:all disambiguation pages",
        "category:all article disambiguation pages", "\"wgisdisambiguation\":true", "ppprop=\"disambiguation\"")
    private val MAY_REFER = Regex("""\bmay (?:also )?refer to\b""")
    const val DISAMBIG_MAX_BODY = 8_000

    /**
     * Structural only (never the bare word "disambiguation", which hatnotes on real articles contain): title ending in
     * "(disambiguation)", a MediaWiki disambiguation marker (class/category/pageprops) when the HTML is available, or
     * "may refer to" near the top of a short page. Returns the signal, or null for a real article.
     */
    fun disambiguationSignal(title: String, text: String, url: String = ""): String? {
        if (DISAMBIG_TITLE.containsMatchIn(title.trim())) return "title"
        val path = runCatching { java.net.URLDecoder.decode(url.substringBefore('?').substringBefore('#'), "UTF-8") }.getOrDefault(url)
        if (Regex("""[_ ]\(disambiguation\)/?$""", RegexOption.IGNORE_CASE).containsMatchIn(path)) return "title"
        val low = text.lowercase()
        MW_MARKERS.firstOrNull { it in low }?.let { return "mediawiki_marker" }
        val c = clean(text)
        if (MAY_REFER.containsMatchIn(c.take(600)) && c.length < DISAMBIG_MAX_BODY) return "may_refer_to_short_page"
        return null
    }

    fun isDisambiguation(title: String, text: String, url: String = ""): Boolean = disambiguationSignal(title, text, url) != null

    fun flags(url: String, finalUrl: String, status: Int, title: String, text: String, blocked: Boolean): Set<String> = buildSet {
        if (status !in 200..299 || blocked) add(DEAD)
        else if (text.trim().length < MIN_TEXT) add(EMPTY)
        if (ResearchPolicy.isSearchPage(url) || ResearchPolicy.isSearchPage(finalUrl)) add(SEARCH_PAGE)
        if (isDisambiguation(title, text, finalUrl.ifBlank { url })) add(DISAMBIGUATION)
    }

    private val FENCED = Regex("""(?s)<<[^\n]*?nonce="[0-9a-f]+">>\n(.*)\n<</[^\n]*?>>""")
    /** Page text without the untrusted-data preamble that web_fetch wraps around it. */
    fun unfence(content: String): String = FENCED.find(content)?.groupValues?.get(1) ?: content

    fun h1(text: String): String = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim().orEmpty()

    /** Lowercase alphanumerics separated by single spaces (quotes, dashes, markdown and whitespace differences ignored). */
    fun norm(s: String): String = tokens(s).joinToString(" ")

    private val ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ndash" to "-",
        "mdash" to "-", "hellip" to "...", "lsquo" to "'", "rsquo" to "'", "ldquo" to "\"", "rdquo" to "\"", "laquo" to "\"", "raquo" to "\"",
        "thinsp" to " ", "ensp" to " ", "emsp" to " ", "shy" to "", "zwj" to "", "zwnj" to "")
    private val ENTITY = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});""")
    private val FOOTNOTE = Regex("""\[(?:\d{1,4}|[a-z]|[ivx]{1,5}|(?:note|nb|n) ?\d{1,3}|citation needed|clarification needed|failed verification|better source needed|when\?|who\?|which\?|page needed|dubious[^\]]{0,20}|according to whom\?)]""", RegexOption.IGNORE_CASE)
    private val MD_LINK_TEXT = Regex("""!?\[([^\[\]]*)]\((?:[^()\s]|\([^()\s]*\))*\)""")

    fun decodeEntities(s: String): String = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: m.value
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: m.value
            else -> ENTITIES[e.lowercase()] ?: m.value
        }
    }

    /**
     * The one cleaning used on BOTH sides of quote grounding: drop script/style/sup, tags, footnote markers ([12], [a],
     * [citation needed]), markdown link/emphasis syntax; decode HTML entities; NFKC; nbsp → space; curly → straight quotes;
     * dashes → '-'; collapse whitespace; case-fold.
     */
    fun clean(s: String): String {
        var t = s.replace(Regex("""(?is)<(script|style)\b[^>]*>.*?</\1\s*>"""), " ")
            .replace(Regex("""(?is)<sup\b[^>]*>.*?</sup\s*>"""), " ")
            .replace(Regex("""(?s)<[^>]{0,2000}>"""), " ")
        t = decodeEntities(decodeEntities(t)) // &amp;nbsp; → &nbsp; → space
        t = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFKC)
        t = t.replace('\u00A0', ' ').replace('\u202F', ' ').replace("\u200B", "")
            .replace(Regex("""[\u2018\u2019\u201A\u201B\u2032\u00B4`]"""), "'")
            .replace(Regex("""[\u201C\u201D\u201E\u201F\u2033\u00AB\u00BB]"""), "\"")
            .replace(Regex("""[\u2010-\u2015\u2212\uFE58\uFE63\uFF0D]"""), "-")
            .replace("\u2026", "...")
        t = FOOTNOTE.replace(t, "")
        t = MD_LINK_TEXT.replace(t) { it.groupValues[1] }
        t = t.replace(Regex("""[*_#>|~]+"""), " ")
        return t.replace(Regex("""\s+"""), " ").trim().lowercase()
    }

    /** Word tokens of [clean]ed text (punctuation, including quote marks and apostrophes, separates tokens). */
    fun tokens(s: String): List<String> = clean(s).split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }

    const val FUZZY_RATIO = 0.92
    private const val MAX_QUOTE_TOKENS = 120
    private const val MAX_WINDOWS = 40

    data class QuoteCheck(val verified: Boolean, val tier: String = "", val reason: String = "")

    /** Tiered: exact normalized substring → fuzzy token window (ratio ≥ [FUZZY_RATIO]) → every ellipsis fragment. */
    fun checkQuote(quote: String, page: PageIndex): QuoteCheck {
        val q = tokens(quote)
        if (q.size < 3) return QuoteCheck(false, reason = "too_short")
        if (page.tokens.isEmpty()) return QuoteCheck(false, reason = "no_page_text")
        if (page.containsSeq(q)) return QuoteCheck(true, "exact")
        if (fuzzy(q, page) >= FUZZY_RATIO) return QuoteCheck(true, "fuzzy")
        val parts = quote.split("[...]", "[…]", "...", "…").map(::tokens).filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            val real = parts.filter { it.size >= 3 }
            if (real.isNotEmpty() && real.all { page.containsSeq(it) || fuzzy(it, page) >= FUZZY_RATIO }) return QuoteCheck(true, "ellipsis")
            return QuoteCheck(false, reason = "ellipsis_fragment_missing")
        }
        return QuoteCheck(false, reason = "no_window_above_0.92")
    }

    fun checkQuote(quote: String, pageText: String): QuoteCheck = checkQuote(quote, PageIndex(pageText))

    /** Quote is grounded on the page (see [checkQuote]). */
    fun grounded(quote: String, pageText: String): Boolean = checkQuote(quote, pageText).verified

    /**
     * Best difflib-style ratio 2·LCS/(|q|+|w|) over page windows anchored on the quote's rarest tokens. Bounded: ≤[MAX_WINDOWS]
     * anchor positions × 9 window shapes, quotes capped at [MAX_QUOTE_TOKENS] tokens.
     */
    fun fuzzy(q0: List<String>, page: PageIndex): Double {
        val q = q0.take(MAX_QUOTE_TOKENS)
        val m = q.size
        if (m < 3) return 0.0
        if (q.count { it in page.pos }.toDouble() / m < FUZZY_RATIO - 0.1) return 0.0 // too many words absent: cannot reach the ratio
        val anchors = q.withIndex().mapNotNull { (i, t) -> page.pos[t]?.let { i to it } }.sortedBy { it.second.size }.take(3)
        val starts = java.util.TreeSet<Int>()
        for ((i, ps) in anchors) for (p in ps.take(MAX_WINDOWS)) { starts += p - i; if (starts.size >= MAX_WINDOWS) break }
        var best = 0.0
        val n = page.tokens.size
        for (s0 in starts.take(MAX_WINDOWS)) for (ds in -1..1) for (dl in -1..1) {
            val a = (s0 + ds).coerceAtLeast(0); val b = (a + m + dl).coerceAtMost(n)
            if (b - a < 2) continue
            val r = 2.0 * lcs(q, page.tokens, a, b) / (m + (b - a))
            if (r > best) { best = r; if (best >= 0.999) return best }
        }
        return best
    }

    private fun lcs(q: List<String>, t: List<String>, a: Int, b: Int): Int {
        val w = b - a
        var prev = IntArray(w + 1); var cur = IntArray(w + 1)
        for (i in 1..q.size) {
            for (j in 1..w) cur[j] = if (q[i - 1] == t[a + j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
            val x = prev; prev = cur; cur = x; cur.fill(0)
        }
        return prev[w]
    }
}

/** Cleaned token stream of one page with a token → positions index (for fuzzy windows). */
class PageIndex(text: String) {
    val tokens: List<String> = SourceChecks.tokens(text)
    private val joined: String = tokens.joinToString(" ", " ", " ")
    val pos: Map<String, List<Int>> by lazy { val m = HashMap<String, MutableList<Int>>(); tokens.forEachIndexed { i, t -> m.getOrPut(t) { ArrayList(2) } += i }; m }
    fun containsSeq(q: List<String>): Boolean = q.isNotEmpty() && joined.contains(q.joinToString(" ", " ", " "))
}

/** URL extraction that keeps balanced parentheses (Wikipedia "Foo_(bar)") and validation before classification. */
object Urls {
    private const val STOP = " \t\r\n<>\"'`[]{}|\\^"
    /** End index (exclusive) of a URL starting at [start]: balanced parens kept, trailing punctuation and unmatched ')' dropped. */
    fun scanEnd(s: String, start: Int): Int {
        var i = start; var depth = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isWhitespace() || c in STOP) break
            if (c == '(') depth++
            if (c == ')') { if (depth == 0) break; depth-- }
            i++
        }
        while (i > start && s[i - 1] in ".,;:!?*") i--
        return i
    }

    fun find(s: String): Sequence<IntRange> = sequence {
        var i = 0
        while (true) {
            val a = Regex("""https?://""").find(s, i) ?: break
            val e = scanEnd(s, a.range.first)
            if (e > a.range.last + 1) yield(a.range.first until e)
            i = maxOf(e, a.range.last + 1)
        }
    }

    fun first(s: String?): String = s?.let { t -> find(t).firstOrNull()?.let { t.substring(it.first, it.last + 1) } }.orEmpty()

    /** Why [url] is malformed (unbalanced parentheses, bad percent-encoding, no usable host), or null when well-formed. */
    fun malformed(url: String): String? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return "not an http(s) URL"
        if (url.count { it == '(' } != url.count { it == ')' }) return "unbalanced parentheses"
        if (Regex("""%(?![0-9A-Fa-f]{2})""").containsMatchIn(url)) return "invalid percent-encoding"
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return "unparseable URL"
        if ('.' !in host || host.startsWith('.') || host.endsWith('.')) return "invalid host"
        return null
    }

    /** Registered domain (eTLD+1 approximation: handles co.uk-style second levels). */
    fun domain(url: String): String {
        val host = runCatching { java.net.URI(url.trim()).host }.getOrNull()?.lowercase()?.removePrefix("www.") ?: return ""
        val p = host.split('.')
        if (p.size <= 2) return host
        val sld = p[p.size - 2]
        val n = if (p.last().length == 2 && sld in setOf("co", "com", "org", "net", "ac", "gov", "edu", "ne", "or", "go", "gob", "nic", "ltd", "plc")) 3 else 2
        return p.takeLast(n).joinToString(".")
    }
}

/** Every page workers fetched in this run, keyed by normalized URL (and final URL after redirects). Thread-safe. */
class SourceRegistry {
    private val byKey = LinkedHashMap<String, SourceEntry>()
    private val byId = LinkedHashMap<Int, SourceEntry>()
    private var next = 1
    val rejected = LinkedHashMap<String, String>()
    /** Where a rejected URL came from (worker / report) and the validation detail. */
    val rejectedOrigin = LinkedHashMap<String, String>()

    @Synchronized fun get(url: String): SourceEntry? = key(url)?.let { byKey[it] }?.let { byId[it.id] }
    @Synchronized fun byId(id: Int): SourceEntry? = byId[id]
    @Synchronized fun all(): List<SourceEntry> = byId.values.toList()
    @Synchronized fun reject(url: String, why: String, origin: String = "") {
        if (url.isNotBlank() && url !in rejected) { rejected[url] = why; if (origin.isNotBlank()) rejectedOrigin[url] = origin }
    }

    private fun key(url: String) = ResearchPolicy.normalize(url.trim().substringBefore(' ').substringBefore('#'))

    @Synchronized fun record(
        url: String, finalUrl: String, status: Int, title: String, text: String, blocked: Boolean = false, servedFrom: String = "live",
        complete: Boolean = true,
    ): SourceEntry? {
        val k = key(url) ?: return null
        val fk = key(finalUrl) ?: k
        val flags = SourceChecks.flags(url, finalUrl, status, title, text, blocked)
        val dis = if (SourceChecks.DISAMBIGUATION in flags) SourceChecks.disambiguationSignal(title, text, finalUrl.ifBlank { url }).orEmpty() else ""
        val full = complete && text.length <= MAX_TEXT
        val old = byKey[k] ?: byKey[fk]
        val e = when {
            old == null -> SourceEntry(next++, url, finalUrl, status, title, SourceChecks.h1(text), text.take(MAX_TEXT), servedFrom, flags, full, dis)
            old.ok && flags.isEmpty() -> when {
                old.complete -> old
                full && text.length >= old.text.length -> old.copy(text = text, complete = true) // whole document replaces a partial one
                old.text.length < MAX_TEXT && !old.text.contains(text.take(200)) -> old.copy(text = (old.text + "\n" + text).take(MAX_TEXT))
                else -> old
            }
            old.ok -> old // a later failed page/attempt never downgrades a good one
            else -> SourceEntry(old.id, old.url, finalUrl, status, title.ifBlank { old.title }, SourceChecks.h1(text), text.take(MAX_TEXT), servedFrom, flags, full, dis)
        }
        byKey[k] = e; byKey[fk] = e; byId[e.id] = e
        return e
    }

    /** Records a web_fetch result (markdown or raw), as the worker saw it. Partial (paginated / truncated) text is marked incomplete. */
    fun recordFetch(argsJson: String, result: ToolResult, servedFrom: String = "live", originalUrl: String? = null): SourceEntry? {
        val args = runCatching { Json.parseToJsonElement(argsJson) as JsonObject }.getOrNull()
        val asked = originalUrl ?: args?.str("url") ?: return null
        val o = runCatching { Json.parseToJsonElement(result.json) as? JsonObject }.getOrNull()
        if (result.isError || o == null || o["error"] != null) {
            val msg = o?.str("error") ?: result.json
            val code = Regex("""HTTP (\d{3})""").find(msg)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return record(asked, asked, code, "", "", blocked = true, servedFrom = servedFrom)
        }
        val status = o.str("status")?.toIntOrNull() ?: if (o.str("ok") == "true") 200 else 0
        val text = SourceChecks.unfence(o.str("content") ?: o.str("body").orEmpty())
        val pages = o.str("total_pages")?.toIntOrNull() ?: 1
        val complete = pages <= 1 && o.str("truncated") != "true"
        return record(asked, o.str("url") ?: asked, status, o.str("title").orEmpty(), text, blocked = o.str("blocked") == "true", servedFrom = servedFrom, complete = complete)
    }

    /** Per-page cap for the cached full text (~1 MB of characters). */
    companion object { const val MAX_TEXT = 1_000_000 }
}

internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

/** Worker output: JSON findings parsed leniently, or claims + URLs pulled from prose. */
object WorkerOutput {
    data class Finding(val claim: String, val url: String, val quote: String = "", val confidence: String = "")
    data class Parsed(val findings: List<Finding>, val insufficient: Boolean, val summary: String, val structured: Boolean, val entity: String = "")

    private fun cleanUrl(s: String?): String = Urls.first(s)
    private fun stripUrls(s: String): String { var t = s; Urls.find(s).toList().asReversed().forEach { t = t.removeRange(it) }; return t }

    private fun prim(e: JsonElement?): String = when (e) {
        is JsonPrimitive -> e.contentOrNull.orEmpty()
        is JsonArray -> e.joinToString(" ") { prim(it) }
        else -> ""
    }

    private fun finding(o: JsonObject): Finding? {
        val claim = prim(o["claim"] ?: o["fact"] ?: o["text"] ?: o["finding"]).trim()
        if (claim.isEmpty()) return null
        return Finding(claim, cleanUrl(prim(o["url"] ?: o["source"] ?: o["source_url"])), prim(o["quote"]).trim(), prim(o["confidence"]).trim().lowercase())
    }

    private fun parseJson(s: String): JsonElement? = runCatching { Json.parseToJsonElement(s) }.getOrNull()

    fun parse(text: String?): Parsed {
        val t = text.orEmpty().trim()
        val fenced = Regex("""```(?:json)?\s*\n?(.*?)```""", RegexOption.DOT_MATCHES_ALL).find(t)?.groupValues?.get(1)?.trim()
        val candidates = listOfNotNull(fenced, t, t.indexOf('{').takeIf { it >= 0 }?.let { s -> t.lastIndexOf('}').takeIf { it > s }?.let { t.substring(s, it + 1) } })
        for (c in candidates) {
            when (val e = parseJson(c)) {
                is JsonObject -> {
                    val arr = (e["findings"] ?: e["claims"]) as? JsonArray ?: JsonArray(emptyList())
                    val ins = (e["insufficient_evidence"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "true") } ?: false
                    return Parsed(arr.mapNotNull { (it as? JsonObject)?.let(::finding) }, ins, prim(e["summary"]), true, prim(e["entity"]))
                }
                is JsonArray -> return Parsed(e.mapNotNull { (it as? JsonObject)?.let(::finding) }, false, "", true)
                else -> {}
            }
        }
        // Truncated / broken JSON: salvage every complete finding object.
        val objs = Regex("""\{[^{}]*"claim"[^{}]*\}""").findAll(t).mapNotNull { (parseJson(it.value) as? JsonObject)?.let(::finding) }.toList()
        val ins = Regex(""""insufficient_evidence"\s*:\s*true""").containsMatchIn(t)
        if (objs.isNotEmpty() || ins) return Parsed(objs, ins, Regex(""""summary"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(t)?.groupValues?.get(1).orEmpty(), true)
        return prose(t)
    }

    /** "### Title / URL: x / - fact" blocks, or any line with a URL and text. */
    fun prose(t: String): Parsed {
        val out = mutableListOf<Finding>()
        var url = ""
        var summary = ""
        for (raw in t.lines()) {
            val line = raw.trim()
            if (line.startsWith("summary:", true)) { summary = line.substringAfter(':').trim(); continue }
            val u = cleanUrl(line)
            val isUrlLine = line.startsWith("url:", true) || line.startsWith("source:", true) || line == u || line.startsWith("<http")
            if (u.isNotEmpty() && isUrlLine) { url = u; continue }
            if (line.startsWith("#")) { url = ""; continue }
            val bullet = Regex("""^([-*•]|\d+[.)])\s+(.*)""").find(line)?.groupValues?.get(2)
            val claim = (bullet ?: line.takeIf { u.isNotEmpty() })?.let(::stripUrls)?.trim()?.trim('(', ')', '-', ' ')
            if (claim.isNullOrBlank() || claim.length < 8) continue
            val src = u.ifEmpty { url }
            if (src.isNotEmpty()) out += Finding(claim, src)
        }
        return Parsed(out, out.isEmpty() && Regex("""insufficient[ _]evidence""", RegexOption.IGNORE_CASE).containsMatchIn(t), summary, false)
    }
}

/**
 * Search engine circuit breaker with cooldown: [threshold] consecutive failures → skip that engine for [baseCooldownMs]
 * (doubling on each repeated trip, ≤[maxCooldownMs]; a 429 with a Retry-After hint uses that). After the cooldown the engine is
 * tried again (half-open); never skipped permanently for the run.
 */
class EngineBreaker(
    private val threshold: Int = 2,
    private val baseCooldownMs: Long = 60_000,
    private val maxCooldownMs: Long = 5 * 60_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val fails = HashMap<String, Int>()
    private val trips = HashMap<String, Int>()
    private val until = HashMap<String, Long>()
    private val lastOk = HashMap<String, Boolean>()
    /** Engines that were tripped at least once in this run, with the last reason. */
    val reasons = LinkedHashMap<String, String>()

    @Synchronized fun skipped(): Set<String> { val t = now(); return until.filterValues { it > t }.keys.toSortedSet() }

    /** Engines whose latest answer was OK and that are not cooling down. */
    @Synchronized fun healthy(): Set<String> { val sk = skipped(); return lastOk.filter { it.value && it.key !in sk }.keys.toSortedSet() }

    @Synchronized fun seen(): Set<String> = lastOk.keys.toSortedSet()

    /** Reads web_search's "engines" object: {"bing":"ok (8)","brave":"error: HTTP 429"}. */
    @Synchronized fun observe(resultJson: String) {
        val engines = runCatching { (Json.parseToJsonElement(resultJson) as JsonObject)["engines"] as? JsonObject }.getOrNull() ?: return
        for ((name, v) in engines) {
            val s = (v as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (s.startsWith("skipped")) continue
            if (s.startsWith("error")) {
                lastOk[name] = false
                val n = (fails[name] ?: 0) + 1; fails[name] = n
                if (n >= threshold) {
                    val k = (trips[name] ?: 0) + 1; trips[name] = k
                    val cd = retryAfterMs(s) ?: minOf(maxCooldownMs, baseCooldownMs shl (k - 1).coerceAtMost(10))
                    until[name] = now() + cd
                    reasons[name] = s.removePrefix("error:").trim().take(160) + " ($n failures in a row; retry in ${cd / 1000} s)"
                }
            } else { fails[name] = 0; trips[name] = 0; until.remove(name); lastOk[name] = true }
        }
    }

    private fun retryAfterMs(s: String): Long? = Regex("""retry[- ]after[:= ]*(\d{1,5})""", RegexOption.IGNORE_CASE).find(s)
        ?.groupValues?.get(1)?.toLongOrNull()?.let { (it * 1000).coerceIn(1_000, maxCooldownMs) }
}

/** Off-topic detection (token overlap) and one deterministic query reformulation. */
object Recall {
    const val WEAK = 0.2
    private val STOP = setOf("the", "and", "for", "with", "what", "which", "who", "how", "why", "when", "where", "are", "was", "were", "is",
        "does", "did", "from", "that", "this", "these", "those", "into", "about", "their", "there", "have", "has", "its", "than", "any",
        "can", "could", "should", "would", "will", "between", "over", "under", "more", "most", "some", "such", "other", "also", "been")

    fun tokens(s: String): List<String> = s.lowercase().replace(Regex("""site:\S+"""), " ")
        .split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length >= 3 && it !in STOP }.distinct()

    /** Average share of query tokens found in each of the top 5 results' title + snippet (0 when no results). */
    fun overlap(query: String, resultJson: String): Double {
        val q = tokens(query).ifEmpty { return 1.0 }
        val results = runCatching { (Json.parseToJsonElement(resultJson) as JsonObject)["results"] as? JsonArray }.getOrNull().orEmpty().take(5)
        if (results.isEmpty()) return 0.0
        return results.map { r ->
            val o = r as? JsonObject
            val text = ((o?.str("title") ?: "") + " " + (o?.str("snippet") ?: "") + " " + (o?.str("url") ?: "")).lowercase()
            q.count { it in text }.toDouble() / q.size
        }.average()
    }

    /** Sub-question keywords first, then the query's (max 8); null when that is the same query. */
    fun reformulate(query: String, question: String): String? {
        val toks = (tokens(question) + tokens(query)).distinct().take(8)
        val r = toks.joinToString(" ")
        return r.takeIf { toks.size >= 2 && tokens(query) != toks }
    }
}

/** Overall wall-clock budget of one run. 0 = unlimited. */
class RunBudget(val budgetMs: Long, private val now: () -> Long) {
    private val start = now()
    val reserveMs: Long = if (budgetMs <= 0) 0 else minOf(120_000L, budgetMs / 5)
    fun elapsed() = now() - start
    /** Time left for workers (keeps [reserveMs] for writing the report). */
    fun workerLeft(): Long = if (budgetMs <= 0) Long.MAX_VALUE else budgetMs - reserveMs - elapsed()
    val limited: Boolean get() = budgetMs > 0
    @Volatile var hit = false

    companion object {
        const val MIN_WORKER_MS = 20_000L
        val STANDARD_MIN_RANGE = 3..30
        val DEEP_MIN_RANGE = 5..60
        const val STANDARD_MIN = 10
        const val DEEP_MIN = 15
        /** Per-worker rate-limit wait inside a budgeted run: ≤4 min and ≤ a third of the budget. */
        fun rateWaitMs(budgetMs: Long): Long = if (budgetMs <= 0) 4 * 60_000L else minOf(4 * 60_000L, budgetMs / 3)
    }
}

/** Shared per-run state for the web tool wrapper (registry, breaker, Wayback caps, weak recall) + its counters. */
class ResearchRunState(
    val registry: SourceRegistry = SourceRegistry(),
    val breaker: EngineBreaker = EngineBreaker(),
    private val maxWayback: Int = 6,
    private val waybackTotalMs: Long = 90_000,
    private val waybackCallMs: Long = 20_000,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxFullText: Int = 40,
    private val fullTextCallMs: Long = 15_000,
) {
    private var waybackUsed = 0
    private var waybackMs = 0L
    @Volatile var waybackServed = 0; private set
    val weakRecall = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile var reformulations = 0
    @Volatile var queriesIssued = 0
    @Volatile var pagesReturned = 0
    @Volatile var fullTextFetches = 0
    @Volatile var diversifyQueries = 0
    /** Queries tried per sub-question, and URLs whose fetch failed per sub-question (for gaps). */
    val queriesBy = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()
    val fetchFailedBy = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    /** Sources fetched by the diversification step (not by a worker). */
    val extraSources = java.util.concurrent.CopyOnWriteArrayList<Int>()

    @Synchronized fun searched(question: String, query: String, resultJson: String) {
        queriesIssued++
        pagesReturned += runCatching { ((Json.parseToJsonElement(resultJson) as JsonObject)["results"] as? JsonArray)?.size ?: 0 }.getOrDefault(0)
        queriesBy.getOrPut(question) { mutableListOf() }.let { if (query !in it) it += query }
    }

    /**
     * Whole document for an entry whose stored text is partial: web_fetch markdown with page_size_tokens=0 (normally served from
     * web_fetch's own cache, so no network). Capped by [maxFullText] per run and [fullTextCallMs] per call.
     */
    suspend fun fullText(url: String, exec: suspend (String, String) -> ToolResult): SourceEntry? {
        val e = registry.get(url) ?: return null
        if (e.complete || !e.ok) return e
        if (!synchronized(this) { if (fullTextFetches < maxFullText) { fullTextFetches++; true } else false }) return e
        val args = buildJsonObject { put("url", e.url); put("format", "markdown"); put("page", 1); put("page_size_tokens", 0) }.toString()
        val r = try { withTimeoutOrNull(fullTextCallMs) { exec("web_fetch", args) } } catch (c: CancellationException) { throw c } catch (_: Exception) { null } ?: return e
        val o = runCatching { Json.parseToJsonElement(r.json) as JsonObject }.getOrNull() ?: return e
        if (r.isError || o["error"] != null || o.str("ok") == "false") return e
        val text = SourceChecks.unfence(o.str("content").orEmpty())
        return registry.record(e.url, o.str("url") ?: e.finalUrl, o.str("status")?.toIntOrNull() ?: 200, o.str("title").orEmpty().ifBlank { e.title },
            text, servedFrom = e.servedFrom, complete = (o.str("total_pages")?.toIntOrNull() ?: 1) <= 1) ?: e
    }

    /**
     * Source diversity step (no model calls): ≤[maxQueries] web searches excluding the dominant domain, fetching ≤[maxFetches]
     * new pages from other domains into the registry. Returns the ids added.
     */
    suspend fun diversify(topic: String, dominant: String, exec: suspend (String, String) -> ToolResult, maxQueries: Int = 3, maxFetches: Int = 3): List<Int> {
        val added = mutableListOf<Int>()
        val qs = listOf(topic, "$topic analysis", "$topic news").map { "$it -site:$dominant" }.take(maxQueries)
        for (q in qs) {
            if (added.size >= maxFetches) break
            synchronized(this) { diversifyQueries++ }
            val args = buildJsonObject { put("query", q); val sk = breaker.skipped(); if (sk.isNotEmpty()) put("skip_engines", sk.joinToString(",")) }.toString()
            val r = runCatching { exec("web_search", args) }.getOrElse { if (it is CancellationException) throw it; null } ?: continue
            breaker.observe(r.json); searched("(diversification)", q, r.json)
            val urls = runCatching { ((Json.parseToJsonElement(r.json) as JsonObject)["results"] as? JsonArray).orEmpty() }.getOrDefault(emptyList())
                .mapNotNull { (it as? JsonObject)?.str("url") }
                .filter { Urls.domain(it).let { d -> d.isNotEmpty() && d != dominant } && ResearchPolicy.usable(it) && Urls.malformed(it) == null && registry.get(it) == null }
            val u = urls.firstOrNull { c -> added.none { registry.byId(it)?.let { e -> Urls.domain(e.url) == Urls.domain(c) } == true } } ?: urls.firstOrNull() ?: continue
            val fa = buildJsonObject { put("url", u); put("format", "markdown") }.toString()
            val fr = runCatching { exec("web_fetch", fa) }.getOrElse { if (it is CancellationException) throw it; ToolResult("{\"error\":\"${it.message}\"}", true) }
            val e = registry.recordFetch(fa, fr)
            if (e != null && e.ok) { added += e.id; extraSources += e.id }
        }
        return added
    }

    @Synchronized private fun takeWayback(): Boolean = if (waybackUsed < maxWayback && waybackMs < waybackTotalMs) { waybackUsed++; true } else false
    @Synchronized private fun spent(ms: Long) { waybackMs += ms }

    /** Tries the Internet Archive for [url] through web_fetch: availability API → snapshot (markdown). Capped by count and time. */
    suspend fun wayback(url: String, exec: suspend (String, String) -> ToolResult): ToolResult? {
        if (!ResearchPolicy.usable(url) || !takeWayback()) return null
        val t0 = now()
        try {
            return withTimeoutOrNull(waybackCallMs) {
                val api = "https://archive.org/wayback/available?url=" + URLEncoder.encode(url, "UTF-8")
                val avail = exec("web_fetch", buildJsonObject { put("url", api); put("format", "raw") }.toString())
                val snap = waybackSnapshot(avail.json) ?: return@withTimeoutOrNull null
                val r = exec("web_fetch", buildJsonObject { put("url", snap); put("format", "markdown") }.toString())
                val e = registry.recordFetch(buildJsonObject { put("url", url) }.toString(), r, servedFrom = "wayback", originalUrl = url)
                if (e?.ok == true && e.servedFrom == "wayback") { synchronized(this@ResearchRunState) { waybackServed++ } } else return@withTimeoutOrNull null
                val o = runCatching { Json.parseToJsonElement(r.json) as JsonObject }.getOrNull() ?: return@withTimeoutOrNull null
                ToolResult(JsonObject(o + mapOf("served_from" to JsonPrimitive("wayback"), "original_url" to JsonPrimitive(url),
                    "note" to JsonPrimitive("Live page failed; this is the Internet Archive copy. Cite the original URL."))).toString(), false)
            }
        } catch (c: CancellationException) { throw c } catch (_: Exception) { return null } finally { spent(now() - t0) }
    }

    /** Fetches a cited URL nobody fetched (used before writing; recorded, with Wayback fallback). */
    suspend fun verifyFetch(url: String, exec: suspend (String, String) -> ToolResult) {
        val args = buildJsonObject { put("url", url); put("format", "markdown") }.toString()
        val r = runCatching { exec("web_fetch", args) }.getOrElse { if (it is CancellationException) throw it; ToolResult("{\"error\":\"${it.message}\"}", true) }
        val e = registry.recordFetch(args, r)
        if (e == null || !e.ok) wayback(url, exec)
    }

    companion object {
        fun waybackSnapshot(json: String): String? = runCatching {
            val o = Json.parseToJsonElement(json) as JsonObject
            val body = o.str("body") ?: json
            val b = Json.parseToJsonElement(body) as JsonObject
            val c = (b["archived_snapshots"] as JsonObject)["closest"] as JsonObject
            if (c.str("available") == "false") null else c.str("url")?.replace("http://", "https://")
        }.getOrNull()
    }
}

/** Wraps one worker's web tools: records fetches, Wayback on failed/empty pages, engine breaker, one reformulation per worker. */
class ResearchToolGuard(
    private val exec: suspend (String, String) -> ToolResult,
    private val state: ResearchRunState,
    private val question: String,
) {
    private var reformulated = false

    suspend fun execute(name: String, argsJson: String): ToolResult = when (name) {
        "web_fetch" -> fetch(argsJson)
        "web_search" -> search(argsJson)
        else -> exec(name, argsJson)
    }

    private suspend fun fetch(argsJson: String): ToolResult {
        val r = exec("web_fetch", argsJson)
        val e = state.registry.recordFetch(argsJson, r)
        if (e != null && (SourceChecks.DEAD in e.flags || SourceChecks.EMPTY in e.flags) && SourceChecks.SEARCH_PAGE !in e.flags) {
            state.wayback(e.url, exec)?.let { return it }
            state.fetchFailedBy.getOrPut(question) { java.util.concurrent.ConcurrentHashMap.newKeySet() } += e.url
        }
        // The worker reads one page at a time; grounding needs the whole cleaned document, cached once per URL.
        if (e != null && e.ok && !e.complete) state.fullText(e.url, exec)
        return r
    }

    private suspend fun search(argsJson: String): ToolResult {
        val args = runCatching { Json.parseToJsonElement(argsJson) as JsonObject }.getOrNull() ?: return exec("web_search", argsJson)
        val query = args.str("query").orEmpty()
        fun withQuery(q: String): String {
            val skip = state.breaker.skipped()
            return JsonObject(args + buildMap {
                put("query", JsonPrimitive(q))
                if (skip.isNotEmpty()) put("skip_engines", JsonPrimitive(skip.joinToString(",")))
            }).toString()
        }
        var r = exec("web_search", withQuery(query)); state.breaker.observe(r.json); state.searched(question, query, r.json)
        var ov = Recall.overlap(query, r.json)
        if (ov < Recall.WEAK && !reformulated) {
            reformulated = true
            Recall.reformulate(query, question)?.let { q2 ->
                state.reformulations++
                val r2 = exec("web_search", withQuery(q2)); state.breaker.observe(r2.json); state.searched(question, q2, r2.json)
                val ov2 = Recall.overlap(q2, r2.json)
                if (ov2 > ov) { r = r2; ov = ov2 }
            }
        }
        if (ov < Recall.WEAK) {
            state.weakRecall += question
            val o = runCatching { Json.parseToJsonElement(r.json) as JsonObject }.getOrNull()
            if (o != null) return ToolResult(JsonObject(o + ("weak_recall" to JsonPrimitive(true)) +
                ("note" to JsonPrimitive("Results look off-topic: refine the query with disambiguating words or report insufficient_evidence."))).toString(), r.isError)
        }
        return r
    }
}

/** Grounded notes for the writer, bibliography numbering and quote marking on the written report. */
object Grounding {
    data class Note(val question: String, val parsed: WorkerOutput.Parsed)
    data class Stats(var checked: Int = 0, var verified: Int = 0, val tiers: MutableMap<String, Int> = LinkedHashMap(), val misses: MutableMap<String, Int> = LinkedHashMap()) {
        @Synchronized fun add(c: SourceChecks.QuoteCheck) {
            checked++
            if (c.verified) { verified++; tiers.merge(c.tier, 1, Int::plus) } else misses.merge(c.reason.ifBlank { "unknown" }, 1, Int::plus)
        }
    }

    /** Why a URL cannot be cited, as a reason code: malformed_url / never_fetched / the entry's primary state. */
    fun rejectReason(url: String, e: SourceEntry?): String = Urls.malformed(url)?.let { "malformed_url" } ?: e?.let { SourceTable.state(it) } ?: "never_fetched"

    /** Writer notes: only findings whose URL is well-formed and resolved OK in the registry, cited as [S<id>], with quote verification. */
    fun notes(items: List<Note>, reg: SourceRegistry, stats: Stats, maxChars: Int = ResearchPolicy.REPORT_INPUT_CHARS, extra: List<SourceEntry> = emptyList()): Pair<String, List<SourceEntry>> {
        val used = LinkedHashMap<Int, SourceEntry>()
        val per = (maxChars / (items.size + if (extra.isEmpty()) 0 else 1).coerceAtLeast(1)).coerceAtLeast(500)
        val blocks = items.map { (q, p) ->
            buildString {
                append("## Sub-question: $q\n")
                if (p.entity.isNotBlank()) append("(entity: ${p.entity})\n")
                var n = 0
                for (f in p.findings) {
                    if (f.url.isBlank()) continue
                    val bad = Urls.malformed(f.url)
                    if (bad != null) { reg.reject(f.url, "malformed_url", "$bad; worker: ${q.take(80)}"); continue }
                    val e = reg.get(f.url)
                    if (e == null || !e.ok) { reg.reject(f.url, rejectReason(f.url, e), "worker: ${q.take(80)}"); continue }
                    used[e.id] = e; n++
                    append("- ${f.claim} [S${e.id}]")
                    if (f.quote.isNotBlank()) {
                        val c = SourceChecks.checkQuote(f.quote, e.index); stats.add(c)
                        if (c.verified) append(" — verified quote: \"${f.quote}\"")
                        else append(" — UNVERIFIED quote (not found on the page; do not quote it, never use it in the summary or takeaways)")
                    }
                    append('\n')
                }
                if (n == 0) append(if (p.insufficient) "- (worker found insufficient evidence)\n" else "- (no claims with a verified source)\n")
                if (p.summary.isNotBlank()) append("Worker summary: ${p.summary}\n")
            }.take(per)
        }
        val extraBlock = if (extra.isEmpty()) emptyList() else listOf(buildString {
            append("## Additional independent sources (fetched to diversify; cite one only for what its excerpt says)\n")
            for (e in extra) { used[e.id] = e; append("- [S${e.id}] ${e.label.take(120)}: ${e.text.replace(Regex("""\s+"""), " ").take(600)}\n") }
        }.take(per))
        return (blocks + extraBlock).joinToString("\n\n").take(maxChars) to used.values.toList()
    }

    private val MARKER = Regex("""\[((?:S?\d+)(?:\s*[,;]\s*S?\d+)*)]""")
    private val MD_LINK = Regex("""\[([^\]\n]+)]\((https?://(?:[^()\s]|\([^()\s]*\))+)\)""")
    const val UNVERIFIED_SOURCE = "[unverified source]"
    const val UNVERIFIED_QUOTE = "[unverified quote]"

    data class Rendered(val text: String, val cited: List<SourceEntry>)

    /** [S3] / [3] / raw URLs → [1], [2]… numbered by first citation; unknown, malformed or failed sources → [unverified source]. */
    fun bibliography(report: String, reg: SourceRegistry): Rendered {
        val order = LinkedHashMap<Int, Int>()
        fun num(e: SourceEntry) = order.getOrPut(e.id) { order.size + 1 }
        // URLs become [S<id>] first, so the single numbering pass below orders everything by first citation.
        fun cite(url: String): String {
            val bad = Urls.malformed(url)
            if (bad != null) { reg.reject(url, "malformed_url", "$bad; report"); return UNVERIFIED_SOURCE }
            val e = reg.get(url)
            return if (e != null && e.ok) "[S${e.id}]" else { reg.reject(url, rejectReason(url, e), "report"); UNVERIFIED_SOURCE }
        }
        var t = MD_LINK.replace(report) { m -> m.groupValues[1] + " " + cite(m.groupValues[2]) }
        t = buildString {
            var i = 0
            for (r in Urls.find(t).toList()) {
                var a = r.first; var b = r.last + 1
                append(t, i, if (a > 0 && t[a - 1] == '<') a - 1 else a)
                append(cite(t.substring(a, b)))
                if (b < t.length && t[b] == '>') b++
                i = b
            }
            append(t, i, t.length)
        }
        t = MARKER.replace(t) { m ->
            val ids = m.groupValues[1].split(',', ';').mapNotNull { it.trim().removePrefix("S").toIntOrNull() }
            val good = ids.mapNotNull { id -> reg.byId(id)?.takeIf { it.ok } }
            val bad = ids.size - good.size
            if (bad > 0) ids.filter { id -> reg.byId(id)?.ok != true }.forEach { id -> reg.byId(id)?.let { reg.reject(it.url, SourceTable.state(it), "report") } }
            (if (good.isEmpty()) "" else good.map(::num).distinct().joinToString(", ", "[", "]")) + (if (bad > 0) (if (good.isEmpty()) "" else " ") + UNVERIFIED_SOURCE else "")
        }
        return Rendered(t, order.keys.mapNotNull { reg.byId(it) })
    }

    fun sourcesSection(cited: List<SourceEntry>): String = if (cited.isEmpty()) "" else "\n\n## Sources\n\n" + cited.mapIndexed { i, e ->
        "${i + 1}. ${e.label.replace(Regex("""[\[\]\n]"""), " ").trim().take(150)} — <${e.url}>" + if (e.servedFrom == "wayback") " (archived copy)" else ""
    }.joinToString("\n")

    /**
     * Quotation spans in one line, quote marks included: “…” pairs, and straight double quotes paired in order. Content must be
     * 25..400 chars. Pairing in order (not a regex) keeps a span from starting at a closing quote.
     */
    fun quoteSpans(line: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            val close = when (c) { '\u201C' -> '\u201D'; '"' -> '"'; else -> null }
            if (close == null) { i++; continue }
            var j = line.indexOf(close, i + 1)
            if (c == '\u201C' && j < 0) j = line.indexOf('"', i + 1)
            if (j < 0) break
            if (j - i - 1 in 25..400) out += i..j
            i = j + 1
        }
        return out
    }

    /**
     * Checks every quotation in the report against the cleaned full text of the OK pages. Unverified ones get [UNVERIFIED_QUOTE]
     * AFTER the closing quote mark (the quoted text is never changed); lines with them are dropped from summary/takeaway sections.
     */
    fun markQuotes(report: String, reg: SourceRegistry, stats: Stats): String {
        val pages = reg.all().filter { it.ok }
        fun check(q: String): SourceChecks.QuoteCheck {
            val toks = SourceChecks.tokens(q)
            if (toks.size < 3) return SourceChecks.QuoteCheck(false, reason = "too_short")
            if (pages.any { it.index.containsSeq(toks) }) return SourceChecks.QuoteCheck(true, "exact")
            var miss: SourceChecks.QuoteCheck? = null
            for (p in pages) { val c = SourceChecks.checkQuote(q, p.index); if (c.verified) return c; if (miss == null || c.reason != "no_window_above_0.92") miss = c }
            return miss ?: SourceChecks.QuoteCheck(false, reason = "no_page_text")
        }
        val marked = report.lines().joinToString("\n") { line ->
            val spans = quoteSpans(line)
            if (spans.isEmpty()) line else buildString {
                var i = 0
                for (r in spans) {
                    append(line, i, r.last + 1); i = r.last + 1
                    val c = check(line.substring(r.first + 1, r.last)); stats.add(c)
                    if (!c.verified && !line.startsWith(" $UNVERIFIED_QUOTE", i)) append(" ").append(UNVERIFIED_QUOTE)
                }
                append(line, i, line.length)
            }
        }
        var inKey = false
        return marked.lines().filter { line ->
            if (line.startsWith("#")) { val h = line.lowercase(); inKey = "summary" in h || "takeaway" in h || "tl;dr" in h; true }
            else !(inKey && UNVERIFIED_QUOTE in line)
        }.joinToString("\n")
    }

    /** "## Heading" lines with their line number, for reading a long report by section. */
    fun sectionIndex(report: String): List<Pair<String, Int>> = report.lines().mapIndexedNotNull { i, l ->
        if (Regex("""^#{1,3} \S""").containsMatchIn(l)) l.trim() to (i + 1) else null
    }
}

/** One source-state table: every registered page plus every rejected URL that never made it into the registry. */
object SourceTable {
    const val CITED = "cited"
    const val UNCITED = "uncited"
    data class Row(val url: String, val id: Int?, val state: String, val detail: String = "")

    /** Primary state of a registered entry (one per entry, so counters add up). */
    fun state(e: SourceEntry): String = when {
        SourceChecks.SEARCH_PAGE in e.flags -> SourceChecks.SEARCH_PAGE
        SourceChecks.DISAMBIGUATION in e.flags -> SourceChecks.DISAMBIGUATION
        SourceChecks.DEAD in e.flags -> SourceChecks.DEAD
        SourceChecks.EMPTY in e.flags -> SourceChecks.EMPTY
        else -> "ok"
    }

    fun build(reg: SourceRegistry, cited: Set<Int>): List<Row> {
        val all = reg.all()
        val rows = all.map { e ->
            val st = state(e)
            Row(e.url, e.id, if (st == "ok") (if (e.id in cited) CITED else UNCITED) else st,
                if (st == SourceChecks.DISAMBIGUATION) "signal: ${e.disambigReason}" else if (e.servedFrom == "wayback") "wayback" else "")
        }
        val (rej, origin) = synchronized(reg) { LinkedHashMap(reg.rejected) to LinkedHashMap(reg.rejectedOrigin) }
        val extra = rej.filter { (u, _) -> reg.get(u) == null || Urls.malformed(u) != null }.map { (u, why) -> Row(u, null, why, origin[u].orEmpty()) }
        return rows + extra
    }

    fun counts(rows: List<Row>): Map<String, Int> = rows.groupingBy { it.state }.eachCount()
}

/** A "we tried and got nothing" path, with the queries that were tried. */
data class Gap(val type: String, val question: String, val detail: String = "", val queries: List<String> = emptyList()) {
    fun json(): JsonObject = buildJsonObject {
        put("type", type); put("question", question.take(200)); if (detail.isNotBlank()) put("detail", detail.take(300))
        if (queries.isNotEmpty()) put("queries", buildJsonArray { queries.take(6).forEach { add(JsonPrimitive(it.take(160))) } })
    }
    fun line(): String = "$type: ${question.take(160)}" + (if (detail.isNotBlank()) " — ${detail.take(160)}" else "") +
        (if (queries.isNotEmpty()) " (tried: ${queries.take(3).joinToString(" | ") { it.take(60) }})" else "")
}

/** Distinct registered domains of the usable sources and the share of the most common one. */
data class SourceDiversity(val distinct: Int, val top: String, val share: Double) {
    companion object {
        fun of(entries: List<SourceEntry>): SourceDiversity {
            val d = entries.map { Urls.domain(it.url) }.filter { it.isNotEmpty() }
            if (d.isEmpty()) return SourceDiversity(0, "", 0.0)
            val top = d.groupingBy { it }.eachCount().maxBy { it.value }
            return SourceDiversity(d.distinct().size, top.key, top.value.toDouble() / d.size)
        }
        const val LOW_SHARE = 0.6
    }
}

/** Honest numbers about one run (tool result + "Run notes" section). Source counters are all derived from [table]. */
data class ResearchDiagnostics(
    val table: List<SourceTable.Row> = emptyList(),
    val wayback: Int = 0,
    val quotesChecked: Int = 0, val quotesVerified: Int = 0, val quoteMisses: Map<String, Int> = emptyMap(), val quoteTiers: Map<String, Int> = emptyMap(),
    val enginesFailed: Map<String, String> = emptyMap(), val enginesHealthy: Set<String> = emptySet(),
    val queriesIssued: Int = 0, val pagesReturned: Int = 0, val reformulations: Int = 0, val diversifyQueries: Int = 0,
    val diversity: SourceDiversity = SourceDiversity(0, "", 0.0),
    val weakRecall: Int = 0, val gaps: List<Gap> = emptyList(), val warnings: List<String> = emptyList(),
    val searchDegraded: Boolean = false,
    val budgetMs: Long = 0, val elapsedMs: Long = 0, val budgetHit: Boolean = false,
) {
    private val counts = SourceTable.counts(table)
    private fun n(s: String) = counts[s] ?: 0
    val registered: List<SourceTable.Row> get() = table.filter { it.id != null }
    val collected: Int get() = registered.size
    val cited: Int get() = n(SourceTable.CITED)
    val orphansPruned: Int get() = n(SourceTable.UNCITED)
    val ok: Int get() = cited + orphansPruned
    val dead: Int get() = n(SourceChecks.DEAD)
    val empty: Int get() = n(SourceChecks.EMPTY)
    val disambiguation: Int get() = n(SourceChecks.DISAMBIGUATION)
    val searchPages: Int get() = n(SourceChecks.SEARCH_PAGE)
    val malformed: Int get() = n("malformed_url")
    /** Every row that cannot be cited, registered or not. */
    val rejectedRows: List<SourceTable.Row> get() = table.filter { it.state != SourceTable.CITED && it.state != SourceTable.UNCITED }
    val rejected: Map<String, String> get() = rejectedRows.associate { it.url to it.state }
    /** collected == ok + dead + empty + disambiguation + search pages, by construction; checked anyway. */
    val consistent: Boolean get() = collected == ok + dead + empty + disambiguation + searchPages

    fun json(): JsonObject = buildJsonObject {
        put("sources_collected", collected); put("sources_ok", ok); put("sources_dead", dead); put("sources_empty", empty)
        put("sources_disambiguation", disambiguation); put("search_pages", searchPages); put("malformed_urls", malformed)
        put("served_from_wayback", wayback); put("sources_cited", cited); put("orphans_pruned", orphansPruned)
        put("source_states", buildJsonObject { counts.forEach { (k, v) -> put(k, v) } })
        put("counters_consistent", consistent)
        put("quotes_checked", quotesChecked); put("quotes_verified", quotesVerified)
        if (quoteMisses.isNotEmpty()) put("quote_miss_reasons", buildJsonObject { quoteMisses.forEach { (k, v) -> put(k, v) } })
        if (quoteTiers.isNotEmpty()) put("quote_match_tiers", buildJsonObject { quoteTiers.forEach { (k, v) -> put(k, v) } })
        put("weak_recall_questions", weakRecall)
        put("engines_healthy", buildJsonArray { enginesHealthy.forEach { add(JsonPrimitive(it)) } })
        put("engines_failed", buildJsonObject { enginesFailed.forEach { (k, v) -> put(k, v) } })
        put("search", buildJsonObject { put("queries_issued", queriesIssued); put("pages_returned", pagesReturned); put("reformulations", reformulations); put("diversification_queries", diversifyQueries) })
        put("search_degraded", searchDegraded)
        put("source_diversity", buildJsonObject { put("distinct_domains", diversity.distinct); put("top_domain", diversity.top); put("top_domain_share", Math.round(diversity.share * 100) / 100.0) })
        put("rejected_sources", buildJsonArray { rejectedRows.take(40).forEach { r -> add(buildJsonObject { put("url", r.url.take(300)); put("reason", r.state); if (r.detail.isNotBlank()) put("detail", r.detail.take(200)) }) } })
        put("gaps", buildJsonArray { gaps.take(40).forEach { add(it.json()) } })
        put("warnings", buildJsonArray { warnings.forEach { add(JsonPrimitive(it)) } })
        if (budgetMs > 0) { put("time_budget_s", budgetMs / 1000); put("elapsed_s", elapsedMs / 1000); put("time_budget_hit", budgetHit) }
    }

    fun runNotes(): String = buildString {
        append("\n\n## Run notes\n\n")
        append("- Sources: $collected fetched, $ok usable, $dead dead/blocked, $empty empty, $disambiguation disambiguation pages, $searchPages search pages, $wayback from the Internet Archive; ")
        append("$cited cited ($orphansPruned uncited dropped, ${rejectedRows.size} rejected" + (if (malformed > 0) ", $malformed malformed URLs" else "") + ").\n")
        if (diversity.distinct > 0) append("- Source diversity: ${diversity.distinct} distinct domain(s); top domain ${diversity.top} = ${Math.round(diversity.share * 100)}% of usable sources.\n")
        if (quotesChecked > 0) append("- Quotes: $quotesVerified of $quotesChecked found on the cited pages" + (if (quoteMisses.isNotEmpty()) " (misses: ${quoteMisses.entries.joinToString { "${it.key} ${it.value}" }})" else "") + ".\n")
        append("- Search: $queriesIssued queries, $pagesReturned results, $reformulations reformulation(s)" + (if (diversifyQueries > 0) ", $diversifyQueries diversification queries" else "") +
            "; healthy engines: ${enginesHealthy.joinToString().ifEmpty { "none" }}.\n")
        if (enginesFailed.isNotEmpty()) append("- Search engines paused after repeated failures (retried after a cooldown): ${enginesFailed.keys.joinToString()}.\n")
        if (weakRecall > 0) append("- $weakRecall sub-question(s) only got off-topic search results (weak recall).\n")
        if (budgetMs > 0) append("- Time: ${elapsedMs / 1000} s of a ${budgetMs / 60_000} min budget" + (if (budgetHit) " (budget reached: unfinished workers were stopped)" else "") + ".\n")
        gaps.forEach { append("- Gap: ${it.line()}\n") }
        warnings.forEach { append("- ⚠️ $it\n") }
    }
}
