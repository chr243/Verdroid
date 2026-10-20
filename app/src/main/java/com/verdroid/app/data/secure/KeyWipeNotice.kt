package com.verdroid.app.data.secure

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Review #16: an encrypted store that became unreadable (e.g. Keystore reset after a backup restore) is reset so the
 * app doesn't crash; record that here so the user is TOLD their saved keys/tokens are gone instead of finding out later.
 * Persisted in plain prefs (only store names, never secrets) until the user dismisses it.
 */
object KeyWipeNotice {
    private const val PREFS = "key_wipe_notice"
    private const val KEY = "wiped"
    private val _wiped = MutableStateFlow<Set<String>>(emptySet())
    val wiped: StateFlow<Set<String>> = _wiped.asStateFlow()

    fun init(context: Context) {
        _wiped.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty()
    }

    fun record(context: Context, what: String) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = p.getStringSet(KEY, emptySet()).orEmpty() + what
        p.edit().putStringSet(KEY, s).apply()
        _wiped.value = s
    }

    fun dismiss(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        _wiped.value = emptySet()
    }

    fun message(stores: Set<String>): String =
        "Verdroid could not decrypt its secure storage (usually after a phone reset or backup restore), so it was reset. " +
            "Please re-enter: " + stores.sorted().joinToString(", ") + "."
}
