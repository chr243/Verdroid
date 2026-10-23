package com.verdroid.app.data.profiles

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A profile: a named bundle of tool groups, skills, model, instructions and memory scope the user switches with one tap.
 * Keys, logins and safety settings are never part of a profile (always shared).
 * Everyone starts with [Profiles.DEFAULT_ID], which behaves exactly like the app did before profiles existed.
 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val emoji: String = "🤖",
    /** Model priority id tried first (with fallback to Model priority); "" = follow Model priority. */
    val model: String = "",
    /** Legacy (≤ test4): tool group ids turned off. Migrated into [tools] by [Profiles.normalize]; always empty after. */
    val toolGroupsOff: Set<String> = emptySet(),
    /** Tool group ids ([com.verdroid.app.data.tools.ToolPreset.groupId]) on in this profile; null = all groups (incl. new ones). */
    val tools: Set<String>? = null,
    /** Skill ids available in this profile; null = every enabled skill. */
    val skills: Set<String>? = null,
    /** Extra instructions / context added to the system prompt. */
    val instructions: String = "",
    /** Legacy (≤ test4) "separate memory" switch; migrated into [memories] by [ProfileStore] on load. */
    val separateMemory: Boolean = false,
    /**
     * Long-term memory ids this profile sees; null = "All memories" (every memory not owned by a Selected-only profile).
     * Selected-only: only these; memories learned while in it are owned by it (memories.profileId = id) and auto-added here.
     */
    val memories: Set<Long>? = null,
) {
    val isDefault: Boolean get() = id == Profiles.DEFAULT_ID
    val label: String get() = "$emoji $name".trim()
}

/** Pure profile rules (unit-tested and used by the self-test). */
object Profiles {
    const val DEFAULT_ID = "default"
    val DEFAULT = Profile(DEFAULT_ID, "Default", "🤖")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(list: List<Profile>): String = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Profile.serializer()), list)

    /** Stored list → profiles; Default is always present, first, and kept even if the stored data is broken. */
    fun decode(raw: String?): List<Profile> {
        val stored = raw?.let { runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Profile.serializer()), it) }.getOrNull() }.orEmpty()
        return normalize(stored)
    }

    fun normalize(list: List<Profile>): List<Profile> {
        val def = (list.firstOrNull { it.isDefault } ?: DEFAULT).copy(memories = null, separateMemory = false)
        return (listOf(def) + list.filter { !it.isDefault }.distinctBy { it.id }).map(::migrateTools)
    }

    val ALL_TOOL_GROUPS: List<String> get() = com.verdroid.app.data.tools.ToolPreset.entries.map { it.groupId }

    /** Legacy toolGroupsOff → Selected tools (every known group except the off ones). */
    fun migrateTools(p: Profile): Profile =
        if (p.toolGroupsOff.isEmpty()) p else p.copy(tools = (p.tools ?: ALL_TOOL_GROUPS.toSet()) - p.toolGroupsOff, toolGroupsOff = emptySet())

    /** Legacy memory switch: "separate" → Selected with the memories it owned; "shared" → All. [owned]: profile id → its memory ids. */
    fun migrateMemory(list: List<Profile>, owned: Map<String, List<Long>>): List<Profile> = list.map { p ->
        if (!p.separateMemory) p
        else p.copy(separateMemory = false, memories = if (p.isDefault) null else (p.memories.orEmpty() + owned[p.id].orEmpty()).toSet())
    }

    fun needsMemoryMigration(list: List<Profile>): Boolean = list.any { it.separateMemory }

    /** Adds a newly learned memory to its owner's selection. */
    fun addMemory(list: List<Profile>, profileId: String, memoryId: Long): List<Profile> =
        list.map { if (it.id == profileId && it.memories != null) it.copy(memories = it.memories + memoryId) else it }

    /** Whether [profile] sees a long-term memory with [memoryId] owned by [owner] (null = unowned/shared). */
    fun memoryVisible(profile: Profile, memoryId: Long, owner: String?): Boolean =
        profile.memories?.contains(memoryId) ?: (owner == null)

    /** Profiles UI (badges, chips, header) only appears once a second profile exists. */
    fun visible(list: List<Profile>): Boolean = list.size > 1

    /** Unknown/deleted id → Default. */
    fun resolve(list: List<Profile>, id: String?): Profile = list.firstOrNull { it.id == id } ?: list.firstOrNull { it.isDefault } ?: DEFAULT

    fun newId(existing: Collection<String>, now: Long = System.currentTimeMillis()): String {
        var n = now; while ("p$n" in existing) n++; return "p$n"
    }

    /** "New profile" starts as a copy of [base] (everything but id/name). */
    fun copyOf(base: Profile, id: String, name: String): Profile = base.copy(id = id, name = name)

    /** Ready templates shown under "New profile". */
    fun templates(id: (String) -> String): List<Profile> = listOf(
        Profile(id("coding"), "Coding", "💻",
            tools = ALL_TOOL_GROUPS.toSet() - setOf("twitter", "crypto", "pdf"),
            instructions = "You are a coding assistant. Prefer Termux/Python and Git tools, show code in fenced blocks, keep explanations short and test what you write when you can."),
        Profile(id("research"), "Research", "🔎",
            tools = ALL_TOOL_GROUPS.toSet() - setOf("termux", "system", "git", "crypto"),
            instructions = "You are a research assistant. Search several sources, read the pages before answering, cite links for key claims and say when sources disagree."),
    )

    /** Effective state of a tool in a chat: the chat's preset override wins, then the profile, then Settings → Tools. */
    fun toolAllowed(group: String, override: Boolean?, profile: Profile, globallyOn: Boolean): Boolean =
        // Tools with no preset group (memory & skills, charts, self-test, MCP) are always-on: a profile's
        // selected tool groups must never turn them off — they follow Settings → Tools only.
        if (group.isBlank()) globallyOn
        else override ?: if (group in profile.toolGroupsOff || profile.tools?.contains(group) == false) false else globallyOn

    /**
     * Tool presets dialog baseline: Settings → Tools state, with groups the profile leaves out shown as off.
     * The chat's overrides are applied on top (and only to that chat).
     */
    fun presetBaseline(global: Map<com.verdroid.app.data.tools.ToolPreset, com.verdroid.app.data.tools.ToolPreset.Companion.Global>, profile: Profile) =
        global.mapValues { (p, g) -> if (g.total > 0 && !toolAllowed(p.groupId, null, profile, true)) g.copy(on = 0) else g }

    fun skillAllowed(profile: Profile, skillId: String): Boolean = profile.skills?.contains(skillId) ?: true

    /**
     * Note added to the skills prompt so the agent knows skill creation is never blocked by a profile.
     * A profile's skills selection only limits which *existing* skills it may use; saving a new one always
     * works and the new skill is auto-added to this profile (see ProfileStore.onSkillSaved). "" for All-skills.
     */
    fun skillCreationNote(profile: Profile): String =
        if (profile.skills == null) ""
        else "You can always create a new skill here with skill_save even though this profile (\"${profile.name}\") only lists some skills — a skill you save is added to this profile automatically. The profile only blocks *using* existing skills left out of it, never saving a new one."

    /** Owner stamped on long-term memories learned in [profile]: null = unowned (All memories), else the Selected-only profile's id. */
    fun memoryScope(profile: Profile): String? = if (profile.memories != null && !profile.isDefault) profile.id else null

    /** System-prompt block ("" for a profile that changes nothing). */
    fun promptBlock(profile: Profile, all: List<Profile>): String {
        if (!visible(all) && profile.instructions.isBlank()) return ""
        val off = profile.toolGroupsOff + ALL_TOOL_GROUPS.filter { profile.tools?.contains(it) == false }
        return buildString {
            if (visible(all)) append("Active profile: ${profile.name} (the user switches profiles at the top of the chat).")
            if (off.isNotEmpty()) append(" This profile turns off some tool groups; don't work around them — tell the user to switch profile or edit it in Settings → Profiles if a task needs them.")
            if (profile.instructions.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append("## Profile instructions (${profile.name})\n").append(profile.instructions.trim().take(MAX_INSTRUCTIONS))
            }
        }.trim()
    }

    /** Remove a profile; Default can't be removed. Returns the new list and the id chats of the removed one move to. */
    fun delete(list: List<Profile>, id: String): List<Profile> = if (id == DEFAULT_ID) list else list.filter { it.id != id }

    /** Chat list filter chip: null = All; a deleted profile's id falls back to All. */
    fun <T> filterChats(items: List<T>, filter: String?, list: List<Profile>, profileOf: (T) -> String): List<T> =
        if (filter == null || list.none { it.id == filter }) items else items.filter { Profiles.resolve(list, profileOf(it)).id == filter }

    const val MAX_INSTRUCTIONS = 6000
    const val SHARED_NOTE = "API keys, logins and safety settings are always shared by all profiles."
}
