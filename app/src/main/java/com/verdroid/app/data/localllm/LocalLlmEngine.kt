package com.verdroid.app.data.localllm

import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/** What the engine is doing (Settings → LLM providers → Local shows it). */
sealed interface LocalEngineState {
    data object Idle : LocalEngineState
    data class Loading(val id: String) : LocalEngineState
    data class Loaded(val id: String, val nCtx: Int, val info: String) : LocalEngineState
    data class Failed(val id: String, val message: String) : LocalEngineState
}

/** Holds at most one loaded model (one native handle). Every call is serialised. */
@Singleton
class LocalLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: LocalModelStore,
) {
    private val mutex = Mutex()
    @Volatile private var handle = 0L
    @Volatile private var loadedKey: String? = null
    private val _state = MutableStateFlow<LocalEngineState>(LocalEngineState.Idle)
    val state: StateFlow<LocalEngineState> = _state.asStateFlow()

    val loadedId: String? get() = (state.value as? LocalEngineState.Loaded)?.id

    private fun availRam(): Long = runCatching {
        val am = context.getSystemService(ActivityManager::class.java)
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
    }.getOrDefault(Long.MAX_VALUE)

    /** Loads [id] (unloading any other). Returns null on success, else a reason (missing file, RAM, bad GGUF, …). */
    suspend fun ensureLoaded(id: String): String? = mutex.withLock { loadLocked(id) }

    private suspend fun loadLocked(id: String): String? {
        if (!LlamaNative.available) return LlamaNative.unavailableReason
        val m = store.find(id) ?: return "Local model \"$id\" isn't imported (Settings → LLM providers → Local)"
        val f = File(m.path)
        if (!f.isFile) return "Model file for \"$id\" is missing"
        val nCtx = store.contextSize
        val key = "$id|$nCtx|${store.threads}"
        if (handle != 0L && loadedKey == key) return null
        unloadLocked()
        if (!LocalModels.fitsInRam(f.length(), nCtx, availRam()))
            return fail(id, "Not enough free memory for ${m.name} (${LocalModels.human(f.length())}, ${LocalModels.human(availRam())} free). Try a smaller model or context.")
        _state.value = LocalEngineState.Loading(id)
        val h = withContext(Dispatchers.Default) {
            try { LlamaNative.nativeLoad(f.absolutePath, nCtx, store.threads) } catch (e: OutOfMemoryError) { 0L }
        }
        if (h == 0L) return fail(id, runCatching { LlamaNative.nativeLastError() }.getOrDefault("").ifBlank { "Load failed" })
        handle = h; loadedKey = key
        val info = runCatching { LlamaNative.nativeInfo(h) }.getOrDefault("{}")
        val ctx = Regex("\"n_ctx\":(\\d+)").find(info)?.groupValues?.get(1)?.toIntOrNull() ?: nCtx
        _state.value = LocalEngineState.Loaded(id, ctx, info)
        return null
    }

    private fun fail(id: String, msg: String): String { _state.value = LocalEngineState.Failed(id, msg); return msg }

    suspend fun unload() = mutex.withLock { unloadLocked() }

    private fun unloadLocked() {
        val h = handle
        handle = 0L; loadedKey = null
        if (h != 0L) runCatching { LlamaNative.nativeFree(h) }
        _state.value = LocalEngineState.Idle
    }

    /** Runs one chat turn on [id]; cancelling the coroutine stops generation. Returns the native JSON or a load error. */
    suspend fun chat(id: String, messagesJson: String, toolsJson: String, maxTokens: Int, temperature: Float = 0.6f): Pair<String?, String?> =
        mutex.withLock {
            loadLocked(id)?.let { return@withLock null to it }
            val h = handle
            val body = suspendCancellableCoroutine { cont ->
                thread(name = "verdroid-llama", isDaemon = true) {
                    val r = try { LlamaNative.nativeChat(h, messagesJson, toolsJson, maxTokens, temperature, false) }
                        catch (e: Throwable) { "{\"error\":\"${(e.message ?: e.javaClass.simpleName).replace("\"", "'")}\"}" }
                    if (cont.isActive) cont.resumeWith(Result.success(r))
                }
                cont.invokeOnCancellation { runCatching { LlamaNative.nativeCancel(h) } } // native side holds its own lock; a later call waits for it
            }
            body to null
        }

    /** Context size of the loaded model (or the setting). */
    fun contextSize(): Int = (state.value as? LocalEngineState.Loaded)?.nCtx ?: store.contextSize
}
