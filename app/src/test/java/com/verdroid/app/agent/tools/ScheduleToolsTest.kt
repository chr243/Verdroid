package com.verdroid.app.agent.tools

import com.verdroid.app.data.local.ScheduleEntity
import com.verdroid.app.data.schedule.ScheduledPrompt
import com.verdroid.app.domain.schedule.ScheduleKind
import com.verdroid.app.domain.schedule.ScheduleSpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleToolsTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val now = ZonedDateTime.parse("2026-10-17T09:33+02:00[Europe/Paris]").toInstant().toEpochMilli()

    private inner class Fake(private val warn: List<String> = emptyList()) : ScheduleBackend {
        val items = mutableListOf<ScheduleEntity>(); val posts = mutableListOf<Pair<Long, String>>()
        var lastSpec: ScheduleSpec? = null; var lastChat: Long? = null; var lastOnly = false
        override fun now() = this@ScheduleToolsTest.now
        override fun zone() = paris
        override suspend fun create(kind: ScheduleKind, name: String, message: String, spec: ScheduleSpec, onlyIfChanged: Boolean,
                                    sourceTaskId: Long?, chatTaskId: Long?): Pair<ScheduleEntity, List<String>> {
            lastSpec = spec; lastChat = chatTaskId; lastOnly = onlyIfChanged
            val (t, v) = ScheduleSpec.encode(spec)
            val e = ScheduleEntity(id = items.size + 1L, kind = kind.name, name = name, message = message, specType = t, specValue = v,
                onlyIfChanged = onlyIfChanged, nextRunAt = com.verdroid.app.domain.schedule.ScheduleMath.nextRun(spec, now(), paris), createdAt = now())
            items += e; return e to warn
        }
        override suspend fun list() = items.toList()
        override suspend fun delete(id: Long) = items.removeIf { it.id == id }
        override suspend fun setEnabled(id: Long, on: Boolean) = items.firstOrNull { it.id == id }?.copy(enabled = on)
        override suspend fun postInChat(taskId: Long, text: String) { posts += taskId to text }
    }

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun `remind me in 10 minutes about my soup`() = runBlocking {
        val b = Fake(listOf("Battery optimisation is on"))
        val out = obj(ScheduleReminderTool(b).execute(buildJsonObject { put("message", "Soup!"); put("in_minutes", 10) }, ToolContext(5)))
        assertEquals(ScheduleSpec.Once(now + 600_000), b.lastSpec)
        assertEquals("Sat 17 Oct 09:43", out["schedule"]!!.jsonObject["next_run_local"]!!.jsonPrimitive.content)
        assertTrue(out.containsKey("warnings"))
        val (chat, card) = b.posts.single()
        assertEquals(5L, chat); assertTrue(card, "Soup!" in card && "in 10 min" in card && "⚠️ Battery" in card)
    }

    @Test fun `daily and interval tasks`() = runBlocking {
        val b = Fake()
        obj(ScheduleTaskTool(b).execute(buildJsonObject { put("prompt", "Check cnn.com and summarise"); put("daily_at", "10am") }, ToolContext(5)))
        assertEquals(ScheduleSpec.Daily(10, 0), b.lastSpec); assertEquals(5L, b.lastChat) // default: the creating chat
        obj(ScheduleTaskTool(b).execute(buildJsonObject { put("prompt", "Check cnn.com"); put("daily_at", "11:00"); put("chat", "new") }, ToolContext(5)))
        assertNull(b.lastChat) // dedicated chat on request
        b.items.removeAt(b.items.lastIndex)
        obj(ScheduleTaskTool(b).execute(buildJsonObject { put("prompt", "Check the blog"); put("every_minutes", 15); put("only_if_changed", true); put("chat", "this") }, ToolContext(5)))
        assertEquals(ScheduleSpec.Interval(15), b.lastSpec); assertTrue(b.lastOnly); assertEquals(5L, b.lastChat)
        assertTrue(ScheduleTaskTool(b).execute(buildJsonObject { put("prompt", "x"); put("every_minutes", 5) }, ToolContext(5)).contains("error"))
        assertTrue(ScheduleTaskTool(b).execute(buildJsonObject { put("prompt", "x"); put("every_minutes", 30); put("daily_at", "10:00") }, ToolContext(5)).contains("error"))
        assertTrue(ScheduleReminderTool(b).execute(buildJsonObject { put("message", "x"); put("at", "yesterday") }, ToolContext(5)).contains("error"))
        val list = obj(ScheduleListTool(b).execute(buildJsonObject {}))
        assertEquals(2, list["count"]!!.jsonPrimitive.int)
        assertTrue(obj(ScheduleCancelTool(b).execute(buildJsonObject { put("id", 1) }, ToolContext(5)))["ok"]!!.jsonPrimitive.boolean)
        assertEquals(1, b.items.size)
    }

    @Test fun `chat target defaults to the creating chat`() {
        assertEquals(7L, ScheduleToolsParse.chatTarget(null, 7)); assertEquals(7L, ScheduleToolsParse.chatTarget("this", 7))
        listOf("new", "separate", " New ").forEach { assertNull(it, ScheduleToolsParse.chatTarget(it, 7)) }
        assertNull(ScheduleToolsParse.chatTarget(null, 0)) // no originating chat (UI) → dedicated
        val p = ScheduledPrompt.build("n", "do it", false, "10:00")
        assertTrue(p.contains("only context"))
    }

    @Test fun `run texts`() {
        assertTrue(ScheduledPrompt.isNoChange("NO_CHANGE")); assertTrue(ScheduledPrompt.isNoChange(" **no_change**. "))
        assertFalse(ScheduledPrompt.isNoChange("NO_CHANGE but a new post: X"))
        assertEquals("Top stories: A and B", ScheduledPrompt.summary("## Top stories:\n**A** and [B](https://b.c)"))
        assertTrue(ScheduledPrompt.summary("x".repeat(500)).length <= 300)
    }

    @Test fun `scheduling tools are their own preset group`() {
        listOf("schedule_reminder", "schedule_task", "schedule_list", "schedule_cancel", "schedule_pause", "schedule_resume").forEach {
            assertEquals("scheduling", com.verdroid.app.data.tools.ToolGroups.groupOf(it))
            assertEquals(com.verdroid.app.data.tools.ToolPreset.SCHEDULING, com.verdroid.app.data.tools.ToolPreset.of(it))
            assertFalse(it in com.verdroid.app.data.tools.ToolPrefs.DEFAULT_OFF)
        }
    }
}
