package com.verdroid.app.agent.tools

import com.verdroid.app.data.cookies.CookieVault
import com.verdroid.app.data.storage.SharedFolder
import com.verdroid.app.data.termux.TermuxResult
import com.verdroid.app.data.termux.TermuxRunner
import com.verdroid.app.data.termux.VerdroidSeleniumPy
import com.verdroid.app.data.tools.ToolGroups
import com.verdroid.app.data.tools.ToolPrefs
import com.verdroid.app.data.tools.ToolPreset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CookieToolsTest {
    private lateinit var base: File
    private lateinit var folder: SharedFolder
    private val vault = CookieVault.InMemory()
    @Before fun setUp() { base = Files.createTempDirectory("verdroid-ck").toFile(); folder = SharedFolder(File(base, "Documents/Verdroid")) { true }; folder.ensure() }
    @After fun tearDown() { base.deleteRecursively() }

    private val secret = "AUTHSECRET0123456789"

    @Test fun importStoresAndEchoesRedacted() = runTest {
        File(folder.input, "x.json").writeText("""[{"name":"auth_token","value":"$secret","domain":".x.com"}]""")
        val r = CookieImportTool(folder, vault).execute(buildJsonObject { put("path", "Input/x.json") })
        assertFalse(r.contains(secret))
        assertEquals(1, Json.parseToJsonElement(r).jsonObject["imported"]?.jsonPrimitive?.int)
        assertEquals(secret, vault.all().single().value)
        assertFalse(CookieListTool(vault).execute(buildJsonObject {}).contains(secret))
    }

    @Test fun importRejectsPathOutsideSharedFolder() = runTest {
        val r = CookieImportTool(folder, vault).execute(buildJsonObject { put("path", "../../etc/passwd") })
        assertTrue(r.contains("error"))
    }

    @Test fun openWritesPrivateFileAndScrubsOutput() = runTest {
        File(folder.input, "c.txt").writeText(".x.com\tTRUE\t/\tTRUE\t0\tauth_token\t$secret\n")
        CookieImportTool(folder, vault).execute(buildJsonObject { put("path", "Input/c.txt") })
        val cmds = mutableListOf<String>()
        val t = object : TermuxRunner {
            override fun isInstalled() = true
            override fun hasRunCommandPermission() = true
            override suspend fun runAndWait(command: String, tag: String, timeoutMs: Long, label: String): TermuxResult {
                cmds += command
                return TermuxResult(tag, "${VerdroidSeleniumPy.MARKER}{\"ok\":true,\"text\":\"leak $secret\",\"cookies\":{\"added\":1}}\n", "", 0, -1, null)
            }
        }
        val r = CookieOpenTool(t, folder, vault).execute(buildJsonObject { put("url", "https://x.com/home") })
        assertFalse(r.contains(secret)); assertTrue(r.contains("[redacted cookie]"))
        val cmd = cmds.single()
        assertTrue(cmd.contains("umask 077")); assertTrue(cmd.contains("--cookies"))
        assertFalse("raw value must not be in the command", cmd.contains(secret))
        val none = CookieOpenTool(t, folder, vault).execute(buildJsonObject { put("url", "https://example.org/") })
        assertTrue(none.contains("No stored cookies"))
    }

    @Test fun clearRemovesDomain() = runTest {
        vault.save(listOf(com.verdroid.app.data.cookies.BrowserCookie("a", "1", "x.com"), com.verdroid.app.data.cookies.BrowserCookie("b", "2", "y.com")))
        CookieClearTool(vault).execute(buildJsonObject { put("domain", "x.com") })
        assertEquals(listOf("b"), vault.all().map { it.name })
    }

    @Test fun cookieToolsInWebXToolsInTwitter() {
        listOf("cookie_import", "cookie_list", "cookie_clear", "cookie_open", "cookie_login").forEach {
            assertEquals("web", ToolGroups.groupOf(it)); assertTrue(it in ToolPrefs.DEFAULT_OFF); assertEquals(ToolPreset.WEB, ToolPreset.of(it))
        }
        listOf("x_timeline", "x_user_posts", "x_search", "x_post").forEach {
            assertEquals("twitter", ToolGroups.groupOf(it)); assertTrue(it in ToolPrefs.DEFAULT_OFF)
            assertEquals(ToolPreset.TWITTER, ToolPreset.of(it))
            assertTrue(ToolPreset.effective(it, false, mapOf(ToolPreset.TWITTER to true)))
        }
        assertEquals("Twitter", ToolGroups.ORDER.first { it.id == "twitter" }.title)
        assertTrue(ToolGroups.ORDER.none { it.id == "cookies" })
        assertTrue(ToolGroups.ORDER.first { it.id == "twitter" }.description.contains("Terms of Service"))
        assertTrue(ToolGroups.needsTosWarning("twitter", wasOn = false, turningOn = true))
        assertFalse(ToolGroups.needsTosWarning("twitter", wasOn = true, turningOn = true))
        assertFalse(ToolGroups.needsTosWarning("twitter", wasOn = false, turningOn = false))
        assertFalse(ToolGroups.needsTosWarning("web", wasOn = false, turningOn = true))
    }

    @Test fun legacyCookiesPresetMigrates() {
        assertEquals(setOf(ToolPreset.TWITTER, ToolPreset.WEB), ToolPreset.decode(setOf("COOKIES"), on = true))
        assertEquals(setOf(ToolPreset.TWITTER), ToolPreset.decode(setOf("COOKIES"), on = false))
        assertEquals(setOf(ToolPreset.GIT), ToolPreset.decode(setOf("GIT"), on = true))
    }
}

class CookieLoginToolTest {
    @Test fun returnsCountsWhenDone() = runTest {
        var opened: String? = null
        val t = CookieLoginTool({ u -> opened = u; kotlinx.coroutines.CompletableDeferred(com.verdroid.app.ui.cookies.CookieLoginResult(true, listOf("x.com"), 5, listOf("auth_token", "ct0"), true, closedBy = "done")) })
        val r = t.execute(buildJsonObject {})
        assertEquals("https://x.com/i/flow/login", opened)
        assertTrue(r.contains("\"cookies_saved\":5")); assertTrue(r.contains("\"logged_in\":true")); assertTrue(r.contains("auth_token"))
    }

    @Test fun loggedInOnlyFromARealLoginCheck() = runTest {
        fun tool(r: com.verdroid.app.ui.cookies.CookieLoginResult) = CookieLoginTool({ kotlinx.coroutines.CompletableDeferred(r) })
        val cancelled = tool(com.verdroid.app.ui.cookies.CookieLoginResult(false, emptyList(), 0)).execute(buildJsonObject {})
        assertTrue(cancelled, cancelled.contains("\"logged_in\":false") && cancelled.contains("cancelled"))
        val guest = tool(com.verdroid.app.ui.cookies.CookieLoginResult(false, listOf("reddit.com"), 0, listOf("loid", "token_v2"), false, "not_logged_in", closedBy = "done"))
            .execute(buildJsonObject { put("url", "https://www.reddit.com/login/") })
        assertTrue(guest, guest.contains("\"logged_in\":false") && guest.contains("guest cookies") && guest.contains("unchanged"))
        val unchecked = tool(com.verdroid.app.ui.cookies.CookieLoginResult(true, listOf("example.org"), 1, listOf("sid"), false, "unchecked", closedBy = "done")).execute(buildJsonObject {})
        assertTrue(unchecked, unchecked.contains("\"logged_in\":false") && unchecked.contains("could not be verified"))
        val timeout = CookieLoginTool({ kotlinx.coroutines.CompletableDeferred() }, timeoutMs = 10).execute(buildJsonObject {})
        assertTrue(timeout, timeout.contains("\"logged_in\":false") && timeout.contains("\"reason\":\"timeout\""))
    }

    @Test fun reportsWhatClosedTheScreenAndOnlyDoneCanBeALogin() = runTest {
        val R = com.verdroid.app.ui.cookies.CookieLoginResult
        suspend fun run(r: com.verdroid.app.ui.cookies.CookieLoginResult) = Json.parseToJsonElement(
            CookieLoginTool({ kotlinx.coroutines.CompletableDeferred(r) }).execute(buildJsonObject {})).jsonObject
        for (by in listOf(R.CLOSED_CANCEL, R.CLOSED_BACK, R.CLOSED_TIMEOUT, R.CLOSED_SELF)) {
            val o = run(R.closed(by))
            assertEquals(by, o["closed_by"]!!.jsonPrimitive.content)
            assertEquals(false, o["logged_in"]!!.jsonPrimitive.boolean); assertEquals(false, o["ok"]!!.jsonPrimitive.boolean)
        }
        assertTrue(run(R.closed(R.CLOSED_SELF))["note"]!!.jsonPrimitive.content.contains("without Done"))
        // Even a result claiming saved + logged in is not a login unless it came from Done.
        val forged = com.verdroid.app.ui.cookies.CookieLoginResult(true, listOf("reddit.com"), 8, listOf("loid"), true, "saved", closedBy = R.CLOSED_SELF)
        assertEquals(false, run(forged)["logged_in"]!!.jsonPrimitive.boolean)
        val done = run(forged.copy(closedBy = R.CLOSED_DONE))
        assertEquals(true, done["logged_in"]!!.jsonPrimitive.boolean); assertEquals("done", done["closed_by"]!!.jsonPrimitive.content)
        // The default (no path recorded) is a self-close.
        assertEquals(R.CLOSED_SELF, com.verdroid.app.ui.cookies.CookieLoginResult(true, emptyList(), 1).closedBy)
    }

    @Test fun timesOutWithHint() = runTest {
        val t = CookieLoginTool({ kotlinx.coroutines.CompletableDeferred() }, timeoutMs = 10)
        assertTrue(t.execute(buildJsonObject { put("url", "https://example.org/login") }).contains("tap Done"))
        assertTrue(CookieLoginTool({ null }).execute(buildJsonObject {}).contains("error"))
    }
}

class SeleniumHelperCookieTest {
    private fun py(code: String): String? {
        val dir = java.nio.file.Files.createTempDirectory("vsel").toFile()
        return try {
            File(dir, "verdroid_selenium.py").writeText(VerdroidSeleniumPy.SOURCE.replace("\$" + "MARKER", "M"))
            val p = runCatching { ProcessBuilder("python3", "-c", code).directory(dir).redirectErrorStream(true).start() }.getOrNull() ?: return null
            p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
        } finally { dir.deleteRecursively() }
    }

    @Test fun cdpCookieAndUserAgent() {
        val out = py("import verdroid_selenium as v, json; print(v.normal_user_agent('Mozilla/5.0 HeadlessChrome/129.0 Safari')); " +
            "print(json.dumps(v.cdp_cookie({'name':'auth_token','value':'v','domain':'.x.com','httpOnly':True,'secure':True,'expiry':1900000000,'sameSite':'None'}), sort_keys=True))")
        org.junit.Assume.assumeTrue(out != null && !out.contains("Traceback") && !out.contains("not found"))
        val lines = out!!.lines()
        assertEquals("Mozilla/5.0 Chrome/129.0 Safari", lines[0])
        val o = Json.parseToJsonElement(lines[1]).jsonObject
        assertEquals(".x.com", o["domain"]?.jsonPrimitive?.content); assertEquals(1.9E9, o["expires"]?.jsonPrimitive?.double!!, 0.1)
        assertEquals("None", o["sameSite"]?.jsonPrimitive?.content); assertTrue(o["httpOnly"]?.jsonPrimitive?.boolean == true)
    }
}

class XToolsTest {
    private val folder = SharedFolder(java.nio.file.Files.createTempDirectory("vx").toFile()) { true }
    private class T(val reply: String) : TermuxRunner {
        val cmds = mutableListOf<String>()
        override fun isInstalled() = true
        override fun hasRunCommandPermission() = true
        override suspend fun runAndWait(command: String, tag: String, timeoutMs: Long, label: String): TermuxResult {
            cmds += command; return TermuxResult(tag, VerdroidSeleniumPy.MARKER + reply + "\n", "", 0, -1, null)
        }
    }

    @Test fun needsLogin() = runTest {
        val r = XTimelineTool(T("{}"), folder, CookieVault.InMemory()).execute(buildJsonObject {})
        assertTrue(r.contains("cookie_login")); assertTrue(r.contains("NOT_LOGGED_IN")); assertTrue(r.contains("retry"))
    }

    @Test fun formatsWithoutFilesAndBuildsUrls() = runTest {
        val vault = CookieVault.InMemory(listOf(com.verdroid.app.data.cookies.BrowserCookie("auth_token", "SECRETTOKEN123", ".x.com")))
        val t = T("""{"ok":true,"posts":[{"name":"N","handle":"@h","text":"hi SECRETTOKEN123","rel":"2m","url":"https://x.com/h/status/5"}]}""")
        val r = Json.parseToJsonElement(XTimelineTool(t, folder, vault).execute(buildJsonObject { put("count", 5) })).jsonObject
        val posts = r["posts"]!!.jsonPrimitive.content
        assertTrue(posts.startsWith("Post 1: @h (N) - hi [redacted cookie] - 2m ago"))
        assertNull(r["saved"]); assertTrue(folder.output.listFiles().isNullOrEmpty())
        assertTrue(t.cmds.single().contains("'xposts' 'https://x.com/home'"))
        XSearchTool(t, folder, vault).execute(buildJsonObject { put("query", "from:nasa moon") })
        assertTrue(t.cmds.last().contains("https://x.com/search?q=from%3Anasa+moon&src=typed_query&f=live"))
        XUserPostsTool(t, folder, vault).execute(buildJsonObject { put("handle", "@NASA") })
        assertTrue(t.cmds.last().contains("'https://x.com/NASA'"))
        assertTrue(XTimelineTool(t, folder, vault).execute(buildJsonObject { put("url", "https://evil.com/") }).contains("error"))
    }
}

class XPostToolTest {
    private val folder = SharedFolder(java.nio.file.Files.createTempDirectory("vp").toFile()) { true }
    private val loggedIn = CookieVault.InMemory(listOf(com.verdroid.app.data.cookies.BrowserCookie("auth_token", "TOKENSECRET99", ".x.com")))
    private class T(val reply: String?) : TermuxRunner {
        val cmds = mutableListOf<String>()
        override fun isInstalled() = true
        override fun hasRunCommandPermission() = true
        override suspend fun runAndWait(command: String, tag: String, timeoutMs: Long, label: String): TermuxResult? {
            cmds += command; return reply?.let { TermuxResult(tag, VerdroidSeleniumPy.MARKER + it + "\n", "", 0, -1, null) }
        }
    }

    @Test fun lengthRules() {
        assertEquals(5, XText.weightedLength("hello"))
        assertEquals(23 + 2, XText.weightedLength("https://example.com/a/very/long/path?x=1 !"))
        assertEquals(2, XText.weightedLength("😀"))
        assertEquals("https://x.com/a/status/1", XText.replyUrl("https://mobile.twitter.com/a/status/1/photo/1"))
        assertEquals("", XText.replyUrl("https://evil.com/a/status/1"))
        assertNull(XText.replyUrl(" "))
    }

    @Test fun overLimitAndNotLoggedInNeverAsk() = runTest {
        var asked = 0; val t = T("{}")
        val long = XPostTool(t, folder, loggedIn) { _, _, _ -> asked++; true }.execute(buildJsonObject { put("text", "a".repeat(281)) })
        assertTrue(long.contains("limit is 280"))
        val nl = XPostTool(t, folder, CookieVault.InMemory()) { _, _, _ -> asked++; true }.execute(buildJsonObject { put("text", "hi") })
        assertTrue(nl.contains("NOT_LOGGED_IN"))
        assertEquals(0, asked); assertTrue(t.cmds.isEmpty())
    }

    @Test fun confirmGating() = runTest {
        val t = T("""{"ok":true,"posted":true,"post_url":"https://x.com/me/status/9"}""")
        var seen: Triple<Long, String, String?>? = null
        val cancel = XPostTool(t, folder, loggedIn) { id, txt, r -> seen = Triple(id, txt, r); false }
            .execute(buildJsonObject { put("text", "hello"); put("reply_to_url", "https://x.com/a/status/5") }, ToolContext(7))
        assertTrue(cancel.contains("CANCELLED")); assertTrue(t.cmds.isEmpty())
        assertEquals(Triple(7L, "hello", "https://x.com/a/status/5"), seen)
        assertTrue(XPostTool(t, folder, loggedIn) { _, _, _ -> null }.execute(buildJsonObject { put("text", "x") }).contains("No answer"))
        val ok = XPostTool(t, folder, loggedIn) { _, _, _ -> true }.execute(buildJsonObject { put("text", "hello") })
        assertTrue(ok.contains("https://x.com/me/status/9"))
        val cmd = t.cmds.single()
        assertTrue(cmd.contains("'xpost' 'https://x.com/compose/post'")); assertFalse(cmd.contains("TOKENSECRET99"))
    }

    @Test fun timeoutIsUncertain() = runTest {
        val r = XPostTool(T(null), folder, loggedIn) { _, _, _ -> true }.execute(buildJsonObject { put("text", "hi") })
        assertTrue(r.contains("\"posted\":\"uncertain\""))
    }
}
