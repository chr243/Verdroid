package com.verdroid.app.chathead

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingWindowGeometryTest {
    private val g = FloatingWindowGeometry
    @Test fun defaultSize() {
        val r = g.default(1000, 2000, top = 50)
        assertEquals(850, r.w); assertEquals(1200, r.h); assertEquals(75, r.x); assertEquals(50 + 280, r.y)
    }
    @Test fun moveKeepsGrip() {
        val r = FloatingWindowGeometry.Rect(100, 100, 500, 800)
        assertEquals(-400, g.move(r, -5000, 0, 1000, 2000, 0, 100).x)
        assertEquals(900, g.move(r, 5000, 0, 1000, 2000, 0, 100).x)
        assertEquals(30, g.move(r, 0, -5000, 1000, 2000, 30, 100).y)
    }
    @Test fun resizeClamps() {
        val r = FloatingWindowGeometry.Rect(100, 100, 500, 800)
        val small = g.resize(r, -9999, -9999, 1000, 2000, 0, 300, 400)
        assertEquals(300, small.w); assertEquals(400, small.h)
        val big = g.resize(r, 9999, 9999, 1000, 2000, 0, 300, 400)
        assertEquals(900, big.w); assertEquals(1900, big.h)
    }
    @Test fun pivotPointsAtHead() {
        assertEquals(0f to 0f, g.pivot(FloatingWindowGeometry.Rect(100, 100, 500, 500), 0, 0))
        assertEquals(1f to 0.5f, g.pivot(FloatingWindowGeometry.Rect(100, 100, 500, 500), 900, 350))
    }
}

class FocusTrackerTest {
    @Test fun ignoresLossBeforeFirstFocus() {
        val t = ChatHeadPanelPolicy.FocusTracker()
        assertEquals(false, t.onFocusChanged(false))
        assertEquals(false, t.onFocusChanged(true))
        assertEquals(true, t.onFocusChanged(false))
    }
}

class HomeSignalTest {
    @Test fun launcherAndRecentsOnly() {
        val l = setOf("com.miui.home")
        assertEquals(true, HomeSignal.isHomeOrRecents("com.miui.home", l))
        assertEquals(true, HomeSignal.isHomeOrRecents("com.android.quickstep", l))
        assertEquals(false, HomeSignal.isHomeOrRecents("com.whatsapp", l))
        assertEquals(false, HomeSignal.isHomeOrRecents(null, l))
    }
}

class HomeSignalKeysTest {
    @org.junit.Test fun leaveKeys() {
        org.junit.Assert.assertTrue(com.verdroid.app.chathead.HomeSignal.isLeaveKey(android.view.KeyEvent.KEYCODE_HOME))
        org.junit.Assert.assertTrue(com.verdroid.app.chathead.HomeSignal.isLeaveKey(android.view.KeyEvent.KEYCODE_APP_SWITCH))
        org.junit.Assert.assertFalse(com.verdroid.app.chathead.HomeSignal.isLeaveKey(android.view.KeyEvent.KEYCODE_BACK))
    }
}
