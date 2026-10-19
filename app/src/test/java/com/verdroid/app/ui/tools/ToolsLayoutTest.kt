package com.verdroid.app.ui.tools

import com.verdroid.app.data.tools.TermuxPackages
import com.verdroid.app.data.tools.ToolEnv
import com.verdroid.app.data.tools.ToolGroups
import com.verdroid.app.data.tools.ToolStatus
import com.verdroid.app.ui.cookies.CookieLoginBridge
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ToolsLayoutTest {
    @Test fun `universal login opens the right pages and Reddit gets the Reddit flow`() {
        val B = CookieLoginBridge
        assertEquals(B.REDDIT_URL, B.loginUrlFor("reddit.com"))
        assertEquals(B.REDDIT_URL, B.loginUrlFor(" https://www.reddit.com/ "))
        assertEquals(B.REDDIT_OLD_URL, B.loginUrlFor("old.reddit.com"))
        assertEquals("https://www.reddit.com/r/x", B.loginUrlFor("www.reddit.com/r/x"))
        assertEquals(B.DEFAULT_URL, B.loginUrlFor("x.com")); assertEquals(B.DEFAULT_URL, B.loginUrlFor("twitter.com"))
        assertEquals("https://example.org/login", B.loginUrlFor("example.org/login"))
        assertNull(B.loginUrlFor("")); assertNull(B.loginUrlFor("not a url"))
        assertTrue(B.isReddit(B.loginUrlFor("reddit.com")!!))
        assertEquals(listOf("reddit.com", "x.com"), B.SUGGESTIONS)
    }

    @Test fun `sections, add-on split and helpers`() {
        assertEquals(7, ToolsLayout.SECTIONS.size)
        assertEquals(setOf("chromium-selenium", "ebook-translate", "pdf-tools", "crypto-backtest"), TermuxPackages.ALL.map { it.pkg }.filter(TermuxPackages::isAddon).toSet())
        assertTrue(TermuxPackages.ALL.any { !TermuxPackages.isAddon(it.pkg) })
        assertTrue(ToolsLayout.allOff(listOf("cookie_open", "web_search"), setOf("cookie_open")) { it.startsWith("cookie_") })
        assertFalse(ToolsLayout.allOff(listOf("cookie_open"), emptySet()) { it.startsWith("cookie_") })
        assertFalse(ToolsLayout.allOff(listOf("web_search"), emptySet()) { it.startsWith("cookie_") })
        assertEquals("2 of 8 installed", ToolsLayout.installedLabel(2, 8))
        // group ids unchanged; X ToS warning still gates turning X tools on
        assertEquals(listOf("files", "web", "twitter", "termux", "pdf", "system", "git", "crypto", "scheduling", "memory", "other", "mcp"), ToolGroups.ORDER.map { it.id })
        assertTrue(ToolGroups.needsTosWarning("twitter", false, true)); assertFalse(ToolGroups.needsTosWarning("twitter", true, true))
    }

    @Test fun `no stale section names or removed tools in user-facing texts`() {
        val env = ToolEnv(termuxReady = true)
        for (n in listOf("cookie_open", "x_timeline", "selenium_open", "termux_python", "termux_run", "workspace_read"))
            for (t in listOf(ToolStatus.of(n, env).text, ToolStatus.of(n, ToolEnv()).text))
                assertFalse(t, listOf("Logged-in sites", "Log in to Reddit", "Available to install", "cookie_import)", "Tools > Termux").any { it in t })
        assertTrue(ToolStatus.of("reddit_search", env).ready && ToolStatus.of("reddit_search", env).text == "Ready")   // dead branch removed
        val src = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        val stale = src.walk().filter { it.extension == "kt" && it.name != "SelfTestRunner.kt" }.flatMap { f -> f.readLines().mapIndexedNotNull { i, l ->
            if (Regex("Log in to Reddit|Tools → Logged-in sites|Available to install|Tools → Twitter").containsMatchIn(l)) "${f.name}:${i + 1}" else null } }.toList()
        assertEquals(emptyList<String>(), stale)
    }
}
