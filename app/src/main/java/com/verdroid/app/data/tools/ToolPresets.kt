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
    FILES("files"), WEB("web"), TWITTER("twitter"), REDDIT("reddit"), TERMUX("termux"), PDF("pdf"), SYSTEM("system"), GIT("git"), CRYPTO("crypto"), SCHEDULING("scheduling");

    /** Same one-line description as the Settings > Tools group (shared in [ToolGroups.ORDER]). */
    val description: String get() = ToolGroups.ORDER.first { it.id == groupId }.description

    /** Same title as the Settings > Tools group. */
    val label: String get() = ToolGroups.ORDER.first { it.id == groupId }.title

    companion object {
        /** The preset a tool belongs to (its Settings group), or null for always-on groups (memory/skills, charts & other, MCP). */
        fun of(tool: String): ToolPreset? = ToolGroups.groupOf(tool).let { g -> entries.firstOrNull { it.groupId == g } }

        /**
         * Effective per-chat state of [tool]: the chat's override for its group if set (true = forced on, false = forced
         * off), else the Settings → Tools switch [globallyOn]. Tools outside the preset groups always follow Settings.
         */
        fun effective(tool: String, globallyOn: Boolean, overrides: Map<ToolPreset, Boolean>): Boolean =
            of(tool)?.let { overrides[it] } ?: globallyOn

        /** Legacy helper: true unless [tool] belongs to a preset in [off] (= forced-off overrides, Settings on). */
        fun allowed(tool: String, off: Set<ToolPreset>): Boolean = effective(tool, true, off.associateWith { false })

        /** System-prompt note for a chat with overrides ("" when it just follows Settings). */
        fun promptNote(overrides: Map<ToolPreset, Boolean>): String {
            val off = entries.filter { overrides[it] == false }
            val on = entries.filter { overrides[it] == true }
            if (off.isEmpty() && on.isEmpty()) return ""
            return buildString {
                append("Tool presets for this chat (chat menu → Tool presets; they override Settings → Tools for this chat):")
                if (off.isNotEmpty()) append(" turned off: ").append(off.joinToString { it.label }).append('.')
                if (on.isNotEmpty()) append(" turned on: ").append(on.joinToString { it.label }).append('.')
                if (off.isNotEmpty()) append(" Turned-off tools are not available here; don't try to reach the same thing another way " +
                    "(e.g. a shell or another tool). If the task needs them, tell the user which preset to turn on.")
            }
        }
        fun promptNote(off: Set<ToolPreset>): String = promptNote(off.associateWith { false })

        fun encode(off: Set<ToolPreset>): Set<String> = off.map { it.name }.toSet()
        fun decode(raw: Set<String>?): Set<ToolPreset> = raw.orEmpty().flatMap { n ->
            LEGACY[n] ?: listOfNotNull(entries.firstOrNull { it.name == n })
        }.toSet()

        /** Settings → Tools state of one preset's group: [on] of [total] tools enabled globally. */
        data class Global(val on: Int, val total: Int) {
            val offInSettings: Boolean get() = total > 0 && on == 0
        }

        /** Settings state of a group as one switch: on while any of its tools is on (partly on = on). */
        fun settingsOn(p: ToolPreset, global: Map<ToolPreset, Global>): Boolean = global[p]?.offInSettings != true

        /** Dialog switch = effective state: the chat override if set, else Settings. */
        fun shownOn(p: ToolPreset, overrides: Map<ToolPreset, Boolean>, global: Map<ToolPreset, Global>): Boolean =
            overrides[p] ?: settingsOn(p, global)

        /**
         * New overrides after the user flips [p] to [on]: matching the Settings state clears the override (inherit),
         * otherwise it is stored (on while off in Settings = forced on: the chat gets all of that group's tools).
         */
        fun toggle(overrides: Map<ToolPreset, Boolean>, p: ToolPreset, on: Boolean, global: Map<ToolPreset, Global>): Map<ToolPreset, Boolean> =
            if (on == settingsOn(p, global)) overrides - p else overrides + (p to on)

        /** "All" switch: on when every group is effectively on; flipping it applies [toggle] to every group. */
        fun allOn(overrides: Map<ToolPreset, Boolean>, global: Map<ToolPreset, Global>): Boolean = entries.all { shownOn(it, overrides, global) }
        fun toggleAll(overrides: Map<ToolPreset, Boolean>, on: Boolean, global: Map<ToolPreset, Global>): Map<ToolPreset, Boolean> =
            entries.fold(overrides) { acc, p -> toggle(acc, p, on, global) }

        /** Per-preset Settings → Tools state from the registered tool names and the globally [disabled] set (display only). */
        fun globalState(tools: Collection<String>, disabled: Set<String>): Map<ToolPreset, Global> = entries.associateWith { p ->
            val names = tools.filter { ToolGroups.groupOf(it) == p.groupId }
            Global(names.count { it !in disabled }, names.size)
        }

        /** Persisted names from before presets mirrored the Settings groups. */
        private val LEGACY = mapOf("DEVICE" to listOf(SYSTEM, GIT), "FILES" to listOf(FILES, PDF), "COOKIES" to listOf(TWITTER))

        /**
         * Browser cookies (COOKIES, Oct 2026) was split into Twitter + the cookie tools in Web. A chat that had it forced ON
         * keeps both on (TWITTER + WEB); forced OFF only turns Twitter off (Web is not forced off).
         */
        fun decode(raw: Set<String>?, on: Boolean): Set<ToolPreset> =
            decode(raw) + if (on && raw.orEmpty().contains("COOKIES")) setOf(WEB) else emptySet()
    }
}

/** What [com.verdroid.app.agent.tools.ToolRegistry] needs for per-chat presets (testable without Android). */
fun interface ChatToolFilter {
    /** Effective state of [tool] in chat [taskId], given its Settings → Tools state [globallyOn]. */
    fun allowed(taskId: Long, tool: String, globallyOn: Boolean): Boolean

    companion object { val ALL = ChatToolFilter { _, _, g -> g } }
}

/**
 * Per-chat overrides per group: inherit (not stored), forced on ("on_<id>") or forced off ("off_<id>"). Chats saved before
 * overrides existed only had "off_<id>" sets, which read as forced-off overrides unchanged (no data migration needed).
 */
@Singleton
class ChatToolPresets @Inject constructor(@ApplicationContext context: Context) : ChatToolFilter {
    private val prefs = context.getSharedPreferences("chat_tool_presets", Context.MODE_PRIVATE)
    private val state = MutableStateFlow<Map<Long, Map<ToolPreset, Boolean>>>(load())

    private fun load(): Map<Long, Map<ToolPreset, Boolean>> {
        val out = mutableMapOf<Long, Map<ToolPreset, Boolean>>()
        prefs.all.forEach { (k, v) ->
            val on = k.startsWith(ON_PREFIX)
            val id = k.removePrefix(if (on) ON_PREFIX else OFF_PREFIX).toLongOrNull() ?: return@forEach
            @Suppress("UNCHECKED_CAST")
            val set = ToolPreset.decode(v as? Set<String>, on)
            out[id] = out[id].orEmpty() + set.associateWith { on }
        }
        return out.filterValues { it.isNotEmpty() }
    }

    fun overrides(taskId: Long): Map<ToolPreset, Boolean> = state.value[taskId].orEmpty()
    fun observe(taskId: Long): Flow<Map<ToolPreset, Boolean>> = state.map { it[taskId].orEmpty() }

    override fun allowed(taskId: Long, tool: String, globallyOn: Boolean): Boolean =
        ToolPreset.effective(tool, globallyOn, overrides(taskId))

    @Synchronized
    fun set(taskId: Long, overrides: Map<ToolPreset, Boolean>) {
        if (taskId == 0L) return
        val off = overrides.filterValues { !it }.keys
        val on = overrides.filterValues { it }.keys
        val e = prefs.edit()
        if (off.isEmpty()) e.remove(OFF_PREFIX + taskId) else e.putStringSet(OFF_PREFIX + taskId, ToolPreset.encode(off))
        if (on.isEmpty()) e.remove(ON_PREFIX + taskId) else e.putStringSet(ON_PREFIX + taskId, ToolPreset.encode(on))
        e.apply()
        state.value = if (overrides.isEmpty()) state.value - taskId else state.value + (taskId to overrides)
    }

    private companion object { const val OFF_PREFIX = "off_"; const val ON_PREFIX = "on_" }
}
