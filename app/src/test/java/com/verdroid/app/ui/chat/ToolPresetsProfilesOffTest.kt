package com.verdroid.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPresetsProfilesOffTest {
    @Test fun introMentionsProfileOnlyWhenProfilesOn() {
        assertTrue(toolPresetsIntro(true).contains("profile"))
        assertFalse(toolPresetsIntro(false).contains("profile", ignoreCase = true))
        assertTrue(toolPresetsIntro(false).startsWith("Starts from Settings → Tools"))
    }

    @Test fun resetLabelFollowsProfilesSwitch() {
        org.junit.Assert.assertEquals("Reset to profile", toolPresetsResetLabel(true))
        org.junit.Assert.assertEquals("Reset to Settings", toolPresetsResetLabel(false))
    }
}
