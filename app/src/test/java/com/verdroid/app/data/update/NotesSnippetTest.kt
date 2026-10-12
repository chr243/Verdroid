package com.verdroid.app.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesSnippetTest {
    @Test fun stripsMarkdownAndTruncates() {
        assertEquals("Fixes\nFaster", UpdateLogic.notesSnippet("## Fixes\n\n- Faster\n"))
        val long = UpdateLogic.notesSnippet("x".repeat(500), max = 10)
        assertTrue(long.endsWith("…") && long.length == 11)
    }
}

class UpdaterOptInTest {
    @Test fun freshInstallOffExistingOn() {
        assertEquals(false, UpdateLogic.defaultEnabled(false))
        assertEquals(true, UpdateLogic.defaultEnabled(true))
    }
}
