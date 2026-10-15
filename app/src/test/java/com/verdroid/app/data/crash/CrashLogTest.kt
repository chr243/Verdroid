package com.verdroid.app.data.crash

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Date

class CrashLogTest {
    @Test fun formatsWritesAndFindsLatest() {
        val base = Files.createTempDirectory("crash").toFile()
        val now = Date(1_760_000_000_000L)
        val name = CrashLog.fileName(now)
        assertTrue(name.matches(Regex("crash_\\d{8}_\\d{6}\\.txt")))
        val text = CrashLog.format(IllegalStateException("boom"), "1.0.53 (82)", "main", now, "Xiaomi X")
        assertTrue(text.contains("Verdroid 1.0.53 (82) crash") && text.contains("IllegalStateException: boom") && text.contains("Thread: main"))
        val unwritable = File(base, "file-not-dir").apply { writeText("x") }
        val dirs = CrashLog.dirs(unwritable, File(base, "files"))
        val f = CrashLog.write(dirs, name, text)!!
        assertEquals(File(base, "files/crash"), f.parentFile) // falls back to app-private
        val f2 = CrashLog.write(dirs, name, "second")!!
        assertNotEquals(f, f2); assertEquals("x", unwritable.readText())
        assertEquals(f2, CrashLog.latest(dirs))
        assertNull(CrashLog.latest(listOf(File(base, "none"))))
    }
}
