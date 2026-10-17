package com.verdroid.app.domain.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleMathTest {
    private val paris = ZoneId.of("Europe/Paris")
    private fun t(s: String) = ZonedDateTime.parse(s).toInstant().toEpochMilli()

    @Test fun `daily picks today when ahead, else tomorrow`() {
        val d = ScheduleSpec.Daily(10, 0)
        assertEquals(t("2026-10-17T10:00+02:00[Europe/Paris]"), ScheduleMath.nextRun(d, t("2026-10-17T09:33+02:00[Europe/Paris]"), paris))
        assertEquals(t("2026-10-18T10:00+02:00[Europe/Paris]"), ScheduleMath.nextRun(d, t("2026-10-17T10:00+02:00[Europe/Paris]"), paris))
        assertEquals(t("2026-10-18T10:00+02:00[Europe/Paris]"), ScheduleMath.nextRun(d, t("2026-10-17T11:00+02:00[Europe/Paris]"), paris))
    }

    @Test fun `daily keeps local wall time across DST`() {
        // Autumn change on 2026-10-25: 10:00 local stays 10:00 (now UTC+1), 25 h after the previous day's run.
        val sat = t("2026-10-24T10:00+02:00[Europe/Paris]")
        val sun = ScheduleMath.nextRun(ScheduleSpec.Daily(10, 0), sat, paris)!!
        assertEquals(t("2026-10-25T10:00+01:00[Europe/Paris]"), sun)
        assertEquals(25 * 3_600_000L, sun - sat)
    }

    @Test fun `DST gap shifts forward and overlap fires once at the first occurrence`() {
        // Spring: 2026-03-29 02:00 → 03:00, so 02:30 doesn't exist → 03:30 CEST.
        assertEquals(t("2026-03-29T03:30+02:00[Europe/Paris]"), ScheduleMath.nextRun(ScheduleSpec.Daily(2, 30), t("2026-03-29T00:00+01:00[Europe/Paris]"), paris))
        // Autumn: 02:30 happens twice on 2026-10-25 → the first (CEST, +02:00); after it, the next is the following day.
        val first = ScheduleMath.nextRun(ScheduleSpec.Daily(2, 30), t("2026-10-25T00:00+02:00[Europe/Paris]"), paris)!!
        assertEquals(t("2026-10-25T02:30+02:00"), first)
        assertEquals(t("2026-10-26T02:30+01:00[Europe/Paris]"), ScheduleMath.nextRun(ScheduleSpec.Daily(2, 30), first, paris))
    }

    @Test fun `interval keeps cadence and skips missed slots`() {
        val m15 = 15 * 60_000L
        assertEquals(1_000L + m15, ScheduleMath.nextRun(ScheduleSpec.Interval(15), 1_000L, paris))
        assertEquals(m15, ScheduleMath.nextRun(ScheduleSpec.Interval(15), 60_000L, paris, last = 0L))
        // Phone off for 1 h: one run at the next slot, not four catch-ups.
        assertEquals(5 * m15, ScheduleMath.nextRun(ScheduleSpec.Interval(15), 4 * m15 + 1, paris, last = 0L))
        assertEquals(5 * m15, ScheduleMath.nextRun(ScheduleSpec.Interval(15), 4 * m15, paris, last = 0L))
    }

    @Test fun `once only in the future`() {
        assertEquals(5_000L, ScheduleMath.nextRun(ScheduleSpec.Once(5_000), 1_000, paris))
        assertNull(ScheduleMath.nextRun(ScheduleSpec.Once(5_000), 5_000, paris))
    }

    @Test fun `parses absolute and time-of-day input`() {
        val now = t("2026-10-17T09:33+02:00[Europe/Paris]")
        assertEquals(t("2026-10-17T10:00+02:00[Europe/Paris]"), ScheduleMath.parseAt("10am", now, paris))
        assertEquals(t("2026-10-18T09:00+02:00[Europe/Paris]"), ScheduleMath.parseAt("09:00", now, paris))
        assertEquals(t("2026-10-17T18:30+02:00[Europe/Paris]"), ScheduleMath.parseAt("2026-10-17T18:30", now, paris))
        assertEquals(t("2026-10-17T18:30+02:00[Europe/Paris]"), ScheduleMath.parseAt("2026-10-17 18:30", now, paris))
        assertEquals(t("2026-10-17T16:30+00:00"), ScheduleMath.parseAt("2026-10-17T16:30:00Z", now, paris))
        assertEquals(t("2026-10-17T18:30+02:00"), ScheduleMath.parseAt("2026-10-17T18:30+02:00", now, paris))
        assertNull(ScheduleMath.parseAt("soon", now, paris))
        assertEquals(19 to 30, ScheduleMath.parseTimeOfDay("7:30 pm"))
        assertEquals(0 to 15, ScheduleMath.parseTimeOfDay("12:15am"))
        assertEquals(12 to 0, ScheduleMath.parseTimeOfDay("12pm"))
        assertEquals(22 to 0, ScheduleMath.parseTimeOfDay("22h"))
        assertNull(ScheduleMath.parseTimeOfDay("10"))
        assertNull(ScheduleMath.parseTimeOfDay("25:00"))
        assertNull(ScheduleMath.parseTimeOfDay("13pm"))
    }

    @Test fun `spec encoding roundtrips and rejects bad values`() {
        listOf(ScheduleSpec.Once(123), ScheduleSpec.Daily(23, 59), ScheduleSpec.Interval(15)).forEach {
            val (type, v) = ScheduleSpec.encode(it); assertEquals(it, ScheduleSpec.decode(type, v))
        }
        assertNull(ScheduleSpec.decode("INTERVAL", 5)); assertNull(ScheduleSpec.decode("DAILY", 24 * 60)); assertNull(ScheduleSpec.decode("X", 1))
    }

    @Test fun `describe and system prompt time line`() {
        assertEquals("every day at 10:00", ScheduleMath.describe(ScheduleSpec.Daily(10, 0), paris))
        assertEquals("every 15 min", ScheduleMath.describe(ScheduleSpec.Interval(15), paris))
        assertEquals("every 2 h", ScheduleMath.describe(ScheduleSpec.Interval(120), paris))
        assertEquals("Current local date and time: Saturday 2026-10-17 09:33, time zone Europe/Paris (UTC+02:00).",
            ScheduleMath.nowLine(t("2026-10-17T09:33+02:00[Europe/Paris]"), paris))
    }
}
