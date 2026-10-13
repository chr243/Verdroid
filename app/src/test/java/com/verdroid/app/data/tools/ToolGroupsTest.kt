package com.verdroid.app.data.tools

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ToolGroupsTest {
    @Test fun knownToolsLandInTheirGroup() {
        mapOf("workspace_read" to "files", "web_search" to "web", "selenium_open" to "web", "termux_python" to "termux",
            "pdf_merge" to "pdf", "ebook_translate" to "pdf", "screen_tap" to "system", "rish_run" to "system", "git_push" to "git",
            "crypto_backtest_script" to "crypto", "skill_get" to "memory", "chart" to "other", "mcp__srv__x" to "mcp")
            .forEach { (n, g) -> assertEquals(n, g, ToolGroups.groupOf(n)) }
    }

    @Test fun everyRealToolIsGroupedOutsideOtherExceptChartAndSelfTest() {
        val dir = listOf("src/main/java/com/verdroid/app/agent/tools", "app/src/main/java/com/verdroid/app/agent/tools").map(::File).first { it.isDirectory }
        val re = Regex("""override val name(?:\s*:\s*String)?\s*=\s*"([a-z0-9_]+)"""")
        val names = dir.walk().filter { it.extension == "kt" }.flatMap { re.findAll(it.readText()).map { m -> m.groupValues[1] } }.toSet()
        assertTrue(names.size >= 40)
        val other = names.filter { ToolGroups.groupOf(it) == "other" }.toSet()
        assertTrue("ungrouped tools: $other", setOf("chart", "self_test").containsAll(other))
    }

    @Test fun groupingKeepsOrderAndDropsEmpty() {
        val g = ToolGroups.group(listOf("git_status", "read_file", "git_push")) { it }
        assertEquals(listOf("files", "git"), g.map { it.first.id })
        assertEquals(listOf("git_status", "git_push"), g[1].second)
    }

    @Test fun masterTriState() {
        val n = listOf("a", "b")
        assertEquals(ToolGroups.Master.ON, ToolGroups.master(n, emptySet()))
        assertEquals(ToolGroups.Master.PARTIAL, ToolGroups.master(n, setOf("a")))
        assertEquals(ToolGroups.Master.OFF, ToolGroups.master(n, setOf("a", "b")))
        assertFalse(ToolGroups.nextEnabled(ToolGroups.Master.ON))
        assertTrue(ToolGroups.nextEnabled(ToolGroups.Master.PARTIAL)); assertTrue(ToolGroups.nextEnabled(ToolGroups.Master.OFF))
    }
}
