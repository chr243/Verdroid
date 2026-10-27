package com.verdroid.app.agent.research

import com.verdroid.app.agent.tools.AgentTool
import com.verdroid.app.agent.tools.ToolContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

sealed interface DeepResearchResult {
    data class Done(val outcome: ResearchOutcome, val savedPath: String?, val workerModel: String?) : DeepResearchResult
    data class Error(val message: String) : DeepResearchResult
}

fun interface DeepResearchBackend {
    suspend fun research(taskId: Long, topic: String, mode: ResearchMode, language: String?): DeepResearchResult
}

/** deep_research (group "agents"): multi-source web research with parallel sub-agent workers → long cited Markdown report file. */
class DeepResearchTool(private val backend: DeepResearchBackend) : AgentTool {
    override val name = ResearchPolicy.TOOL_NAME
    override val description =
        "Deep research: produces a long, detailed Markdown report with numbered, cited web sources and saves it to " +
            "Documents/Verdroid/Output/Research/. Plans sub-questions, runs parallel sub-agent workers (web_search + web_fetch) that read and " +
            "summarize many pages, filters/dedupes URLs, then writes the report. mode=standard (~25 sources, one round, a few minutes) or " +
            "mode=deep (more sub-questions, follow-up rounds, up to ~100 sources, slow). Use it when the user asks for research, a report or an " +
            "in-depth comparison; choose mode=deep when they ask for deep/thorough/exhaustive/comprehensive research, otherwise standard. Not for a quick fact. Call it once per topic; after it returns, show the user " +
            "the report (or its key sections if very long) and the saved file path. Never call it again for the same topic in a loop."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("topic", buildJsonObject { put("type", "string"); put("description", "Self-contained research topic/question, with any constraints the user gave.") })
            put("mode", buildJsonObject {
                put("type", "string"); put("enum", buildJsonArray { add(JsonPrimitive("standard")); add(JsonPrimitive("deep")) })
                put("description", "standard (default) or deep")
            })
            put("language", buildJsonObject { put("type", "string"); put("description", "Optional report language, e.g. English, Arabic (default: the topic's language).") })
        })
        put("required", buildJsonArray { add(JsonPrimitive("topic")) })
    }

    override suspend fun execute(args: JsonObject): String = execute(args, ToolContext(0L))

    override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
        fun s(k: String) = runCatching { args[k]?.jsonPrimitive?.content }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val topic = s("topic") ?: return buildJsonObject { put("error", "topic is required") }.toString()
        return when (val r = backend.research(ctx.taskId, topic.take(2_000), ResearchMode.of(s("mode")), s("language"))) {
            is DeepResearchResult.Error -> buildJsonObject { put("error", r.message) }.toString()
            is DeepResearchResult.Done -> resultJson(r).toString()
        }
    }

    companion object {
        fun resultJson(r: DeepResearchResult.Done): JsonObject = buildJsonObject {
            val o = r.outcome
            put("topic", o.topic); put("mode", o.mode.id)
            put("rounds", o.rounds); put("sub_questions", o.questions.size); put("failed_workers", o.failedWorkers)
            put("sources_count", o.sources.size)
            if (o.failures.isNotEmpty()) put("failed_worker_details", JsonArray(o.failures.map { (q, f) ->
                buildJsonObject { put("question", q.take(200)); put("reason", f.summary); put("failure", f.json()) }
            }))
            r.workerModel?.let { put("worker_model", it) }
            put("saved_to", r.savedPath ?: "not saved (no storage access)")
            if (o.reportFallback) put("note", "The report writer failed; the report contains the raw notes per sub-question.")
            put("sources", JsonArray(o.sources.map(::JsonPrimitive)))
            val truncated = o.report.length > ResearchPolicy.RESULT_REPORT_CHARS
            put("report_truncated", truncated)
            put("report", if (truncated) o.report.take(ResearchPolicy.RESULT_REPORT_CHARS) + "\n…(full report in the saved file)" else o.report)
        }

        /** "Deep research done · 27 sources" (chat card after the run). */
        fun summary(resultJson: String?): String? = runCatching {
            val o = kotlinx.serialization.json.Json.parseToJsonElement(resultJson ?: return null) as JsonObject
            if (o["error"] != null) return null
            val n = o["sources_count"]?.jsonPrimitive?.content ?: return null
            val failed = o["failed_workers"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            "🔎 Research done · $n sources" + (if (failed > 0) " · $failed failed" else "") +
                " · ${o["saved_to"]?.jsonPrimitive?.content.orEmpty().substringAfterLast('/')}"
        }.getOrNull()

        fun failedWorkers(resultJson: String?): List<Pair<String, String>> = com.verdroid.app.agent.subagents.FailedWorkersJson.parse(resultJson)
    }
}
