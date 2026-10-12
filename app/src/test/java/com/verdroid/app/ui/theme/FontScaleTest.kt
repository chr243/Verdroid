package com.verdroid.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class FontScaleTest {
    @Test fun clampsAndSnaps() {
        assertEquals(0.85f, FontScale.clamp(0.1f), 1e-4f)
        assertEquals(1.30f, FontScale.clamp(9f), 1e-4f)
        assertEquals(1.10f, FontScale.clamp(1.12f), 1e-4f)
        assertEquals(1.15f, FontScale.clamp(1.13f), 1e-4f)
        assertEquals(1.0f, FontScale.clamp(Float.NaN), 1e-4f)
    }
    @Test fun stepsAndLabels() {
        assertEquals(8, FontScale.STEPS) // 85,90,...,130 = 10 values → 8 inner steps
        assertEquals("100%", FontScale.label(FontScale.DEFAULT))
        assertEquals("130%", FontScale.label(1.3f))
    }
    @Test fun multipliesSystemScale() = assertEquals(1.3f * 1.2f, FontScale.effective(1.3f, 1.2f), 1e-4f)
}
