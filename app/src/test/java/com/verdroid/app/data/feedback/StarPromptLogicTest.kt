package com.verdroid.app.data.feedback

import org.junit.Assert.*
import org.junit.Test

class StarPromptLogicTest {
    @Test fun pendingExactlyAtThirdCreation() {
        var n = 0; val fired = mutableListOf<Int>()
        repeat(10) { val (c, p) = StarPromptLogic.onChatCreated(n, done = false); n = c; if (p) fired += c }
        assertEquals(listOf(3), fired)
    }
    @Test fun neverAfterDone() {
        assertFalse(StarPromptLogic.onChatCreated(2, done = true).second)
        assertEquals(3, StarPromptLogic.onChatCreated(2, done = true).first)
    }
    @Test fun notWhileTaskRuns() {
        assertFalse(StarPromptLogic.shouldShow(pending = true, done = false, anyTaskActive = true))
        assertTrue(StarPromptLogic.shouldShow(pending = true, done = false, anyTaskActive = false))
        assertFalse(StarPromptLogic.shouldShow(pending = true, done = true, anyTaskActive = false))
        assertFalse(StarPromptLogic.shouldShow(pending = false, done = false, anyTaskActive = false))
    }
    @Test fun deviceInfo() = assertEquals("Verdroid 1.0.48, Android 14 (API 34), Xiaomi 23078PND5G",
        FeedbackLinks.deviceInfo("1.0.48", 34, "14", "Xiaomi", "23078PND5G"))
    @Test fun chooserUrl() = assertEquals("https://github.com/chr243/Verdroid/issues/new/choose", FeedbackLinks.CHOOSER)
}
