package com.verdroid.app.agent

import com.verdroid.app.data.network.ParsedToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Review #17: detects a model stuck calling the same tool(s) with identical arguments step after step.
 * Pure; one instance per run. [observe] returns how many consecutive steps had this exact batch (1 = new).
 */
class RepeatCallGuard {
    private var last: String? = null
    private var count = 0

    fun observe(calls: List<ParsedToolCall>): Int {
        val sig = signature(calls)
        count = if (sig == last) count + 1 else 1
        last = sig
        return count
    }

    fun reset() { last = null; count = 0 }

    companion object {
        const val NUDGE_AT = 3
        const val PAUSE_AT = 5

        /** Name + canonical JSON args (key order/whitespace don't matter). */
        fun signature(calls: List<ParsedToolCall>): String = calls.joinToString("|") { it.name + ":" + canon(it.argumentsJson) }

        private fun canon(args: String): String = runCatching { sorted(Json.parseToJsonElement(args)).toString() }.getOrDefault(args.trim())

        private fun sorted(e: JsonElement): JsonElement = when (e) {
            is kotlinx.serialization.json.JsonObject -> kotlinx.serialization.json.JsonObject(e.toSortedMap().mapValues { sorted(it.value) })
            is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(e.map(::sorted))
            else -> e
        }

        fun nudge(n: Int, names: String) =
            "You have called $names with identical arguments $n times in a row; the result will not change. " +
                "Use the result you already have, try a different approach, or give your answer."
    }
}
