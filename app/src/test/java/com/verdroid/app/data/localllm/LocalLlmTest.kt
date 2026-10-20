package com.verdroid.app.data.localllm

import com.verdroid.app.agent.AgentLoop
import com.verdroid.app.data.network.ApiFunctionCall
import com.verdroid.app.data.network.ApiMessage
import com.verdroid.app.data.network.ApiToolCall
import com.verdroid.app.data.network.ModelIds
import com.verdroid.app.data.network.ModelProvider
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalLlmTest {
    @Test fun localIds() {
        assertEquals(ModelProvider.LOCAL, ModelIds.provider("local:qwen3-4b"))
        assertTrue(ModelIds.isLocal("local:qwen3-4b"))
        assertFalse(ModelIds.isOpenRouter("local:qwen3-4b"))
        assertEquals("qwen3-4b", ModelIds.bare("local:qwen3-4b"))
        assertEquals("qwen3-4b (on-device)", ModelIds.label("local:qwen3-4b"))
        assertEquals("local:a", ModelIds.local(" a "))
    }

    @Test fun compactSystemKeepsTail() {
        val full = AgentLoop.systemPrompt(listOf("web_search")) + "\n\nUSER RULES\n<verdroid-memory>m</verdroid-memory>"
        val c = LocalPrompt.compactSystem(full)
        assertTrue(c.startsWith(LocalPrompt.SYSTEM))
        assertTrue(c.endsWith("<verdroid-memory>m</verdroid-memory>"))
        assertTrue(c.length < full.length / 3)
        assertEquals("other", LocalPrompt.compactSystem("other"))
    }

    private fun tool(name: String, desc: String) = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject { put("name", name); put("description", desc); put("parameters", buildJsonObject { put("type", "object") }) })
    }

    @Test fun compactToolsFiltersAndShortens() {
        val tools = JsonArray(listOf(tool("web_search", "Search the web. Long details here."), tool("x_post", "Post."), tool("read_file", "Read.")))
        val c = LocalPrompt.compactTools(tools)!!
        assertEquals(listOf("web_search", "read_file"), LocalPrompt.toolNames(c))
        assertEquals("Search the web.", c[0].jsonObject["function"]!!.jsonObject["description"]!!.jsonPrimitive.content)
        assertNull(LocalPrompt.compactTools(JsonArray(listOf(tool("x_post", "p")))))
        assertTrue(LocalPrompt.CORE_TOOLS.size in 8..12)
    }

    @Test fun fitKeepsSystemAndNewestAndNoOrphanToolResult() {
        val big = "x".repeat(4000)
        val msgs = listOf(ApiMessage("system", "sys"), ApiMessage("user", big), ApiMessage("assistant", null,
            listOf(ApiToolCall("c1", function = ApiFunctionCall("web_search", "{}")))), ApiMessage("tool", big, toolCallId = "c1"),
            ApiMessage("user", "last"))
        val f = LocalPrompt.fit(msgs, nCtx = 1600, reserve = 256)
        assertEquals("system", f.first().role)
        assertEquals("last", f.last().content)
        assertNotEquals("tool", f[1].role)
        assertTrue(f.size < msgs.size)
    }

    @Test fun messagesJsonShape() {
        val j = Json.parseToJsonElement(LocalPrompt.messagesJson(listOf(
            ApiMessage("assistant", null, listOf(ApiToolCall("c1", function = ApiFunctionCall("read_file", "{\"path\":\"a\"}")))),
            ApiMessage("tool", "ok", toolCallId = "c1", name = "read_file"),
            ApiMessage("user", "hi", images = listOf("data:image/png;base64,AA"))))).jsonArray
        assertEquals("read_file", j[0].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("c1", j[1].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        assertNull(j[2].jsonObject["images"])
    }

    @Test fun replyParsing() {
        val ok = LocalReply.parse("""{"content":"Hi","reasoning":"","tool_calls":[{"id":"a","name":"web_search","arguments":"{\"q\":\"x\"}"}],"finish":"tool_calls","prompt_tokens":10,"completion_tokens":3}""", "m")
        ok as LocalReply.Result.Ok
        assertEquals("web_search", ok.completion.toolCalls.single().name)
        assertEquals(3L, ok.completionTokens)
        val think = LocalReply.parse("""{"content":"<think>hmm</think>Answer","tool_calls":[],"finish":"stop"}""", "m") as LocalReply.Result.Ok
        assertEquals("Answer", think.completion.content?.trim())
        val oom = LocalReply.parse("""{"error":"Out of memory"}""", "m") as LocalReply.Result.Error
        assertTrue(oom.outOfMemory)
        assertTrue(LocalReply.parse("garbage", "m") is LocalReply.Result.Error)
    }

    @Test fun modelHelpers() {
        assertEquals("qwen3-4b-q4_k_m", LocalModels.idFor("Qwen3-4B-Q4_K_M.gguf", emptySet()))
        assertEquals("qwen3-4b-q4_k_m-2", LocalModels.idFor("Qwen3-4B-Q4_K_M.gguf", setOf("qwen3-4b-q4_k_m")))
        assertTrue(LocalModels.isGguf("GGUF....".toByteArray()))
        assertFalse(LocalModels.isGguf("GG".toByteArray()))
        val gb = 1L shl 30
        assertTrue(LocalModels.fitsInRam(2 * gb + gb / 2, 4096, 6 * gb))
        assertFalse(LocalModels.fitsInRam(8 * gb, 4096, 3 * gb))
        assertEquals(2, LocalModels.defaultThreads(2)); assertEquals(4, LocalModels.defaultThreads(8))
    }
}
