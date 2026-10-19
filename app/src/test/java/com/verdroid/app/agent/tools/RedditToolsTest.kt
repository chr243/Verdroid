package com.verdroid.app.agent.tools

import com.verdroid.app.data.cookies.BrowserCookie
import com.verdroid.app.data.cookies.CookieVault
import com.verdroid.app.data.cookies.RedditHints
import com.verdroid.app.data.storage.SharedFolder
import com.verdroid.app.data.termux.TermuxResult
import com.verdroid.app.data.termux.TermuxRunner
import com.verdroid.app.data.termux.VerdroidSeleniumPy
import com.verdroid.app.data.tools.ToolGroups
import com.verdroid.app.data.tools.ToolPrefs
import com.verdroid.app.data.tools.ToolPreset
import com.verdroid.app.domain.schedule.ScheduleSafety
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RedditToolsTest {
    private val secret = "SESSIONSECRET123"
    private lateinit var folder: SharedFolder
    @Before fun setUp() {
        folder = SharedFolder(File(Files.createTempDirectory("verdroid-rd").toFile(), "Documents/Verdroid")) { true }
        RedditHints.resetForTest()
    }

    private class T(val reply: String?, val installed: Boolean = true) : TermuxRunner {
        val cmds = mutableListOf<String>()
        override fun isInstalled() = installed
        override fun hasRunCommandPermission() = installed
        override suspend fun runAndWait(command: String, tag: String, timeoutMs: Long, label: String): TermuxResult? {
            cmds += command; return reply?.let { TermuxResult(tag, VerdroidSeleniumPy.MARKER + it + "\n", "", 0, -1, null) }
        }
    }
    private fun loggedIn() = CookieVault.InMemory(listOf(BrowserCookie("reddit_session", secret, ".reddit.com"), BrowserCookie("token_v2", "g", ".reddit.com")))
    private fun o(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun loggedInSearchUsesChromiumAndFormats() = runTest {
        val body = """{"data":{"children":[{"kind":"t3","data":{"title":"Hello","subreddit":"s","author":"a","score":3,"num_comments":1,"permalink":"/r/s/comments/z/h/"}}]}}"""
        val t = T(buildJsonObject { put("ok", true); put("body", body) }.toString())
        val raw = RedditSearchTool(t, folder, loggedIn()).execute(buildJsonObject { put("query", "hello"); put("subreddit", "sub") }, ToolContext(3))
        val r = o(raw); assertNotNull(raw, r["ok"])
        assertEquals(raw, true, r["ok"]!!.jsonPrimitive.boolean)
        assertTrue(r["posts"]!!.jsonPrimitive.content.startsWith("Post 1: r/s · u/a"))
        val cmd = t.cmds.single()
        assertTrue(cmd.contains("rjson")); assertTrue(cmd.contains("r/sub/search.json")); assertFalse(cmd.contains(secret))
    }

    @Test fun noLoginFallsBackWithOneHintPerChat() = runTest {
        val t = T(null)
        val vault = CookieVault.InMemory(listOf(BrowserCookie("token_v2", "guest", ".reddit.com")))
        val r1 = o(RedditSearchTool(t, folder, vault).execute(buildJsonObject { put("query", "pixel") }, ToolContext(9)))
        assertEquals("NOT_LOGGED_IN", r1["code"]!!.jsonPrimitive.content)
        assertTrue(r1["fallback"]!!.jsonPrimitive.content.contains("site:reddit.com pixel"))
        assertTrue(r1["tell_user"]!!.jsonPrimitive.content.contains("Log in to Reddit"))
        assertTrue(t.cmds.isEmpty())
        val r2 = o(RedditReadTool(t, folder, vault).execute(buildJsonObject { put("url", "https://www.reddit.com/r/s/comments/a1/b/") }, ToolContext(9)))
        assertEquals("NOT_LOGGED_IN", r2["code"]!!.jsonPrimitive.content); assertNull("hint only once per chat", r2["tell_user"])
    }

    @Test fun noChromiumFallsBackWithInstallHint() = runTest {
        val r = o(RedditSearchTool(T(null, installed = false), folder, loggedIn()).execute(buildJsonObject { put("query", "x") }, ToolContext(4)))
        assertEquals("NO_CHROMIUM", r["code"]!!.jsonPrimitive.content)
        assertTrue(r["tell_user"]!!.jsonPrimitive.content.contains("chromium-selenium"))
        // add-on missing inside Termux: detected from the helper error
        val t = T(buildJsonObject { put("ok", false); put("error", "Selenium is not installed in Termux (x)") }.toString())
        val r2 = o(RedditSearchTool(t, folder, loggedIn()).execute(buildJsonObject { put("query", "x") }, ToolContext(5)))
        assertEquals("NO_CHROMIUM", r2["code"]!!.jsonPrimitive.content); assertNotNull(r2["tell_user"])
    }

    @Test fun blockPageGivesFallbackNotGarbage() = runTest {
        val t = T(buildJsonObject { put("ok", true); put("body", "whoa there, pardner! $secret") }.toString())
        val r = RedditReadTool(t, folder, loggedIn()).execute(buildJsonObject { put("url", "https://redd.it/abc12") }, ToolContext(1))
        assertFalse(r.contains(secret))
        val j = o(r); assertEquals(false, j["ok"]!!.jsonPrimitive.boolean); assertNotNull(j["fallback"])
    }

    @Test fun groupDefaultsAndScheduledRuns() {
        assertEquals("reddit", ToolGroups.groupOf("reddit_search")); assertEquals(ToolPreset.REDDIT, ToolPreset.of("reddit_read"))
        assertFalse("on by default", "reddit_search" in ToolPrefs.DEFAULT_OFF)
        assertNull(ScheduleSafety.blockReason("reddit_read", buildJsonObject {}))
        assertFalse(ToolGroups.needsTosWarning("reddit", false, true))
        assertTrue("rjson" in VerdroidSeleniumPy.SOURCE)
    }
}
