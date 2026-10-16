package com.verdroid.app.data.consent

import com.verdroid.app.agent.tools.InstallConfirmer
import com.verdroid.app.agent.tools.PackageSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Install / Don't install for agent-driven package installs (pip/apt/… in Termux). Like [FileChangeConsent], the tool
 * call suspends on the dialog (main app or bubble; a notification brings the user there). No answer within [TIMEOUT_MS]
 * returns null, and the tool falls back to the old ask-in-chat flow (confirm_install + install_id).
 */
@Singleton
class InstallPrompt @Inject constructor(
    private val notifier: com.verdroid.app.data.notify.AlertNotifier,
) : InstallConfirmer {
    data class Pending(val id: Long, val tool: String, val packages: List<PackageSpec>, val reason: String, val estimate: String,
                       internal val answer: CompletableDeferred<Boolean>)

    private val _pending = MutableStateFlow<List<Pending>>(emptyList())
    val pending: StateFlow<List<Pending>> = _pending.asStateFlow()
    private var nextId = 1L

    override suspend fun confirm(tool: String, packages: List<PackageSpec>, reason: String, estimate: String): Boolean? {
        val p = Pending(synchronized(this) { nextId++ }, tool, packages, reason, estimate, CompletableDeferred())
        _pending.update { it + p }
        notifier.notify(NOTIFY_BASE + (p.id % 100).toInt(), "Verdroid wants to install packages",
            "$tool: ${com.verdroid.app.agent.tools.InstallConsent.describe(packages)}".take(200), 0L)
        return try { withTimeoutOrNull(TIMEOUT_MS) { p.answer.await() } } finally { _pending.update { l -> l.filterNot { it === p } } }
    }

    fun answer(id: Long, install: Boolean) { _pending.value.firstOrNull { it.id == id }?.answer?.complete(install) }

    companion object {
        const val TIMEOUT_MS = 10 * 60_000L
        private const val NOTIFY_BASE = 7550
    }
}
