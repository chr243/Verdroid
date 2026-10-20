package com.verdroid.app.data.localllm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LiteRtModelFormatTest {
    private val lrtHead = "LITERTLM".toByteArray() + byteArrayOf(1, 0, 0, 0)

    @Test fun magic() {
        assertTrue(LocalModels.isLiteRtLm(lrtHead))
        assertFalse(LocalModels.isLiteRtLm("LITERT".toByteArray()))
        assertFalse(LocalModels.isLiteRtLm("GGUF\u0003\u0000\u0000\u0000".toByteArray()))
        assertEquals(LocalModels.LITERTLM, LocalModels.formatOf("Gemma3.litertlm", lrtHead))
        assertEquals(LocalModels.LITERTLM, LocalModels.formatOf("X.LITERTLM", lrtHead))
        assertNull(LocalModels.formatOf("model.bin", lrtHead)) // extension required for LiteRT
        assertNull(LocalModels.formatOf("model.litertlm", "PK\u0003\u0004....".toByteArray()))
        assertEquals(LocalModels.GGUF, LocalModels.formatOf("anything", "GGUF\u0003\u0000\u0000\u0000".toByteArray()))
    }

    @Test fun extensionsAndCollisions() {
        assertEquals("gemma3-1b-it", LocalModels.idFor("Gemma3-1B-IT.litertlm", emptySet()))
        assertEquals("qwen", LocalModels.idFor("qwen.GGUF", emptySet()))
        val taken = LocalModels.takenIds(listOf("a"), listOf("gemma.litertlm", "qwen.gguf", "old.gguf.part", "x.litertlm.part"))
        assertEquals(setOf("a", "gemma", "qwen", "old", "x"), taken)
        assertEquals("gemma-2", LocalModels.idFor("gemma.litertlm", taken))
        assertEquals("qwen-2", LocalModels.idFor("qwen.litertlm", taken)) // same id across formats collides
        assertEquals("Gemma", LocalModels.stripExt("Gemma.litertlm"))
    }

    @Test fun jsonBackCompat() {
        val json = Json { ignoreUnknownKeys = true }
        val old = """[{"id":"q","name":"Q","path":"/x/q.gguf","sizeBytes":5}]"""
        val m = json.decodeFromString(ListSerializer(LocalModel.serializer()), old).single()
        assertEquals("gguf", m.format)
        val n = LocalModel("g", "G", "/x/g.litertlm", 1, LocalModels.LITERTLM)
        assertEquals(n, json.decodeFromString(LocalModel.serializer(), json.encodeToString(LocalModel.serializer(), n)))
    }

    @Test fun liteRtGpuLabel() {
        assertEquals("g (on device) 10 tok/s · GPU", LocalLabel.format("g", 20, 2000, "LiteRT: GPU"))
        assertEquals("g (on device) 10 tok/s", LocalLabel.format("g", 20, 2000, "LiteRT: CPU"))
    }
}

class LiteRtFormatTest {
    @Test fun convoFromMessagesJson() {
        val msgs = """[
          {"role":"system","content":"S"},
          {"role":"user","content":"read a"},
          {"role":"assistant","content":"","tool_calls":[{"id":"c1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"a\",\"n\":2}"}}]},
          {"role":"tool","content":"{\"ok\":true}","tool_call_id":"c1"}
        ]"""
        val c = LiteRtFormat.convo(msgs)
        assertEquals("S", c.system)
        assertEquals(listOf("user", "assistant"), c.history.map { it.role })
        assertEquals("read_file" to mapOf("path" to "a", "n" to 2L), c.history[1].toolCalls.single())
        assertEquals("tool", c.last!!.role)
        assertEquals("read_file", c.last!!.toolName) // resolved from tool_call_id
        assertEquals(mapOf("ok" to true), LiteRtFormat.toolResponse(c.last!!.text))
        assertEquals("plain", LiteRtFormat.toolResponse("plain"))
        assertNull(LiteRtFormat.convo("""[{"role":"user","content":"a"},{"role":"assistant","content":"b"}]""").last)
    }

    @Test fun toolDescriptions() {
        val tools = """[{"type":"function","function":{"name":"read_file","description":"Read","parameters":{"type":"object"}}},{"type":"function","function":{}}]"""
        val d = LiteRtFormat.toolDescriptions(tools).single()
        assertEquals("read_file", Json.parseToJsonElement(d).jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(LiteRtFormat.toolDescriptions("").isEmpty())
    }

    @Test fun replyRoundTripsThroughLocalReply() {
        val text = LocalReply.parse(LiteRtFormat.reply("Hello there", "think", emptyList(), "stop", 12, 0, 500, "LiteRT: GPU"), "m") as LocalReply.Result.Ok
        assertEquals("Hello there", text.completion.content)
        assertEquals("think", text.completion.reasoning)
        assertEquals("stop", text.completion.finishReason)
        assertTrue(text.completionTokens > 0) // never 0 with output
        assertEquals(500L, text.genMs); assertEquals("LiteRT: GPU", text.backend)
        val call = LocalReply.parse(LiteRtFormat.reply("", null, listOf("read_file" to mapOf("path" to "a")), "stop", 1, 4, 10, "LiteRT: CPU"), "m") as LocalReply.Result.Ok
        assertEquals("tool_calls", call.completion.finishReason)
        val tc = call.completion.toolCalls.single()
        assertEquals("read_file", tc.name)
        assertEquals("a", Json.parseToJsonElement(tc.argumentsJson).jsonObject["path"]!!.jsonPrimitive.content)
        val cancelled = LocalReply.parse(LiteRtFormat.reply("", null, emptyList(), "cancelled", 0, 0, 0, "LiteRT: CPU"), "m")
        assertTrue(cancelled is LocalReply.Result.Error && cancelled.cancelled)
        val oom = LocalReply.parse(LiteRtFormat.errorFor(OutOfMemoryError("x")), "m")
        assertTrue(oom is LocalReply.Result.Error && oom.outOfMemory)
    }
}

class LocalEnginesTest {
    private class Fake(val name: String, val log: MutableList<String>) : LocalEngine {
        private val _s = MutableStateFlow<LocalEngineState>(LocalEngineState.Idle)
        override val state: StateFlow<LocalEngineState> = _s
        override val gpuNote: StateFlow<String?> = MutableStateFlow(null)
        override val loadedId: String? get() = (_s.value as? LocalEngineState.Loaded)?.id
        override suspend fun ensureLoaded(id: String): String? { log += "$name load $id"; _s.value = LocalEngineState.Loaded(id, 4096, "{}", name); return null }
        override suspend fun chat(id: String, messagesJson: String, toolsJson: String, maxTokens: Int, temperature: Float): Pair<String?, String?> {
            ensureLoaded(id); log += "$name chat $id"; return "{}" to null
        }
        override suspend fun unload() { if (_s.value !is LocalEngineState.Idle) log += "$name unload"; _s.value = LocalEngineState.Idle }
        override fun contextSize() = 4096
    }

    @Test fun routesByFormatAndKeepsOneModelInRam() = runBlocking {
        val log = mutableListOf<String>()
        val llama = Fake("llama", log); val lrt = Fake("litert", log)
        val formats = mapOf("q" to "gguf", "g" to "litertlm")
        val e = LocalEngines(llama, lrt) { formats[it] }
        assertSame(llama, e.engineFor("q")); assertSame(lrt, e.engineFor("g")); assertSame(llama, e.engineFor("unknown"))
        e.ensureLoaded("q")
        assertEquals("q", e.loadedId)
        e.chat("g", "[]", "", 10)
        assertEquals(listOf("llama load q", "llama unload", "litert load g", "litert chat g"), log)
        assertNull(llama.loadedId); assertEquals("g", e.loadedId)
        assertEquals("litert", (e.current as LocalEngineState.Loaded).backend)
        e.ensureLoaded("q")
        assertNull(lrt.loadedId); assertEquals("q", e.loadedId)
    }
}
