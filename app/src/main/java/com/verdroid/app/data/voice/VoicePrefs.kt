package com.verdroid.app.data.voice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Voice settings (Settings → Appearance → Voice). Device default language is always used. */
@Singleton
class VoicePrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("voice_prefs", Context.MODE_PRIVATE)

    private val _speak = MutableStateFlow(prefs.getBoolean(KEY_SPEAK, false))
    /** Read assistant replies aloud — default OFF. */
    val speakReplies: StateFlow<Boolean> = _speak.asStateFlow()
    fun setSpeakReplies(v: Boolean) { prefs.edit().putBoolean(KEY_SPEAK, v).apply(); _speak.value = v }

    private val _autoSend = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SEND, true))
    /** Send recognized speech right away — default ON. */
    val autoSend: StateFlow<Boolean> = _autoSend.asStateFlow()
    fun setAutoSend(v: Boolean) { prefs.edit().putBoolean(KEY_AUTO_SEND, v).apply(); _autoSend.value = v }

    private val _handsFree = MutableStateFlow(prefs.getBoolean(KEY_HANDS_FREE, false))
    /** Hands-free: listen again after a reply was spoken — default OFF. */
    val handsFree: StateFlow<Boolean> = _handsFree.asStateFlow()
    fun setHandsFree(v: Boolean) { prefs.edit().putBoolean(KEY_HANDS_FREE, v).apply(); _handsFree.value = v }

    private val _micButton = MutableStateFlow(prefs.getBoolean(KEY_MIC, true))
    /** Show the mic button next to the chat input — default ON. */
    val micButton: StateFlow<Boolean> = _micButton.asStateFlow()
    fun setMicButton(v: Boolean) { prefs.edit().putBoolean(KEY_MIC, v).apply(); _micButton.value = v }

    companion object {
        const val KEY_SPEAK = "speak_replies"
        const val KEY_AUTO_SEND = "auto_send"
        const val KEY_HANDS_FREE = "hands_free"
        const val KEY_MIC = "mic_button"
    }
}
