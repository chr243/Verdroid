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
    /** Settings → Tools group ids turned off for this profile ([com.verdroid.app.data.tools.ToolPreset.groupId]). */
    val toolGroupsOff: Set<String> = emptySet(),
    /** Skill ids available in this profile; null = every enabled skill. */
    val skills: Set<String>? = null,
    /** Extra instructions / context added to the system prompt. */
    val instructions: String = "",
    /** true = long-term memory of its own; false = shared with all profiles (default). */
    val separateMemory: Boolean = false,
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
        val def = list.firstOrNull { it.isDefault } ?: DEFAULT
        return listOf(def) + list.filter { !it.isDefault }.distinctBy { it.id }
    }

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
            toolGroupsOff = setOf("twitter", "crypto", "pdf"),
            instructions = "You are a coding assistant. Prefer Termux/Python and Git tools, show code in fenced blocks, keep explanations short and test what you write when you can."),
        Profile(id("research"), "Research", "🔎",
            toolGroupsOff = setOf("termux", "system", "git", "crypto"),
            instructions = "You are a research assistant. Search several sources, read the pages before answering, cite links for key claims and say when sources disagree."),
    )

    /** Effective state of a tool in a chat: the chat's preset override wins, then the profile, then Settings → Tools. */
    fun toolAllowed(group: String, override: Boolean?, profile: Profile, globallyOn: Boolean): Boolean =
        override ?: if (group in profile.toolGroupsOff) false else globallyOn

    fun skillAllowed(profile: Profile, skillId: String): Boolean = profile.skills?.contains(skillId) ?: true

    /** Memory scope for long-term memories: null = shared, else the profile's own id. */
    fun memoryScope(profile: Profile): String? = if (profile.separateMemory && !profile.isDefault) profile.id else null

    /** System-prompt block ("" for a profile that changes nothing). */
    fun promptBlock(profile: Profile, all: List<Profile>): String {
        if (!visible(all) && profile.instructions.isBlank()) return ""
        val off = profile.toolGroupsOff
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
