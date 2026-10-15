package com.verdroid.app.data.consent

import android.content.Context
import com.verdroid.app.agent.tools.XPostConfirmer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Post / Cancel before x_post publishes anything (main app and bubble dialog; a notification brings the user there).
 * "Post to X without asking" (Settings → Tools, off by default) or the dialog's "Always allow" skips it. No answer = Cancel.
 */
@Singleton
class XPostConsent @Inject constructor(
    @ApplicationContext context: Context,
    private val notifier: com.verdroid.app.data.notify.AlertNotifier,
) : XPostConfirmer {
    data class Pending(val id: Long, val taskId: Long, val text: String, val replyTo: String?, internal val answer: CompletableDeferred<Boolean>)

    private val prefs = context.getSharedPreferences("x_post_consent", Context.MODE_PRIVATE)
    private val _always = MutableStateFlow(prefs.getBoolean(KEY, false))
    val always: StateFlow<Boolean> = _always.asStateFlow()
    private val _pending = MutableStateFlow<List<Pending>>(emptyList())
    val pending: StateFlow<List<Pending>> = _pending.asStateFlow()
    private var nextId = 1L

    fun setAlways(on: Boolean) { prefs.edit().putBoolean(KEY, on).apply(); _always.value = on }

    override suspend fun confirm(taskId: Long, text: String, replyTo: String?): Boolean? {
        if (_always.value) return true
        val p = Pending(synchronized(this) { nextId++ }, taskId, text, replyTo, CompletableDeferred())
        _pending.update { it + p }
        notifier.notify(NOTIFY_BASE + (p.id % 100).toInt(), "Verdroid wants to post on X",
            (if (replyTo != null) "Reply: " else "") + text.take(200), taskId)
        return try { withTimeoutOrNull(TIMEOUT_MS) { p.answer.await() } } finally { _pending.update { l -> l.filterNot { it === p } } }
    }

    fun answer(id: Long, post: Boolean, always: Boolean = false) {
        if (post && always) setAlways(true)
        _pending.value.firstOrNull { it.id == id }?.answer?.complete(post)
    }

    companion object {
        const val TIMEOUT_MS = 5 * 60_000L
        private const val KEY = "post_without_asking"
        private const val NOTIFY_BASE = 7450
    }
}
