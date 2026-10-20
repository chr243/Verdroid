package com.verdroid.app.data.localllm

import com.verdroid.app.data.localllm.GpuPolicy.Mode
import org.junit.Assert.*
import org.junit.Test

class GpuPolicyTest {
    @Test fun decide() {
        assertEquals(Mode.CPU, GpuPolicy.decide(false, null, true, null).mode)
        assertNull(GpuPolicy.decide(false, null, true, null).note)
        assertEquals(Mode.GPU, GpuPolicy.decide(true, null, true, null).mode)
        assertEquals(Mode.GPU, GpuPolicy.decide(true, null, true, true).mode)
        assertEquals(Mode.CPU, GpuPolicy.decide(true, "crashed", true, true).mode)
        assertTrue(GpuPolicy.decide(true, "crashed", true, true).note!!.contains("crashed"))
        assertEquals(Mode.CPU, GpuPolicy.decide(true, null, false, true).mode)
        assertTrue(GpuPolicy.decide(true, null, true, false).note!!.contains("restart"))
    }

    @Test fun layers() {
        assertEquals(0, GpuPolicy.nativeLayers(Mode.CPU, -1))
        assertEquals(-1, GpuPolicy.nativeLayers(Mode.GPU, -1))
        assertEquals(16, GpuPolicy.nativeLayers(Mode.GPU, 16))
        assertEquals(1, GpuPolicy.nativeLayers(Mode.GPU, 0))
        assertEquals("All", GpuPolicy.layersLabel(-1))
        assertEquals(-1, GpuPolicy.LAYER_CHOICES.first())
    }

    @Test fun crashAndBackend() {
        assertTrue(GpuPolicy.crashMessage("model load|123").contains("model load"))
        assertTrue(GpuPolicy.crashMessage("").contains("GPU"))
        assertFalse(GpuPolicy.isGpuFailure("cancelled"))
        assertTrue(GpuPolicy.isGpuFailure("vk::DeviceLostError"))
        assertEquals("Vulkan: Adreno (TM) 740", GpuPolicy.backendLabel("{\"desc\":\"x\",\"backend\":\"Vulkan: Adreno (TM) 740\"}"))
        assertEquals("CPU", GpuPolicy.backendLabel("{}"))
        assertEquals("CPU", GpuPolicy.backendLabel(null))
    }
}

class LocalLabelTest {
    @Test fun format() {
        assertEquals("gemma-2-2b (on device) 10 tok/s", LocalLabel.format("gemma-2-2b", 52, 5200, "CPU"))
        assertEquals("gemma-2-2b (on device) 13 tok/s · GPU", LocalLabel.format("gemma-2-2b", 40, 3100, "Vulkan: Adreno (TM) 740"))
        assertEquals("gemma-2-2b (on device)", LocalLabel.format("gemma-2-2b", 0, 0, "CPU"))
        val r = LocalReply.parse("""{"content":"hi","tool_calls":[],"finish":"stop","completion_tokens":20,"gen_ms":2000,"backend":"CPU"}""", "m") as LocalReply.Result.Ok
        assertEquals(2000L, r.genMs); assertEquals("CPU", r.backend)
    }
}
