package com.verdroid.app.ui

import org.junit.Assert.*
import org.junit.Test

class CampaignsTest {
    private val id = Campaigns.IDEAS
    @Test fun `shows once, only on main screen, never over another dialog`() {
        assertTrue(Campaigns.shouldShow(id, null, onMainScreen = true, otherDialogUp = false))           // fresh install
        assertTrue(Campaigns.shouldShow(id, "older-campaign", true, false))                              // update
        assertFalse(Campaigns.shouldShow(id, id, true, false))                                           // seen
        assertFalse(Campaigns.shouldShow(id, null, onMainScreen = false, otherDialogUp = false))         // deferred
        assertFalse(Campaigns.shouldShow(id, null, true, otherDialogUp = true))                          // update prompt up
        assertTrue(Campaigns.shouldShow("next", id, true, false))                                        // future campaign
        assertEquals("https://github.com/chr243/Verdroid/discussions/2", Campaigns.IDEAS_URL)
    }
}
