package com.verdroid.app.data.crash

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class CrashBreadcrumbsTest {
    @After fun reset() { CrashBreadcrumbs.clear(); CrashBreadcrumbs.runningTasks = { emptyList() } }

    @Test fun ringKeepsLastTenOldestFirst() {
        repeat(13) { CrashBreadcrumbs.add("a$it") }
        val r = CrashBreadcrumbs.recent()
        assertEquals(10, r.size)
        assertTrue(r.first().endsWith(" a3")); assertTrue(r.last().endsWith(" a12"))
    }

    @Test fun sectionHasChatTasksAndMemory() {
        CrashBreadcrumbs.openChat(42, "chat head", 120, 37)
        CrashBreadcrumbs.runningTasks = { listOf(42L, 7L) }
        CrashBreadcrumbs.add("chat #42 tool termux_run")
        val s = CrashBreadcrumbs.section(CrashBreadcrumbs.runtimeMemory())
        assertTrue("#42 (chat head), 120 messages, 37 tool calls loaded" in s)
        assertTrue("Running tasks: 42, 7" in s); assertTrue("Java heap: used" in s); assertTrue("tool termux_run" in s)
        CrashBreadcrumbs.closeChat(42)
        assertTrue("Open chat: none" in CrashBreadcrumbs.section(""))
    }

    @Test fun reserveIsReleasedAndReportIncludesContext() {
        assertTrue(CrashBreadcrumbs.hasReserve())
        CrashBreadcrumbs.releaseReserve(); assertFalse(CrashBreadcrumbs.hasReserve())
        val txt = CrashLog.format(OutOfMemoryError("x"), "1.0", "main", Date(0), "dev", "Memory:\n  Java heap: used 1 MB\n")
        assertTrue(txt.indexOf("Java heap") in 0 until txt.indexOf("OutOfMemoryError"))
        assertTrue("Java heap" in CrashLog.memory(full = false)) // Android parts are skipped on the JVM
    }
}
