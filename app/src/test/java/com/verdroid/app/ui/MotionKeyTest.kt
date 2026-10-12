package com.verdroid.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MotionKeyTest {
    @Test fun rowAndDetailShareKey() {
        val rowId: Long = 42L
        val detailId: Long = "42".toLong() // nav argument parsed as LongType
        assertEquals(chatContainerKey(rowId), chatContainerKey(detailId))
    }
    @Test fun distinctChatsDistinctKeys() = assertNotEquals(chatContainerKey(1), chatContainerKey(2))
}
