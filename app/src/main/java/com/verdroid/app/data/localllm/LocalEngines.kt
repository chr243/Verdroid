package com.verdroid.app.data.localllm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes a local model to its engine by format ("gguf" → llama.cpp, "litertlm" → LiteRT-LM) and keeps only one
 * model in RAM across both engines: loading on one unloads the other. Calls are serialised.
 */
@Singleton
class LocalEngines(
    private val llama: LocalEngine,
    private val litert: LocalEngine,
    private val formatOf: (String) -> String?,
) {
    @Inject constructor(llama: LocalLlmEngine, litert: LiteRtLmEngine, store: LocalModelStore) :
        this(llama, litert, { id: String -> store.find(id)?.format })

    private val mutex = Mutex()

    fun engineFor(id: String): LocalEngine = if (formatOf(id) == LocalModels.LITERTLM) litert else llama
    private fun other(e: LocalEngine) = if (e === litert) llama else litert

    /** The non-idle engine's state (Loading/Loaded/Failed), else Idle. */
    fun state(scope: CoroutineScope): StateFlow<LocalEngineState> = combine(llama.state, litert.state) { a, b -> pick(a, b) }
        .stateIn(scope, SharingStarted.Eagerly, current)

    val current: LocalEngineState get() = pick(llama.state.value, litert.state.value)

    /** Latest GPU note from either engine. */
    fun gpuNote(scope: CoroutineScope): StateFlow<String?> = combine(litert.gpuNote, llama.gpuNote) { a, b -> a ?: b }
        .stateIn(scope, SharingStarted.Eagerly, litert.gpuNote.value ?: llama.gpuNote.value)

    private fun pick(a: LocalEngineState, b: LocalEngineState) = if (a !is LocalEngineState.Idle) a else b

    val loadedId: String? get() = llama.loadedId ?: litert.loadedId

    suspend fun ensureLoaded(id: String): String? = mutex.withLock {
        val e = engineFor(id)
        other(e).let { if (it.loadedId != null || it.state.value !is LocalEngineState.Idle) it.unload() }
        e.ensureLoaded(id)
    }

    suspend fun chat(id: String, messagesJson: String, toolsJson: String, maxTokens: Int, temperature: Float = 0.6f): Pair<String?, String?> =
        mutex.withLock {
            val e = engineFor(id)
            other(e).let { if (it.loadedId != null) it.unload() }
            e.chat(id, messagesJson, toolsJson, maxTokens, temperature)
        }

    suspend fun unload() = mutex.withLock { llama.unload(); litert.unload() }

    fun contextSize(id: String? = loadedId): Int = (id?.let(::engineFor) ?: llama).contextSize()
}
