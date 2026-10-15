package com.verdroid.app.agent.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OverwritePolicyTest {
    private val dir = Files.createTempDirectory("ow").toFile().apply { File(this, "Output").mkdirs(); File(this, "Output/a.txt").writeText("x") }
    private fun guard(allowTask: Long?, asked: MutableList<FileChangeRequest>) =
        FileChangeGuard({ asked += it; false }, shared = dir, overwritePolicy = { it == allowTask })

    @Test fun overwriteSkipsPromptWhenAllowed() = runTest {
        val asked = mutableListOf<FileChangeRequest>()
        val g = guard(1L, asked)
        assertNull(g.check(1, "workspace_write", buildJsonObject { put("path", "Output/a.txt"); put("content", "y") }))
        assertNull(g.check(1, "termux_run", buildJsonObject { put("command", "echo hi > /sdcard/x.txt") }))
        assertTrue(asked.isEmpty())
        // other chat (per-chat allowance) still asks, and the request is marked overwrite-only
        assertNotNull(g.check(2, "workspace_write", buildJsonObject { put("path", "Output/a.txt"); put("content", "y") }))
        assertTrue(asked.single().overwriteOnly)
    }

    @Test fun deleteMoveRenameStillAsk() = runTest {
        val asked = mutableListOf<FileChangeRequest>()
        val g = guard(1L, asked)
        assertNotNull(g.check(1, "workspace_delete", buildJsonObject { put("path", "Output/a.txt") }))
        assertNotNull(g.check(1, "termux_run", buildJsonObject { put("command", "mv /sdcard/a /sdcard/b") }))
        assertNotNull(g.check(1, "termux_run", buildJsonObject { put("command", "rm /sdcard/a; echo x > /sdcard/b") }))
        assertNotNull(g.check(1, "termux_python", buildJsonObject { put("code", "import os\nos.rename('/sdcard/a','/sdcard/b')") }))
        assertEquals(4, asked.size); assertTrue(asked.none { it.overwriteOnly })
    }

    @Test fun classification() {
        assertTrue(DestructiveDetector.overwriteOnly(listOf("overwrite existing Output/a.txt", "> /sdcard/x (may overwrite)", "cp → /sdcard/y (may overwrite)")))
        assertFalse(DestructiveDetector.overwriteOnly(listOf("overwrite existing a", "rm (delete/move/rename)")))
        assertFalse(DestructiveDetector.overwriteOnly(listOf("os.replace")))
        assertFalse(DestructiveDetector.overwriteOnly(emptyList()))
    }
}
