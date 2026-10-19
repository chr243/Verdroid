package com.verdroid.app.agent.tools

import com.verdroid.app.data.cookies.BrowserCookie
import com.verdroid.app.data.cookies.CookieVault
import com.verdroid.app.data.cookies.RedditHints
import com.verdroid.app.data.storage.SharedFolder
import com.verdroid.app.data.termux.TermuxResult
import com.verdroid.app.data.termux.TermuxRunner
import com.verdroid.app.data.termux.VerdroidSeleniumPy
import com.verdroid.app.data.tools.ToolGroups
import com.verdroid.app.data.tools.ToolPreset
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Reddit reads go through cookie_open (reddit_search / reddit_read were removed). */
class CookieOpenRedditTest {
    private class T(val reply: String?, val installed: Boolean = true) : TermuxRunner {
        val cmds = mutableListOf<String>()
        override fun isInstalled() = installed
        override fun hasRunCommandPermission() = installed
        override suspend fun runAndWait(command: String, tag: String, timeoutMs: Long, label: String): TermuxResult? {
            cmds += command; return reply?.let { TermuxResult(tag, VerdroidSeleniumPy.MARKER + it + "\n", "", 0, -1, null) }
        }
    }
    private val secret = "SESSIONSECRET123"
    private lateinit var folder: SharedFolder
    @Before fun setUp() { RedditHints.resetForTest(); folder = SharedFolder(File(Files.createTempDirectory("vd-co").toFile(), "Documents/Verdroid")) { true } }
    private fun loggedIn() = CookieVault.InMemory(listOf(BrowserCookie("reddit_session", secret, ".reddit.com"), BrowserCookie("loid", "l", ".reddit.com")))
    private fun o(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun open(t: T, v: CookieVault, url: String, task: Long, wait: Int? = null) = kotlinx.coroutines.runBlocking {
        o(CookieOpenTool(t, folder, v).execute(buildJsonObject { put("url", url); wait?.let { put("wait_seconds", it) } }, ToolContext(task)))
    }

    @Test fun redditDefaultsToAPlainFifteenSecondWaitAndConvertsOldRedditThreads() {
        val t = T("""{"ok":true,"title":"pixel : r/Android","text":"Pixel 10 review","cookies":{"added":2,"present":2,"names":["loid","reddit_session"]}}""")
        val r = open(t, loggedIn(), "https://www.reddit.com/search/?q=pixel&type=posts", 1)
        assertEquals(true, r["ok"]!!.jsonPrimitive.boolean)
        assertTrue(t.cmds.single(), t.cmds.single().contains("'open' 'https://www.reddit.com/search/?q=pixel&type=posts' '--wait' '15'"))
        assertFalse(t.cmds.single().contains("--wait-for"))
        open(t, loggedIn(), "https://old.reddit.com/r/s/comments/abc/t/", 2, wait = 20)
        assertTrue(t.cmds.last().contains("'https://www.reddit.com/r/s/comments/abc/t/' '--wait' '20'"))
        assertFalse(r.toString().contains(secret))
    }

    @Test fun challengeIsBlockedNotEmptyWithFallbackAndHintOnce() {
        for (reply in listOf("""{"ok":false,"code":"CHALLENGE","title":"Reddit - Prove your humanity","error":"bot check","cookies":{"present":2}}""",
                             """{"ok":true,"title":"Reddit - Prove your humanity","text":""}""")) {
            RedditHints.resetForTest()
            val r = open(T(reply), loggedIn(), "https://www.reddit.com/search/?q=x&type=posts", 5)
            assertEquals("CHALLENGE", r["code"]!!.jsonPrimitive.content); assertEquals(false, r["ok"]!!.jsonPrimitive.boolean)
            assertTrue(r["fallback"]!!.jsonPrimitive.content.contains("web_search")); assertNotNull(r["tell_user"]); assertNull(r["text"])
            assertNull(open(T(reply), loggedIn(), "https://www.reddit.com/search/?q=y&type=posts", 5)["tell_user"])  // once per chat
        }
        val n = open(T("""{"ok":false,"code":"COOKIES_NOT_SET","error":"refused"}"""), loggedIn(), "https://www.reddit.com/", 6)
        assertEquals("COOKIES_NOT_SET", n["code"]!!.jsonPrimitive.content); assertNotNull(n["fallback"])
        // A genuine empty search stays ok.
        val e = open(T("""{"ok":true,"title":"zzqx - Reddit Search!","text":"Hm... we couldn't find any results for zzqx"}"""), loggedIn(), "https://www.reddit.com/search/?q=zzqx", 7)
        assertEquals(true, e["ok"]!!.jsonPrimitive.boolean); assertNull(e["code"])
    }

    @Test fun noLoginOrNoChromiumFallsBackToWebSearchAndTellsTheUserOnce() {
        val guest = CookieVault.InMemory(listOf(BrowserCookie("token_v2", "g", ".reddit.com")))
        val t = T(null)
        val a = open(t, guest, "https://www.reddit.com/search/?q=x", 9)
        assertEquals("NOT_LOGGED_IN", a["code"]!!.jsonPrimitive.content)
        assertTrue(a["tell_user"]!!.jsonPrimitive.content.contains("Log in to Reddit")); assertTrue(a["fallback"]!!.jsonPrimitive.content.contains("site:reddit.com"))
        assertNull(open(t, guest, "https://www.reddit.com/search/?q=y", 9)["tell_user"])
        val b = open(T(null, installed = false), loggedIn(), "https://www.reddit.com/r/Android/new/", 10)
        assertEquals("NO_CHROMIUM", b["code"]!!.jsonPrimitive.content); assertTrue(b["tell_user"]!!.jsonPrimitive.content.contains("chromium-selenium"))
        assertTrue(t.cmds.isEmpty())
    }

    @Test fun redditToolsAndPresetAreGoneAndOldPresetsMapToCookieOpen() {
        assertNull(ToolGroups.ORDER.firstOrNull { it.id == "reddit" })
        assertEquals("web", ToolGroups.groupOf("cookie_open"))
        assertTrue(ToolPreset.entries.none { it.groupId == "reddit" })
        assertEquals(setOf(ToolPreset.WEB), ToolPreset.decode(setOf("REDDIT"), on = true))   // forced on → cookie_open's group on
        assertEquals(emptySet<ToolPreset>(), ToolPreset.decode(setOf("REDDIT"), on = false))  // forced off → dropped (not all of Web)
        assertEquals(setOf(ToolPreset.TWITTER), ToolPreset.decode(setOf("TWITTER", "REDDIT"), on = false))
        val steer = CookieOpenTool.REDDIT
        assertTrue(steer.contains("https://www.reddit.com/search/?q=<urlencoded>&type=posts") && steer.contains("restrict_sr=1") &&
            steer.contains("/r/<sub>/<hot|new|top|rising>/") && steer.contains("cookies_for"))
    }
}
