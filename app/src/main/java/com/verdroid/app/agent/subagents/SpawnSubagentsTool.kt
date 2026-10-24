package com.verdroid.app.agent.subagents

import com.verdroid.app.agent.tools.AgentTool
import com.verdroid.app.agent.tools.ToolContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the tool needs to run sub-agents (the app wires [SubAgentService]; tests use fakes). */
fun interface SubAgentBackend {
    suspend fun runAll(parentTaskId: Long, tasks: List<SubAgentTask>, modelOverride: String?): SubAgentBatch
}

data class SubAgentBatch(val results: List<SubAgentResult>, val model: String?, val concurrency: Int, val tools: Set<String>)

/**
 * spawn_subagents: runs several independent research tasks in parallel as short sub-agent loops (read-only tools) and
 * returns their findings. Group "agents" in Settings → Tools / Tool presets / Profiles. Never offered to a sub-agent.
 */
class SpawnSubagentsTool(
    private val maxPerCall: () -> Int = { SubAgentPolicy.DEFAULT_PER_CALL },
    /** Settings → Sub-agents allowed tools (shown to the model so it can pick per task). */
    private val allowedTools: () -> Set<String> = { SubAgentPolicy.DEFAULT_TOOLS },
    private val backend: SubAgentBackend,
) : AgentTool {
    override val name = SubAgentPolicy.TOOL_NAME
    override val description: String get() = baseDescription +
        " ALWAYS give every task a minimal tools list with only what that task needs, e.g. scraping a known site/URL = " +
        "[\"web_fetch\"]; general research = [\"web_search\", \"web_fetch\"]; a local file = [\"read_file\"]. Fewer tools = faster, cheaper, " +
        "safer sub-agents; never pass every tool. Allowed names: " +
        (allowedTools() intersect SubAgentPolicy.READ_ONLY_TOOLS - SubAgentPolicy.TOOL_NAME).sorted().joinToString().ifEmpty { "(none enabled)" } +
        ". A task without tools gets only web_search + web_fetch. Each result lists verified source URLs; URLs marked [unverified] were not seen by the sub-agent."
    private val baseDescription =
        "Run several independent research tasks in parallel as sub-agents, each with its own short context and " +
            "read-only tools (web search/fetch, reading files, PDFs, memory). Use it to split broad research (compare several products, " +
            "check several sources, summarize several pages) into self-contained tasks; each task must be fully understandable on its own. " +
            "Returns concise findings per task with source links or quotes. Sub-agents cannot write, post, run commands or spawn sub-agents. " +
            "Not worth it for a single quick lookup."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("tasks", buildJsonObject {
                put("type", "array")
                put("description", "One entry per sub-agent (the user sets the maximum per call, default ${SubAgentPolicy.DEFAULT_PER_CALL}): " +
                    "{\"task\": self-contained task, \"tools\": minimal list of tool names that task needs}.")
                // Schema asks for task + tools; the parser still accepts plain strings / missing tools (→ web_search + web_fetch).
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("task", buildJsonObject { put("type", "string") })
                        put("tools", buildJsonObject {
                            put("type", "array"); put("items", buildJsonObject { put("type", "string") })
                            put("description", "Only the tools this task needs, e.g. [\"web_fetch\"] for a known URL.")
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("task")); add(JsonPrimitive("tools")) })
                })
            })
            put("context", buildJsonObject {
                put("type", "string"); put("description", "Optional shared background given to every sub-agent.")
            })
            put("model", buildJsonObject {
                put("type", "string")
                put("description", "Optional model id for this call only (e.g. openrouter/free). Default: Settings → Sub-agents → Model.")
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("tasks")) })
    }

    override suspend fun execute(args: JsonObject): String = execute(args, ToolContext(0L))

    override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
        val parsed = SubAgentArgs.parse(args, maxPerCall()).getOrElse { return buildJsonObject { put("error", it.message ?: "bad arguments") }.toString() }
        val prompts = parsed.tasks.mapIndexed { i, t -> SubAgentTask(SubAgentArgs.taskPrompt(t, parsed.context), parsed.tools[i]) }
        val batch = backend.runAll(ctx.taskId, prompts, parsed.model)
        return resultJson(parsed.tasks, batch).toString()
    }

    companion object {
        fun resultJson(tasks: List<String>, b: SubAgentBatch): JsonObject = buildJsonObject {
            put("total", b.results.size)
            put("done", b.results.size)
            put("ok", b.results.count { it.status == SubAgentResult.Status.OK })
            put("failed", b.results.count { it.status != SubAgentResult.Status.OK })
            b.model?.let { put("model", it) }
            put("concurrency", b.concurrency)
            put("allowed_tools", JsonArray(b.tools.sorted().map(::JsonPrimitive)))
            put("results", JsonArray(b.results.map { r ->
                buildJsonObject {
                    put("index", r.index + 1)
                    put("task", tasks.getOrElse(r.index) { r.task }.take(300))
                    put("status", r.status.name.lowercase())
                    put("steps", r.steps)
                    r.model?.let { put("model", it) }
                    put(if (r.status == SubAgentResult.Status.OK) "findings" else "error", r.findings)
                    put("sources", JsonArray(r.sources.map(::JsonPrimitive)))
                    if (r.corrected.isNotEmpty()) put("corrected_urls", buildJsonObject { r.corrected.forEach { (k, v) -> put(k, v) } })
                    if (r.unverified.isNotEmpty()) put("unverified_urls", JsonArray(r.unverified.map(::JsonPrimitive)))
                    put("tools", JsonArray(r.tools.sorted().map(::JsonPrimitive)))
                    if (r.rejectedTools.isNotEmpty()) put("rejected_tools", buildJsonObject { r.rejectedTools.forEach { (k, v) -> put(k, v) } })
                }
            }))
        }

        /** "Sub-agents 3/3 done · 1 failed" from a finished result (chat card). */
        fun summary(resultJson: String?): String? = runCatching {
            val o = kotlinx.serialization.json.Json.parseToJsonElement(resultJson ?: return null) as JsonObject
            val total = (o["total"] as? JsonPrimitive)?.content?.toInt() ?: return null
            val failed = (o["failed"] as? JsonPrimitive)?.content?.toInt() ?: 0
            "Sub-agents $total/$total done" + if (failed > 0) " · $failed failed" else ""
        }.getOrNull()
    }
}
