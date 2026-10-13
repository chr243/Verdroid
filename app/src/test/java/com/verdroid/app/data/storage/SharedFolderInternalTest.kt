package com.verdroid.app.data.storage

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SharedFolderInternalTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun folder() = SharedFolder(File(tmp.root, "Verdroid")) { true }

    @Test fun ensureCreatesInternal() {
        val f = folder()
        assertTrue(f.ensure())
        assertTrue(f.internal.isDirectory && f.input.isDirectory && f.output.isDirectory)
        assertFalse(SharedFolder(File(tmp.root, "x")) { false }.ensure())
    }

    @Test fun migratesOnlyAgentScratchOnce() {
        val f = folder(); f.ensure()
        listOf("scrape.py", "run.sh", "debug.log", "photo.jpg", "notes.txt", "data.json", ".hidden.py").forEach { File(f.root, it).writeText(it) }
        File(f.internal, "scrape.py").writeText("old")
        val moved = f.migrateStrayRootFiles()
        assertEquals(setOf("scrape.py", "run.sh", "debug.log"), moved.toSet())
        assertEquals("scrape.py", File(f.internal, "scrape (2).py").readText()) // no overwrite
        assertEquals("old", File(f.internal, "scrape.py").readText())
        listOf("photo.jpg", "notes.txt", "data.json", ".hidden.py").forEach { assertTrue(it, File(f.root, it).isFile) }
        File(f.root, "again.py").writeText("x")
        assertTrue(f.migrateStrayRootFiles().isEmpty()) // one-time
        assertTrue(File(f.root, "again.py").isFile)
    }

    @Test fun scratchNames() {
        assertTrue(SharedFolder.isAgentScratch("a.PY"))
        assertFalse(SharedFolder.isAgentScratch("report.pdf"))
        assertFalse(SharedFolder.isAgentScratch("Makefile"))
    }
}
