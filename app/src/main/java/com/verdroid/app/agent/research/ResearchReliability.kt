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
) {
    val ok: Boolean get() = flags.isEmpty()
    val label: String get() = title.ifBlank { h1 }.ifBlank { finalUrl }
}

object SourceChecks {
    const val DEAD = "dead"
    const val DISAMBIGUATION = "disambiguation"
    const val SEARCH_PAGE = "search_page"
    const val EMPTY = "empty"
    const val MIN_TEXT = 120

    fun isDisambiguation(title: String, text: String): Boolean {
        val t = title.lowercase()
        if ("(disambiguation)" in t || "disambiguation page" in t) return true
        val head = text.take(3_000).lowercase()
        return "may refer to:" in head || "may also refer to:" in head || "(disambiguation)" in head || Regex("""\bmay refer to\b""").containsMatchIn(head.take(800))
    }

    fun flags(url: String, finalUrl: String, status: Int, title: String, text: String, blocked: Boolean): Set<String> = buildSet {
        if (status !in 200..299 || blocked) add(DEAD)
        else if (text.trim().length < MIN_TEXT) add(EMPTY)
        if (ResearchPolicy.isSearchPage(url) || ResearchPolicy.isSearchPage(finalUrl)) add(SEARCH_PAGE)
        if (isDisambiguation(title, text)) add(DISAMBIGUATION)
    }

    private val FENCED = Regex("""(?s)<<[^\n]*?nonce="[0-9a-f]+">>\n(.*)\n<</[^\n]*?>>""")
    /** Page text without the untrusted-data preamble that web_fetch wraps around it. */
    fun unfence(content: String): String = FENCED.find(content)?.groupValues?.get(1) ?: content

    fun h1(text: String): String = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim().orEmpty()

    /** Lowercase alphanumerics separated by single spaces (quotes, dashes, markdown and whitespace differences ignored). */
    fun norm(s: String): String = s.lowercase()
        .replace(Regex("""[\u2018\u2019\u201C\u201D'"`*_\[\]()#>|]"""), " ")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    /** Quote is grounded when every part (split on ellipses) of at least 3 words occurs in the page text. */
    fun grounded(quote: String, pageText: String): Boolean {
        val parts = quote.split("...", "…").map(::norm).filter { it.split(' ').size >= 3 }
        if (parts.isEmpty()) return false
        val t = norm(pageText)
        return parts.all { t.contains(it) }
    }
}

/** Every page workers fetched in this run, keyed by normalized URL (and final URL after redirects). Thread-safe. */
class SourceRegistry {
    private val byKey = LinkedHashMap<String, SourceEntry>()
    private val byId = LinkedHashMap<Int, SourceEntry>()
    private var next = 1
    val rejected = LinkedHashMap<String, String>()

    @Synchronized fun get(url: String): SourceEntry? = key(url)?.let { byKey[it] }?.let { byId[it.id] }
    @Synchronized fun byId(id: Int): SourceEntry? = byId[id]
    @Synchronized fun all(): List<SourceEntry> = byId.values.toList()
    @Synchronized fun reject(url: String, why: String) { if (url.isNotBlank() && url !in rejected) rejected[url] = why }

    private fun key(url: String) = ResearchPolicy.normalize(url.trim().substringBefore(' ').substringBefore('#'))

    @Synchronized fun record(url: String, finalUrl: String, status: Int, title: String, text: String, blocked: Boolean = false, servedFrom: String = "live"): SourceEntry? {
        val k = key(url) ?: return null
        val fk = key(finalUrl) ?: k
        val flags = SourceChecks.flags(url, finalUrl, status, title, text, blocked)
        val old = byKey[k] ?: byKey[fk]
        val e = when {
            old == null -> SourceEntry(next++, url, finalUrl, status, title, SourceChecks.h1(text), text.take(MAX_TEXT), servedFrom, flags)
            old.ok && flags.isEmpty() -> if (old.text.length < MAX_TEXT && !old.text.contains(text.take(200))) old.copy(text = (old.text + "\n" + text).take(MAX_TEXT)) else old
            old.ok -> old // a later failed page/attempt never downgrades a good one
            else -> SourceEntry(old.id, old.url, finalUrl, status, title.ifBlank { old.title }, SourceChecks.h1(text), text.take(MAX_TEXT), servedFrom, flags)
        }
        byKey[k] = e; byKey[fk] = e; byId[e.id] = e
        return e
    }

    /** Records a web_fetch result (markdown or raw), as the worker saw it. */
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
        return record(asked, o.str("url") ?: asked, status, o.str("title").orEmpty(), text, blocked = o.str("blocked") == "true", servedFrom = servedFrom)
    }

    companion object { const val MAX_TEXT = 60_000 }
}

internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

/** Worker output: JSON findings parsed leniently, or claims + URLs pulled from prose. */
object WorkerOutput {
    data class Finding(val claim: String, val url: String, val quote: String = "", val confidence: String = "")
    data class Parsed(val findings: List<Finding>, val insufficient: Boolean, val summary: String, val structured: Boolean, val entity: String = "")

    private val URL = Regex("""https?://[^\s"'<>)\]]+""")
    private fun cleanUrl(s: String?): String = s?.let { URL.find(it)?.value }?.trimEnd('.', ',', ';').orEmpty()

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
            val claim = (bullet ?: line.takeIf { u.isNotEmpty() })?.replace(URL, "")?.trim()?.trim('(', ')', '-', ' ')
            if (claim.isNullOrBlank() || claim.length < 8) continue
            val src = u.ifEmpty { url }
            if (src.isNotEmpty()) out += Finding(claim, src)
        }
        return Parsed(out, out.isEmpty() && Regex("""insufficient[ _]evidence""", RegexOption.IGNORE_CASE).containsMatchIn(t), summary, false)
    }
}

/** Per-run search engine circuit breaker: [threshold] consecutive failures → skip that engine for the rest of the run. */
class EngineBreaker(private val threshold: Int = 2) {
    private val fails = HashMap<String, Int>()
    val reasons = LinkedHashMap<String, String>()
    private val tripped = LinkedHashSet<String>()

    @Synchronized fun skipped(): Set<String> = tripped.toSet()

    /** Reads web_search's "engines" object: {"bing":"ok (8)","brave":"error: HTTP 429"}. */
    @Synchronized fun observe(resultJson: String) {
        val engines = runCatching { (Json.parseToJsonElement(resultJson) as JsonObject)["engines"] as? JsonObject }.getOrNull() ?: return
        for ((name, v) in engines) {
            val s = (v as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (s.startsWith("error")) {
                val n = (fails[name] ?: 0) + 1; fails[name] = n
                if (n >= threshold && tripped.add(name)) reasons[name] = s.removePrefix("error:").trim().take(160) + " ($n failures in a row)"
            } else fails[name] = 0
        }
    }
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
) {
    private var waybackUsed = 0
    private var waybackMs = 0L
    @Volatile var waybackServed = 0; private set
    val weakRecall = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile var reformulations = 0

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
        }
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
        var r = exec("web_search", withQuery(query)); state.breaker.observe(r.json)
        var ov = Recall.overlap(query, r.json)
        if (ov < Recall.WEAK && !reformulated) {
            reformulated = true
            Recall.reformulate(query, question)?.let { q2 ->
                state.reformulations++
                val r2 = exec("web_search", withQuery(q2)); state.breaker.observe(r2.json)
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
    data class Stats(var checked: Int = 0, var verified: Int = 0)

    /** Writer notes: only findings whose URL resolved OK in the registry, cited as [S<id>], with quote verification. */
    fun notes(items: List<Note>, reg: SourceRegistry, stats: Stats, maxChars: Int = ResearchPolicy.REPORT_INPUT_CHARS): Pair<String, List<SourceEntry>> {
        val used = LinkedHashMap<Int, SourceEntry>()
        val per = (maxChars / items.size.coerceAtLeast(1)).coerceAtLeast(500)
        val text = items.joinToString("\n\n") { (q, p) ->
            buildString {
                append("## Sub-question: $q\n")
                if (p.entity.isNotBlank()) append("(entity: ${p.entity})\n")
                var n = 0
                for (f in p.findings) {
                    if (f.url.isBlank()) continue
                    val e = reg.get(f.url)
                    if (e == null || !e.ok) { reg.reject(f.url, e?.flags?.joinToString()?.let { "source $it" } ?: "never fetched"); continue }
                    used[e.id] = e; n++
                    append("- ${f.claim} [S${e.id}]")
                    if (f.quote.isNotBlank()) {
                        stats.checked++
                        if (SourceChecks.grounded(f.quote, e.text)) { stats.verified++; append(" — verified quote: \"${f.quote}\"") }
                        else append(" — UNVERIFIED quote (not found on the page; do not quote it, never use it in the summary or takeaways)")
                    }
                    append('\n')
                }
                if (n == 0) append(if (p.insufficient) "- (worker found insufficient evidence)\n" else "- (no claims with a verified source)\n")
                if (p.summary.isNotBlank()) append("Worker summary: ${p.summary}\n")
            }.take(per)
        }.take(maxChars)
        return text to used.values.toList()
    }

    private val MARKER = Regex("""\[((?:S?\d+)(?:\s*[,;]\s*S?\d+)*)]""")
    private val MD_LINK = Regex("""\[([^\]\n]+)]\((https?://[^)\s]+)\)""")
    private val BARE = Regex("""<?(https?://[^\s<>)\]"']+)>?""")
    const val UNVERIFIED_SOURCE = "[unverified source]"
    const val UNVERIFIED_QUOTE = "[unverified quote]"

    data class Rendered(val text: String, val cited: List<SourceEntry>)

    /** [S3] / [3] / raw URLs → [1], [2]… numbered by first citation; unknown or failed sources → [unverified source]. */
    fun bibliography(report: String, reg: SourceRegistry): Rendered {
        val order = LinkedHashMap<Int, Int>()
        fun num(e: SourceEntry) = order.getOrPut(e.id) { order.size + 1 }
        // URLs become [S<id>] first, so the single numbering pass below orders everything by first citation.
        fun cite(url: String): String {
            val e = reg.get(url)
            return if (e != null && e.ok) "[S${e.id}]" else { reg.reject(url.trimEnd('.', ','), e?.flags?.joinToString()?.let { "source $it" } ?: "not in registry"); UNVERIFIED_SOURCE }
        }
        var t = MD_LINK.replace(report) { m -> m.groupValues[1] + " " + cite(m.groupValues[2]) }
        t = BARE.replace(t) { m -> val u = m.groupValues[1].trimEnd('.', ',', ';'); cite(u) + m.groupValues[1].substring(u.length) }
        t = MARKER.replace(t) { m ->
            val ids = m.groupValues[1].split(',', ';').mapNotNull { it.trim().removePrefix("S").toIntOrNull() }
            val good = ids.mapNotNull { id -> reg.byId(id)?.takeIf { it.ok } }
            val bad = ids.size - good.size
            if (bad > 0) ids.filter { id -> reg.byId(id)?.ok != true }.forEach { id -> reg.byId(id)?.let { reg.reject(it.url, "source ${it.flags.joinToString()}") } }
            (if (good.isEmpty()) "" else good.map(::num).distinct().joinToString(", ", "[", "]")) + (if (bad > 0) (if (good.isEmpty()) "" else " ") + UNVERIFIED_SOURCE else "")
        }
        return Rendered(t, order.keys.mapNotNull { reg.byId(it) })
    }

    fun sourcesSection(cited: List<SourceEntry>): String = if (cited.isEmpty()) "" else "\n\n## Sources\n\n" + cited.mapIndexed { i, e ->
        "${i + 1}. ${e.label.replace(Regex("""[\[\]\n]"""), " ").trim().take(150)} — <${e.url}>" + if (e.servedFrom == "wayback") " (archived copy)" else ""
    }.joinToString("\n")

    private val QUOTE = Regex("""["“]([^"”\n]{25,400})["”]""")

    /** Marks quotes not found on any OK page and drops lines with them from the executive summary / key takeaways. */
    fun markQuotes(report: String, reg: SourceRegistry, stats: Stats): String {
        val pages = reg.all().filter { it.ok }
        val marked = QUOTE.replace(report) { m ->
            stats.checked++
            if (pages.any { SourceChecks.grounded(m.groupValues[1], it.text) }) { stats.verified++; m.value } else m.value + " " + UNVERIFIED_QUOTE
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

/** Honest numbers about one run (tool result + "Run notes" section). */
data class ResearchDiagnostics(
    val collected: Int = 0, val ok: Int = 0, val dead: Int = 0, val disambiguation: Int = 0, val searchPages: Int = 0,
    val wayback: Int = 0, val cited: Int = 0, val orphansPruned: Int = 0, val rejected: Map<String, String> = emptyMap(),
    val quotesChecked: Int = 0, val quotesVerified: Int = 0, val enginesFailed: Map<String, String> = emptyMap(),
    val weakRecall: Int = 0, val gaps: List<String> = emptyList(), val warnings: List<String> = emptyList(),
    val budgetMs: Long = 0, val elapsedMs: Long = 0, val budgetHit: Boolean = false,
) {
    fun json(): JsonObject = buildJsonObject {
        put("sources_collected", collected); put("sources_ok", ok); put("sources_dead", dead); put("sources_disambiguation", disambiguation)
        put("search_pages", searchPages); put("served_from_wayback", wayback); put("sources_cited", cited); put("orphans_pruned", orphansPruned)
        put("quotes_checked", quotesChecked); put("quotes_verified", quotesVerified); put("weak_recall_questions", weakRecall)
        put("engines_failed", buildJsonObject { enginesFailed.forEach { (k, v) -> put(k, v) } })
        put("rejected_sources", buildJsonArray { rejected.entries.take(30).forEach { (u, w) -> add(buildJsonObject { put("url", u.take(300)); put("reason", w) }) } })
        put("gaps", buildJsonArray { gaps.forEach { add(JsonPrimitive(it.take(200))) } })
        put("warnings", buildJsonArray { warnings.forEach { add(JsonPrimitive(it)) } })
        if (budgetMs > 0) { put("time_budget_s", budgetMs / 1000); put("elapsed_s", elapsedMs / 1000); put("time_budget_hit", budgetHit) }
    }

    fun runNotes(): String = buildString {
        append("\n\n## Run notes\n\n")
        append("- Sources: $collected fetched, $ok usable, $dead dead/blocked, $disambiguation disambiguation pages, $wayback from the Internet Archive; ")
        append("$cited cited ($orphansPruned uncited dropped, ${rejected.size} rejected).\n")
        if (quotesChecked > 0) append("- Quotes: $quotesVerified of $quotesChecked found verbatim on the cited pages.\n")
        if (enginesFailed.isNotEmpty()) append("- Search engines skipped after repeated failures: ${enginesFailed.keys.joinToString()}.\n")
        if (weakRecall > 0) append("- $weakRecall sub-question(s) only got off-topic search results (weak recall).\n")
        if (budgetMs > 0) append("- Time: ${elapsedMs / 1000} s of a ${budgetMs / 60_000} min budget" + (if (budgetHit) " (budget reached: unfinished workers were stopped)" else "") + ".\n")
        gaps.forEach { append("- Gap: ${it.take(200)}\n") }
        warnings.forEach { append("- ⚠️ $it\n") }
    }
}
