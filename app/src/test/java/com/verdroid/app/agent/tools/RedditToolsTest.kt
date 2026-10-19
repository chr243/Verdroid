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

    private val page = "<shreddit-post post-title=\"Hello\" permalink=\"/r/sub/comments/z1/h/\" subreddit-prefixed-name=\"r/sub\" author=\"a\" score=\"3\" comment-count=\"1\"></shreddit-post>"

    @Test fun loggedInSearchRendersInChromiumAndParses() = runTest {
        val t = T(buildJsonObject { put("ok", true); put("state", "ready"); put("html", page) }.toString())
        val raw = RedditSearchTool(t, folder, loggedIn()).execute(buildJsonObject { put("query", "hello"); put("subreddit", "sub") }, ToolContext(3))
        val r = o(raw); assertEquals(raw, true, r["ok"]!!.jsonPrimitive.boolean)
        assertTrue(r["posts"]!!.jsonPrimitive.content.startsWith("Post 1: r/sub · u/a"))
        val cmd = t.cmds.single()
        assertTrue(cmd.contains("rhtml")); assertTrue(cmd.contains("r/sub/search/")); assertTrue(cmd.contains("--wait-for")); assertFalse(cmd.contains(secret))
    }

    @Test fun loginWithUserTokenV2OnlyCounts() = runTest {
        val jwt = "h." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"user","lid":"t2_x"}""".toByteArray()) + ".s"
        val t = T(buildJsonObject { put("ok", true); put("state", "ready"); put("html", page) }.toString())
        val v = CookieVault.InMemory(listOf(BrowserCookie("token_v2", jwt, ".reddit.com"), BrowserCookie("loid", "l", ".reddit.com")))
        assertEquals(true, o(RedditSearchTool(t, folder, v).execute(buildJsonObject { put("query", "x") }, ToolContext(2)))["ok"]!!.jsonPrimitive.boolean)
    }

    @Test fun challengeAndBlocksAreTriaged() = runTest {
        fun run(state: String, task: Long) = RedditReadTool(T(buildJsonObject { put("ok", true); put("state", state); put("title", "Reddit - Prove your humanity"); put("html", "") }.toString()),
            folder, loggedIn())
        val c = o(run("challenge", 1).execute(buildJsonObject { put("url", "https://redd.it/abc12") }, ToolContext(11)))
        assertEquals("CHALLENGE", c["code"]!!.jsonPrimitive.content); assertTrue(c["tell_user"]!!.jsonPrimitive.content.contains("Log in to Reddit again"))
        val b = o(run("ip_blocked", 2).execute(buildJsonObject { put("url", "https://redd.it/abc12") }, ToolContext(12)))
        assertEquals("IP_BLOCKED", b["code"]!!.jsonPrimitive.content); assertNull(b["tell_user"]); assertNotNull(b["fallback"])
        val e = o(RedditSearchTool(T(buildJsonObject { put("ok", true); put("state", "empty"); put("html", "") }.toString()), folder, loggedIn())
            .execute(buildJsonObject { put("query", "zzqx") }, ToolContext(13)))
        assertEquals(0, e["count"]!!.jsonPrimitive.int)
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
        val t = T(buildJsonObject { put("ok", true); put("state", "ready"); put("html", "<html>whoa there, pardner! $secret</html>") }.toString())
        val r = RedditReadTool(t, folder, loggedIn()).execute(buildJsonObject { put("url", "https://redd.it/abc12") }, ToolContext(1))
        assertFalse(r.contains(secret))
        val j = o(r); assertEquals(false, j["ok"]!!.jsonPrimitive.boolean); assertNotNull(j["fallback"])
    }

    @Test fun groupDefaultsAndScheduledRuns() {
        assertEquals("reddit", ToolGroups.groupOf("reddit_search")); assertEquals(ToolPreset.REDDIT, ToolPreset.of("reddit_read"))
        assertFalse("on by default", "reddit_search" in ToolPrefs.DEFAULT_OFF)
        assertNull(ScheduleSafety.blockReason("reddit_read", buildJsonObject {}))
        assertFalse(ToolGroups.needsTosWarning("reddit", false, true))
        assertTrue("rhtml" in VerdroidSeleniumPy.SOURCE)
    }

    @Test fun challengeHtmlIsNeverReportedAsEmpty() = runTest {
        val html = "<html><head><title>Reddit - Prove your humanity</title><script src=\"https://www.google.com/recaptcha/enterprise.js\"></script></head><body><div class=\"grecaptcha-badge\"></div></body></html>"
        for (state in listOf("empty", "ready", "timeout")) {
            val t = T(buildJsonObject { put("ok", true); put("state", state); put("html", html)
                putJsonObject("cookies") { put("added", 8); put("present", 8); putJsonArray("names") { add("token_v2") } } }.toString())
            val r = o(RedditSearchTool(t, folder, loggedIn()).execute(buildJsonObject { put("query", "x") }, ToolContext((40 + state.length).toLong())))
            assertEquals(state, "CHALLENGE", r["code"]!!.jsonPrimitive.content)
            assertEquals(8, r["cookies"]!!.jsonObject["present"]!!.jsonPrimitive.int)
        }
        val n = o(RedditSearchTool(T(buildJsonObject { put("ok", true); put("state", "no_cookies"); put("html", "") }.toString()), folder, loggedIn())
            .execute(buildJsonObject { put("query", "x") }, ToolContext(50)))
        assertEquals("COOKIES_NOT_SET", n["code"]!!.jsonPrimitive.content)
    }

    @Test fun everyStoredRedditCookieGoesToTheHelper() = runTest {
        val names = listOf("edgebucket", "loid", "csrf_token", "csv", "token_v2", "session_tracker", "eu_cookie_opted", "g_state")
        val v = CookieVault.InMemory(names.map { BrowserCookie(it, if (it == "token_v2") "h." + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"user"}""".toByteArray()) + ".s" else "v$it", ".reddit.com") } + BrowserCookie("auth_token", "x", ".x.com"))
        val t = T(buildJsonObject { put("ok", true); put("state", "ready"); put("html", page) }.toString())
        RedditSearchTool(t, folder, v).execute(buildJsonObject { put("query", "x") }, ToolContext(60))
        val b64 = Regex("echo ([A-Za-z0-9+/=]+) \\| base64 -d > \\S*ck-").find(t.cmds.single())!!.groupValues[1]
        val json = Json.parseToJsonElement(String(java.util.Base64.getDecoder().decode(b64))).jsonArray
        assertEquals(names.sorted(), json.map { it.jsonObject["name"]!!.jsonPrimitive.content }.sorted())
        assertTrue(json.all { it.jsonObject["domain"]!!.jsonPrimitive.content == ".reddit.com" })
    }
}
