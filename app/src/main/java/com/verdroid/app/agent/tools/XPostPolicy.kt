package com.verdroid.app.agent.tools

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Optional rate cap ("Limit X posts", on by default) + audit trail (always) for x_post. The cap applies even with "Always allow". */
interface XPostPolicy {
    /** Null = allowed now; otherwise the user-facing reason the cap was hit. */
    fun blockReason(now: Long): String?
    /** Called once the browser is about to post (approved; counts toward the cap even if the result is uncertain). */
    fun recordAttempt(now: Long)
    fun audit(entry: XPostAudit.Entry)

    object None : XPostPolicy {
        override fun blockReason(now: Long): String? = null
        override fun recordAttempt(now: Long) {}
        override fun audit(entry: XPostAudit.Entry) {}
    }
}

/** Pure sliding-window cap (unit-tested). */
object XPostLimits {
    const val HOUR = 3_600_000L
    const val DAY = 24 * HOUR
    const val DEFAULT_PER_HOUR = 5
    const val DEFAULT_PER_DAY = 20

    fun prune(times: List<Long>, now: Long): List<Long> = times.filter { it in (now - DAY + 1)..now }.sorted()

    /** [enabled] = the "Limit X posts" switch; off = never blocked. */
    fun blockReason(times: List<Long>, now: Long, perHour: Int, perDay: Int, enabled: Boolean = true): String? {
        if (!enabled) return null
        val day = prune(times, now)
        val hour = day.filter { it > now - HOUR }
        fun wait(ms: Long) = ((ms + 59_999) / 60_000).coerceAtLeast(1).let { if (it >= 60) "${(it + 59) / 60} h" else "$it min" }
        return when {
            perHour > 0 && hour.size >= perHour -> "RATE_LIMITED: x_post cap reached ($perHour posts per hour). Nothing was posted. " +
                "Next post possible in about ${wait(hour[hour.size - perHour] + HOUR - now)}. Don't retry now; tell the user " +
                "(they can change or turn off the limit in Settings → Tools → X (Twitter) → Limit posts)."
            perDay > 0 && day.size >= perDay -> "RATE_LIMITED: x_post cap reached ($perDay posts per day). Nothing was posted. " +
                "Next post possible in about ${wait(day[day.size - perDay] + DAY - now)}. Don't retry now; tell the user " +
                "(they can change or turn off the limit in Settings → Tools → X (Twitter) → Limit posts)."
            else -> null
        }
    }
}

/** One line per x_post attempt in Internal/x_post_log.txt. Only the post text/target/result/URL: never cookies. */
object XPostAudit {
    const val FILE = "x_post_log.txt"

    enum class Result { POSTED, NOT_POSTED, UNCERTAIN, CANCELLED, NO_ANSWER, BLOCKED }

    data class Entry(val time: Long, val result: Result, val text: String, val replyTo: String?, val postUrl: String?, val note: String? = null)

    private fun clean(s: String) = s.replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n").replace("\t", " ")

    fun line(e: Entry): String = buildString {
        append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(e.time)))
        append(" | ").append(e.result.name.lowercase())
        append(" | reply_to=").append(e.replyTo ?: "-")
        append(" | url=").append(e.postUrl?.takeIf { it.isNotBlank() } ?: "-")
        e.note?.let { append(" | note=").append(clean(it.take(200))) }
        append(" | text=").append(clean(e.text))
        append('\n')
    }

    /** Classifies the helper's (already scrubbed) JSON output. */
    fun resultOf(out: String): Pair<Result, String?> {
        val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(out) as kotlinx.serialization.json.JsonObject }.getOrNull()
            ?: return Result.UNCERTAIN to null
        fun s(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val url = s("post_url")
        return when (s("posted")) {
            "true" -> Result.POSTED to url
            "false" -> Result.NOT_POSTED to url
            "uncertain" -> Result.UNCERTAIN to url
            else -> (if (s("error")?.startsWith("NOT_LOGGED_IN") == true) Result.NOT_POSTED else Result.UNCERTAIN) to url
        }
    }
}
