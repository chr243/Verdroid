package com.verdroid.app.data.localllm

import com.verdroid.app.data.network.ApiMessage
import com.verdroid.app.data.network.KiloResult
import com.verdroid.app.data.network.ModelIds
import kotlinx.serialization.json.JsonArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serves `local:<id>` entries of Model priority with the on-device engine for the model's format
 * (GGUF → llama.cpp, .litertlm → LiteRT-LM; see [LocalEngines]). Missing file, load failure, out of
 * memory or a native error → [KiloResult.Unavailable] so the caller falls back to the next model.
 */
@Singleton
class LocalLlmClient @Inject constructor(
    private val engine: LocalEngines,
    private val store: LocalModelStore,
) {
    suspend fun complete(models: List<String>, messages: List<ApiMessage>, tools: JsonArray?, status: suspend (String) -> Unit = {}): KiloResult {
        var lastError = "No on-device model available"
        for (id in models) {
            val bare = ModelIds.bare(id)
            val label = ModelIds.label(id)
            if (engine.loadedId != bare) status("Loading $label…")
            val loadErr = engine.ensureLoaded(bare)
            if (loadErr != null) { lastError = "$label: $loadErr"; continue }
            val nCtx = engine.contextSize(bare)
            val maxTokens = store.maxTokens.coerceAtMost(nCtx / 2)
            val compactTools = LocalPrompt.compactTools(tools)
            val toolsJson = compactTools?.toString().orEmpty()
            val shaped = messages.map { if (it.role == "system" && it.content != null) it.copy(content = LocalPrompt.compactSystem(it.content)) else it }
            val fitted = LocalPrompt.fit(shaped, nCtx, maxTokens + 64, toolsJson.length)
            status("Thinking on-device ($label)…")
            val (body, err) = engine.chat(bare, LocalPrompt.messagesJson(fitted), toolsJson, maxTokens)
            if (err != null || body == null) { lastError = "$label: ${err ?: "no reply"}"; continue }
            when (val r = LocalReply.parse(body, label)) {
                is LocalReply.Result.Ok -> {
                    val line = LocalLabel.format(bare, r.completionTokens, r.genMs, r.backend)
                    return KiloResult.Success(r.completion.copy(model = line), line, id)
                }
                is LocalReply.Result.Error -> {
                    if (r.outOfMemory) engine.unload()
                    lastError = "$label: ${r.message}"
                }
            }
        }
        return KiloResult.Unavailable(lastError, null, false)
    }
}
