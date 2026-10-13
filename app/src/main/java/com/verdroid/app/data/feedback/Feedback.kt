package com.verdroid.app.data.feedback

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** GitHub new-issue links with app/device info prefilled (issue forms take field values as `<field id>=` params). */
object FeedbackLinks {
    const val REPO = "https://github.com/chr243/Verdroid"

    fun deviceInfo(appVersion: String, sdk: Int, release: String, manufacturer: String, model: String): String =
        "Verdroid $appVersion, Android $release (API $sdk), $manufacturer $model"

    fun bugUrl(info: String): String =
        "$REPO/issues/new?template=bug_report.yml&labels=bug&device=" + Uri.encode(info)

    fun featureUrl(info: String): String =
        "$REPO/issues/new?template=feature_request.yml&labels=enhancement&device=" + Uri.encode(info)

    fun current(context: Context): String = deviceInfo(
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?",
        android.os.Build.VERSION.SDK_INT, android.os.Build.VERSION.RELEASE ?: "?",
        android.os.Build.MANUFACTURER ?: "?", android.os.Build.MODEL ?: "?")
}

/** Pure trigger logic for the one-time "star on GitHub" prompt (unit-tested). */
object StarPromptLogic {
    const val TRIGGER_AT = 3
    /** After a chat was created: new count, and whether the prompt becomes pending (exactly at the 3rd, never once done). */
    fun onChatCreated(createdBefore: Int, done: Boolean): Pair<Int, Boolean> {
        val n = createdBefore + 1
        return n to (!done && n == TRIGGER_AT)
    }
    /** Show only when pending, not done, and nothing is running (don't interrupt a task). */
    fun shouldShow(pending: Boolean, done: Boolean, anyTaskActive: Boolean) = pending && !done && !anyTaskActive
}

/** Persistent counters for the star prompt: total chats ever created (not current), pending, done. */
class StarPromptStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("star_prompt", Context.MODE_PRIVATE)
    private val _pending = MutableStateFlow(prefs.getBoolean("pending", false) && !prefs.getBoolean("done", false))
    val pending: StateFlow<Boolean> = _pending.asStateFlow()
    val created: Int get() = prefs.getInt("created", 0)
    val done: Boolean get() = prefs.getBoolean("done", false)

    @Synchronized fun onChatCreated() {
        val (n, becomesPending) = StarPromptLogic.onChatCreated(created, done)
        prefs.edit().putInt("created", n).apply { if (becomesPending) putBoolean("pending", true) }.apply()
        if (becomesPending) _pending.value = true
    }

    /** Any choice or dismiss: never again. */
    fun finish() { prefs.edit().putBoolean("done", true).putBoolean("pending", false).apply(); _pending.value = false }

    companion object {
        @Volatile private var instance: StarPromptStore? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: StarPromptStore(context.applicationContext).also { instance = it } }
    }
}
