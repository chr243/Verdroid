package com.verdroid.app.chathead

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingWindowGeometryTest {
    private val g = FloatingWindowGeometry
    @Test fun defaultSize() {
        val r = g.default(1000, 2000, top = 50)
        assertEquals(850, r.w); assertEquals(1200, r.h); assertEquals(75, r.x); assertEquals(50 + 280, r.y)
    }
    @Test fun landscapeDefaultIsTallAndCentred() {
        val r = g.default(2400, 1000, top = 40, density = 2.75f)
        assertEquals(880, r.h); assertEquals(990, r.w) // max(h, 360dp)=990 < 60% of 2400
        assertEquals(705, r.x); assertEquals(40 + 60, r.y)
        val wide = g.default(3000, 600, density = 1f) // short screen: width capped by max(h, 360dp)
        assertEquals(528, wide.h); assertEquals(528, wide.w)
    }
    @Test fun savedSizePerOrientationIsClamped() {
        val big = g.default(2400, 1000, density = 2f, saved = 5000 to 5000)
        assertEquals(2400, big.w); assertEquals(1000, big.h); assertEquals(0, big.x)
        val tiny = g.default(1000, 2000, density = 1f, saved = 100 to 100)
        assertEquals(280, tiny.w); assertEquals(240, tiny.h)
        val kept = g.default(1000, 2000, density = 1f, saved = 600 to 900)
        assertEquals(600, kept.w); assertEquals(900, kept.h); assertEquals(200, kept.x)
    }
    @Test fun savedPositionRestoredAndClamped() {
        val r = g.default(1000, 2000, top = 50, density = 1f, saved = 600 to 900, savedPos = 120 to 300)
        assertEquals(120, r.x); assertEquals(300, r.y)
        val off = g.default(1000, 2000, top = 50, density = 1f, saved = 600 to 900, savedPos = 900 to 5000)
        assertEquals(400, off.x); assertEquals(50 + 2000 - 900, off.y)
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
