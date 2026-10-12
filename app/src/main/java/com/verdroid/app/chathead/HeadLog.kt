package com.verdroid.app.chathead

import android.util.Log

/** "VerdroidHead" log: logcat + in-memory ring buffer shown in Settings → App → Debug log (no adb/rish needed). */
object HeadLog {
    const val TAG = "VerdroidHead"
    private const val MAX = 400
    private val lines = ArrayDeque<String>()
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

    fun d(msg: String) {
        Log.d(TAG, msg)
        val line = "${fmt.format(java.util.Date())} $msg"
        synchronized(lines) { lines.addLast(line); while (lines.size > MAX) lines.removeFirst() }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }
    fun clear() = synchronized(lines) { lines.clear() }
}
