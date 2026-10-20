package com.verdroid.app.data.localllm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Pure conversions between Verdroid's local message/tool/reply JSON and what the LiteRT-LM engine needs
 * (no LiteRT types here, so it's JVM-tested). The reply has the same shape as verdroid_llama.cpp's, so
 * [LocalReply.parse] reads both engines.
 */
object LiteRtFormat {
    /** One history entry. [toolCalls] = (name, arguments); [toolName] only for role "tool". */
    data class Turn(val role: String, val text: String, val toolCalls: List<Pair<String, Map<String, Any?>>> = emptyList(), val toolName: String? = null)

    data class Convo(val system: String?, val history: List<Turn>, val last: Turn?)

    /** messagesJson (see [LocalPrompt.messagesJson]) → system text, history, and the message to send now. */
    fun convo(messagesJson: String): Convo {
        val arr = runCatching { Json.parseToJsonElement(messagesJson) as JsonArray }.getOrDefault(JsonArray(emptyList()))
        val system = StringBuilder()
        val turns = mutableListOf<Turn>()
        val callNames = HashMap<String, String>() // tool_call_id → tool name
        for (el in arr) {
            val o = el as? JsonObject ?: continue
            val role = o.str("role") ?: continue
            val text = o.str("content").orEmpty()
            when (role) {
                "system" -> { if (system.isNotEmpty()) system.append("\n\n"); system.append(text) }
                "assistant" -> {
                    val calls = (o["tool_calls"] as? JsonArray).orEmpty().mapNotNull { c -> callOf(c, callNames) }
                    turns += Turn("assistant", text, calls)
                }
                "tool" -> turns += Turn("tool", text, toolName = o.str("name") ?: o.str("tool_call_id")?.let(callNames::get) ?: "tool")
                else -> turns += Turn("user", text)
            }
        }
        return finish(system, turns)
    }

    private fun callOf(c: JsonElement, callNames: MutableMap<String, String>): Pair<String, Map<String, Any?>>? {
        val co = c as? JsonObject ?: return null
        val fn = co["function"] as? JsonObject ?: return null
        val name = fn.str("name") ?: return null
        co.str("id")?.let { callNames[it] = name }
        val args = fn.str("arguments")?.let { a -> runCatching { Json.parseToJsonElement(a) as JsonObject }.getOrNull() }
        @Suppress("UNCHECKED_CAST")
        val map = args?.let { toKotlin(it) as Map<String, Any?> } ?: emptyMap()
        return name to map
    }

    private fun finish(system: StringBuilder, turns: List<Turn>): Convo {
        val last = turns.lastOrNull()?.takeIf { it.role == "user" || it.role == "tool" }
        // Consecutive trailing tool results all belong to the turn being sent; LiteRT sends one message, so the
        // earlier ones stay in the history (initialMessages) and the last one is sent.
        return Convo(system.toString().ifBlank { null }, if (last != null) turns.dropLast(1) else turns, last)
    }

    /** toolsJson (OpenAI "tools" array) → one OpenAPI function JSON per tool ({name, description, parameters}). */
    fun toolDescriptions(toolsJson: String): List<String> {
        if (toolsJson.isBlank()) return emptyList()
        val arr = runCatching { Json.parseToJsonElement(toolsJson) as JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { t ->
            val fn = (t as? JsonObject)?.get("function") as? JsonObject ?: return@mapNotNull null
            if (fn.str("name").isNullOrBlank()) null else fn.toString()
        }
    }

    /** A tool's text result: parsed as JSON when it is JSON (LiteRT renders it), else the plain string. */
    fun toolResponse(text: String): Any? =
        runCatching { toKotlin(Json.parseToJsonElement(text)) }.getOrNull()?.takeIf { it is Map<*, *> || it is List<*> } ?: text

    fun reply(content: String, reasoning: String?, calls: List<Pair<String, Map<String, Any?>>>, finish: String,
              promptTokens: Long, completionTokens: Long, genMs: Long, backend: String): String = buildJsonObject {
        put("content", content)
        if (!reasoning.isNullOrBlank()) put("reasoning", reasoning)
        if (calls.isNotEmpty()) put("tool_calls", buildJsonArray {
            calls.forEachIndexed { i, (name, args) -> add(buildJsonObject {
                put("id", "call_local_$i"); put("name", name); put("arguments", toJson(args).toString())
            }) }
        })
        put("finish", if (calls.isNotEmpty()) "tool_calls" else finish)
        put("prompt_tokens", promptTokens)
        // Never 0 when there was output (the tok/s line and stats rely on it).
        put("completion_tokens", if (completionTokens > 0 || (content.isEmpty() && calls.isEmpty())) completionTokens else estimateTokens(content).coerceAtLeast(1))
        put("gen_ms", genMs)
        put("backend", backend)
    }.toString()

    fun error(message: String): String = buildJsonObject { put("error", message) }.toString()

    /** Out-of-memory style failures → an error with the word "memory" so the caller unloads the model. */
    fun errorFor(t: Throwable): String {
        val m = t.message ?: t.javaClass.simpleName
        return error(if (t is OutOfMemoryError || m.contains("memory", true) || m.contains("alloc", true)) "Out of memory: $m" else m)
    }

    fun estimateTokens(text: String): Long = ((text.length + 3) / 4).toLong()

    fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
        is Iterable<*> -> JsonArray(v.map(::toJson))
        is Array<*> -> JsonArray(v.map(::toJson))
        else -> JsonPrimitive(v.toString())
    }

    fun toKotlin(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonObject -> e.mapValues { toKotlin(it.value) }
        is JsonArray -> e.map(::toKotlin)
        is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.longOrNull ?: e.doubleOrNull ?: e.content
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
}
