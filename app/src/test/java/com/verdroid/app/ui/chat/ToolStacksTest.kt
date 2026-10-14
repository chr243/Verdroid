package com.verdroid.app.ui.chat

import com.verdroid.app.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class ToolStacksTest {
    private fun msg(id: Long, role: MessageRole, content: String? = null) =
        ChatMessage(id, 1, role, content, MessageKind.NORMAL, null, null, null, null, false, null, id)
    private fun call(id: Long, mid: Long, name: String, st: ToolCallStatus = ToolCallStatus.SUCCESS) =
        ToolCallRecord(id, 1, mid, "c$id", name, "{}", "{}", st, "native", id, id)

    @Test fun `consecutive termux_run turns fold into one stack, others stay separate`() {
        val visible = listOf(
            msg(1, MessageRole.USER, "do it"),
            msg(2, MessageRole.ASSISTANT, "Let me check"),
            msg(3, MessageRole.ASSISTANT), msg(4, MessageRole.ASSISTANT), msg(5, MessageRole.ASSISTANT),
            msg(6, MessageRole.ASSISTANT), msg(7, MessageRole.ASSISTANT, "Done"),
        )
        val calls = listOf(
            call(10, 2, "termux_run"), call(11, 3, "termux_run"), call(12, 4, "termux_run", ToolCallStatus.ERROR),
            call(13, 4, "termux_run"), call(14, 5, "web_search"), call(15, 6, "termux_run"),
        ).groupBy { it.messageId }
        val rows = ToolStacks.rows(visible, calls)
        assertEquals(listOf(1L, 2L, 5L, 6L, 7L), rows.map { it.key })
        assertEquals(listOf(listOf(10L, 11L, 12L, 13L)), rows[1].groups.map { g -> g.map { it.id } })
        assertEquals(ToolCallStatus.ERROR, ToolStacks.status(rows[1].groups.single()))
        assertEquals(listOf(listOf(14L)), rows[2].groups.map { g -> g.map { it.id } }) // non-stackable tool breaks the run
        assertEquals(1, rows[3].groups.single().size)
        assertTrue(rows[4].groups.isEmpty())
    }

    @Test fun `non-stackable tools are never merged`() {
        val visible = listOf(msg(1, MessageRole.ASSISTANT), msg(2, MessageRole.ASSISTANT))
        val calls = listOf(call(1, 1, "web_search"), call(2, 1, "web_search"), call(3, 2, "web_search")).groupBy { it.messageId }
        val rows = ToolStacks.rows(visible, calls)
        assertEquals(2, rows.size); assertEquals(listOf(1, 1), rows[0].groups.map { it.size })
    }

    @Test fun `a turn with thinking is not folded into the previous stack`() {
        val visible = listOf(msg(1, MessageRole.ASSISTANT), msg(2, MessageRole.ASSISTANT).copy(reasoning = "why"))
        val calls = listOf(call(1, 1, "termux_run"), call(2, 2, "termux_run")).groupBy { it.messageId }
        assertEquals(listOf(1L, 2L), ToolStacks.rows(visible, calls).map { it.key })
    }

    @Test fun `display splits legacy inline think and prefers stored reasoning`() {
        assertEquals("old" to "Hi", ToolStacks.display(msg(1, MessageRole.ASSISTANT, "<think>old</think>Hi")).let { it.reasoning to it.content })
        assertEquals("r" to "Hi", ToolStacks.display(msg(1, MessageRole.ASSISTANT, "Hi").copy(reasoning = "r")).let { it.reasoning to it.content })
        assertEquals("First line", ToolStacks.thinkingPreview("\n## First line\nmore"))
    }
}

class ChatScrollTest {
    @Test fun `near bottom only when last item is visible and its bottom is close to the viewport end`() {
        assertTrue(ChatScroll.nearBottom(null, 0, 0, 1000, 100))
        assertTrue(ChatScroll.nearBottom(9, 10, 1050, 1000, 100))
        assertFalse(ChatScroll.nearBottom(9, 10, 1500, 1000, 100)) // tall last item, user is reading its top
        assertFalse(ChatScroll.nearBottom(6, 10, 1000, 1000, 100)) // scrolled up
    }
}

class StepsBlockTest {
    private fun msg(id: Long, role: MessageRole, content: String? = null) =
        ChatMessage(id, 1, role, content, MessageKind.NORMAL, null, null, null, null, false, null, id)
    private fun call(id: Long, mid: Long, name: String, st: ToolCallStatus = ToolCallStatus.SUCCESS) =
        ToolCallRecord(id, 1, mid, "c$id", name, "{}", "{}", st, "native", id, id)
    private fun think(id: Long) = ChatMessage(id, 1, MessageRole.ASSISTANT, null, MessageKind.NORMAL, null, null, null, null, false, null, id, "hmm")

    @Test fun `thinking and tool steps between replies collapse into one steps block`() {
        val visible = listOf(msg(1, MessageRole.USER, "q"), think(2), msg(3, MessageRole.ASSISTANT), msg(4, MessageRole.ASSISTANT),
            msg(5, MessageRole.ASSISTANT), msg(6, MessageRole.ASSISTANT, "Answer"))
        val calls = listOf(call(10, 3, "web_search"), call(11, 4, "web_fetch"), call(12, 4, "web_fetch"), call(13, 5, "web_fetch"))
            .groupBy { it.messageId }
        val b = ToolStacks.blocks(ToolStacks.rows(visible, calls), generating = false)
        assertEquals(listOf(1L, 2L, 6L), b.map { it.key })
        val st = b[1] as ToolStacks.Steps
        assertEquals(5, st.stepCount)
        assertEquals("web_search, web_fetch ×3", st.label)
        assertEquals(ToolStacks.StepsStatus.DONE, st.status); assertNull(st.current)
        assertTrue(b[0] is ToolStacks.Single && b[2] is ToolStacks.Single)
    }

    @Test fun `steps status is running at the tail while generating, failed on any error, single step stays a row`() {
        val v = listOf(msg(1, MessageRole.ASSISTANT), msg(2, MessageRole.ASSISTANT))
        val ok = listOf(call(1, 1, "web_search"), call(2, 2, "web_fetch")).groupBy { it.messageId }
        val run = ToolStacks.blocks(ToolStacks.rows(v, ok), generating = true).single() as ToolStacks.Steps
        assertEquals(ToolStacks.StepsStatus.RUNNING, run.status); assertEquals("web_fetch", run.current)
        val pending = listOf(call(1, 1, "web_search"), call(2, 2, "web_fetch", ToolCallStatus.PENDING)).groupBy { it.messageId }
        assertEquals(ToolStacks.StepsStatus.RUNNING, (ToolStacks.blocks(ToolStacks.rows(v, pending), false).single() as ToolStacks.Steps).status)
        val err = listOf(call(1, 1, "web_search", ToolCallStatus.ERROR), call(2, 2, "web_fetch")).groupBy { it.messageId }
        assertEquals(ToolStacks.StepsStatus.FAILED, (ToolStacks.blocks(ToolStacks.rows(v, err), false).single() as ToolStacks.Steps).status)
        val one = ToolStacks.blocks(ToolStacks.rows(v.take(1), mapOf(1L to listOf(call(1, 1, "web_search")))), false)
        assertTrue(one.single() is ToolStacks.Single)
        assertEquals("Thinking", ToolStacks.stepsLabel(emptyList()))
    }

    @Test fun `unread counts only user-facing assistant replies`() {
        fun m(role: MessageRole, c: String?, calls: String? = null, kind: MessageKind = MessageKind.NORMAL) =
            ChatMessage(1, 1, role, c, kind, calls, null, null, null, false, null, 1)
        assertTrue(UnreadPolicy.isUserFacing(m(MessageRole.ASSISTANT, "Here is the answer")))
        assertTrue(UnreadPolicy.isUserFacing(m(MessageRole.ASSISTANT, "Which file do you mean?", "[]")))
        assertFalse(UnreadPolicy.isUserFacing(m(MessageRole.ASSISTANT, null)))
        assertFalse(UnreadPolicy.isUserFacing(think(2)))
        assertFalse(UnreadPolicy.isUserFacing(m(MessageRole.ASSISTANT, "Let me search", "[{\"id\":\"1\"}]")))
        assertFalse(UnreadPolicy.isUserFacing(m(MessageRole.TOOL, "{result}")))
        assertFalse(UnreadPolicy.isUserFacing(m(MessageRole.ASSISTANT, "status", kind = MessageKind.STATUS)))
    }
}
