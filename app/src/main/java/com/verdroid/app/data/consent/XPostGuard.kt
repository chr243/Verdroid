package com.verdroid.app.data.consent

import android.content.Context
import com.verdroid.app.agent.tools.XPostAudit
import com.verdroid.app.agent.tools.XPostLimits
import com.verdroid.app.agent.tools.XPostPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * x_post rate cap (Settings → Tools → Twitter; default 5/hour, 20/day; 0 = no cap) and the audit log
 * Documents/Verdroid/Internal/x_post_log.txt (app-private files/ fallback without storage access). The cap applies even
 * with "Always allow". Post times are kept in prefs (last 24 h only).
 */
@Singleton
class XPostGuard @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shared: com.verdroid.app.data.storage.SharedFolder,
) : XPostPolicy {
    private val prefs = context.getSharedPreferences("x_post_guard", Context.MODE_PRIVATE)
    private val _perHour = MutableStateFlow(prefs.getInt(K_HOUR, XPostLimits.DEFAULT_PER_HOUR))
    private val _perDay = MutableStateFlow(prefs.getInt(K_DAY, XPostLimits.DEFAULT_PER_DAY))
    val perHour: StateFlow<Int> = _perHour.asStateFlow()
    val perDay: StateFlow<Int> = _perDay.asStateFlow()

    fun setPerHour(n: Int) { val v = n.coerceIn(0, 100); prefs.edit().putInt(K_HOUR, v).apply(); _perHour.value = v }
    fun setPerDay(n: Int) { val v = n.coerceIn(0, 500); prefs.edit().putInt(K_DAY, v).apply(); _perDay.value = v }

    private fun times(): List<Long> = prefs.getString(K_TIMES, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    @Synchronized override fun blockReason(now: Long): String? = XPostLimits.blockReason(times(), now, _perHour.value, _perDay.value)

    @Synchronized override fun recordAttempt(now: Long) {
        prefs.edit().putString(K_TIMES, (XPostLimits.prune(times(), now) + now).joinToString(",")).apply()
    }

    /** Posts counted in the last hour / day (for Settings). */
    fun usage(now: Long = System.currentTimeMillis()): Pair<Int, Int> {
        val d = XPostLimits.prune(times(), now); return d.count { it > now - XPostLimits.HOUR } to d.size
    }

    fun logFile(): File = File(if (runCatching { shared.hasAccess() }.getOrDefault(false)) shared.internal else File(context.filesDir, "internal"), XPostAudit.FILE)

    @Synchronized override fun audit(entry: XPostAudit.Entry) {
        runCatching { val f = logFile(); f.parentFile?.mkdirs(); f.appendText(XPostAudit.line(entry)) }
    }

    /** Last [maxChars] of the log (for the Settings viewer). */
    fun tail(maxChars: Int = 20_000): String = runCatching {
        val f = logFile(); if (!f.exists()) return ""
        java.io.RandomAccessFile(f, "r").use { raf ->
            val start = (raf.length() - maxChars).coerceAtLeast(0); raf.seek(start)
            val b = ByteArray((raf.length() - start).toInt()); raf.readFully(b); String(b, Charsets.UTF_8)
        }
    }.getOrDefault("")

    private companion object {
        const val K_HOUR = "per_hour"; const val K_DAY = "per_day"; const val K_TIMES = "times"
    }
}
