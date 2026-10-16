package com.verdroid.app.agent.tools

import com.verdroid.app.domain.model.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OomHardeningTest {
    @Test fun readHeadStopsAtMax() {
        val f = Files.createTempFile("big", ".bin").toFile()
        try {
            f.outputStream().use { o -> val b = ByteArray(100_000) { 'a'.code.toByte() }; repeat(30) { o.write(b) } } // 3 MB
            assertEquals(4096, ReadFileTool.readHead(f, 4096).size)
            assertEquals(3_000_000, ReadFileTool.readHead(f, 5_000_000).size)
            val small = Files.createTempFile("s", ".txt").toFile().apply { writeText("hi") }
            assertEquals("hi", String(ReadFileTool.readHead(small, 65_536))); small.delete()
        } finally { f.delete() }
    }

    @Test fun readFileToolReportsSizeAndTruncation() = runTest {
        val root = Files.createTempDirectory("ws").toFile()
        try {
            File(root, "log.txt").writeText("x".repeat(200_000))
            val o = Json.parseToJsonElement(ReadFileTool(WorkspaceSandbox(root)).execute(buildJsonObject { put("path", "log.txt"); put("max_bytes", 100) })).jsonObject
            assertEquals(200_000, o["size"]!!.jsonPrimitive.int)
            assertTrue(o["truncated"]!!.jsonPrimitive.boolean)
            assertEquals(100, o["content"]!!.jsonPrimitive.content.length)
        } finally { root.deleteRecursively() }
    }

    private fun msg(id: Long, role: MessageRole, content: String) =
        ChatMessage(id, 1, role, content, MessageKind.NORMAL, null, null, null, null, false, null, id, null)
    private fun call(id: Long, msgId: Long, name: String, result: String) =
        ToolCallRecord(id, 1, msgId, "c$id", name, "{}", result, ToolCallStatus.SUCCESS, "native", 0, 0)

    @Test fun chatWindowKeepsNewestAndCutsBigOutputs() {
        val big = "r".repeat(ChatWindow.CAP + 5000)
        val msgs = (1L..300L).map { msg(it, if (it % 3 == 0L) MessageRole.TOOL else MessageRole.ASSISTANT, if (it % 3 == 0L) big else "m$it") }
        val w = ChatWindow.messages(msgs, 120)
        assertEquals(120, w.size); assertEquals(181L, w.first().id); assertEquals(300L, w.last().id)
        assertTrue(w.filter { it.role == MessageRole.TOOL }.all { it.content!!.length < ChatWindow.CAP + 200 && it.content!!.endsWith(ChatWindow.MARKER) })
        val calls = listOf(call(1, 10, "termux_run", big), call(2, 200, "termux_run", big), call(3, 250, ChartTool.NAME, big))
        val c = ChatWindow.toolCalls(msgs, calls, 120)
        assertEquals(listOf(2L, 3L), c.map { it.id })
        assertTrue(c[0].resultJson!!.endsWith(ChatWindow.MARKER))
        assertEquals(big, c[1].resultJson) // charts stay whole
        assertTrue(ChatWindow.hasMore(120, 120)); assertFalse(ChatWindow.hasMore(119, 120))
    }

    @Test fun unknownPauseReasonStaysPaused() {
        assertFalse(PauseReason.autoResumes(null)); assertFalse(PauseReason.autoResumes("SOMETHING_NEW"))
        assertFalse(PauseReason.autoResumes("USER")); assertTrue(PauseReason.autoResumes("RATE_LIMIT"))
    }
}
