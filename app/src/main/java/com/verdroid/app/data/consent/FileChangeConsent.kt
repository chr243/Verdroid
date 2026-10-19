package com.verdroid.app.data.consent

import com.verdroid.app.agent.tools.FileChangeConfirmer
import com.verdroid.app.agent.tools.FileChangeRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-action Allow/Deny for file deletes/moves/renames/overwrites. The tool call suspends until the user answers in the
 * dialog (main app or bubble) — a notification brings them there. No answer within [TIMEOUT_MS] = Deny. "Always allow" /
 * "This chat" exist for OVERWRITES ([OverwritePrefs]) and for deletes/moves/renames proven inside Documents/Verdroid
 * ([SharedDeletePrefs]); every other delete/move/rename is asked every time.
 */
@Singleton
class FileChangeConsent @Inject constructor(
    private val notifier: com.verdroid.app.data.notify.AlertNotifier,
    private val tasks: com.verdroid.app.domain.repository.TaskRepository,
    val overwrite: OverwritePrefs,
    val deletes: SharedDeletePrefs,
) : FileChangeConfirmer {
    data class Pending(val req: FileChangeRequest, val chatTitle: String?, internal val answer: CompletableDeferred<Boolean>)

    private val _pending = MutableStateFlow<List<Pending>>(emptyList())
    val pending: StateFlow<List<Pending>> = _pending.asStateFlow()

    override suspend fun confirm(req: FileChangeRequest): Boolean {
        val title = runCatching { tasks.getTask(req.taskId)?.title }.getOrNull()
        val p = Pending(req, title, CompletableDeferred())
        _pending.update { it + p }
        notifier.notify(NOTIFY_BASE + (req.id % 1000).toInt(), "Verdroid needs your OK",
            "${req.summary}: ${req.details.joinToString("; ").take(200)}${title?.let { " (chat: $it)" } ?: ""}", req.taskId)
        return try { withTimeoutOrNull(TIMEOUT_MS) { p.answer.await() } ?: false }
        finally { _pending.update { l -> l.filterNot { it === p } } }
    }

    /** Overwrite-only requests: allow now and stop asking (globally, or for this chat). Ignored for anything else. */
    fun allowOverwrites(id: Long, thisChatOnly: Boolean) {
        val p = _pending.value.firstOrNull { it.req.id == id } ?: return
        if (!p.req.overwriteOnly) return
        if (thisChatOnly) overwrite.allowChat(p.req.taskId) else overwrite.setAlways(true)
        p.answer.complete(true)
    }

    /** Requests whose deletes/moves/renames are all proven inside Documents/Verdroid: allow now and stop asking for those. */
    fun allowSharedDeletes(id: Long, thisChatOnly: Boolean) {
        val p = _pending.value.firstOrNull { it.req.id == id } ?: return
        if (!p.req.sharedDeletesOnly) return
        if (thisChatOnly) deletes.allowChat(p.req.taskId) else deletes.setAlways(true)
        p.answer.complete(true)
    }

    fun answer(id: Long, allow: Boolean) { _pending.value.firstOrNull { it.req.id == id }?.answer?.complete(allow) }

    companion object {
        const val TIMEOUT_MS = 10 * 60_000L
        private const val NOTIFY_BASE = 7300
    }
}
