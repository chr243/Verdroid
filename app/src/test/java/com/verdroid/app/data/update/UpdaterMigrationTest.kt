package com.verdroid.app.data.update

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opt-in GitHub updater: fresh installs OFF, upgrades from versions that already checked GitHub stay ON. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdaterMigrationTest {
    private fun prefs(name: String) =
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test fun freshInstallIsOff() {
        val p = prefs("fresh")
        assertEquals(false, AppUpdater.resolveEnabled(p))
        assertEquals(false, p.getBoolean(AppUpdater.KEY_ENABLED, true)) // persisted
    }

    @Test fun upgradeWithPriorCheckStaysOn() {
        val p = prefs("upgrade")
        p.edit().putLong(AppUpdater.KEY_LAST_CHECK, 123L).putString(AppUpdater.KEY_LATEST, "1.0.41").commit()
        assertEquals(true, AppUpdater.resolveEnabled(p))
        assertEquals(true, AppUpdater.resolveEnabled(p)) // stable on later launches
    }

    @Test fun userChoiceWinsOverMigration() {
        val p = prefs("chosen")
        p.edit().putLong(AppUpdater.KEY_LAST_CHECK, 1L).putBoolean(AppUpdater.KEY_ENABLED, false).commit()
        assertEquals(false, AppUpdater.resolveEnabled(p))
    }
}
