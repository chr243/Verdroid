package com.verdroid.app.data.localllm

/**
 * JNI bridge to the bundled llama.cpp (`libverdroid_llama.so`, arm64-v8a, CPU only; see app/src/main/cpp).
 * The library is loaded lazily; on devices/ABIs without it [available] is false and local models count as unavailable.
 */
object LlamaNative {
    @Volatile private var loadError: String? = null
    private val loaded: Boolean by lazy {
        try { System.loadLibrary("verdroid_llama"); true } catch (e: Throwable) { loadError = e.message ?: e.javaClass.simpleName; false }
    }
    val available: Boolean get() = loaded
    val unavailableReason: String get() = if (loaded) "" else "On-device engine not available on this phone (${loadError ?: "arm64 only"})"

    /** Returns a handle, or 0 on failure ([nativeLastError] says why). */
    /** [gpuLayers]: 0 = CPU only, -1 = all layers on the Vulkan GPU, n = first n layers. */
    external fun nativeLoad(path: String, nCtx: Int, nThreads: Int, gpuLayers: Int): Long
    /** Decides once per process whether Vulkan is registered (call before the first load). Returns whether it is. */
    external fun nativeInitBackend(vulkan: Boolean): Boolean
    /** True when this build contains the Vulkan backend (arm64). */
    external fun nativeHasVulkan(): Boolean
    /** JSON {ok, device, devices[], max_err, ms, error}. Only call in the probe process (see [GpuProbeService]). */
    external fun nativeGpuProbe(): String
    external fun nativeLastError(): String
    /** Round-trips [s] through the native UTF-8 conversion (self-test: emoji must survive). */
    external fun nativeEcho(s: String): String
    external fun nativeFree(handle: Long)
    external fun nativeCancel(handle: Long)
    /** JSON: {desc, n_ctx, n_ctx_train, size_bytes, n_params, has_template, system}. */
    external fun nativeInfo(handle: Long): String
    /** [messagesJson]/[toolsJson] in OpenAI chat format; returns the JSON parsed by [LocalReply]. Blocking. */
    external fun nativeChat(handle: Long, messagesJson: String, toolsJson: String, maxTokens: Int, temperature: Float, thinking: Boolean): String
}
