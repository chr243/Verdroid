package com.verdroid.app.agent.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile

/** Live output of a running termux_run, shown in its tool card. [lines] = recent lines (stdout, then stderr). */
data class LiveOutput(val lines: List<String>, val bytes: Long, val elapsedMs: Long, val quietMs: Long, val idleLimitS: Int) {
    companion object {
        const val REFRESH_MS = 500L
        /** Lines kept for the expanded view; the collapsed card shows the last [COLLAPSED_LINES]. */
        const val MAX_LINES = 200
        const val COLLAPSED_LINES = 12
        private const val TAIL_BYTES = 32 * 1024

        /** Last [n] non-empty lines of [text]; `\r` progress redraws keep only their latest state. */
        fun lastLines(text: String, n: Int): List<String> =
            text.split('\n').map { it.substringAfterLast('\r').trimEnd() }.filter { it.isNotBlank() }.takeLast(n)

        /** Reads the tails of the .out/.err files Termux is writing (missing files = nothing yet). */
        fun read(out: File, err: File, startedMs: Long, idleS: Int, now: Long = System.currentTimeMillis()): LiveOutput {
            val o = tail(out); val e = tail(err)
            val lines = (lastLines(o, MAX_LINES) + lastLines(e, MAX_LINES).map { "! $it" }).takeLast(MAX_LINES)
            val mod = maxOf(out.lastModified(), err.lastModified()).takeIf { it > 0 } ?: startedMs
            return LiveOutput(lines, out.length() + err.length(), now - startedMs, (now - maxOf(mod, startedMs)).coerceAtLeast(0), idleS)
        }

        internal fun tail(f: File, max: Int = TAIL_BYTES): String = runCatching {
            RandomAccessFile(f, "r").use { r ->
                val len = r.length(); val start = (len - max).coerceAtLeast(0)
                val buf = ByteArray((len - start).toInt()); r.seek(start); r.readFully(buf)
                String(buf).let { if (start > 0) it.substringAfter('\n') else it }
            }
        }.getOrDefault("")

        /** 75 → "1 min 15 s", 300 → "5 min", 42 → "42 s". */
        fun duration(s: Int): String = when {
            s < 60 -> "$s s"
            s % 60 == 0 -> "${s / 60} min"
            else -> "${s / 60} min ${s % 60} s"
        }
    }
}

/** Live termux_run output per chat (task id), like ResearchLive. */
object TermuxLive {
    private val _live = MutableStateFlow<Map<Long, LiveOutput>>(emptyMap())
    val live: StateFlow<Map<Long, LiveOutput>> = _live.asStateFlow()
    fun set(taskId: Long, p: LiveOutput?) {
        synchronized(this) { _live.value = if (p == null) _live.value - taskId else _live.value + (taskId to p) }
    }
}
