package com.verdroid.app.chathead

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdroid.app.MainActivity
import com.verdroid.app.ui.chat.ChatViewModel
import com.verdroid.app.ui.chathead.CompactChatPanel
import com.verdroid.app.ui.theme.VerdroidTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Expanded content of an Android bubble (embedded activity; see manifest: allowEmbedded,
 * resizeableActivity, documentLaunchMode=always). The "taskId" extra feeds ChatViewModel's
 * SavedStateHandle through the activity's default view-model arguments.
 */
@AndroidEntryPoint
class BubbleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, 0L)
        setContent {
            VerdroidTheme {
                com.verdroid.app.ui.consent.FileChangeDialog()
                com.verdroid.app.ui.consent.XPostDialog()
                com.verdroid.app.ui.consent.InstallDialog()
                com.verdroid.app.ui.schedule.ScheduleHealthDialog()
                val vm: ChatViewModel = hiltViewModel()
                val task by vm.task.collectAsStateWithLifecycle()
                val messages by vm.messages.collectAsStateWithLifecycle()
                val toolCalls by vm.toolCalls.collectAsStateWithLifecycle()
                val generating by vm.isGenerating.collectAsStateWithLifecycle()
                val window by vm.window.collectAsStateWithLifecycle()
                CompactChatPanel(
                    task = task,
                    messages = messages,
                    toolCalls = toolCalls,
                    generating = generating,
                    onSend = vm::send,
                    onStop = vm::stop,
                    onResume = vm::resume,
                    onOpenFull = { startActivity(MainActivity.openTaskIntent(this, taskId)) },
                    onCollapse = null,
                    onSeen = vm::markOpened,
                    canLoadEarlier = com.verdroid.app.domain.model.ChatWindow.hasMore(messages.size, window),
                    onLoadEarlier = vm::loadEarlier,
                )
            }
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "taskId"

        fun intent(context: Context, taskId: Long): Intent =
            Intent(context, BubbleActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("verdroid://task/$taskId"))
                .putExtra(EXTRA_TASK_ID, taskId)
    }
}
