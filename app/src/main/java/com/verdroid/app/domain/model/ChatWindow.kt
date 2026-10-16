package com.verdroid.app.domain.model

/** Pure rules for the chat UI window (mirrors the Room SQL in MessageDao/ToolCallDao; unit-tested). */
object ChatWindow {
    /** Messages loaded at first; "load earlier" adds [PAGE] more. */
    const val PAGE = 120
    /** Longest tool output/argument kept in UI state (the card shows 8000 chars anyway). */
    const val CAP = 16_000
    const val MARKER = "\n…[cut in the chat view to save memory; the agent has the full text]"
    const val CHART = "chart"

    fun cut(s: String?): String? = if (s != null && s.length > CAP) s.substring(0, CAP) + MARKER else s

    fun messages(all: List<ChatMessage>, limit: Int): List<ChatMessage> =
        all.takeLast(limit.coerceAtLeast(1)).map { if (it.role == MessageRole.TOOL) it.copy(content = cut(it.content)) else it }

    fun toolCalls(messages: List<ChatMessage>, calls: List<ToolCallRecord>, limit: Int): List<ToolCallRecord> {
        val min = messages.takeLast(limit.coerceAtLeast(1)).minOfOrNull { it.id } ?: return emptyList()
        return calls.filter { it.messageId >= min }.map {
            if (it.name == CHART) it else it.copy(argumentsJson = cut(it.argumentsJson) ?: "", resultJson = cut(it.resultJson))
        }
    }

    /** More rows may exist above the window when it came back full. */
    fun hasMore(loaded: Int, limit: Int): Boolean = loaded >= limit
}
