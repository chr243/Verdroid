package com.verdroid.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.verdroid.app.domain.repository.QuotaRepository
import com.verdroid.app.ui.VerdroidRoot
import com.verdroid.app.ui.theme.VerdroidTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.flow.first

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var quota: QuotaRepository
    @Inject lateinit var appPrefs: com.verdroid.app.data.prefs.AppPrefs
    @Inject lateinit var chatHeads: com.verdroid.app.chathead.ChatHeadController
    @Inject lateinit var updater: com.verdroid.app.data.update.AppUpdater
    @Inject lateinit var taskRepo: com.verdroid.app.domain.repository.TaskRepository
    @Inject lateinit var agent: com.verdroid.app.domain.repository.AgentController

    // v0.9.8 "Auto chat head on Home": onUserLeaveHint (Home/Recents) marks a leave; onStop (really in the background,
    // not rotation, not a translucent permission dialog) opens the head. Our own startActivity calls (settings,
    // permission screens) are recorded so they never trigger it.
    private var leaveHintAt = 0L
    private var ownLaunchAt = 0L
    private var autoHeadTaskId: Long? = null

    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        ownLaunchAt = android.os.SystemClock.elapsedRealtime()
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - ownLaunchAt > OWN_LAUNCH_GRACE_MS) leaveHintAt = now
    }

    override fun onStop() {
        super.onStop()
        val now = android.os.SystemClock.elapsedRealtime()
        val hinted = now - leaveHintAt < LEAVE_HINT_WINDOW_MS && now - ownLaunchAt > OWN_LAUNCH_GRACE_MS
        leaveHintAt = 0L
        val taskId = com.verdroid.app.chathead.VisibleChat.taskId
        if (isChangingConfigurations) return
        if (hinted && taskId != null && appPrefs.autoChatHeadOnHome.value && chatHeads.canDrawOverlays()) {
            runCatching { chatHeads.startOverlay(taskId) }.onSuccess { autoHeadTaskId = taskId }
            return
        }
        restoreShareHead()
    }

    /** Bring a share-spawned head back only while its chat has an unread or running reply; otherwise forget it. */
    private fun restoreShareHead() {
        val id = com.verdroid.app.chathead.ShareHead.taskId ?: return
        val running = id in agent.runningTaskIds.value
        lifecycleScope.launch {
            val unread = runCatching { taskRepo.observeConversations().first().firstOrNull { it.task.id == id }?.unreadCount ?: 0 }.getOrDefault(0)
            if (com.verdroid.app.domain.share.ShareLogic.restoreShareHead(unread, running, chatHeads.canDrawOverlays()))
                runCatching { chatHeads.startOverlay(id) }
            else com.verdroid.app.chathead.ShareHead.taskId = null
        }
    }

    override fun onStart() {
        super.onStart()
        // Back in the app: remove the head we opened automatically when the user is on that chat (or any route).
        // A share-spawned head/floating window hides while the app is in front (opening its chat marks it read).
        if (autoHeadTaskId != null || com.verdroid.app.chathead.ShareHead.taskId != null) {
            autoHeadTaskId = null
            runCatching { stopService(Intent(this, com.verdroid.app.chathead.ChatHeadService::class.java)) }
        }
    }

    /** Task to open from a shortcut / bubble / chat-head "Open" action. */
    private val openTaskId = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Silent update check (at most every 6 h; the gear and Settings > App update show a dot when one exists).
        if (savedInstanceState == null) lifecycleScope.launch { if (updater.enabled.value) runCatching { updater.check(silent = true) } } // quiet offline; feeds the start-up panel
        enableEdgeToEdge()
        if (savedInstanceState == null) openTaskId.value = taskIdFrom(intent)
        addOnNewIntentListener { newIntent -> taskIdFrom(newIntent)?.let { openTaskId.value = it } }
        // Poll GET /key on start and every 5 minutes while in the foreground.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    quota.refresh()
                    delay(FOREGROUND_POLL_MS)
                }
            }
        }
        setContent {
            VerdroidTheme {
                VerdroidRoot(openTaskId = openTaskId.value, onOpenTaskConsumed = { openTaskId.value = null })
                com.verdroid.app.ui.consent.FileChangeDialog()
                com.verdroid.app.ui.consent.XPostDialog()
            }
        }
    }

    companion object {
        private const val FOREGROUND_POLL_MS = 5 * 60_000L
        private const val OWN_LAUNCH_GRACE_MS = 1_500L
        private const val LEAVE_HINT_WINDOW_MS = 3_000L
        const val EXTRA_TASK_ID = "taskId"

        private fun taskIdFrom(intent: Intent?): Long? =
            intent?.getLongExtra(EXTRA_TASK_ID, 0L)?.takeIf { it != 0L }

        fun openTaskIntent(context: Context, taskId: Long): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(EXTRA_TASK_ID, taskId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
