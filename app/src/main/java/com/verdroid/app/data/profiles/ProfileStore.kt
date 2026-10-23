package com.verdroid.app.data.profiles

import android.content.Context
import com.verdroid.app.data.local.MemoryDao
import com.verdroid.app.data.local.TaskDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the agent needs to know about a chat's profile (fake-able in tests). */
interface ProfileLookup {
    fun profileOf(taskId: Long): Profile
    fun all(): List<Profile>
    /** A long-term memory was learned in Selected-only profile [profileId]: add it to that profile's selection. */
    fun onMemoryLearned(profileId: String, memoryId: Long) {}

    companion object {
        val DEFAULT_ONLY = object : ProfileLookup {
            override fun profileOf(taskId: Long) = Profiles.DEFAULT
            override fun all() = listOf(Profiles.DEFAULT)
        }
    }
}

/**
 * Profiles (SharedPreferences JSON) + the active profile + a cache of chat → profile (source of truth: tasks.profileId).
 * The cache only holds chats that are not on Default, so it stays tiny and lookups are synchronous.
 */
@Singleton
class ProfileStore @Inject constructor(
    @ApplicationContext context: Context,
    private val taskDao: TaskDao,
    private val memoryDao: MemoryDao,
) : ProfileLookup {
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private val _profiles = MutableStateFlow(Profiles.decode(prefs.getString(KEY_LIST, null)))
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    /** Settings → Profiles on/off (default on). Off = everything behaves as the Default profile; stored profiles are kept. */
    val enabled: StateFlow<Boolean> = _enabled
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    private fun defaultOf(l: List<Profile>) = Profiles.resolve(l, Profiles.DEFAULT_ID)
    /** Raw stored list (for the profiles editor). */
    val allProfiles: StateFlow<List<Profile>> = _profiles
    /** Effective list: only Default while profiles are off (so every picker hides). */
    val profiles: StateFlow<List<Profile>> = kotlinx.coroutines.flow.combine(_profiles, _enabled) { l, on -> if (on) l else listOf(defaultOf(l)) }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, if (_enabled.value) _profiles.value else listOf(defaultOf(_profiles.value)))
    private val _active = MutableStateFlow(Profiles.resolve(_profiles.value, prefs.getString(KEY_ACTIVE, null)).id)
    /** Profile new chats start with (and the one shown in the chat header picker). Default while profiles are off. */
    val activeId: StateFlow<String> = kotlinx.coroutines.flow.combine(_active, _enabled) { a, on -> if (on) a else Profiles.DEFAULT_ID }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, if (_enabled.value) _active.value else Profiles.DEFAULT_ID)

    fun setEnabled(on: Boolean) { prefs.edit().putBoolean(KEY_ENABLED, on).apply(); _enabled.value = on }

    private val _chats = MutableStateFlow<Map<Long, String>>(emptyMap())
    /** Chat id → profile id for chats that are not on Default. */
    val chatProfiles: StateFlow<Map<Long, String>> = _chats
    @Volatile private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _chats.value = runCatching { runBlocking(Dispatchers.IO) { taskDao.profileRows() }.associate { it.id to it.profileId } }.getOrDefault(emptyMap())
            loaded = true
        }
    }

    init {
        // test4 → test5: "separate memory" profiles become Selected-only with the memories they owned.
        if (Profiles.needsMemoryMigration(_profiles.value)) runCatching {
            val owned = runBlocking(Dispatchers.IO) { memoryDao.ownedRows() }.groupBy({ it.profileId }, { it.id })
            write(Profiles.migrateMemory(_profiles.value, owned))
        }
    }

    override fun all(): List<Profile> = if (_enabled.value) _profiles.value else listOf(defaultOf(_profiles.value))

    fun skillGate(): (Long, String) -> Boolean = { taskId, skill -> Profiles.skillAllowed(profileOf(taskId), skill) }

    /** A skill saved in a profile with selected skills joins that profile's selection (so the agent can use what it just wrote). */
    @Synchronized
    fun onSkillSaved(taskId: Long, skillId: String) {
        val p = profileOf(taskId)
        if (p.skills != null && skillId !in p.skills) save(p.copy(skills = p.skills + skillId))
    }

    @Synchronized
    override fun onMemoryLearned(profileId: String, memoryId: Long) = write(Profiles.addMemory(_profiles.value, profileId, memoryId))
    fun get(id: String?): Profile = Profiles.resolve(_profiles.value, id)
    fun active(): Profile = if (_enabled.value) get(_active.value) else defaultOf(_profiles.value)

    override fun profileOf(taskId: Long): Profile {
        if (!_enabled.value) return defaultOf(_profiles.value)
        if (taskId <= 0) return active()
        if (_profiles.value.size == 1) return _profiles.value.first()
        ensureLoaded()
        return get(_chats.value[taskId])
    }

    fun profileIdOf(taskId: Long): String = profileOf(taskId).id

    fun setActive(id: String) {
        val p = get(id)
        prefs.edit().putString(KEY_ACTIVE, p.id).apply()
        _active.value = p.id
    }

    /** Called right after a chat is inserted (the DB row already carries [profileId]). */
    fun onChatCreated(taskId: Long, profileId: String) {
        ensureLoaded()
        if (profileId != Profiles.DEFAULT_ID) _chats.value = _chats.value + (taskId to profileId)
    }

    suspend fun setChatProfile(taskId: Long, profileId: String) {
        if (!_enabled.value) return
        if (taskId <= 0) { setActive(profileId); return }
        val id = get(profileId).id
        withContext(Dispatchers.IO) { taskDao.setProfile(taskId, id) }
        ensureLoaded()
        _chats.value = if (id == Profiles.DEFAULT_ID) _chats.value - taskId else _chats.value + (taskId to id)
        setActive(id)
    }

    @Synchronized
    fun save(profile: Profile) {
        val list = _profiles.value
        val next = if (list.any { it.id == profile.id }) list.map { if (it.id == profile.id) profile else it } else list + profile
        write(next)
    }

    /** Deletes a profile (never Default): its chats move to Default, its separate memories become shared. */
    suspend fun delete(id: String) {
        if (id == Profiles.DEFAULT_ID) return
        withContext(Dispatchers.IO) { taskDao.resetProfile(id); memoryDao.unscopeProfile(id) }
        synchronized(this) { write(Profiles.delete(_profiles.value, id)) }
        _chats.value = _chats.value.filterValues { it != id }
        if (_active.value == id) setActive(Profiles.DEFAULT_ID)
    }

    private fun write(list: List<Profile>) {
        val n = Profiles.normalize(list)
        prefs.edit().putString(KEY_LIST, Profiles.encode(n)).apply()
        _profiles.value = n
    }

    private companion object { const val KEY_LIST = "list"; const val KEY_ACTIVE = "active"; const val KEY_ENABLED = "enabled" }
}
