package com.verdroid.app.data.crash

/**
 * Cheap in-memory context for the crash log (v1.0.56, after two idle OOMs that the bare stack trace couldn't explain):
 * the last [SIZE] agent actions, the open chat (main screen or chat-head panel) with its loaded message/tool-call
 * counts, and the running tasks. Writes are a single array slot / volatile field: safe to call from anywhere.
 */
object CrashBreadcrumbs {
    const val SIZE = 10
    private val ring = arrayOfNulls<String>(SIZE)
    private val times = LongArray(SIZE)
    private var next = 0

    @Volatile var openChatId: Long = 0
    @Volatile var openChatWhere: String = ""
    @Volatile var loadedMessages: Int = 0
    @Volatile var loadedToolCalls: Int = 0
    @Volatile var runningTasks: () -> Collection<Long> = { emptyList() }

    /** Memory released at the start of the crash handler so the report itself can be allocated after an OOM. */
    @Volatile private var reserve: ByteArray? = ByteArray(RESERVE_BYTES)
    private const val RESERVE_BYTES = 256 * 1024

    fun add(action: String) {
        synchronized(ring) { ring[next] = action.take(160); times[next] = System.currentTimeMillis(); next = (next + 1) % SIZE }
    }

    fun openChat(id: Long, where: String, messages: Int, toolCalls: Int) {
        openChatId = id; openChatWhere = where; loadedMessages = messages; loadedToolCalls = toolCalls
    }

    fun closeChat(id: Long) { if (openChatId == id) { openChatId = 0; openChatWhere = ""; loadedMessages = 0; loadedToolCalls = 0 } }

    fun releaseReserve() { reserve = null }
    fun hasReserve(): Boolean = reserve != null

    /** Oldest first, "HH:mm:ss action". */
    fun recent(): List<String> = synchronized(ring) {
        (0 until SIZE).map { (next + it) % SIZE }.mapNotNull { i -> ring[i]?.let { fmtTime(times[i]) + " " + it } }
    }

    private fun fmtTime(t: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = t }
        fun p(n: Int) = if (n < 10) "0$n" else "$n"
        return p(c.get(java.util.Calendar.HOUR_OF_DAY)) + ":" + p(c.get(java.util.Calendar.MINUTE)) + ":" + p(c.get(java.util.Calendar.SECOND))
    }

    fun clear() { synchronized(ring) { ring.fill(null); next = 0 }; closeChat(openChatId); reserve = reserve ?: ByteArray(RESERVE_BYTES) }

    /** The "Context" section of a crash report. [memory] is pre-formatted (Android-only parts are added by the caller). */
    fun section(memory: String): String = buildString {
        append("Memory:\n").append(memory)
        append("\nOpen chat: ").append(if (openChatId == 0L) "none" else "#$openChatId ($openChatWhere), $loadedMessages messages, $loadedToolCalls tool calls loaded")
        append("\nRunning tasks: ").append(runCatching { runningTasks().joinToString(", ").ifEmpty { "none" } }.getOrDefault("?"))
        append("\nLast actions (oldest first):\n")
        val r = recent()
        if (r.isEmpty()) append("  none\n") else r.forEach { append("  ").append(it).append('\n') }
    }

    /** JVM memory figures (pure, unit-testable). */
    fun runtimeMemory(rt: Runtime = Runtime.getRuntime()): String {
        val mb = 1024 * 1024
        return "  Java heap: used ${(rt.totalMemory() - rt.freeMemory()) / mb} MB, free ${rt.freeMemory() / mb} MB, " +
            "total ${rt.totalMemory() / mb} MB, max ${rt.maxMemory() / mb} MB\n"
    }
}
