package com.verdroid.app.data.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Model "thinking" (reasoning) from an OpenAI-compatible completion message, shown in the chat as a collapsible
 * Thinking row. Never sent back to the model.
 *
 * Sources, first non-blank wins:
 *  1. `message.reasoning` (OpenRouter's normalised field, returned by default when the model emits reasoning)
 *  2. `message.reasoning_content` (DeepSeek / vLLM / many OpenAI-compatible gateways, incl. Kilo upstreams)
 *  3. `message.reasoning_details[]` (OpenRouter structured blocks: `reasoning.text` → text, `reasoning.summary` → summary;
 *     `reasoning.encrypted` is skipped)
 *  4. Inline `<think>…</think>` in content (R1 distills, Qwen/QwQ, some free models) — stripped out of the content.
 */
object ReasoningText {
    const val MAX_CHARS = 20_000
    private val THINK_BLOCK = Regex("<think(?:ing)?>(.*?)</think(?:ing)?>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val OPEN = Regex("^\\s*<think(?:ing)?>", RegexOption.IGNORE_CASE)
    private val CLOSE = Regex("</think(?:ing)?>", RegexOption.IGNORE_CASE)

    data class Split(val content: String?, val reasoning: String?)

    /** Reasoning from the dedicated message fields (1–3), or null. */
    fun fromMessage(message: JsonObject): String? {
        message["reasoning"]?.primitiveContent()?.takeIf { it.isNotBlank() }?.let { return clip(it) }
        message["reasoning_content"]?.primitiveContent()?.takeIf { it.isNotBlank() }?.let { return clip(it) }
        val details = (message["reasoning_details"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el.asObjectOrNull() ?: return@mapNotNull null
            when (o["type"]?.primitiveContent()) {
                "reasoning.text" -> o["text"]?.primitiveContent()
                "reasoning.summary" -> o["summary"]?.primitiveContent()
                else -> null
            }?.takeIf { it.isNotBlank() }
        }
        return details.takeIf { it.isNotEmpty() }?.joinToString("\n\n")?.let(::clip)
    }

    /**
     * Pulls `<think>` blocks out of [content]. Also handles a reply that starts with `<think>` and never closes it
     * (cut off mid-thought: all of it is reasoning) and a reply with only a closing `</think>` (the chat template
     * opened the tag: everything before it is reasoning).
     */
    fun splitInline(content: String?): Split {
        if (content.isNullOrEmpty() || !content.contains("think", ignoreCase = true)) return Split(content, null)
        val parts = mutableListOf<String>()
        var rest = content
        if (THINK_BLOCK.containsMatchIn(rest)) {
            THINK_BLOCK.findAll(rest).forEach { parts += it.groupValues[1] }
            rest = THINK_BLOCK.replace(rest, "")
        }
        val close = CLOSE.find(rest)
        if (close != null && !OPEN.containsMatchIn(rest.substring(0, close.range.first))) {
            parts += rest.substring(0, close.range.first)
            rest = rest.substring(close.range.last + 1)
        } else if (OPEN.containsMatchIn(rest) && close == null) {
            parts += OPEN.replace(rest, "")
            rest = ""
        }
        val reasoning = parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n").ifEmpty { null }
        if (reasoning == null && rest == content) return Split(content, null)
        return Split(rest.trim().ifEmpty { null }, reasoning?.let(::clip))
    }

    /** Merges field reasoning with inline reasoning (both may exist on odd gateways). */
    fun merge(field: String?, inline: String?): String? = when {
        field.isNullOrBlank() -> inline
        inline.isNullOrBlank() || field.contains(inline.trim()) -> field
        else -> clip(field + "\n\n" + inline)
    }

    private fun clip(s: String): String = s.trim().let { if (it.length > MAX_CHARS) it.take(MAX_CHARS) + "\n…(truncated)" else it }
}
