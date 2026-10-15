package com.verdroid.app.data.consent

import android.content.Context
import com.verdroid.app.agent.tools.OverwritePolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Allow overwriting files without asking" (Settings → Tools, off by default) + per-chat "This chat" allowances (kept until
 * the app restarts). Overwrites only; deletes/moves/renames always ask.
 */
@Singleton
class OverwritePrefs @Inject constructor(@ApplicationContext context: Context) : OverwritePolicy {
    private val prefs = context.getSharedPreferences("file_consent", Context.MODE_PRIVATE)
    private val _always = MutableStateFlow(prefs.getBoolean(KEY, false))
    val always: StateFlow<Boolean> = _always.asStateFlow()
    private val chats = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    fun setAlways(on: Boolean) { prefs.edit().putBoolean(KEY, on).apply(); _always.value = on }
    fun allowChat(taskId: Long) { chats += taskId }

    override fun allowsOverwrite(taskId: Long): Boolean = _always.value || taskId in chats

    private companion object { const val KEY = "always_allow_overwrite" }
}
