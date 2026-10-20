package com.verdroid.app.data.localllm

import com.verdroid.app.agent.AgentLoop
import com.verdroid.app.data.network.ApiMessage
import com.verdroid.app.data.network.ImageStrip
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Prompt shaping for small on-device models (pure, JVM-tested): a short system prompt, a compact core tool set with
 * one-line descriptions, and history trimmed to fit the context window.
 */
object LocalPrompt {
    /** The compact tool set (only those the chat already has enabled are kept; nothing is added). */
    val CORE_TOOLS = listOf(
        "web_search", "web_fetch", "read_file", "write_file", "list_dir",
        "workspace_list", "workspace_read", "workspace_write", "memory_save", "memory_search", "show_image", "schedule_reminder",
    )

    val SYSTEM = """
        You are Verdroid, an assistant running on the user's Android phone (on-device model).
        Use a tool only when it is needed; call at most one tool at a time and wait for its result.
        When you are done, reply with a short, plain answer and no tool call. Reply in the user's language.
    """.trimIndent()

    private const val MAX_TOOL_DESC = 160
    private const val MAX_TOOL_RESULT_CHARS = 6000
    /** Rough chars per token for budget estimates (real count is done natively). */
    private const val CHARS_PER_TOKEN = 3.2

    /** Replaces the long cloud system prompt (up to the English-sources rule) with [SYSTEM]; keeps what follows (instructions, skills, memory, time). */
    fun compactSystem(content: String): String {
        val marker = AgentLoop.ENGLISH_SOURCES
        val i = content.indexOf(marker)
        if (!content.startsWith("You are Verdroid") || i < 0) return content
        return SYSTEM + content.substring(i + marker.length)
    }

    /** Keeps only [CORE_TOOLS] (in the given order) and shortens their descriptions to the first sentence. */
    fun compactTools(tools: JsonArray?): JsonArray? {
        if (tools == null) return null
        val kept = tools.filter { name(it) in CORE_TOOLS }
        if (kept.isEmpty()) return null
        return JsonArray(kept.map { t ->
            val o = t.jsonObject
            val fn = o["function"]?.jsonObject ?: return@map t
            val desc = fn["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            JsonObject(o + ("function" to JsonObject(fn + ("description" to JsonPrimitive(shortDesc(desc))))))
        })
    }

    fun toolNames(tools: JsonArray?): List<String> = tools.orEmpty().mapNotNull(::name)

    private fun name(t: kotlinx.serialization.json.JsonElement): String? =
        runCatching { t.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull }.getOrNull()

    fun shortDesc(d: String): String {
        val first = d.trim().split(Regex("(?<=[.!?])\\s")).firstOrNull().orEmpty().ifBlank { d.trim() }
        return if (first.length <= MAX_TOOL_DESC) first else first.take(MAX_TOOL_DESC - 1).trimEnd() + "…"
    }

    /**
     * Fits the history into [nCtx] tokens (minus [reserve] for the reply): the system message is always kept, then
     * the newest messages; long tool results are cut. Never starts with an orphan tool result.
     */
    fun fit(messages: List<ApiMessage>, nCtx: Int, reserve: Int, toolsChars: Int = 0): List<ApiMessage> {
        val msgs = ImageStrip.strip(messages).map { m ->
            val c = m.content
            if (m.role == "tool" && c != null && c.length > MAX_TOOL_RESULT_CHARS)
                m.copy(content = c.take(MAX_TOOL_RESULT_CHARS) + "\n…(cut to fit the on-device model)") else m
        }
        val budget = ((nCtx - reserve).coerceAtLeast(256) * CHARS_PER_TOKEN).toInt() - toolsChars
        val (system, rest) = mergeSystem(msgs)
        var used = system.sumOf(::size)
        val kept = ArrayDeque<ApiMessage>()
        for (m in rest.asReversed()) {
            val s = size(m)
            if (kept.isNotEmpty() && used + s > budget) break
            kept.addFirst(m); used += s
        }
        while (kept.size > 1 && kept.first().role == "tool") kept.removeFirst()
        return system + kept
    }

    /**
     * Local templates want one leading system message: the first system message + every earlier-conversation summary
     * (and other mid-history system notes) are merged into it; trailing system nudges (continue after a cut-off,
     * resend a tool call, …) become a final user turn "[Verdroid] …" so the model still sees them.
     */
    const val CLOCK_PREFIX = "Current local date and time:"

    fun mergeSystem(msgs: List<ApiMessage>): Pair<List<ApiMessage>, List<ApiMessage>> {
        val lastNonSystem = msgs.indexOfLast { it.role != "system" }
        val sysText = StringBuilder()
        val rest = mutableListOf<ApiMessage>()
        val trailing = mutableListOf<String>()
        msgs.forEachIndexed { i, m ->
            when {
                m.role != "system" -> rest += m
                i > lastNonSystem && lastNonSystem >= 0 -> m.content?.takeIf { it.isNotBlank() }?.let(trailing::add)
                else -> m.content?.takeIf { it.isNotBlank() }?.let { if (sysText.isNotEmpty()) sysText.append("\n\n"); sysText.append(it) }
            }
        }
        // v1.0.65: the trailing clock line made small models answer "Task: … The current date and time is …".
        // It becomes a day-stable date line at the END of the system prompt (never a user turn; conversation reuse
        // survives within a day). Other notes (nudges) go BEFORE the last real user/tool text, so that text stays last.
        val (clock, notes) = trailing.partition { it.startsWith(CLOCK_PREFIX) }
        clock.lastOrNull()?.let { c ->
            if (sysText.isNotEmpty()) sysText.append("\n\n")
            sysText.append("Today: ").append(c.removePrefix(CLOCK_PREFIX).trim().replace(Regex(" \\d{2}:\\d{2},"), ","))
        }
        if (notes.isNotEmpty()) {
            val note = "(Note: " + notes.joinToString(" ") + ")"
            val lastMsg = rest.lastOrNull()
            if (lastMsg != null && (lastMsg.role == "user" || lastMsg.role == "tool"))
                rest[rest.lastIndex] = lastMsg.copy(content = note + "\n\n" + lastMsg.content.orEmpty())
            else rest += ApiMessage("user", note)
        }
        val system = if (sysText.isEmpty()) emptyList() else listOf(ApiMessage("system", sysText.toString()))
        return system to rest
    }

    private fun size(m: ApiMessage): Int = (m.content?.length ?: 0) + 16 +
        m.toolCalls.orEmpty().sumOf { it.function.name.length + it.function.arguments.length + 24 }

    /** OpenAI-style messages JSON for the native side (no images, plain string content). */
    fun messagesJson(messages: List<ApiMessage>): String = buildJsonArray {
        for (m in messages) add(buildJsonObject {
            put("role", m.role)
            put("content", m.content.orEmpty())
            m.toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
                put("tool_calls", buildJsonArray {
                    calls.forEach { c -> add(buildJsonObject {
                        put("id", c.id); put("type", "function")
                        put("function", buildJsonObject { put("name", c.function.name); put("arguments", c.function.arguments) })
                    }) }
                })
            }
            m.toolCallId?.let { put("tool_call_id", it) }
            if (m.role == "tool") m.name?.let { put("name", it) }
        })
    }.toString()
}
