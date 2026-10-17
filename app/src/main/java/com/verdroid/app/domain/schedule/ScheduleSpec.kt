package com.verdroid.app.domain.schedule

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What fires: a plain reminder notification (no model call) or a headless agent run. */
enum class ScheduleKind { REMINDER, TASK }

/** When it fires. Times of day are device-local (re-evaluated in the current zone, so DST and travel are handled). */
sealed interface ScheduleSpec {
    data class Once(val atMillis: Long) : ScheduleSpec
    data class Daily(val hour: Int, val minute: Int) : ScheduleSpec
    data class Interval(val minutes: Int) : ScheduleSpec

    companion object {
        const val MIN_INTERVAL_MINUTES = 15
        const val MAX_INTERVAL_MINUTES = 7 * 24 * 60

        /** DB encoding: ONCE / DAILY / INTERVAL plus one number (epoch ms, minute-of-day, minutes). */
        fun encode(s: ScheduleSpec): Pair<String, Long> = when (s) {
            is Once -> "ONCE" to s.atMillis
            is Daily -> "DAILY" to (s.hour * 60L + s.minute)
            is Interval -> "INTERVAL" to s.minutes.toLong()
        }

        fun decode(type: String, value: Long): ScheduleSpec? = when (type) {
            "ONCE" -> Once(value)
            "DAILY" -> if (value in 0 until 24 * 60) Daily((value / 60).toInt(), (value % 60).toInt()) else null
            "INTERVAL" -> if (value >= MIN_INTERVAL_MINUTES) Interval(value.toInt()) else null
            else -> null
        }
    }
}

/** Pure schedule math and parsing (unit-tested). */
object ScheduleMath {
    /**
     * Next fire time strictly after [after] (epoch ms). [last] = the previous fire (intervals keep their cadence from it).
     * Once: its time if still ahead, else null. Daily: today's HH:MM in [zone] if ahead, else tomorrow's; a time inside a
     * DST gap fires at the shifted time (e.g. 02:30 → 03:30), an ambiguous one at the first occurrence.
     */
    fun nextRun(spec: ScheduleSpec, after: Long, zone: ZoneId, last: Long? = null): Long? = when (spec) {
        is ScheduleSpec.Once -> spec.atMillis.takeIf { it > after }
        is ScheduleSpec.Daily -> {
            val now = Instant.ofEpochMilli(after).atZone(zone)
            var day = now.toLocalDate()
            var t: ZonedDateTime
            while (true) {
                t = ZonedDateTime.of(day, LocalTime.of(spec.hour, spec.minute), zone)
                if (t.toInstant().toEpochMilli() > after) break
                day = day.plusDays(1)
            }
            t.toInstant().toEpochMilli()
        }
        is ScheduleSpec.Interval -> {
            val step = spec.minutes * 60_000L
            val base = last ?: after
            // Keep the cadence from the last fire; skip missed slots (one run, not a burst of catch-ups).
            if (base + step > after) base + step else base + ((after - base) / step + 1) * step
        }
    }

    /**
     * Parses an absolute time: ISO with offset ("2026-10-17T10:00:00+02:00", "…Z"), local ISO ("2026-10-17T10:00",
     * "2026-10-17 10:00") in [zone], or a bare "HH:MM" / "10am" / "7:30 pm" = the next such time (today or tomorrow).
     */
    fun parseAt(text: String, now: Long, zone: ZoneId): Long? {
        val s = text.trim()
        if (s.isEmpty()) return null
        runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        runCatching { return Instant.parse(s).toEpochMilli() }
        runCatching { return LocalDateTime.parse(s.replace(' ', 'T')).atZone(zone).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(s.replace(' ', 'T') + ":00").atZone(zone).toInstant().toEpochMilli() }
        val tod = parseTimeOfDay(s) ?: return null
        return nextRun(ScheduleSpec.Daily(tod.first, tod.second), now, zone)
    }

    /** "10:00", "9:05", "10am", "7:30 pm", "22h" → (hour, minute) or null. */
    fun parseTimeOfDay(text: String): Pair<Int, Int>? {
        val m = Regex("""^\s*(\d{1,2})(?:[:h.](\d{2}))?\s*(am|pm|a\.m\.|p\.m\.|h)?\s*$""", RegexOption.IGNORE_CASE).find(text) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ap = m.groupValues[3].lowercase().replace(".", "")
        if (m.groupValues[2].isEmpty() && ap.isEmpty()) return null // a bare number is too ambiguous
        if (ap == "am" || ap == "pm") {
            if (h !in 1..12) return null
            if (ap == "am" && h == 12) h = 0
            if (ap == "pm" && h != 12) h += 12
        }
        if (h !in 0..23 || min !in 0..59) return null
        return h to min
    }

    /** Human description in the device's terms: "once at Sat 17 Oct 10:00", "every day at 10:00", "every 15 min". */
    fun describe(spec: ScheduleSpec, zone: ZoneId): String = when (spec) {
        is ScheduleSpec.Once -> "once at " + formatLocal(spec.atMillis, zone)
        is ScheduleSpec.Daily -> "every day at %02d:%02d".format(spec.hour, spec.minute)
        is ScheduleSpec.Interval -> if (spec.minutes % 60 == 0) "every ${spec.minutes / 60} h" else "every ${spec.minutes} min"
    }

    private val FMT = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)
    fun formatLocal(millis: Long, zone: ZoneId): String = Instant.ofEpochMilli(millis).atZone(zone).format(FMT)

    /** "Saturday 2026-10-17 09:40, time zone Europe/Paris (UTC+02:00)" for the system prompt. */
    fun nowLine(now: Long, zone: ZoneId): String {
        val z = Instant.ofEpochMilli(now).atZone(zone)
        val day = z.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)
        return "Current local date and time: $day ${z.toLocalDate()} %02d:%02d, time zone ${zone.id} (UTC${z.offset.id.replace("Z", "+00:00")})."
            .format(z.hour, z.minute)
    }
}
