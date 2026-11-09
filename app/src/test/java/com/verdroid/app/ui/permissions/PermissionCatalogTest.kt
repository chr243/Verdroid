package com.verdroid.app.ui.permissions

import com.verdroid.app.shizuku.ShizukuState
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PermissionCatalogTest {
    private val ctx: android.content.Context = ApplicationProvider.getApplicationContext()
    private val none = PermissionCatalog.Status(false, false, false, false, ShizukuState.NOT_INSTALLED, false, false, false, false)

    @Test fun listsEveryPermissionWithTheRightAction() {
        val rows = PermissionCatalog.rows(ctx, none).associateBy { it.id }
        assertEquals(setOf("all_files", "notifications", "termux", "shizuku", "accessibility", "overlay", "battery", "install"), rows.keys)
        assertTrue(rows.values.none { it.granted })
        assertEquals("Get Termux", rows["termux"]!!.action)
        assertEquals("Get Shizuku", rows["shizuku"]!!.action)
        val r2 = PermissionCatalog.rows(ctx, none.copy(termuxInstalled = true, shizuku = ShizukuState.NO_PERMISSION)).associateBy { it.id }
        assertEquals("Grant", r2["termux"]!!.action); assertEquals("Grant", r2["shizuku"]!!.action)
        assertEquals("Open Shizuku", PermissionCatalog.rows(ctx, none.copy(shizuku = ShizukuState.NOT_RUNNING)).first { it.id == "shizuku" }.action)
    }

    @Test fun grantedRowsAreMarked() {
        val all = PermissionCatalog.Status(true, true, true, true, ShizukuState.READY, true, true, true, true)
        assertTrue(PermissionCatalog.rows(ctx, all).all { it.granted })
        assertNull(PermissionCatalog.rows(ctx, all).first { it.id == "shizuku" }.action)
    }
}
