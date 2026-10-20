package com.verdroid.app.data.localllm

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.tool
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.resume
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google LiteRT-LM engine for `.litertlm` models (pinned in libs.versions.toml). GPU = LiteRT's OpenCL backend,
 * used when "Use GPU" is on and the GPU isn't blocked; any GPU init/generation failure falls back to the CPU
 * once (reason in [gpuNote]); a native crash during GPU work is caught on the next start by the shared sentinel
 * ([GpuGuard]).
 *
 * Tools: registered at runtime from the OpenAI tools JSON as [OpenApiTool]s with `automaticToolCalling = false`,
 * so LiteRT only reports tool calls; Verdroid's agent loop runs them (with its own confirmations). [OpenApiTool.execute]
 * never runs anything.
 */
@Singleton
class LiteRtLmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: LocalModelStore,
) : LocalEngine {
    private val mutex = Mutex()
    @Volatile private var engine: Engine? = null
    @Volatile private var loadedKey: String? = null
    @Volatile private var onGpu = false
    @Volatile private var gpuFailed = false
    private val _state = MutableStateFlow<LocalEngineState>(LocalEngineState.Idle)
    override val state: StateFlow<LocalEngineState> = _state.asStateFlow()
    private val _gpuNote = MutableStateFlow<String?>(null)
    override val gpuNote: StateFlow<String?> = _gpuNote.asStateFlow()

    init { GpuGuard.check(store)?.let(::note) }

    override val loadedId: String? get() = (state.value as? LocalEngineState.Loaded)?.id

    private fun note(msg: String?) { _gpuNote.value = msg; if (msg != null) Log.w(TAG, msg) }

    private fun availRam(): Long = runCatching {
        val am = context.getSystemService(ActivityManager::class.java)
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
    }.getOrDefault(Long.MAX_VALUE)

    override suspend fun ensureLoaded(id: String): String? = mutex.withLock { loadLocked(id) }

    private suspend fun loadLocked(id: String, forceCpu: Boolean = false): String? {
        if (!available) return unavailableReason
        val m = store.find(id) ?: return "Local model \"$id\" isn't imported (Settings → LLM providers → Local)"
        val f = File(m.path)
        if (!f.isFile) return "Model file for \"$id\" is missing"
        val wantGpu = !forceCpu && !gpuFailed && store.useGpu && store.gpuBlocked == null
        if (store.useGpu && store.gpuBlocked != null) note("GPU off: ${store.gpuBlocked}")
        val nCtx = store.contextSize
        val key = "$id|$nCtx|${store.threads}|$wantGpu"
        if (engine != null && loadedKey == key) return null
        unloadLocked()
        if (!LocalModels.fitsInRam(f.length(), nCtx, availRam()))
            return fail(id, "Not enough free memory for ${m.name} (${LocalModels.human(f.length())}, ${LocalModels.human(availRam())} free). Try a smaller model or context.")
        _state.value = LocalEngineState.Loading(id)
        if (wantGpu) {
            GpuGuard.mark(store, "model load (LiteRT)")
            val r = init(f, nCtx, gpu = true)
            GpuGuard.mark(store, null)
            r.getOrNull()?.let { return loaded(id, it, key, nCtx, gpu = true) }
            gpuFailed = true
            note("LiteRT GPU init failed (${r.exceptionOrNull()?.message ?: "unknown error"}); using CPU")
        }
        val r = init(f, nCtx, gpu = false)
        val e = r.getOrNull() ?: return fail(id, r.exceptionOrNull()?.let { LiteRtFormat.errorFor(it) }
            ?.let { Regex("\"error\":\"(.*)\"").find(it)?.groupValues?.get(1) } ?: "Load failed")
        return loaded(id, e, if (wantGpu) "$id|$nCtx|${store.threads}|false" else key, nCtx, gpu = false)
    }

    /** Creates + initialises an engine on [Dispatchers.Default]; a failed engine is closed before returning. */
    private suspend fun init(f: File, nCtx: Int, gpu: Boolean): Result<Engine> = withContext(Dispatchers.Default) {
        var e: Engine? = null
        try {
            val cache = File(context.cacheDir, "litertlm").apply { mkdirs() }
            e = Engine(EngineConfig(
                modelPath = f.absolutePath,
                backend = if (gpu) Backend.GPU() else Backend.CPU(store.threads),
                maxNumTokens = nCtx,
                cacheDir = cache.absolutePath,
            ))
            e.initialize()
            Result.success(e)
        } catch (t: Throwable) {
            runCatching { e?.close() }
            Result.failure(t)
        }
    }

    private fun loaded(id: String, e: Engine, key: String, nCtx: Int, gpu: Boolean): String? {
        engine = e; loadedKey = key; onGpu = gpu
        val backend = if (gpu) GPU_LABEL else CPU_LABEL
        _state.value = LocalEngineState.Loaded(id, nCtx, "{\"engine\":\"litertlm\",\"backend\":\"$backend\"}", backend)
        store.lastBackend = backend
        Log.i(TAG, "loaded $id on $backend")
        return null
    }

    private fun fail(id: String, msg: String): String { _state.value = LocalEngineState.Failed(id, msg); return msg }

    override suspend fun unload() = mutex.withLock { unloadLocked() }

    private fun unloadLocked() {
        val e = engine
        engine = null; loadedKey = null; onGpu = false
        if (e != null) runCatching { e.close() }
        _state.value = LocalEngineState.Idle
    }

    override suspend fun chat(id: String, messagesJson: String, toolsJson: String, maxTokens: Int, temperature: Float): Pair<String?, String?> =
        mutex.withLock {
            loadLocked(id)?.let { return@withLock null to it }
            val body = generate(messagesJson, toolsJson, maxTokens, temperature)
            if (onGpu) {
                val err = Regex("^\\{\"error\":\"([^\"]*)\"").find(body)?.groupValues?.get(1)
                if (err != null && GpuPolicy.isGpuFailure(err)) {
                    gpuFailed = true
                    note("LiteRT GPU generation failed ($err); switched to CPU")
                    unloadLocked()
                    loadLocked(id, forceCpu = true)?.let { return@withLock null to it }
                    return@withLock generate(messagesJson, toolsJson, maxTokens, temperature) to null
                }
            }
            body to null
        }

    /** One turn: a fresh conversation rebuilt from the full history (simple and stateless), then the last message is streamed. */
    private suspend fun generate(messagesJson: String, toolsJson: String, maxTokens: Int, temperature: Float): String {
        val e = engine ?: return LiteRtFormat.error("Engine not loaded")
        val convo = LiteRtFormat.convo(messagesJson)
        val last = convo.last ?: return LiteRtFormat.error("Nothing to answer")
        val gpu = onGpu
        val backend = if (gpu) GPU_LABEL else CPU_LABEL
        var conversation: Conversation? = null
        if (gpu) GpuGuard.mark(store, "generation (LiteRT)")
        try {
            conversation = withContext(Dispatchers.Default) {
                e.createConversation(ConversationConfig(
                    systemInstruction = convo.system?.let { Contents.of(it) },
                    initialMessages = convo.history.map(::toMessage),
                    tools = LiteRtFormat.toolDescriptions(toolsJson).map { tool(NoExecTool(it)) },
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = temperature.toDouble()),
                    automaticToolCalling = false,
                    maxOutputToken = maxTokens.coerceAtLeast(1),
                ))
            }
            val text = StringBuilder()
            val thought = StringBuilder()
            val calls = mutableListOf<Pair<String, Map<String, Any?>>>()
            var chunks = 0L
            var first = 0L
            val before = runCatching { conversation.getTokenCount().toLong() }.getOrDefault(-1L)
            val start = System.currentTimeMillis()
            // Callback API (not the Flow wrapper): every callback is ours and catches Throwable, so nothing thrown on
            // LiteRT's native callback thread can take the process down.
            val err: Throwable? = try {
                suspendCancellableCoroutine { cont ->
                    val done = java.util.concurrent.atomic.AtomicBoolean(false)
                    fun finish(t: Throwable?) { if (done.compareAndSet(false, true) && cont.isActive) cont.resume(t) }
                    cont.invokeOnCancellation { runCatching { conversation.cancelProcess() } }
                    try {
                        conversation.sendMessageAsync(toMessage(last), object : MessageCallback {
                            override fun onMessage(message: Message) {
                                try {
                                    synchronized(text) {
                                        if (first == 0L) first = System.currentTimeMillis()
                                        chunks++
                                        message.contents.contents.forEach { c -> if (c is Content.Text) text.append(c.text) }
                                        message.channels["thought"]?.let(thought::append)
                                        message.toolCalls.forEach { tc -> calls += tc.name to tc.arguments }
                                    }
                                } catch (t: Throwable) { Log.w(TAG, "onMessage", t) }
                            }
                            override fun onDone() { try { finish(null) } catch (t: Throwable) { Log.w(TAG, "onDone", t) } }
                            override fun onError(throwable: Throwable) { try { finish(throwable) } catch (t: Throwable) { Log.w(TAG, "onError", t) } }
                        })
                    } catch (t: Throwable) { finish(t) }
                }
            } catch (c: CancellationException) {
                runCatching { conversation.cancelProcess() }
                throw c
            }
            if (err is CancellationException) // cancelled on the native side
                return LiteRtFormat.reply(text.toString(), thought.toString(), emptyList(), "cancelled", 0, chunks, 0, backend)
            if (err != null) throw err
            val end = System.currentTimeMillis()
            val genMs = (end - (if (first > 0) first else start)).coerceAtLeast(1)
            val after = runCatching { conversation.getTokenCount().toLong() }.getOrDefault(-1L)
            val outText = text.toString() + thought + calls.joinToString { it.first + LiteRtFormat.toJson(it.second) }
            val (promptTokens, completion) = LiteRtFormat.tokens(before, after, chunks, last.text, outText)
            val finish = if (completion >= maxTokens - 1) "length" else "stop"
            return LiteRtFormat.reply(text.toString(), thought.toString(), calls, finish, promptTokens, completion, genMs, backend)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "LiteRT generation failed", t)
            return LiteRtFormat.errorFor(t)
        } finally {
            if (gpu) GpuGuard.mark(store, null)
            conversation?.let { runCatching { it.close() } }
        }
    }

    private fun toMessage(t: LiteRtFormat.Turn): Message = when (t.role) {
        "assistant" -> Message.model(
            contents = Contents.of(if (t.text.isEmpty()) emptyList() else listOf(Content.Text(t.text))),
            toolCalls = t.toolCalls.map { (n, a) -> ToolCall(n, a) },
        )
        "tool" -> Message.tool(Contents.of(Content.ToolResponse(t.toolName ?: "tool", LiteRtFormat.toolResponse(t.text))))
        else -> Message.user(t.text)
    }

    override fun contextSize(): Int = (state.value as? LocalEngineState.Loaded)?.nCtx ?: store.contextSize

    fun resetGpuFailure() { gpuFailed = false }

    /** Describes a tool to LiteRT. With automaticToolCalling=false LiteRT never calls [execute]; if it ever did, nothing runs. */
    private class NoExecTool(private val description: String) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = description
        override fun execute(paramsJsonString: String): String =
            "{\"error\":\"Verdroid runs tools itself; nothing was executed\"}"
    }

    companion object {
        private const val TAG = "VerdroidLiteRt"
        const val GPU_LABEL = "LiteRT: GPU"
        const val CPU_LABEL = "LiteRT: CPU"
        /** Pinned dependency version (keep in sync with gradle/libs.versions.toml). */
        const val VERSION = "0.16.1"

        val available: Boolean by lazy {
            runCatching { Class.forName("com.google.ai.edge.litertlm.Engine"); true }.getOrDefault(false)
        }
        val unavailableReason = "LiteRT-LM isn't included in this build"
    }
}
