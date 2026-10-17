package com.verdroid.app.domain.schedule

import com.verdroid.app.agent.tools.AgentTool
import com.verdroid.app.agent.tools.FileChangeGuard
import com.verdroid.app.agent.tools.InstallConsent
import com.verdroid.app.agent.tools.PackageSpec
import com.verdroid.app.agent.tools.ToolRegistry
import com.verdroid.app.data.schedule.ScheduledPrompt
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScheduleSafetyTest {
    private fun args(vararg p: Pair<String, Any>) = buildJsonObject { p.forEach { (k, v) -> if (v is Boolean) put(k, v) else put(k, v.toString()) } }

    @Test fun `confirmation, sending, phone control and deletes are blocked`() {
        listOf("x_post", "screen_tap", "screen_type", "screen_action", "run_shell", "rish_run", "git_push", "workspace_delete",
            "cookie_login", "schedule_task", "schedule_reminder").forEach { assertNotNull(it, ScheduleSafety.blockReason(it, args())) }
        listOf("web_search", "web_fetch", "workspace_read", "selenium_open", "x_timeline", "memory_save", "chart", "schedule_list",
            "pdf_extract_text", "crypto_backtest").forEach { assertNull(it, ScheduleSafety.blockReason(it, args())) }
        assertNotNull(ScheduleSafety.blockReason("mcp__gmail__send_email", args()))
        assertNull(ScheduleSafety.blockReason("mcp__docs__search", args()))
        // x_post: only with its own Always allow; the block message says how to allow it. Other tools can't be always-allowed.
        assertTrue(ScheduleSafety.blockReason("x_post", args())!!.contains("Post to X without asking"))
        assertNull(ScheduleSafety.blockReason("x_post", args()) { it == "x_post" })
        assertNotNull(ScheduleSafety.blockReason("git_push", args()) { true })
        assertTrue(ScheduleSafety.blockReason("cookie_login", args())!!.contains("NOT_LOGGED_IN"))
        assertNotNull(ScheduleSafety.blockReason("pdf_info", args("confirm_install" to true)))
        assertNotNull(ScheduleSafety.blockReason("ebook_translate", args("confirmed" to true)))
        assertNull(ScheduleSafety.blockReason("ebook_translate", args("input_path" to "Input/a.epub")))
        assertNotNull(ScheduleSafety.blockReason("termux_run", args("command" to "termux-sms-send -n 123 hi")))
        assertNotNull(ScheduleSafety.blockReason("termux_run", args("command" to "pip install requests")))
        assertNull(ScheduleSafety.blockReason("termux_run", args("command" to "curl -s https://example.com")))
    }

    @Test fun `scheduled messages are recognised`() {
        val p = ScheduledPrompt.build("CNN", "Summarise cnn.com", onlyIfChanged = true, whenLocal = "Sat 17 Oct 10:00")
        assertTrue(ScheduleSafety.isScheduledMessage(p))
        assertTrue(ScheduleSafety.NO_CHANGE in p)
        assertFalse(ScheduleSafety.isScheduledMessage("what's on cnn?"))
        assertFalse(ScheduleSafety.isScheduledMessage(null))
        assertEquals("CNN", ScheduleSafety.scheduleName(p)); assertNull(ScheduleSafety.scheduleName("hi"))
        assertTrue(p.contains("x_timeline")); assertTrue(p.contains("Always allow"))
    }

    private class Probe(override val name: String) : AgentTool {
        var ran = false; var sawHeadless = false
        override val description = ""; override val parameters = buildJsonObject {}
        override suspend fun execute(args: JsonObject): String {
            ran = true; sawHeadless = kotlin.coroutines.coroutineContext[HeadlessRun] != null; return "{\"ok\":true}"
        }
    }

    @Test fun `registry refuses blocked tools only in scheduled runs`() = runBlocking {
        val post = Probe("x_post"); val fetch = Probe("web_fetch")
        val reg = ToolRegistry(listOf(post, fetch), headless = { if (it == 7L) "t" else null })
        val r = reg.execute("x_post", "{\"text\":\"hi\"}", 7L)
        assertTrue(r.isError); assertTrue(r.json, "BLOCKED_IN_SCHEDULED_RUN" in r.json); assertFalse(post.ran)
        val ok = reg.execute("web_fetch", "{\"url\":\"https://a.b\"}", 7L)
        assertFalse(ok.isError); assertTrue(fetch.ran); assertTrue("consent prompts must see the headless flag", fetch.sawHeadless)
        // Read-only X tools are not blocked unattended (assume logged in).
        assertNull(ScheduleSafety.blockReason("x_timeline", buildJsonObject {}))
        // A normal (attended) chat is untouched.
        assertFalse(reg.execute("x_post", "{\"text\":\"hi\"}", 8L).isError); assertTrue(post.ran)
    }

    @Test fun `file changes that need a Yes are refused unattended, allowed overwrites pass`() = runBlocking {
        var asked = false
        val guard = FileChangeGuard({ asked = true; true })
        val shell = Probe("termux_run")
        val reg = ToolRegistry(listOf(shell), fileGuard = guard, headless = { "t" })
        val r = reg.execute("termux_run", "{\"command\":\"rm /storage/emulated/0/Documents/Verdroid/Output/a.txt\"}", 3L)
        assertTrue(r.json, r.isError && "BLOCKED_IN_SCHEDULED_RUN" in r.json); assertFalse(asked); assertFalse(shell.ran)
        val always = FileChangeGuard({ true }, overwritePolicy = object : com.verdroid.app.agent.tools.OverwritePolicy {
            override fun allowsOverwrite(taskId: Long) = true })
        assertNull(always.headlessReason(3L, "termux_run", buildJsonObject { put("command", "echo hi > /sdcard/x.txt") }))
        assertNotNull(always.headlessReason(3L, "termux_run", buildJsonObject { put("command", "rm /sdcard/x.txt") }))
    }

    @Test fun `package installs are denied without asking in a scheduled run`() = runBlocking {
        var asked = false
        val d = kotlinx.coroutines.withContext(HeadlessRun(1)) {
            InstallConsent.resolve(buildJsonObject {}, "g", listOf(PackageSpec("pip", "pypdf")), "pdf_info", "why") { _, _, _, _ -> asked = true; true }
        }
        assertEquals(InstallConsent.Decision.Deny, d); assertFalse(asked)
    }
}
