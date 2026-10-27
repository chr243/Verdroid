package com.verdroid.app.data.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Small UI/behaviour toggles (v0.9.8). */
@Singleton
class AppPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        .also { if (it.contains(KEY_SHARE_OPEN)) it.edit().remove(KEY_SHARE_OPEN).apply() }

    private val _rpmOverride = MutableStateFlow(prefs.getInt("rpm_override", 0).coerceIn(0, 60))
    /** Manual requests/minute override for every OpenRouter model bucket; 0 = automatic (headers + AIMD, learned per model). */
    val rpmOverride: StateFlow<Int> = _rpmOverride
    fun setRpmOverride(v: Int) { val c = v.coerceIn(0, 60); prefs.edit().putInt("rpm_override", c).apply(); _rpmOverride.value = c }

    /** Learned per provider|model rpm (AdaptiveRateController.encode). */
    var learnedRpm: String
        get() = prefs.getString("learned_rpm", "") ?: ""
        set(v) { prefs.edit().putString("learned_rpm", v).apply() }

    private val _notifyOnFinish = MutableStateFlow(prefs.getBoolean(KEY_NOTIFY_FINISH, false))
    /** "Notify when a task finishes" — default OFF (only attention-needed events notify). */
    val notifyOnFinish: StateFlow<Boolean> = _notifyOnFinish.asStateFlow()
    fun setNotifyOnFinish(v: Boolean) { prefs.edit().putBoolean(KEY_NOTIFY_FINISH, v).apply(); _notifyOnFinish.value = v }

    private val _autoChatHead = MutableStateFlow(prefs.getBoolean(KEY_AUTO_HEAD, true))
    /** "Auto chat head on Home" — default ON. */
    val autoChatHeadOnHome: StateFlow<Boolean> = _autoChatHead.asStateFlow()
    fun setAutoChatHeadOnHome(v: Boolean) { prefs.edit().putBoolean(KEY_AUTO_HEAD, v).apply(); _autoChatHead.value = v }

    private val _memKilo = MutableStateFlow(prefs.getBoolean(KEY_MEM_KILO, true))
    /** "Send memories to Kilo models" — default ON (Kilo Auto Free may log prompts; the user is informed once). */
    val sendMemoriesToKilo: StateFlow<Boolean> = _memKilo.asStateFlow()
    fun setSendMemoriesToKilo(v: Boolean) { prefs.edit().putBoolean(KEY_MEM_KILO, v).apply(); _memKilo.value = v }

    private val _kiloNotice = MutableStateFlow(prefs.getBoolean(KEY_KILO_NOTICE, false))
    /** One-time Settings notice that Kilo Auto Free may log prompts. */
    val kiloNoticeSeen: StateFlow<Boolean> = _kiloNotice.asStateFlow()
    fun dismissKiloNotice() { prefs.edit().putBoolean(KEY_KILO_NOTICE, true).apply(); _kiloNotice.value = true }

    private val _shareLast = MutableStateFlow(prefs.getBoolean(KEY_SHARE_LAST, false))
    /** Share sheet target: false = New chat (default), true = Last chat. Remembers the last choice. */
    val shareToLastChat: StateFlow<Boolean> = _shareLast.asStateFlow()
    fun setShareToLastChat(v: Boolean) { prefs.edit().putBoolean(KEY_SHARE_LAST, v).apply(); _shareLast.value = v }

    private companion object {
        const val KEY_SHARE_LAST = "share_to_last_chat"
        /** Removed "Share opens in" setting; any stored value is dropped on start. */
        const val KEY_SHARE_OPEN = "share_open_mode"
        const val KEY_MEM_KILO = "send_memories_to_kilo"
        const val KEY_KILO_NOTICE = "kilo_notice_seen"
        const val KEY_NOTIFY_FINISH = "notify_on_finish"
        const val KEY_AUTO_HEAD = "auto_chat_head_on_home"
    }
}
