package com.verdroid.app.agent.research

import com.verdroid.app.agent.subagents.SubAgentPool
import com.verdroid.app.agent.subagents.SubAgentProgress
import com.verdroid.app.agent.subagents.SubAgentResult
import com.verdroid.app.data.network.ApiMessage
import com.verdroid.app.data.network.ChatOutcome
import com.verdroid.app.agent.subagents.FailureKind
import com.verdroid.app.agent.subagents.FailureText
import com.verdroid.app.agent.subagents.WorkerFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
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
    /** Overall wall-clock budget of the run (0 = none): near it, no new workers/rounds start and stragglers are stopped. */
    val budgetMs: Long = 0L,
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
    const val WORKER_FINDINGS_CHARS = 8_000
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
        // Keep a closing parenthesis that balances one in the URL (Wikipedia "Foo_(bar)"); drop only unmatched trailing ones.
        var raw = url.trim().trimEnd('.', ',', ';', ']')
        while (raw.endsWith(")") && raw.count { it == '(' } < raw.count { it == ')' }) raw = raw.dropLast(1).trimEnd('.', ',', ';', ']')
        val u = URI(raw)
        val scheme = u.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = u.host?.lowercase()?.removePrefix("www.") ?: return null
        val q = u.rawQuery?.split('&')?.filter { p -> p.isNotEmpty() && !TRACKING.matches(p.substringBefore('=')) }?.sorted()?.joinToString("&")
        val path = (u.rawPath ?: "").trimEnd('/')
        "https://$host$path" + (if (q.isNullOrEmpty()) "" else "?$q")
    }.getOrNull()

    fun isSearchPage(url: String): Boolean = SEARCH_PAGE.containsMatchIn(url.lowercase())

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
        append("First decide exactly which entity or meaning the sub-question is about (e.g. the company, not the fruit) and put it in ")
        append("\"entity\"; add disambiguating words to your queries. Use web_search (try 1-3 different queries), pick the $sources most ")
        append("relevant and trustworthy results (prefer primary sources, official sites, papers, reputable news; skip ads, SEO spam, ")
        append("disambiguation pages, search-result pages, paywalled stubs), then web_fetch each one (format=markdown) and read it. ")
        append("Skip a page that fails to load and take the next result.\n")
        if (skip.isNotEmpty()) append("Already covered by other researchers, do not fetch again: ${skip.take(40).joinToString(" ")}\n")
        append("\nRules: no URL, no claim. Every claim comes from ONE page you fetched and carries that page's exact URL; never combine ")
        append("facts from different pages into one claim. \"quote\" is a short sentence copied verbatim from that page (or \"\"). ")
        append("If the pages do not answer the sub-question, set \"insufficient_evidence\": true with empty findings; that is fine.\n")
        append("Reply ONLY with JSON in this shape (5-15 findings):\n")
        append("{\"entity\":\"Mercury (planet)\",\"findings\":[{\"claim\":\"Mercury orbits the Sun in about 88 days.\",")
        append("\"url\":\"https://en.wikipedia.org/wiki/Mercury_(planet)\",\"quote\":\"Mercury orbits the Sun once every 88 days\",")
        append("\"confidence\":\"high\"}],\"insufficient_evidence\":false,\"summary\":\"2-4 sentences answering the sub-question.\"}")
    }

    /** Report prompt for a registry-backed run: cite only the [S<id>] markers of the listed sources. */
    fun groundedReportPrompt(topic: String, mode: ResearchMode, notes: String, sources: List<SourceEntry>, language: String?): String = buildString {
        append("Write a ${if (mode == ResearchMode.DEEP) "long, very detailed (2000-4000 words)" else "detailed (1000-2000 words)"} research report ")
        append("in Markdown on: $topic\n\n")
        append("Use ONLY the research notes below. Structure: '# <title>', '## Executive summary', then sections with '##' headings ")
        append("(and tables where useful), then '## Key takeaways' and '## Open questions / limitations'. Cite every claim inline with ")
        append("the marker of its source exactly as given, like [S3] or [S3, S7]. Never write URLs, never invent sources, numbers or quotes. ")
        append("Only quote text marked 'verified quote'. If notes conflict, say so. Do NOT add a sources list (the app adds it).")
        language?.let { append(" Write in $it.") }
        append("\n\nSources:\n")
        sources.forEach { append("[S${it.id}] ${it.label.take(120)} (${DeepResearchEngine.host(it.finalUrl)})\n") }
        append("\nResearch notes:\n").append(notes)
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
    /** Sub-questions that still failed after the retry pass, with the reason. */
    val failures: List<Pair<String, com.verdroid.app.agent.subagents.WorkerFailure>> = emptyList(),
    /** Registry-backed runs: honest numbers + warnings (null for legacy runs without a registry). */
    val diagnostics: ResearchDiagnostics? = null,
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
    /** Called for every failed worker (question, reason, after retry?) — diagnostic log. */
    private val onWorkerFailed: suspend (String, com.verdroid.app.agent.subagents.WorkerFailure, Boolean) -> Unit = { _, _, _ -> },
    /** Source registry + web tool state of this run; null = legacy (no grounding). */
    private val reliability: ResearchRunState? = null,
    /** web_fetch for cited-but-never-fetched URLs (capped); null = skip. */
    private val verifyExec: (suspend (String, String) -> com.verdroid.app.agent.tools.ToolResult)? = null,
    /** The report writer (main model) is a free model: stamp a note on the report. */
    private val writerIsFree: Boolean = false,
) {
    suspend fun run(topic: String, mode: ResearchMode, limits: ResearchLimits, language: String? = null): ResearchOutcome {
        val budget = RunBudget(limits.budgetMs, now)
        onProgress(ResearchProgress("Planning", 1, limits.rounds))
        val planned = ResearchPolicy.parseQuestions(ask(ResearchPolicy.planPrompt(topic, limits.questions, mode)), limits.questions)
        var questions = planned.ifEmpty { listOf(topic) }
        val allQuestions = mutableListOf<String>()
        val findings = mutableListOf<Pair<String, String>>()
        val sources = mutableListOf<String>()
        var failed = 0
        var roundsDone = 0
        val failedQs = mutableListOf<String>()
        val firstFailure = mutableMapOf<String, WorkerFailure>()
        val finalFailures = mutableListOf<Pair<String, WorkerFailure>>()
        val warnings = mutableListOf<String>()
        fun failureOf(r: SubAgentResult) = r.failure ?: WorkerFailure(FailureKind.OTHER, FailureText.sanitize(r.findings))
        fun budgetFail(i: Int, q: String, started: Boolean): SubAgentResult {
            budget.hit = true
            val why = if (started) "stopped after ${budget.elapsed() / 1000} s: run time budget (${limits.budgetMs / 60_000} min) reached"
                else "not started: run time budget (${limits.budgetMs / 60_000} min) nearly used up"
            return SubAgentResult(i, q, SubAgentResult.Status.TIMEOUT, why, failure = WorkerFailure(FailureKind.RUN_BUDGET, why))
        }
        suspend fun runWorker(i: Int, q: String): SubAgentResult {
            val task = ResearchPolicy.workerTask(topic, q, limits.perWorkerSources, sources)
            if (!budget.limited) return worker(i, task)
            val left = budget.workerLeft()
            if (left < RunBudget.MIN_WORKER_MS) return budgetFail(i, q, false)
            return withTimeoutOrNull(left) { worker(i, task) } ?: budgetFail(i, q, true)
        }
        val failedPred = { r: SubAgentResult -> r.status != SubAgentResult.Status.OK }
        for (round in 1..limits.rounds) {
            if (questions.isEmpty() || sources.size >= limits.maxSources) break
            if (round > 1 && budget.limited && budget.workerLeft() < limits.budgetMs / 4) {
                budget.hit = true; warnings += "Skipped follow-up round $round to stay within the run time budget."; break
            }
            roundsDone = round
            allQuestions += questions
            val qs = questions
            val results = SubAgentPool.run(qs, limits.concurrency,
                onProgress = { p -> onProgress(ResearchProgress("Researching", round, limits.rounds, p, sources.size)) },
                failed = failedPred, waits = waits,
            ) { i, q -> runWorker(i, q) }
            for ((i, r) in results.withIndex()) {
                if (r.status != SubAgentResult.Status.OK) { failedQs += qs[i]; firstFailure[qs[i]] = failureOf(r); onWorkerFailed(qs[i], failureOf(r), false); continue }
                val room = limits.maxSources - sources.size
                sources += ResearchPolicy.dedupe(r.sources, room.coerceAtLeast(0), sources)
                findings += qs[i] to r.findings
            }
            onProgress(ResearchProgress("Round $round done", round, limits.rounds, null, sources.size))
            val timeForMore = !budget.limited || budget.workerLeft() >= limits.budgetMs / 4
            if (round < limits.rounds && sources.size < limits.maxSources && findings.isNotEmpty() && timeForMore) {
                onProgress(ResearchProgress("Finding gaps", round + 1, limits.rounds, null, sources.size))
                val n = (limits.questions / 2).coerceAtLeast(2)
                questions = ResearchPolicy.parseQuestions(ask(ResearchPolicy.followUpPrompt(topic, ResearchPolicy.notes(findings, 20_000), n)), n)
                    .filter { q -> allQuestions.none { it.equals(q, ignoreCase = true) } }
            } else if (round < limits.rounds && !timeForMore) { budget.hit = true; warnings += "Skipped follow-up rounds to stay within the run time budget."; break }
        }
        // Failed sub-questions (rate limits, timeouts, errors) get one more try at the end, if the time budget allows.
        val retryable = failedQs.filter { firstFailure[it]?.kind != FailureKind.RUN_BUDGET }
        val retryTime = !budget.limited || budget.workerLeft() >= 60_000
        if (retryable.isNotEmpty() && sources.size < limits.maxSources && retryTime) {
            val retry = retryable
            val results = SubAgentPool.run(retry, limits.concurrency,
                onProgress = { p -> onProgress(ResearchProgress("Retrying failed questions", roundsDone, limits.rounds, p, sources.size)) },
                failed = failedPred, waits = waits,
            ) { i, q -> runWorker(i, q) }
            for ((i, r) in results.withIndex()) {
                if (r.status != SubAgentResult.Status.OK) {
                    val f = failureOf(r)
                    val first = firstFailure[retry[i]]
                    val merged = if (first != null && first.kind != f.kind) f.copy(detail = f.detail + " (first try: ${first.kind.label})") else f
                    finalFailures += retry[i] to merged.copy(attempts = merged.attempts + (first?.attempts ?: 0))
                    onWorkerFailed(retry[i], merged, true)
                    continue
                }
                sources += ResearchPolicy.dedupe(r.sources, (limits.maxSources - sources.size).coerceAtLeast(0), sources)
                findings += retry[i] to r.findings
            }
            (failedQs - retry.toSet()).forEach { q -> firstFailure[q]?.let { finalFailures += q to it } }
        } else {
            val why = when { retryable.isEmpty() -> ""; !retryTime -> " (not retried: run time budget)"; else -> " (not retried: source limit reached)" }
            failedQs.forEach { q -> firstFailure[q]?.let { finalFailures += q to it.copy(detail = it.detail + why) } }
        }
        failed = finalFailures.size
        onProgress(ResearchProgress("Writing report", roundsDone, limits.rounds, null, sources.size))
        if (reliability != null) return finishGrounded(topic, mode, language, findings, allQuestions, roundsDone, finalFailures, budget, warnings)
        var fallback = false
        val body = if (findings.isEmpty()) { fallback = true; "# Research: $topic\n\nNo sub-question returned usable findings ($failed failed). Try again later or with another worker model." }
        else ask(ResearchPolicy.reportPrompt(topic, mode, ResearchPolicy.notes(findings), sources, language))?.trim()?.takeIf { it.length > 200 }
            ?: run { fallback = true; ResearchPolicy.fallbackReport(topic, findings, lastError.ifEmpty { "empty reply" }) }
        return ResearchOutcome(topic, mode, body + ResearchPolicy.sourcesSection(sources) +
            FailureText.markdownSection(finalFailures), sources, allQuestions, roundsDone, failed, fallback, finalFailures)
    }

    /** Registry-backed finish: grounded notes → writer ([S<id>] markers) → bibliography of cited OK sources, quote marks, run notes. */
    private suspend fun finishGrounded(
        topic: String, mode: ResearchMode, language: String?, findings: List<Pair<String, String>>, questions: List<String>, rounds: Int,
        failures: List<Pair<String, WorkerFailure>>, budget: RunBudget, warnings: MutableList<String>,
    ): ResearchOutcome {
        val state = reliability!!
        val reg = state.registry
        val parsed = findings.map { (q, f) -> Grounding.Note(q, WorkerOutput.parse(f)) }
        fun left(): Long = if (budget.limited) (budget.workerLeft() + budget.reserveMs / 2).coerceAtLeast(0) else Long.MAX_VALUE
        // Deterministic checks before writing (no model calls), all within one capped verification window:
        // cited URLs nobody fetched, then whole documents for quoted pages only seen partially.
        val exec = verifyExec
        if (exec != null) {
            val cited = parsed.flatMap { it.parsed.findings }.filter { it.url.isNotBlank() && Urls.malformed(it.url) == null && ResearchPolicy.usable(it.url) }
            val unknown = cited.map { it.url }.filter { reg.get(it) == null }.distinct().take(MAX_VERIFY_FETCHES)
            val cap = minOf(VERIFY_TIME_MS, left())
            if (cap > 0) withTimeoutOrNull(cap) {
                var used = 0
                for (u in unknown) { state.verifyFetch(u, exec); used++ }
                val partial = cited.filter { it.quote.isNotBlank() }.mapNotNull { reg.get(it.url) }.filter { it.ok && !it.complete }.distinctBy { it.id }
                for (e in partial.take((MAX_VERIFY_FETCHES - used).coerceAtLeast(0))) state.fullText(e.url, exec)
            }
        }
        // Source diversity: one small capped step when a single domain dominates and time remains.
        var diversity = SourceDiversity.of(reg.all().filter { it.ok })
        val extra = mutableListOf<SourceEntry>()
        if (exec != null && diversity.share > SourceDiversity.LOW_SHARE && reg.all().count { it.ok } >= 2 && left() >= DIVERSIFY_MIN_LEFT_MS) {
            val ids = withTimeoutOrNull(minOf(DIVERSIFY_TIME_MS, left() / 2)) { state.diversify(topic, diversity.top, exec) }.orEmpty()
            extra += ids.mapNotNull { reg.byId(it) }
            diversity = SourceDiversity.of(reg.all().filter { it.ok })
        }
        val stats = Grounding.Stats()
        val (notes, noteSources) = Grounding.notes(parsed, reg, stats, extra = extra)
        var fallback = false
        val raw = if (noteSources.isEmpty()) {
            fallback = true
            "# Research: $topic\n\nNo finding came with a source that loaded correctly (${failures.size} workers failed). Try again later or with another worker model."
        } else ask(ResearchPolicy.groundedReportPrompt(topic, mode, notes, noteSources, language))?.trim()?.takeIf { it.length > 200 }
            ?: run { fallback = true; "# Research notes: $topic\n\n> The report writer failed (${lastError.ifEmpty { "empty reply" }}), so these are the grounded notes.\n\n$notes" }
        val quoted = Grounding.markQuotes(raw, reg, stats)
        val rendered = Grounding.bibliography(quoted, reg)
        val table = SourceTable.build(reg, rendered.cited.map { it.id }.toSet())
        val citedDiversity = SourceDiversity.of(rendered.cited).takeIf { rendered.cited.isNotEmpty() } ?: diversity
        // Gaps: every "tried and got nothing" path, with the queries tried.
        fun qs(q: String) = state.queriesBy[q].orEmpty().toList()
        val gaps = buildList {
            state.weakRecall.sorted().forEach { add(Gap("weak_recall_after_reformulation", it, "search results stayed off-topic", qs(it))) }
            parsed.filter { n -> n.parsed.findings.none { f -> reg.get(f.url)?.ok == true && Urls.malformed(f.url) == null } }.forEach { n ->
                add(Gap("topic_no_source", n.question, if (n.parsed.insufficient) "worker reported insufficient_evidence" else "no finding with a usable source", qs(n.question)))
            }
            state.fetchFailedBy.forEach { (q, urls) ->
                val still = urls.filter { reg.get(it)?.ok != true }
                if (still.isNotEmpty()) add(Gap("fetch_failed", q, still.take(5).joinToString(" ") + " (live and archive fetch failed)", qs(q)))
            }
            failures.forEach { (q, f) -> add(Gap(if (f.kind == FailureKind.RUN_BUDGET) "budget_stopped" else "worker_failed", q, f.kind.label, qs(q))) }
        }
        val healthy = state.breaker.healthy()
        val degraded = questions.isNotEmpty() && state.weakRecall.size * 2 > questions.size && healthy.size < 2
        if (degraded) warnings += "Search was degraded: ${state.weakRecall.size} of ${questions.size} sub-questions got only off-topic results and ${healthy.size} search engine(s) were healthy (${healthy.joinToString().ifEmpty { "none" }}). Treat the report as incomplete."
        if (writerIsFree) warnings += "The report was written by a free model: check key claims against the cited sources."
        if (budget.hit) warnings += "Run time budget reached: ${failures.count { it.second.kind == FailureKind.RUN_BUDGET }} worker(s) were stopped or not started; the report uses what was collected."
        if (failures.isNotEmpty()) warnings += "${failures.size} of ${questions.size} sub-questions failed (see Failed workers)."
        if (fallback) warnings += "The report writer failed or had nothing grounded to write about."
        val paused = state.breaker.skipped()
        if (paused.isNotEmpty()) warnings += "Search engines still cooling down after repeated failures: ${paused.joinToString()}."
        if (rendered.cited.isNotEmpty() && (citedDiversity.share > SourceDiversity.LOW_SHARE || citedDiversity.distinct < 3))
            warnings += "source_diversity_low: ${citedDiversity.distinct} distinct domain(s); ${citedDiversity.top} = ${Math.round(citedDiversity.share * 100)}% of cited sources."
        if (stats.checked > 0 && stats.verified < stats.checked) warnings += "${stats.checked - stats.verified} quote(s) could not be verified on the pages and are marked."
        var diag = ResearchDiagnostics(
            table = table, wayback = reg.all().count { it.servedFrom == "wayback" && it.ok },
            quotesChecked = stats.checked, quotesVerified = stats.verified, quoteMisses = LinkedHashMap(stats.misses), quoteTiers = LinkedHashMap(stats.tiers),
            enginesFailed = synchronized(state.breaker) { LinkedHashMap(state.breaker.reasons) }, enginesHealthy = healthy,
            queriesIssued = state.queriesIssued, pagesReturned = state.pagesReturned, reformulations = state.reformulations, diversifyQueries = state.diversifyQueries,
            diversity = citedDiversity, weakRecall = state.weakRecall.size, gaps = gaps, searchDegraded = degraded,
            warnings = emptyList(), budgetMs = budget.budgetMs, elapsedMs = budget.elapsed(), budgetHit = budget.hit,
        )
        if (!diag.consistent) warnings += "Internal: source counters do not add up (${diag.collected} collected); please report this."
        diag = diag.copy(warnings = warnings.distinct())
        val stamp = (if (degraded) "> ⚠️ Search was degraded: most sub-questions got only off-topic results and fewer than 2 search engines were healthy. This report is incomplete.\n\n" else "") +
            (if (writerIsFree) "> ℹ️ Written by a free model: verify key claims against the numbered sources.\n\n" else "")
        val report = stamp + rendered.text + Grounding.sourcesSection(rendered.cited) + diag.runNotes() + FailureText.markdownSection(failures)
        return ResearchOutcome(topic, mode, report, rendered.cited.map { it.url }, questions, rounds, failures.size, fallback, failures, diag)
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
        const val MAX_VERIFY_FETCHES = 5
        const val VERIFY_TIME_MS = 60_000L
        const val DIVERSIFY_TIME_MS = 45_000L
        const val DIVERSIFY_MIN_LEFT_MS = 60_000L
        fun host(url: String): String = runCatching { URI(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
    }
}
