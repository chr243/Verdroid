package com.verdroid.app.agent.research

import com.verdroid.app.agent.subagents.SubAgentPool
import com.verdroid.app.agent.subagents.SubAgentProgress
import com.verdroid.app.agent.subagents.SubAgentResult
import com.verdroid.app.data.network.ApiMessage
import com.verdroid.app.data.network.ChatOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

/** Deep research modes (issue #6): Standard = one round, ~25 sources; Deep = more sub-questions, follow-up rounds, ≤100 sources. */
enum class ResearchMode(val id: String) {
    STANDARD("standard"), DEEP("deep");
    companion object { fun of(s: String?): ResearchMode = if (s?.trim()?.lowercase() == "deep") DEEP else STANDARD }
}

/** Effective limits of one run (from Settings → Sub-agents → Deep research). */
data class ResearchLimits(
    val questions: Int,
    val rounds: Int,
    val maxSources: Int,
    val concurrency: Int,
) {
    val perWorkerSources: Int get() = (maxSources / questions.coerceAtLeast(1)).coerceIn(2, 12)
}

/** Pure rules: limits, prompts, plan parsing, URL filtering/dedupe, report assembly. JVM-tested and used by the self-test. */
object ResearchPolicy {
    const val TOOL_NAME = "deep_research"
    const val STANDARD_QUESTIONS = 5
    const val DEEP_QUESTIONS = 10
    const val STANDARD_SOURCES = 25
    const val DEEP_SOURCES = 100
    const val DEEP_ROUNDS = 2
    val QUESTIONS_RANGE = 2..20
    val SOURCES_RANGE = 5..150
    val ROUNDS_RANGE = 1..3
    const val WORKER_FINDINGS_CHARS = 6_000
    /** Max characters of worker notes handed to the report writer (keeps free-model context sane). */
    const val REPORT_INPUT_CHARS = 60_000
    /** Report text returned to the main agent (the full report is in the file). */
    const val RESULT_REPORT_CHARS = 12_000
    val WORKER_TOOLS = listOf("web_search", "web_fetch")

    fun limits(mode: ResearchMode, standardSources: Int, deepSources: Int, deepRounds: Int, concurrency: Int): ResearchLimits = when (mode) {
        ResearchMode.STANDARD -> ResearchLimits(STANDARD_QUESTIONS, 1, standardSources.coerceIn(SOURCES_RANGE), concurrency)
        ResearchMode.DEEP -> ResearchLimits(DEEP_QUESTIONS, deepRounds.coerceIn(ROUNDS_RANGE), deepSources.coerceIn(SOURCES_RANGE), concurrency)
    }

    private val BLOCKED_HOSTS = listOf(
        "pinterest.", "facebook.com", "instagram.com", "tiktok.com", "accounts.google.", "login.", "doubleclick.net",
        "googleadservices.", "bing.com/aclick", "duckduckgo.com/y.js", "t.co", "bit.ly", "translate.google.",
    )
    private val BLOCKED_EXT = Regex("""\.(jpg|jpeg|png|gif|webp|svg|ico|css|js|woff2?|ttf|mp4|mp3|zip|exe|apk)(\?|$)""", RegexOption.IGNORE_CASE)
    private val SEARCH_PAGE = Regex("""^https?://(www\.)?(google|bing|duckduckgo|yahoo|yandex|startpage|search\.brave|searx[^/]*)\.[^/]+/(search|html|\?|$)""", RegexOption.IGNORE_CASE)
    private val TRACKING = Regex("""^(utm_[a-z]+|fbclid|gclid|ref|ref_src|mc_cid|mc_eid|igshid)$""", RegexOption.IGNORE_CASE)

    /** Canonical form for dedupe: lowercase host without www, no fragment, no tracking params, no trailing slash. */
    fun normalize(url: String): String? = runCatching {
        val u = URI(url.trim().trimEnd('.', ',', ';', ')', ']'))
        val scheme = u.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = u.host?.lowercase()?.removePrefix("www.") ?: return null
        val q = u.rawQuery?.split('&')?.filter { p -> p.isNotEmpty() && !TRACKING.matches(p.substringBefore('=')) }?.sorted()?.joinToString("&")
        val path = (u.rawPath ?: "").trimEnd('/')
        "https://$host$path" + (if (q.isNullOrEmpty()) "" else "?$q")
    }.getOrNull()

    /** Useful as a cited source? Drops search-result pages, ad/redirect/social-login hosts and static assets. */
    fun usable(url: String): Boolean {
        val l = url.lowercase()
        if (!l.startsWith("http://") && !l.startsWith("https://")) return false
        if (SEARCH_PAGE.containsMatchIn(l) || BLOCKED_EXT.containsMatchIn(l)) return false
        val host = runCatching { URI(l).host.orEmpty() }.getOrDefault("")
        if (host.isEmpty()) return false
        return BLOCKED_HOSTS.none { b ->
            when {
                b.contains('/') -> l.contains(b)
                b.endsWith(".") -> host.startsWith(b) || host.contains(".$b")
                else -> host == b || host.endsWith(".$b")
            }
        }
    }

    /** Keeps the first occurrence of every normalized, usable URL, at most [cap]. */
    fun dedupe(urls: List<String>, cap: Int, already: Collection<String> = emptyList()): List<String> {
        val seen = already.mapNotNull(::normalize).toMutableSet()
        val out = mutableListOf<String>()
        for (u in urls) {
            if (out.size >= cap) break
            if (!usable(u)) continue
            val n = normalize(u) ?: continue
            if (seen.add(n)) out += u
        }
        return out
    }

    fun planPrompt(topic: String, n: Int, mode: ResearchMode): String =
        "You plan a ${mode.id} research report on the topic below. Split it into at most $n distinct, self-contained web-research " +
            "sub-questions that together cover the topic thoroughly (background, current state, key facts/figures, different viewpoints, " +
            "risks/limitations, recent developments). No overlap. Reply ONLY with a JSON array of strings, nothing else.\n\nTopic: $topic"

    fun followUpPrompt(topic: String, notes: String, n: Int): String =
        "Research topic: $topic\n\nNotes gathered so far (from web sources):\n$notes\n\n" +
            "List at most $n NEW follow-up web-research questions that fill the most important gaps, contradictions or missing " +
            "details in these notes. Do not repeat what is already covered. Reply ONLY with a JSON array of strings ([] if nothing important is missing)."

    fun workerTask(topic: String, question: String, sources: Int, skip: Collection<String>): String = buildString {
        append("Overall research topic: $topic\nYour sub-question: $question\n\n")
        append("Use web_search (try 1-3 different queries), pick the $sources most relevant and trustworthy results (prefer primary sources, ")
        append("official sites, papers, reputable news; skip ads, SEO spam, forums with no substance, paywalled stubs), then web_fetch each ")
        append("one (format=markdown) and read it. Skip a page that fails to load and take the next result.\n")
        if (skip.isNotEmpty()) append("Already covered by other researchers, do not fetch again: ${skip.take(40).joinToString(" ")}\n")
        append("\nReply in this exact format, one block per source you actually read:\n")
        append("### <page title>\nURL: <exact url>\n- <key fact, figure, date or claim from that page>\n- <more facts, 3-8 bullets>\n\n")
        append("End with a line 'Summary:' and 2-4 sentences answering the sub-question. No information that is not from the pages.")
    }

    fun reportPrompt(topic: String, mode: ResearchMode, notes: String, sources: List<String>, language: String?): String = buildString {
        append("Write a ${if (mode == ResearchMode.DEEP) "long, very detailed (2000-4000 words)" else "detailed (1000-2000 words)"} research report ")
        append("in Markdown on: $topic\n\n")
        append("Use ONLY the research notes below. Structure: '# <title>', a short executive summary, then sections with '##' headings ")
        append("(and tables where useful), then 'Key takeaways' and 'Open questions / limitations'. Cite claims inline with the source ")
        append("number in square brackets like [3], using the numbered source list below. Never invent sources, numbers or quotes; ")
        append("if notes conflict, say so. Do NOT add your own sources list at the end (it is appended automatically).")
        language?.let { append(" Write in $it.") }
        append("\n\nNumbered sources:\n")
        sources.forEachIndexed { i, s -> append("[${i + 1}] $s\n") }
        append("\nResearch notes:\n").append(notes)
    }

    /** Parses a JSON array of strings out of a model reply (tolerates ```json fences, prose around it, or numbered lines). */
    fun parseQuestions(reply: String?, max: Int): List<String> {
        val t = reply?.trim().orEmpty()
        if (t.isEmpty()) return emptyList()
        val start = t.indexOf('['); val end = t.lastIndexOf(']')
        val fromJson = if (start >= 0 && end > start) runCatching {
            (Json.parseToJsonElement(t.substring(start, end + 1)) as JsonArray).mapNotNull { e ->
                runCatching { e.jsonPrimitive.content }.getOrNull()
                    ?: (e as? JsonObject)?.values?.firstOrNull()?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            }
        }.getOrNull() else null
        val list = fromJson ?: t.lines().map { it.trim().removePrefix("-").removePrefix("*").replace(Regex("""^\d+[.)]\s*"""), "").trim() }
            .filter { it.length > 8 && it.endsWith("?") }
        return list.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(max)
    }

    /** Notes for the report writer, trimmed to [maxChars], with source numbers mapped from [sources]. */
    fun notes(findings: List<Pair<String, String>>, maxChars: Int = REPORT_INPUT_CHARS): String {
        val per = (maxChars / findings.size.coerceAtLeast(1)).coerceAtLeast(500)
        return findings.joinToString("\n\n") { (q, f) -> "## Sub-question: $q\n${f.take(per)}" }.take(maxChars)
    }

    fun sourcesSection(sources: List<String>): String =
        if (sources.isEmpty()) "" else "\n\n## Sources\n\n" + sources.mapIndexed { i, s -> "${i + 1}. <$s>" }.joinToString("\n")

    /** Fallback when the report model fails: the raw notes, still cited. */
    fun fallbackReport(topic: String, findings: List<Pair<String, String>>, why: String): String =
        "# Research notes: $topic\n\n> The report writer failed ($why), so these are the collected notes per sub-question.\n\n" +
            findings.joinToString("\n\n") { (q, f) -> "## $q\n\n$f" }

    fun fileName(topic: String, stamp: String): String {
        val slug = topic.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(50).trimEnd('-').ifEmpty { "research" }
        return "$stamp-$slug.md"
    }
}

/** Live progress of a deep_research run (chat card + task subtitle). */
data class ResearchProgress(val phase: String, val round: Int = 0, val rounds: Int = 0, val workers: SubAgentProgress? = null, val sources: Int = 0) {
    val label: String get() = buildString {
        append("🔎 ").append(phase)
        if (rounds > 1 && round > 0) append(" · round $round/$rounds")
        workers?.let {
            append(" · ${it.done}/${it.total} questions"); if (it.running > 0) append(" (${it.running} running)")
            if (it.waiting > 0) append(" · ${it.waiting} waiting for rate limit")
        }
        if (sources > 0) append(" · $sources sources")
    }
}

/** Outcome of one research run. */
data class ResearchOutcome(
    val topic: String,
    val mode: ResearchMode,
    val report: String,
    val sources: List<String>,
    val questions: List<String>,
    val rounds: Int,
    val failedWorkers: Int,
    val reportFallback: Boolean,
)

/**
 * The pipeline, independent of Android: plan (main model) → parallel workers (sub-agent loops on the worker model, web_search +
 * web_fetch, with the sub-agent repeat guard / step limit / timeout / rate-limit backoff) → dedupe + filter sources → optional
 * follow-up rounds (deep) → report (main model) + numbered Sources list. Cancelling the caller cancels every worker.
 */
class DeepResearchEngine(
    /** Main model (the chat's model); messages → outcome. */
    private val main: suspend (List<ApiMessage>) -> ChatOutcome,
    /** One worker: task prompt → sub-agent result (findings + verified source URLs). */
    private val worker: suspend (index: Int, task: String) -> SubAgentResult,
    private val onProgress: suspend (ResearchProgress) -> Unit = {},
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
    /** Workers sleeping on a rate limit (shown in progress instead of failures). */
    private val waits: com.verdroid.app.agent.subagents.RateWaitCounter? = null,
) {
    suspend fun run(topic: String, mode: ResearchMode, limits: ResearchLimits, language: String? = null): ResearchOutcome {
        onProgress(ResearchProgress("Planning", 1, limits.rounds))
        val planned = ResearchPolicy.parseQuestions(ask(ResearchPolicy.planPrompt(topic, limits.questions, mode)), limits.questions)
        var questions = planned.ifEmpty { listOf(topic) }
        val allQuestions = mutableListOf<String>()
        val findings = mutableListOf<Pair<String, String>>()
        val sources = mutableListOf<String>()
        var failed = 0
        var roundsDone = 0
        val failedQs = mutableListOf<String>()
        for (round in 1..limits.rounds) {
            if (questions.isEmpty() || sources.size >= limits.maxSources) break
            roundsDone = round
            allQuestions += questions
            val qs = questions
            val results = SubAgentPool.run(qs, limits.concurrency,
                onProgress = { p -> onProgress(ResearchProgress("Researching", round, limits.rounds, p, sources.size)) },
                failed = { r: SubAgentResult -> r.status != SubAgentResult.Status.OK },
                waits = waits,
            ) { i, q -> worker(i, ResearchPolicy.workerTask(topic, q, limits.perWorkerSources, sources)) }
            for ((i, r) in results.withIndex()) {
                if (r.status != SubAgentResult.Status.OK) { failedQs += qs[i]; continue }
                val room = limits.maxSources - sources.size
                sources += ResearchPolicy.dedupe(r.sources, room.coerceAtLeast(0), sources)
                findings += qs[i] to r.findings
            }
            onProgress(ResearchProgress("Round $round done", round, limits.rounds, null, sources.size))
            if (round < limits.rounds && sources.size < limits.maxSources && findings.isNotEmpty()) {
                onProgress(ResearchProgress("Finding gaps", round + 1, limits.rounds, null, sources.size))
                val n = (limits.questions / 2).coerceAtLeast(2)
                questions = ResearchPolicy.parseQuestions(ask(ResearchPolicy.followUpPrompt(topic, ResearchPolicy.notes(findings, 20_000), n)), n)
                    .filter { q -> allQuestions.none { it.equals(q, ignoreCase = true) } }
            }
        }
        // Failed sub-questions (rate limits, timeouts, errors) get one more try at the end.
        if (failedQs.isNotEmpty() && sources.size < limits.maxSources) {
            val retry = failedQs.toList()
            val results = SubAgentPool.run(retry, limits.concurrency,
                onProgress = { p -> onProgress(ResearchProgress("Retrying failed questions", roundsDone, limits.rounds, p, sources.size)) },
                failed = { r: SubAgentResult -> r.status != SubAgentResult.Status.OK },
                waits = waits,
            ) { i, q -> worker(i, ResearchPolicy.workerTask(topic, q, limits.perWorkerSources, sources)) }
            for ((i, r) in results.withIndex()) {
                if (r.status != SubAgentResult.Status.OK) { failed++; continue }
                sources += ResearchPolicy.dedupe(r.sources, (limits.maxSources - sources.size).coerceAtLeast(0), sources)
                findings += retry[i] to r.findings
            }
        } else failed += failedQs.size
        onProgress(ResearchProgress("Writing report", roundsDone, limits.rounds, null, sources.size))
        var fallback = false
        val body = if (findings.isEmpty()) { fallback = true; "# Research: $topic\n\nNo sub-question returned usable findings ($failed failed). Try again later or with another worker model." }
        else ask(ResearchPolicy.reportPrompt(topic, mode, ResearchPolicy.notes(findings), sources, language))?.trim()?.takeIf { it.length > 200 }
            ?: run { fallback = true; ResearchPolicy.fallbackReport(topic, findings, lastError.ifEmpty { "empty reply" }) }
        return ResearchOutcome(topic, mode, body + ResearchPolicy.sourcesSection(sources), sources, allQuestions, roundsDone, failed, fallback)
    }

    private var lastError = ""

    /** Main-model call with backoff on rate limits (2 s, 4 s, 8 s … or the reset time, ≤60 s), one retry on other failures. */
    private suspend fun ask(prompt: String): String? {
        var attempt = 0; var failures = 0
        val msgs = listOf(ApiMessage("system", "You are a careful research assistant. Follow the output format exactly."), ApiMessage("user", prompt))
        while (true) {
            val o = try { main(msgs) } catch (e: CancellationException) { throw e } catch (e: Exception) { ChatOutcome.Failure(e.message ?: "error") }
            when (o) {
                is ChatOutcome.Success -> return o.completion.content
                is ChatOutcome.NoApiKey -> { lastError = "no API key / model"; return null }
                is ChatOutcome.RateLimited -> {
                    if (o.dailyQuota || attempt >= 4) { lastError = "rate limited: ${o.message}"; return null }
                    sleep(com.verdroid.app.agent.subagents.SubAgentLoop.backoffMs(attempt, o.resumeAt - now())); attempt++
                }
                is ChatOutcome.Failure -> { if (failures >= 1) { lastError = o.message; return null }; failures++; sleep(2_000) }
            }
        }
    }

    companion object {
        fun host(url: String): String = runCatching { URI(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
    }
}
