package com.verdroid.app.data.localllm

import com.verdroid.app.data.network.ParsedCompletion
import com.verdroid.app.data.network.ParsedToolCall
import com.verdroid.app.data.network.ReasoningText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Model line under a local reply: "gemma-2-2b (on device) 10 tok/s", plus " · GPU" when Vulkan or LiteRT's GPU ran it. */
object LocalLabel {
    fun format(modelId: String, completionTokens: Long, genMs: Long, backend: String): String {
        val base = "$modelId (on device)"
        val speed = if (completionTokens > 0 && genMs > 0) " ${Math.round(completionTokens * 1000.0 / genMs)} tok/s" else ""
        val gpu = if (backend.startsWith("Vulkan") || backend == LiteRtLmEngine.GPU_LABEL) " · GPU" else ""
        return base + speed + gpu
    }
}

/** Parses the native reply JSON (see verdroid_llama.cpp). Pure, JVM-tested. */
object LocalReply {
    sealed interface Result {
        data class Ok(val completion: ParsedCompletion, val promptTokens: Long, val completionTokens: Long,
                      val genMs: Long = 0, val backend: String = "CPU") : Result
        data class Error(val message: String, val outOfMemory: Boolean, val cancelled: Boolean) : Result
    }

    fun parse(body: String, modelLabel: String): Result {
        val o = runCatching { Json.parseToJsonElement(body) as JsonObject }.getOrNull()
            ?: return Result.Error("Unreadable reply from the on-device engine", false, false)
        fun s(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        s("error")?.let { e ->
            return Result.Error(e, e.contains("memory", true), e == "cancelled")
        }
        val calls = (o["tool_calls"] as? JsonArray).orEmpty().mapIndexedNotNull { i, el ->
            val c = el as? JsonObject ?: return@mapIndexedNotNull null
            val name = c["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            ParsedToolCall(c["id"]?.jsonPrimitive?.contentOrNull ?: "call_local_$i", name,
                c["arguments"]?.jsonPrimitive?.contentOrNull?.ifBlank { "{}" } ?: "{}")
        }
        val raw = s("content").orEmpty().ifBlank { s("raw").orEmpty() }
        val split = ReasoningText.splitInline(raw.ifBlank { null })
        val reasoning = ReasoningText.merge(s("reasoning")?.takeIf { it.isNotBlank() }, split.reasoning)
        val finish = s("finish")
        if (calls.isEmpty() && split.content.isNullOrBlank() && finish == "cancelled") return Result.Error("cancelled", false, true)
        return Result.Ok(
            ParsedCompletion(split.content?.takeIf { it.isNotBlank() }, calls, finish, modelLabel, reasoning),
            o["prompt_tokens"]?.jsonPrimitive?.longOrNull ?: 0, o["completion_tokens"]?.jsonPrimitive?.longOrNull ?: 0,
            o["gen_ms"]?.jsonPrimitive?.longOrNull ?: 0, s("backend")?.ifBlank { null } ?: "CPU",
        )
    }
}
