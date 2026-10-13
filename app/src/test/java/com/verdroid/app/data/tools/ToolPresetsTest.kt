package com.verdroid.app.data.tools

import com.verdroid.app.agent.tools.AgentTool
import com.verdroid.app.agent.tools.ToolRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolPresetsTest {
    private class Echo(override val name: String) : AgentTool {
        override val description = "Echo"
        override val parameters = buildJsonObject { put("type", "object") }
        var calls = 0
        override suspend fun execute(args: JsonObject): String { calls++; return """{"ok":true}""" }
    }

    @Test fun `tools map to presets and uncategorized tools stay on`() {
        assertEquals(ToolPreset.WEB, ToolPreset.of("web_search"))
        assertEquals(ToolPreset.WEB, ToolPreset.of("selenium_open"))
        assertEquals(ToolPreset.FILES, ToolPreset.of("workspace_read"))
        assertEquals(ToolPreset.PDF, ToolPreset.of("pdf_merge"))
        assertEquals(ToolPreset.PDF, ToolPreset.of("ebook_translate"))
        assertEquals(ToolPreset.TERMUX, ToolPreset.of("termux_python"))
        assertEquals(ToolPreset.SYSTEM, ToolPreset.of("run_shell"))
        assertEquals(ToolPreset.SYSTEM, ToolPreset.of("screen_tap"))
        assertEquals(ToolPreset.GIT, ToolPreset.of("git_push"))
        listOf("memory_save", "skill_get", "chart", "self_test", "mcp__x__y").forEach {
            assertNull(it, ToolPreset.of(it)); assertTrue(ToolPreset.allowed(it, ToolPreset.entries.toSet()))
        }
    }

    @Test fun `all on by default and the prompt note only appears when something is off`() {
        assertTrue(ToolPreset.allowed("web_search", emptySet()))
        assertEquals("", ToolPreset.promptNote(emptySet()))
        val note = ToolPreset.promptNote(setOf(ToolPreset.SYSTEM, ToolPreset.WEB))
        assertTrue(note, note.contains("Web, Shizuku & phone screen"))
        assertEquals(setOf(ToolPreset.TERMUX), ToolPreset.decode(ToolPreset.encode(setOf(ToolPreset.TERMUX)) + "BOGUS"))
    }

    @Test fun `registry hides and refuses tools of an off preset only in that chat`() = runTest {
        val web = Echo("web_search"); val mem = Echo("memory_save")
        val off = mutableMapOf(7L to setOf(ToolPreset.WEB))
        val reg = ToolRegistry(listOf(web, mem), chatFilter = { id, n -> ToolPreset.allowed(n, off[id].orEmpty()) })
        assertEquals(setOf("memory_save"), reg.namesFor(7))
        assertEquals(1, reg.schemas(7).size)
        assertEquals(setOf("web_search", "memory_save"), reg.namesFor(8))
        val r = reg.execute("web_search", "{}", 7)
        assertTrue(r.isError); assertTrue(r.json, r.json.contains("Web tool preset")); assertEquals(0, web.calls)
        assertFalse(reg.execute("web_search", "{}", 8).isError)
        off.clear()
        assertFalse(reg.execute("web_search", "{}", 7).isError)
    }

    @Test fun `crypto preset covers every crypto tool and is part of All`() {
        listOf("crypto_history_download", "crypto_history_list", "crypto_backtest", "crypto_backtest_script").forEach {
            assertEquals(it, ToolPreset.CRYPTO, ToolPreset.of(it))
            assertFalse(ToolPreset.allowed(it, setOf(ToolPreset.CRYPTO)))
            assertTrue(ToolPreset.allowed(it, setOf(ToolPreset.WEB)))
        }
        // Every registered crypto tool (self-test coverage list) must be gated by the preset.
        com.verdroid.app.data.selftest.SelfTestCoverage.TOOL_CHECKS.keys.filter { it.startsWith("crypto_") }
            .forEach { assertEquals(it, ToolPreset.CRYPTO, ToolPreset.of(it)) }
        assertTrue(ToolPreset.CRYPTO in ToolPreset.entries)
        assertEquals("Crypto backtesting", ToolPreset.CRYPTO.label)
    }

    @Test fun `old persisted presets migrate with crypto on`() {
        // A chat saved before CRYPTO existed with All off stored only the four old presets.
        val old = ToolPreset.decode(setOf("WEB", "FILES", "TERMUX", "DEVICE"))
        assertEquals(setOf(ToolPreset.WEB, ToolPreset.FILES, ToolPreset.PDF, ToolPreset.TERMUX, ToolPreset.SYSTEM, ToolPreset.GIT), old)
        assertTrue(ToolPreset.allowed("chart", old))
        assertTrue(ToolPreset.decode(setOf("OTHER")).isEmpty())
        assertTrue(ToolPreset.allowed("crypto_backtest", old))
        assertFalse(ToolPreset.allowed("web_search", old))
        assertTrue(ToolPreset.allowed("crypto_backtest", ToolPreset.decode(null)))
        assertTrue(ToolPreset.promptNote(setOf(ToolPreset.CRYPTO)).contains("Crypto"))
    }

    @Test fun `backtest guidance only in prompt when crypto tools are enabled`() = runTest {
        val bt = Echo("crypto_backtest_script"); val mem = Echo("memory_save")
        val off = mapOf(1L to setOf(ToolPreset.CRYPTO))
        val reg = ToolRegistry(listOf(bt, mem), chatFilter = { id, n -> ToolPreset.allowed(n, off[id].orEmpty()) })
        val g = com.verdroid.app.agent.AgentLoop.BACKTEST_GUIDANCE
        assertFalse(com.verdroid.app.agent.AgentLoop.systemPrompt(reg.namesFor(1)).contains(g))
        assertTrue(com.verdroid.app.agent.AgentLoop.systemPrompt(reg.namesFor(2)).contains(g))
        assertTrue(reg.execute("crypto_backtest_script", "{}", 1).isError); assertEquals(0, bt.calls)
    }

    @Test fun `presets mirror the Settings tool groups`() {
        val groups = ToolGroups.ORDER.map { it.id } - setOf("memory", "other", "mcp")
        assertEquals(groups, ToolPreset.entries.map { it.groupId })
        ToolPreset.entries.forEach { p -> assertEquals(ToolGroups.ORDER.first { it.id == p.groupId }.title, p.label) }
        com.verdroid.app.data.selftest.SelfTestCoverage.TOOL_CHECKS.keys.forEach { t ->
            assertEquals(t, ToolGroups.groupOf(t), ToolPreset.of(t)?.groupId ?: ToolGroups.groupOf(t).also { assertTrue(it in setOf("memory", "other", "mcp")) })
        }
    }
}
