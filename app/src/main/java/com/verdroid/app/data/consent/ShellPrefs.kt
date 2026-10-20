package com.verdroid.app.data.consent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Settings → Tools → File safety: "Ask before shell commands" (off by default = unchanged behavior). */
@Singleton
class ShellPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("file_consent", Context.MODE_PRIVATE)
    private val _always = MutableStateFlow(prefs.getBoolean(KEY, false))
    val askAlways: StateFlow<Boolean> = _always.asStateFlow()
    fun setAskAlways(on: Boolean) { prefs.edit().putBoolean(KEY, on).apply(); _always.value = on }
    private companion object { const val KEY = "ask_before_shell" }
}
