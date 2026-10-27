package com.verdroid.app.agent.subagents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI

/** Why a sub-agent / deep research worker failed (shown in the chat card, the tool result, the report and the rate-limit log). */
enum class FailureKind(val id: String, val label: String) {
    RATE_LIMIT("rate_limit", "Rate limited"),
    DAILY_QUOTA("daily_quota", "Daily free quota used up"),
    HTTP("provider_error", "Provider error"),
    NETWORK("network", "Network error"),
    NO_API_KEY("no_api_key", "No API key / model"),
    WORKER_TIMEOUT("worker_timeout", "Worker timeout"),
    WAIT_BUDGET("wait_budget", "Rate-limit wait budget exceeded"),
    STEP_LIMIT("step_limit", "Step limit reached without an answer"),
    LOOP_GUARD("loop_guard", "Stopped by loop guard (repeated tool calls)"),
    EMPTY_OUTPUT("empty_output", "Empty / unparseable model output"),
    TOOL_FAILURE("tool_failure", "Web tools failed"),
    CANCELLED("cancelled", "Cancelled"),
    OTHER("other", "Error"),
}

/**
 * One failure: [detail] is sanitized (no keys), [attempts] = model calls made, [fallback] = what happened with the fallback model
 * list (null = none configured), [toolErrors] = the last failing web_search / web_fetch calls ("web_fetch example.com: HTTP 403").
 */
data class WorkerFailure(
    val kind: FailureKind,
    val detail: String,
    val attempts: Int = 0,
    val fallback: String? = null,
    val toolErrors: List<String> = emptyList(),
    val httpStatus: Int? = null,
) {
    /** One line, e.g. "Rate limited: HTTP 429 … · 7 model calls · fallback: tried, also rate limited · web_fetch x.com: 403". */
    val summary: String get() = buildString {
        append(kind.label)
        if (detail.isNotBlank()) append(": ").append(detail)
        if (attempts > 0) append(" · ").append(attempts).append(if (attempts == 1) " model call" else " model calls")
        fallback?.let { append(" · fallback: ").append(it) }
        if (toolErrors.isNotEmpty()) append(" · tool errors: ").append(toolErrors.joinToString("; "))
    }

    fun json(): JsonObject = buildJsonObject {
        put("kind", kind.id); put("reason", kind.label)
        if (detail.isNotBlank()) put("detail", detail)
        httpStatus?.let { put("http_status", it) }
        put("attempts", attempts)
        put("fallback", fallback ?: "not configured")
        if (toolErrors.isNotEmpty()) put("tool_errors", JsonArray(toolErrors.map(::JsonPrimitive)))
    }
}

/** Pure helpers for failure text (tests). */
object FailureText {
    const val MAX_DETAIL = 300
    private val bearer = Regex("""(?i)bearer\s+[A-Za-z0-9._\-]+""")
    private val skKey = Regex("""\bsk-[A-Za-z0-9_\-]{6,}""")
    private val kv = Regex("""(?i)\b(api[_-]?key|key|token|access_token|authorization)\s*[=:]\s*["']?[^\s"'&,;]+""")
    private val longToken = Regex("""\b[A-Za-z0-9_\-]{40,}\b""")

    /** Removes anything that looks like a key or token, collapses whitespace, caps the length. */
    fun sanitize(s: String?, max: Int = MAX_DETAIL): String {
        var t = (s ?: "").replace(Regex("""\s+"""), " ").trim()
        t = bearer.replace(t, "Bearer [redacted]")
        t = skKey.replace(t, "[redacted]")
        t = kv.replace(t) { "${it.groupValues[1]}=[redacted]" }
        t = longToken.replace(t, "[redacted]")
        return if (t.length > max) t.take(max - 1) + "…" else t
    }

    /** "HTTP 429" / "429:" inside a provider message. */
    fun httpStatus(message: String?): Int? = message?.let { Regex("""\bHTTP (\d{3})\b""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    fun isNetwork(message: String?): Boolean = message?.contains("Network error", ignoreCase = true) == true ||
        message?.contains("timeout", ignoreCase = true) == true && message.contains("socket", ignoreCase = true)

    fun isEmptyOutput(message: String?): Boolean = message?.let {
        it.contains("Empty or unparsable", ignoreCase = true) || it.contains("finish_reason=error", ignoreCase = true)
    } == true

    /** A failing tool call as "web_fetch example.com: HTTP 403" (host only, never the full query string), or null if it succeeded. */
    fun toolError(name: String, argsJson: String, resultJson: String, isError: Boolean): String? {
        val err = runCatching {
            (kotlinx.serialization.json.Json.parseToJsonElement(resultJson) as? JsonObject)?.get("error")?.let { (it as? JsonPrimitive)?.content ?: it.toString() }
        }.getOrNull()
        if (!isError && err == null) return null
        val target = UrlVerifier.extract(argsJson).firstOrNull()?.let { u -> runCatching { URI(u).host?.removePrefix("www.") }.getOrNull() }
            ?: runCatching {
                ((kotlinx.serialization.json.Json.parseToJsonElement(argsJson) as? JsonObject)?.get("query") as? JsonPrimitive)?.content?.let { "\"${it.take(40)}\"" }
            }.getOrNull()
        return listOfNotNull(name, target).joinToString(" ") + ": " + sanitize(err ?: resultJson, 120)
    }

    /** "## Failed workers" Markdown section for the saved report ("" when nothing failed). */
    fun markdownSection(failed: List<Pair<String, WorkerFailure>>): String = if (failed.isEmpty()) "" else buildString {
        append("\n\n## Failed workers\n\n")
        append("${failed.size} sub-question${if (failed.size == 1) "" else "s"} could not be researched:\n\n")
        failed.forEachIndexed { i, (q, f) -> append("${i + 1}. **${q.replace("\n", " ").take(200)}** — ${f.summary}\n") }
    }

    /** Compact text for the chat card: "1. question — reason" per line. */
    fun cardLines(failed: List<Pair<String, String>>): String =
        failed.mapIndexed { i, (q, r) -> "${i + 1}. ${q.replace("\n", " ").take(80)} — $r" }.joinToString("\n")
}

/** Reads the "failed_worker_details" list ({task|question, reason}) of a spawn_subagents / deep_research result. */
object FailedWorkersJson {
    fun parse(resultJson: String?): List<Pair<String, String>> = runCatching {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(resultJson ?: return emptyList()) as JsonObject
        (o["failed_worker_details"] as? JsonArray).orEmpty().mapNotNull { e ->
            val w = e as? JsonObject ?: return@mapNotNull null
            val q = ((w["task"] ?: w["question"]) as? JsonPrimitive)?.content.orEmpty()
            val r = (w["reason"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            q to r
        }
    }.getOrDefault(emptyList())
}

/** Writes a failed sub-agent / research worker to the rate-limit & diagnostics log (Settings → Rate-limit log). */
suspend fun com.verdroid.app.domain.repository.RateLimitRepository.logWorkerFailure(
    taskId: Long, source: String, label: String, model: String?, f: WorkerFailure, outcome: String,
) {
    runCatching {
        log(com.verdroid.app.domain.model.RateLimitEvent(0, System.currentTimeMillis(), taskId.takeIf { it > 0 }, model ?: "-", source,
            f.httpStatus ?: 0, null, null, null, "worker_${f.kind.id}", FailureText.sanitize("$label — ${f.summary}", 600), outcome))
    }
}
