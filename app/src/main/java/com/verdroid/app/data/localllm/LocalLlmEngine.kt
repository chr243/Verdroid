package com.verdroid.app.data.localllm

import android.app.ActivityManager
import android.content.Context
import android.util.Log
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
    /** [backend] = "CPU" or "Vulkan: <GPU name>". */
    data class Loaded(val id: String, val nCtx: Int, val info: String, val backend: String = "CPU") : LocalEngineState
    data class Failed(val id: String, val message: String) : LocalEngineState
}

/** One on-device engine (llama.cpp for GGUF, LiteRT-LM for .litertlm). [LocalEngines] routes between them. */
interface LocalEngine {
    val state: StateFlow<LocalEngineState>
    val gpuNote: StateFlow<String?>
    val loadedId: String?
    suspend fun ensureLoaded(id: String): String?
    suspend fun chat(id: String, messagesJson: String, toolsJson: String, maxTokens: Int, temperature: Float): Pair<String?, String?>
    suspend fun unload()
    fun contextSize(): Int
}

/**
 * The GPU crash sentinel is shared by both engines: whichever engine starts first after a native crash on the GPU
 * (Vulkan or OpenCL) blocks the GPU and both report the message.
 */
object GpuGuard {
    @Volatile private var checked = false
    @Volatile var crashNote: String? = null
        private set

    @Synchronized
    fun check(store: LocalModelStore): String? {
        if (!checked) {
            checked = true
            runCatching {
                val s = store.gpuSentinel
                if (s.isFile) {
                    val msg = GpuPolicy.crashMessage(s.readText())
                    store.gpuBlocked = msg; store.useGpu = false
                    s.delete()
                    crashNote = msg
                }
            }
        }
        return crashNote
    }

    fun mark(store: LocalModelStore, phase: String?) = runCatching {
        if (phase == null) store.gpuSentinel.delete() else store.gpuSentinel.writeText("$phase|${System.currentTimeMillis()}")
    }
}

/**
 * Holds at most one loaded model (one native handle). Every call is serialised.
 * GPU (Vulkan): only when "Use GPU" is on. Any GPU load/generation failure falls back to the CPU (reason in [gpuNote]);
 * a native crash during GPU work is caught on the next start by a sentinel file and switches GPU off.
 */
@Singleton
class LocalLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: LocalModelStore,
) : LocalEngine {
    private val mutex = Mutex()
    @Volatile private var handle = 0L
    @Volatile private var loadedKey: String? = null
    @Volatile private var onGpu = false
    /** GPU failed in this session → CPU until the switch is toggled again (no retry loop every turn). */
    @Volatile private var gpuFailed = false
    /** Whether Vulkan is registered in this process (null = engine not started yet). */
    @Volatile private var vulkanInProcess: Boolean? = null
    private val _state = MutableStateFlow<LocalEngineState>(LocalEngineState.Idle)
    override val state: StateFlow<LocalEngineState> = _state.asStateFlow()
    private val _gpuNote = MutableStateFlow<String?>(null)
    /** Latest GPU problem / fallback reason (shown in the panel, also logged). */
    override val gpuNote: StateFlow<String?> = _gpuNote.asStateFlow()

    init {
        // A crash during GPU work last time (either engine) → GPU off, with the reason.
        GpuGuard.check(store)?.let(::note)
    }

    override val loadedId: String? get() = (state.value as? LocalEngineState.Loaded)?.id
    val backend: String get() = (state.value as? LocalEngineState.Loaded)?.backend ?: "CPU"

    private fun note(msg: String?) { _gpuNote.value = msg; if (msg != null) Log.w(TAG, msg) }

    private fun availRam(): Long = runCatching {
        val am = context.getSystemService(ActivityManager::class.java)
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
    }.getOrDefault(Long.MAX_VALUE)

    private fun hasVulkan(): Boolean = LlamaNative.available && runCatching { LlamaNative.nativeHasVulkan() }.getOrDefault(false)

    private fun decide(): GpuPolicy.Decision = GpuPolicy.decide(store.useGpu, store.gpuBlocked ?: store.vulkanBlocked, hasVulkan(), vulkanInProcess)

    private fun sentinel(phase: String?) = GpuGuard.mark(store, phase)

    /** Loads [id] (unloading any other). Returns null on success, else a reason (missing file, RAM, bad GGUF, …). */
    override suspend fun ensureLoaded(id: String): String? = mutex.withLock { loadLocked(id) }

    private suspend fun loadLocked(id: String, forceCpu: Boolean = false): String? {
        if (!LlamaNative.available) return LlamaNative.unavailableReason
        val m = store.find(id) ?: return "Local model \"$id\" isn't imported (Settings → LLM providers → Local)"
        val f = File(m.path)
        if (!f.isFile) return "Model file for \"$id\" is missing"
        if (vulkanInProcess == null) {
            val want = store.useGpu && store.gpuBlocked == null && store.vulkanBlocked == null && hasVulkan()
            vulkanInProcess = runCatching { LlamaNative.nativeInitBackend(want) }.getOrDefault(false)
        }
        val d = if (forceCpu || gpuFailed) GpuPolicy.Decision(GpuPolicy.Mode.CPU, null) else decide()
        d.note?.let(::note)
        val layers = GpuPolicy.nativeLayers(d.mode, store.gpuLayers)
        val nCtx = store.contextSize
        val key = "$id|$nCtx|${store.threads}|$layers"
        if (handle != 0L && loadedKey == key) return null
        unloadLocked()
        if (!LocalModels.fitsInRam(f.length(), nCtx, availRam()))
            return fail(id, "Not enough free memory for ${m.name} (${LocalModels.human(f.length())}, ${LocalModels.human(availRam())} free). Try a smaller model or context.")
        _state.value = LocalEngineState.Loading(id)
        if (layers != 0) {
            sentinel("model load")
            val h = nativeLoad(f, nCtx, layers)
            sentinel(null)
            if (h != 0L) return loaded(id, h, key, nCtx, gpu = true)
            gpuFailed = true
            note("GPU load failed (${lastError().ifBlank { "unknown error" }}); using CPU")
        }
        val h = nativeLoad(f, nCtx, 0)
        if (h == 0L) return fail(id, lastError().ifBlank { "Load failed" })
        return loaded(id, h, key, nCtx, gpu = false)
    }

    private suspend fun nativeLoad(f: File, nCtx: Int, layers: Int): Long = withContext(Dispatchers.Default) {
        try { LlamaNative.nativeLoad(f.absolutePath, nCtx, store.threads, layers) } catch (e: OutOfMemoryError) { 0L }
    }
    private fun lastError() = runCatching { LlamaNative.nativeLastError() }.getOrDefault("")

    private fun loaded(id: String, h: Long, key: String, nCtx: Int, gpu: Boolean): String? {
        handle = h; loadedKey = key; onGpu = gpu
        val info = runCatching { LlamaNative.nativeInfo(h) }.getOrDefault("{}")
        val ctx = Regex("\"n_ctx\":(\\d+)").find(info)?.groupValues?.get(1)?.toIntOrNull() ?: nCtx
        _state.value = LocalEngineState.Loaded(id, ctx, info, GpuPolicy.backendLabel(info))
        store.lastBackend = GpuPolicy.backendLabel(info)
        Log.i(TAG, "loaded $id on ${GpuPolicy.backendLabel(info)}")
        return null
    }

    private fun fail(id: String, msg: String): String { _state.value = LocalEngineState.Failed(id, msg); return msg }

    override suspend fun unload() = mutex.withLock { unloadLocked() }

    private fun unloadLocked() {
        val h = handle
        handle = 0L; loadedKey = null; onGpu = false
        if (h != 0L) runCatching { LlamaNative.nativeFree(h) }
        _state.value = LocalEngineState.Idle
    }

    /**
     * Runs one chat turn on [id]; cancelling the coroutine stops generation. Returns (native JSON, load error).
     * A GPU generation error reloads the model on the CPU and retries once.
     */
    override suspend fun chat(id: String, messagesJson: String, toolsJson: String, maxTokens: Int, temperature: Float): Pair<String?, String?> =
        mutex.withLock {
            loadLocked(id)?.let { return@withLock null to it }
            val body = generate(messagesJson, toolsJson, maxTokens, temperature)
            if (onGpu) {
                val err = Regex("^\\{\"error\":\"([^\"]*)\"").find(body)?.groupValues?.get(1)
                if (err != null && GpuPolicy.isGpuFailure(err)) {
                    gpuFailed = true
                    note("GPU generation failed ($err); switched to CPU")
                    unloadLocked()
                    loadLocked(id, forceCpu = true)?.let { return@withLock null to it }
                    return@withLock generate(messagesJson, toolsJson, maxTokens, temperature) to null
                }
            }
            body to null
        }

    private suspend fun generate(messagesJson: String, toolsJson: String, maxTokens: Int, temperature: Float): String {
        val h = handle
        val gpu = onGpu
        if (gpu) sentinel("generation")
        try {
            // Watchdog: a hung GPU driver would hold the engine forever; timeout → cancel + error (→ CPU fallback on GPU).
            return kotlinx.coroutines.withTimeoutOrNull(GpuPolicy.generationTimeoutMs(maxTokens)) { suspendCancellableCoroutine { cont ->
                thread(name = "verdroid-llama", isDaemon = true) {
                    val r = try { LlamaNative.nativeChat(h, messagesJson, toolsJson, maxTokens, temperature, false) }
                        catch (e: Throwable) { "{\"error\":\"${(e.message ?: e.javaClass.simpleName).replace("\"", "'")}\"}" }
                    if (cont.isActive) cont.resumeWith(Result.success(r))
                }
                cont.invokeOnCancellation { runCatching { LlamaNative.nativeCancel(h) } } // native side holds its own lock
            } } ?: "{\"error\":\"${GpuPolicy.TIMEOUT_ERROR}\"}"
        } finally { if (gpu) sentinel(null) }
    }

    /** Context size of the loaded model (or the setting). */
    override fun contextSize(): Int = (state.value as? LocalEngineState.Loaded)?.nCtx ?: store.contextSize

    /**
     * Turning the switch on: the Vulkan probe runs in a separate process first; only a passing probe lets GGUF
     * models use the GPU. The switch stays on either way so LiteRT-LM models can still try their GPU (OpenCL)
     * backend, which has its own in-process CPU fallback + crash sentinel. Returns (vulkan ok, summary).
     */
    suspend fun enableGpu(): Pair<Boolean, String> {
        store.gpuBlocked = null; store.useGpu = true; gpuFailed = false
        if (!hasVulkan()) {
            val s = "No Vulkan in this build/phone: GGUF stays on CPU; LiteRT models try GPU (OpenCL)"
            store.vulkanBlocked = s; store.gpuProbe = s
            return false to s
        }
        val json = GpuProbeService.probe(context)
        val ok = Regex("\"ok\":true").containsMatchIn(json)
        val dev = Regex("\"device\":\"([^\"]*)\"").find(json)?.groupValues?.get(1)
        val err = Regex("\"error\":\"([^\"]*)\"").find(json)?.groupValues?.get(1)
        val ms = Regex("\"ms\":(\\d+)").find(json)?.groupValues?.get(1)
        return if (ok) {
            store.vulkanBlocked = null
            val s = "${dev ?: "GPU"} · probe OK${ms?.let { " in $it ms" } ?: ""}"
            store.gpuProbe = s
            note(if (vulkanInProcess == false) "GPU probe passed; restart Verdroid to use the GPU" else null)
            mutex.withLock { if (handle != 0L) unloadLocked() } // reload on GPU next time
            true to s
        } else {
            val s = "GPU probe failed: ${err ?: "unknown error"}${dev?.let { " ($it)" } ?: ""}; GGUF stays on CPU (LiteRT models still try GPU)"
            store.vulkanBlocked = s
            store.gpuProbe = s
            note(s)
            false to s
        }
    }

    suspend fun disableGpu() {
        store.useGpu = false; gpuFailed = false
        note(null)
        mutex.withLock { if (onGpu) unloadLocked() }
    }

    companion object { private const val TAG = "VerdroidLocalLlm" }
}
