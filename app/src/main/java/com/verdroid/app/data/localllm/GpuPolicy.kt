package com.verdroid.app.data.localllm

/** GPU (Vulkan) decisions for the local engine. Pure, JVM-tested. */
object GpuPolicy {
    enum class Mode { CPU, GPU }
    data class Decision(val mode: Mode, val note: String?)

    /**
     * [useGpu] = the switch; [blocked] = why GPU was turned off automatically (crash sentinel / failed probe);
     * [vulkanInProcess] = Vulkan is registered in this process (decided once, at the first load).
     */
    fun decide(useGpu: Boolean, blocked: String?, hasVulkan: Boolean, vulkanInProcess: Boolean?): Decision = when {
        !useGpu -> Decision(Mode.CPU, null)
        !hasVulkan -> Decision(Mode.CPU, "This build has no GPU support; using CPU")
        blocked != null -> Decision(Mode.CPU, "GPU off: $blocked")
        vulkanInProcess == false -> Decision(Mode.CPU, "GPU was turned on after the engine started; restart Verdroid to use it (CPU for now)")
        else -> Decision(Mode.GPU, null)
    }

    /** Native gpuLayers argument: settings value −1 = all. */
    fun nativeLayers(mode: Mode, layers: Int): Int = if (mode == Mode.CPU) 0 else if (layers < 0) -1 else layers.coerceAtLeast(1)

    val LAYER_CHOICES = listOf(-1, 32, 24, 16, 8)
    fun layersLabel(n: Int) = if (n < 0) "All" else "$n"

    /** Sentinel file content "phase|timestamp" → message shown after a crash while on GPU. */
    fun crashMessage(sentinel: String): String {
        val phase = sentinel.substringBefore('|').ifBlank { "GPU use" }
        return "Verdroid closed unexpectedly during $phase on the GPU last time, so GPU was switched off. You can try again with Use GPU."
    }

    /** Errors after which the GPU attempt is abandoned for this model load and the CPU is used instead. */
    fun isGpuFailure(error: String): Boolean = error != "cancelled"

    /** "Vulkan: Adreno (TM) 740" → GPU; "CPU" → CPU. */
    fun backendLabel(info: String?): String =
        info?.let { Regex("\"backend\":\"([^\"]*)\"").find(it)?.groupValues?.get(1) }?.ifBlank { null } ?: "CPU"
}
