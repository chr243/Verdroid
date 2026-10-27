package com.verdroid.app.ui.provider

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.verdroid.app.data.network.KeyStatus
import java.text.DateFormat
import java.util.Date

/** "OK · 12 requests · last used 14:02" / "Cooling down until 14:10 · last error: …" (issue #5 key status). */
object KeyStatusFormat {
    fun line(s: KeyStatus, now: Long = System.currentTimeMillis(), time: (Long) -> String = { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }): String = buildString {
        val locks = s.modelLocks.filterValues { it > now }
        append(when {
            s.coolUntil > now -> "Cooling down until ${time(s.coolUntil)}"
            s.requests == 0 -> "Not used yet"
            else -> "OK"
        })
        if (s.requests > 0) append(" · ${s.requests} request${if (s.requests == 1) "" else "s"} · last used ${time(s.lastUsed)}")
        if (locks.isNotEmpty()) append(" · locked for ${locks.keys.joinToString()}")
        if (s.lastError != null && (s.coolUntil > now || now - s.lastErrorAt < 6 * 3_600_000L)) append(" · last error: ${s.lastError.take(120)}")
    }
}

@Composable
fun KeyStatusText(s: KeyStatus) {
    val now = System.currentTimeMillis()
    Text(KeyStatusFormat.line(s, now), style = MaterialTheme.typography.bodySmall,
        color = if (s.coolUntil > now) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}
