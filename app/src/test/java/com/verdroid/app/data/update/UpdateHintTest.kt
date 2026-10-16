package com.verdroid.app.data.update

import org.junit.Assert.*
import org.junit.Test

class UpdateHintTest {
    @Test fun visibility() {
        assertTrue(UpdateLogic.showHint(true, "1.0.56", "1.0.55", true))
        assertFalse(UpdateLogic.showHint(false, "1.0.56", "1.0.55", true)) // user turned checks off
        assertFalse(UpdateLogic.showHint(true, "1.0.56", "1.0.55", false)) // offline
        assertFalse(UpdateLogic.showHint(true, "1.0.55", "1.0.55", true)) // up to date
        assertFalse(UpdateLogic.showHint(true, null, "1.0.55", true)) // never checked
        assertFalse(UpdateLogic.showHint(true, "1.0.54", "1.0.55", true))
        assertTrue(UpdateLogic.RELEASES_PAGE.startsWith("https://github.com/chr243/Verdroid/releases"))
    }
}
