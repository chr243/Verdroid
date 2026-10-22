package com.verdroid.app.data.profiles

import android.content.Context
import com.verdroid.app.data.local.MemoryDao
import com.verdroid.app.data.local.TaskDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the agent needs to know about a chat's profile (fake-able in tests). */
interface ProfileLookup {
    fun profileOf(taskId: Long): Profile
    fun all(): List<Profile>

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
    val profiles: StateFlow<List<Profile>> = _profiles
    private val _active = MutableStateFlow(Profiles.resolve(_profiles.value, prefs.getString(KEY_ACTIVE, null)).id)
    /** Profile new chats start with (and the one shown in the chat header picker). */
    val activeId: StateFlow<String> = _active

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

    override fun all(): List<Profile> = _profiles.value
    fun get(id: String?): Profile = Profiles.resolve(_profiles.value, id)
    fun active(): Profile = get(_active.value)

    override fun profileOf(taskId: Long): Profile {
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

    private companion object { const val KEY_LIST = "list"; const val KEY_ACTIVE = "active" }
}
