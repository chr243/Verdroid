package com.verdroid.app.data.tools

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-chat tool presets (chat ⋮ menu → Tool presets). Each preset mirrors one Settings > Tools group
 * ([ToolGroups.ORDER], same ids and titles, so the two stay in sync); the user can switch a group off for one chat.
 * Everything is on by default. Memory & skills, Charts & other (charts, self-test) and MCP servers are never affected here — they follow Settings > Tools only.
 *
 * Migration: only the presets a chat switched OFF are persisted (by enum name). Old names map onto the new groups:
 * DEVICE → SYSTEM + GIT, FILES → FILES + PDF (it covered PDFs/ebooks), WEB/TERMUX unchanged. A stored OTHER (Charts & other, briefly a preset) is ignored.
 * Groups added later (Crypto) start ON for existing chats — the same as before, when those tools were not gated.
 */
enum class ToolPreset(val groupId: String) {
    FILES("files"), WEB("web"), TERMUX("termux"), PDF("pdf"), SYSTEM("system"), GIT("git"), CRYPTO("crypto");

    /** Same one-line description as the Settings > Tools group (shared in [ToolGroups.ORDER]). */
    val description: String get() = ToolGroups.ORDER.first { it.id == groupId }.description

    /** Same title as the Settings > Tools group. */
    val label: String get() = ToolGroups.ORDER.first { it.id == groupId }.title

    companion object {
        /** The preset a tool belongs to (its Settings group), or null for always-on groups (memory/skills, charts & other, MCP). */
        fun of(tool: String): ToolPreset? = ToolGroups.groupOf(tool).let { g -> entries.firstOrNull { it.groupId == g } }

        /** True unless [tool] belongs to a preset in [off]. */
        fun allowed(tool: String, off: Set<ToolPreset>): Boolean = off.isEmpty() || of(tool)?.let { it !in off } ?: true

        /** System-prompt note for a chat with some presets off ("" when all are on). */
        fun promptNote(off: Set<ToolPreset>): String = if (off.isEmpty()) "" else
            "Tool presets for this chat: the user turned off " + entries.filter { it in off }.joinToString { it.label } +
                " (chat menu → Tool presets). Those tools are not available in this chat; don't try to reach the same thing " +
                "another way (e.g. a shell or another tool). If the task needs them, tell the user which preset to turn on."

        fun encode(off: Set<ToolPreset>): Set<String> = off.map { it.name }.toSet()
        fun decode(raw: Set<String>?): Set<ToolPreset> = raw.orEmpty().flatMap { n ->
            LEGACY[n] ?: listOfNotNull(entries.firstOrNull { it.name == n })
        }.toSet()

        /** Persisted names from before presets mirrored the Settings groups. */
        private val LEGACY = mapOf("DEVICE" to listOf(SYSTEM, GIT), "FILES" to listOf(FILES, PDF))
    }
}

/** What [com.verdroid.app.agent.tools.ToolRegistry] needs for per-chat presets (testable without Android). */
fun interface ChatToolFilter {
    fun allowed(taskId: Long, tool: String): Boolean

    companion object { val ALL = ChatToolFilter { _, _ -> true } }
}

/** Persists the presets each chat turned off (only "off" is stored, so a new chat = all on). */
@Singleton
class ChatToolPresets @Inject constructor(@ApplicationContext context: Context) : ChatToolFilter {
    private val prefs = context.getSharedPreferences("chat_tool_presets", Context.MODE_PRIVATE)
    private val state = MutableStateFlow<Map<Long, Set<ToolPreset>>>(load())

    private fun load(): Map<Long, Set<ToolPreset>> = prefs.all.mapNotNull { (k, v) ->
        val id = k.removePrefix(KEY_PREFIX).toLongOrNull() ?: return@mapNotNull null
        @Suppress("UNCHECKED_CAST")
        id to ToolPreset.decode(v as? Set<String>)
    }.filter { it.second.isNotEmpty() }.toMap()

    fun off(taskId: Long): Set<ToolPreset> = state.value[taskId].orEmpty()
    fun observe(taskId: Long): Flow<Set<ToolPreset>> = state.map { it[taskId].orEmpty() }

    override fun allowed(taskId: Long, tool: String): Boolean = ToolPreset.allowed(tool, off(taskId))

    fun setEnabled(taskId: Long, preset: ToolPreset, enabled: Boolean) =
        save(taskId, if (enabled) off(taskId) - preset else off(taskId) + preset)

    /** Replaces the whole "off" set for a chat (used to keep presets picked before the chat's first message). */
    fun set(taskId: Long, off: Set<ToolPreset>) = save(taskId, off)

    /** "All" switch: everything on, or every preset off. */
    fun setAll(taskId: Long, enabled: Boolean) = save(taskId, if (enabled) emptySet() else ToolPreset.entries.toSet())

    @Synchronized
    private fun save(taskId: Long, off: Set<ToolPreset>) {
        if (taskId == 0L) return
        val e = prefs.edit()
        if (off.isEmpty()) e.remove(KEY_PREFIX + taskId) else e.putStringSet(KEY_PREFIX + taskId, ToolPreset.encode(off))
        e.apply()
        state.value = if (off.isEmpty()) state.value - taskId else state.value + (taskId to off)
    }

    private companion object { const val KEY_PREFIX = "off_" }
}
