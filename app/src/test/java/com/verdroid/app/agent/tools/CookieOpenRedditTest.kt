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

    @Test fun challengeWithAcceptedCookiesBlamesTheBotCheckNotTheSession() {
        for (reply in listOf("""{"ok":false,"code":"CHALLENGE","title":"Reddit - Prove your humanity","error":"bot check","cookies":{"added":8,"present":8,"present_after":9,"kept_after_load":8}}""",
                             """{"ok":true,"title":"Reddit - Prove your humanity","text":""}""")) {   // no cookie report: never claim rejected
            RedditHints.resetForTest()
            val r = open(T(reply), loggedIn(), "https://www.reddit.com/search/?q=x&type=posts", 5)
            assertEquals("CHALLENGE", r["code"]!!.jsonPrimitive.content); assertEquals(false, r["ok"]!!.jsonPrimitive.boolean)
            assertTrue(r["fallback"]!!.jsonPrimitive.content.contains("web_search")); assertNull(r["text"])
            val tell = r["tell_user"]!!.jsonPrimitive.content
            assertTrue(tell, tell.contains("bot check") && tell.contains("network") && tell.contains("won't help"))
            assertFalse(tell, tell.contains("again in Verdroid") || tell.contains("didn't keep") || tell.contains("Log in to a site"))
            assertFalse(r["error"]!!.jsonPrimitive.content.contains("rejected"))
            assertNull(open(T(reply), loggedIn(), "https://www.reddit.com/search/?q=y&type=posts", 5)["tell_user"])  // once per chat
        }
    }

    @Test fun missingOrRejectedCookiesSuggestSavingTheSessionAgain() {
        RedditHints.resetForTest()
        val dropped = open(T("""{"ok":false,"code":"CHALLENGE","title":"Reddit - Prove your humanity","cookies":{"added":8,"present":8,"present_after":2,"kept_after_load":1}}"""),
            loggedIn(), "https://www.reddit.com/search/?q=x", 6)
        assertEquals("COOKIES_REJECTED", dropped["code"]!!.jsonPrimitive.content)
        assertTrue(dropped["tell_user"]!!.jsonPrimitive.content.contains("Saving the Reddit session again"))
        val notSet = open(T("""{"ok":false,"code":"COOKIES_NOT_SET","error":"refused","cookies":{"added":8,"present":0}}"""), loggedIn(), "https://www.reddit.com/", 7)
        assertEquals("COOKIES_NOT_SET", notSet["code"]!!.jsonPrimitive.content); assertNotNull(notSet["fallback"])
        assertTrue(notSet["tell_user"]!!.jsonPrimitive.content.contains("again"))
        // Once per chat and hint type: the re-save tip doesn't repeat, but a later bot-wall block in that chat still explains itself.
        assertNull(open(T("""{"ok":false,"code":"COOKIES_NOT_SET","cookies":{"added":8,"present":0}}"""), loggedIn(), "https://www.reddit.com/", 7)["tell_user"])
        val wall = open(T("""{"ok":false,"code":"CHALLENGE","cookies":{"added":8,"present":8,"kept_after_load":8}}"""), loggedIn(), "https://www.reddit.com/", 7)
        assertTrue(wall["tell_user"]!!.jsonPrimitive.content.contains("bot check"))
        // A genuine empty search stays ok.
        val e = open(T("""{"ok":true,"title":"zzqx - Reddit Search!","text":"Hm... we couldn't find any results for zzqx"}"""), loggedIn(), "https://www.reddit.com/search/?q=zzqx", 8)
        assertEquals(true, e["ok"]!!.jsonPrimitive.boolean); assertNull(e["code"])
    }

    @Test fun realResultsPageWithInvisibleRecaptchaIsNotAChallenge() {
        val text = "r/dogs · Two dogs playing · 1.2k votes <script src=\\\"https://www.google.com/recaptcha/enterprise.js\\\"></script> grecaptcha-badge"
        val r = open(T("""{"ok":true,"title":"two dogs – wyszukiwanie w serwisie Reddit!","text":"$text","cookies":{"added":8,"present":8,"kept_after_load":8}}"""),
            loggedIn(), "https://www.reddit.com/search/?q=two+dogs&type=posts&sort=relevance", 11)
        assertEquals(r.toString(), true, r["ok"]!!.jsonPrimitive.boolean); assertNull(r["code"]); assertNull(r["tell_user"])
        assertTrue(com.verdroid.app.data.cookies.RedditJson.isChallenge("", "REDDIT - PROVE YOUR HUMANITY"))
        assertFalse(com.verdroid.app.data.cookies.RedditJson.isChallenge("<div class=\"grecaptcha-badge\"></div> recaptcha/enterprise.js", "Reddit"))
    }

    @Test fun hintsNeverRequireAnAccount() {
        for (h in listOf(RedditHints.LOGIN, RedditHints.RESAVE, RedditHints.BOT_WALL, RedditHints.CHROMIUM))
            assertFalse(h, Regex("account|sign in|log in to your", RegexOption.IGNORE_CASE).containsMatchIn(h))
        assertEquals(com.verdroid.app.data.cookies.RedditBlock.Kind.BOT_WALL, com.verdroid.app.data.cookies.RedditBlock.classify(8, 8, 8))
        assertEquals(com.verdroid.app.data.cookies.RedditBlock.Kind.BOT_WALL, com.verdroid.app.data.cookies.RedditBlock.classify(null, null, null))
        assertEquals(com.verdroid.app.data.cookies.RedditBlock.Kind.COOKIES_REJECTED, com.verdroid.app.data.cookies.RedditBlock.classify(8, 0, null))
        assertEquals(com.verdroid.app.data.cookies.RedditBlock.Kind.COOKIES_REJECTED, com.verdroid.app.data.cookies.RedditBlock.classify(8, 8, 3))
    }

    @Test fun noLoginOrNoChromiumFallsBackToWebSearchAndTellsTheUserOnce() {
        val guest = CookieVault.InMemory(listOf(BrowserCookie("token_v2", "g", ".reddit.com")))
        val t = T(null)
        val a = open(t, guest, "https://www.reddit.com/search/?q=x", 9)
        assertEquals("NOT_LOGGED_IN", a["code"]!!.jsonPrimitive.content)
        assertTrue(a["tell_user"]!!.jsonPrimitive.content.contains("Log in to a site")); assertTrue(a["fallback"]!!.jsonPrimitive.content.contains("site:reddit.com"))
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
