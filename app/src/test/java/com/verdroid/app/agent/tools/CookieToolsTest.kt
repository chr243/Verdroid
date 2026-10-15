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

    @Test fun ownGroupOffByDefaultAndPreset() {
        listOf("cookie_import", "cookie_list", "cookie_clear", "cookie_open").forEach {
            assertEquals("cookies", ToolGroups.groupOf(it))
            assertTrue(it in ToolPrefs.DEFAULT_OFF)
            assertEquals(ToolPreset.COOKIES, ToolPreset.of(it))
            assertTrue(ToolPreset.effective(it, false, mapOf(ToolPreset.COOKIES to true)))
        }
        assertTrue(ToolGroups.ORDER.first { it.id == "cookies" }.description.contains("Beta for testing logged-in"))
    }
}

class CookieLoginToolTest {
    @Test fun returnsCountsWhenDone() = runTest {
        var opened: String? = null
        val t = CookieLoginTool({ u -> opened = u; kotlinx.coroutines.CompletableDeferred(com.verdroid.app.ui.cookies.CookieLoginResult(true, listOf("x.com"), 5, listOf("auth_token", "ct0"), true)) })
        val r = t.execute(buildJsonObject {})
        assertEquals("https://x.com/i/flow/login", opened)
        assertTrue(r.contains("\"cookies_saved\":5")); assertTrue(r.contains("\"logged_in\":true")); assertTrue(r.contains("auth_token"))
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
