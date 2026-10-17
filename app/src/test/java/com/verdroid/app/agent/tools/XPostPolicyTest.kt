package com.verdroid.app.agent.tools

import com.verdroid.app.data.cookies.BrowserCookie
import com.verdroid.app.data.cookies.CookieVault
import com.verdroid.app.data.storage.SharedFolder
import com.verdroid.app.data.termux.TermuxResult
import com.verdroid.app.data.termux.TermuxRunner
import com.verdroid.app.data.termux.VerdroidSeleniumPy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class XPostPolicyTest {
    private val H = XPostLimits.HOUR
    private val now = 1_800_000_000_000L

    @Test fun hourlyAndDailyCaps() {
        assertNull(XPostLimits.blockReason(List(4) { now - it * 60_000L }, now, 5, 20))
        val hour = XPostLimits.blockReason(List(5) { now - it * 60_000L }, now, 5, 20)
        assertTrue(hour!!.startsWith("RATE_LIMITED") && "5 posts per hour" in hour && "56 min" in hour)
        val day = List(20) { now - (it + 2) * H / 2 } // spread over the day, none in the last hour
        assertTrue(XPostLimits.blockReason(day, now, 5, 20)!!.contains("20 posts per day"))
        assertNull(XPostLimits.blockReason(day, now, 5, 0)) // 0 = no cap
        assertNull(XPostLimits.blockReason(List(5) { now - 25 * H }, now, 5, 20)) // older than a day
        assertEquals(listOf(now - 2 * H, now), XPostLimits.prune(listOf(now, now - 30 * H, now - 2 * H), now))
    }

    @Test fun auditLineIsOneLineWithoutCookies() {
        val l = XPostAudit.line(XPostAudit.Entry(now, XPostAudit.Result.POSTED, "hi\nthere | x", "https://x.com/a/status/1", "https://x.com/me/status/2"))
        assertEquals(1, l.count { it == '\n' }); assertTrue(l.endsWith("\n"))
        assertTrue(" | posted | reply_to=https://x.com/a/status/1 | url=https://x.com/me/status/2 | text=hi\\nthere | x" in l)
        assertEquals(XPostAudit.Result.UNCERTAIN to null, XPostAudit.resultOf("""{"posted":"uncertain"}"""))
        assertEquals(XPostAudit.Result.POSTED to "u", XPostAudit.resultOf("""{"posted":true,"post_url":"u"}"""))
        assertEquals(XPostAudit.Result.NOT_POSTED, XPostAudit.resultOf("""{"posted":false}""").first)
    }

    private lateinit var base: File
    private lateinit var folder: SharedFolder
    @Before fun setUp() { base = Files.createTempDirectory("verdroid-xp").toFile(); folder = SharedFolder(File(base, "Documents/Verdroid")) { true }; folder.ensure() }
    @After fun tearDown() { base.deleteRecursively() }
    private val vault = CookieVault.InMemory(listOf(BrowserCookie("auth_token", "TOKENSECRET99", ".x.com")))

    private class T(val reply: String?) : TermuxRunner {
        val cmds = mutableListOf<String>()
        override fun isInstalled() = true
        override fun hasRunCommandPermission() = true
        override suspend fun runAndWait(command: String, tag: String, timeoutMs: Long, label: String): TermuxResult? {
            cmds += command; return reply?.let { TermuxResult(tag, VerdroidSeleniumPy.MARKER + it + "\n", "", 0, -1, null) }
        }
    }

    private class P(var cap: Int) : XPostPolicy {
        val times = mutableListOf<Long>(); val log = mutableListOf<XPostAudit.Entry>()
        override fun blockReason(now: Long) = XPostLimits.blockReason(times, now, cap, 0)
        override fun recordAttempt(now: Long) { times += now }
        override fun audit(entry: XPostAudit.Entry) { log += entry }
    }

    @Test fun capAppliesEvenWhenAlwaysAllowedAndEverythingIsAudited() = runTest {
        val t = T("""{"ok":true,"posted":true,"post_url":"https://x.com/me/status/9"}""")
        val p = P(cap = 1)
        val tool = XPostTool(t, folder, vault, p, { now }) { _, _, _ -> true } // "Always allow" = confirmer says yes
        assertTrue(tool.execute(buildJsonObject { put("text", "one") }).contains("status/9"))
        val second = tool.execute(buildJsonObject { put("text", "two") })
        assertTrue(second.contains("RATE_LIMITED")); assertEquals(1, t.cmds.size)
        assertEquals(listOf(XPostAudit.Result.POSTED, XPostAudit.Result.BLOCKED), p.log.map { it.result })
        assertEquals("https://x.com/me/status/9", p.log[0].postUrl)
        val c = XPostTool(t, folder, vault, P(5), { now }) { _, _, _ -> false }
        assertTrue(c.execute(buildJsonObject { put("text", "x") }).contains("CANCELLED"))
        assertFalse(p.log.joinToString().contains("TOKENSECRET99"))
    }

    @Test fun uncertainCountsTowardTheCap() = runTest {
        val p = P(cap = 1)
        XPostTool(T(null), folder, vault, p, { now }) { _, _, _ -> true }.execute(buildJsonObject { put("text", "hi") })
        assertEquals(XPostAudit.Result.UNCERTAIN, p.log.single().result)
        assertNotNull(p.blockReason(now))
    }

    @Test fun scheduledRunWithAlwaysAllowIsCappedAndLoggedAsScheduled() = runTest {
        val t = T("""{"ok":true,"posted":true,"post_url":"https://x.com/me/status/9"}""")
        val p = P(cap = 1)
        val tool = XPostTool(t, folder, vault, p, { now }) { _, _, _ -> true }
        // Through the real registry: unattended run of schedule "Morning post", Always allow on.
        val reg = ToolRegistry(listOf(tool), headless = { "Morning post" }, alwaysAllowed = { it == "x_post" })
        assertFalse(reg.execute("x_post", """{"text":"gm"}""", 4L).isError)
        val second = reg.execute("x_post", """{"text":"gm again"}""", 4L)
        assertTrue(second.json.contains("RATE_LIMITED")); assertEquals(1, t.cmds.size)
        assertEquals(listOf("scheduled: Morning post", "scheduled: Morning post; rate cap"), p.log.map { it.note })
        assertTrue(XPostAudit.line(p.log[0]).contains("note=scheduled: Morning post"))
        // Without Always allow: blocked before the tool runs, with the hint.
        val off = ToolRegistry(listOf(tool), headless = { "Morning post" }).execute("x_post", """{"text":"gm"}""", 4L)
        assertTrue(off.json, off.isError && "BLOCKED_IN_SCHEDULED_RUN" in off.json && "Always allow" in off.json)
        assertEquals(1, t.cmds.size)
    }
}
