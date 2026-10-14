package com.verdroid.app.ui.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.verdroid.app.MainActivity
import com.verdroid.app.data.storage.ChatAttachment
import com.verdroid.app.data.storage.SharedFolder
import com.verdroid.app.domain.model.AttachmentText
import com.verdroid.app.domain.model.Conversation
import com.verdroid.app.domain.repository.TaskRepository
import com.verdroid.app.domain.share.ShareLogic
import com.verdroid.app.domain.usecase.SendMessageUseCase
import com.verdroid.app.domain.usecase.StartConversationUseCase
import com.verdroid.app.ui.theme.VerdroidTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * "Share to Verdroid" (ACTION_SEND / SEND_MULTIPLE; text and documents, never images). The user picks New chat or an
 * existing chat, optionally a quick action that prefills the message, edits it and taps Send. Files are copied into
 * Documents/Verdroid/Input exactly like the chat "+" attach ([ChatAttachment]); All files access is asked for if missing.
 */
@AndroidEntryPoint
class ShareActivity : ComponentActivity() {
    @Inject lateinit var tasks: TaskRepository
    @Inject lateinit var sharedFolder: SharedFolder
    @Inject lateinit var startConversation: StartConversationUseCase
    @Inject lateinit var sendMessage: SendMessageUseCase

    private data class Shared(val uri: Uri, val name: String)

    private val hasAccess = mutableStateOf(false)

    override fun onResume() { super.onResume(); hasAccess.value = sharedFolder.hasAccess() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = ShareLogic.sharedText(intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT))
        val files = streams(intent).mapNotNull { u ->
            val name = displayName(u)
            if (ShareLogic.acceptsFile(runCatching { contentResolver.getType(u) }.getOrNull(), name)) Shared(u, name ?: ChatAttachment.fallbackName(u.lastPathSegment)) else null
        }
        val preTarget = intent.getStringExtra(androidx.core.content.pm.ShortcutManagerCompat.EXTRA_SHORTCUT_ID)
            ?.removePrefix("task_")?.toLongOrNull()
        setContent {
            VerdroidTheme {
                var chats by remember { mutableStateOf<List<Conversation>>(emptyList()) }
                LaunchedEffect(Unit) { chats = runCatching { tasks.observeConversations().first() }.getOrDefault(emptyList()) }
                var target by remember { mutableStateOf(preTarget ?: 0L) }
                var msg by remember { mutableStateOf(text) }
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                val needsAccess = files.isNotEmpty() && !hasAccess.value
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Share to Verdroid", style = MaterialTheme.typography.titleLarge)
                        if (files.isNotEmpty()) Text("📎 " + files.joinToString { it.name }, style = MaterialTheme.typography.bodyMedium)
                        if (files.isEmpty() && text.isBlank()) Text("Nothing Verdroid can use was shared (images are not supported).",
                            color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ShareLogic.QuickAction.entries.forEach { a ->
                                AssistChip(onClick = { msg = ShareLogic.prefill(a, text, files.isNotEmpty()) }, label = { Text(a.label) })
                            }
                        }
                        OutlinedTextField(msg, { msg = it }, Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 220.dp),
                            label = { Text("Message") })
                        Text("Send to", style = MaterialTheme.typography.labelLarge)
                        LazyColumn(Modifier.weight(1f)) {
                            item { TargetRow("New chat", target == 0L) { target = 0L } }
                            items(chats, key = { it.task.id }) { c ->
                                TargetRow(AttachmentText.cleanTitle(c.task.title, c.task.prompt), target == c.task.id) { target = c.task.id }
                            }
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (needsAccess) {
                            Text("Shared files go to Documents/Verdroid/Input, which needs All files access.",
                                style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { startActivity(SharedFolder.accessIntent(this@ShareActivity)) }) { Text("Grant All files access") }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { finish() }) { Text("Cancel") }
                            Button(enabled = !busy && !needsAccess && ShareLogic.canSend(files.size, msg), onClick = {
                                busy = true; error = null
                                lifecycleScope.launch {
                                    runCatching { send(files, msg, target) }
                                        .onSuccess { id -> startActivity(MainActivity.openTaskIntent(this@ShareActivity, id)); finish() }
                                        .onFailure { error = it.message ?: "Could not share"; busy = false }
                                }
                            }) { Text(if (busy) "Sending…" else "Send") }
                        }
                    }
                }
            }
        }
    }

    private suspend fun send(files: List<Shared>, text: String, target: Long): Long {
        val saved = withContext(Dispatchers.IO) { files.map { ChatAttachment.saveToInput(this@ShareActivity, sharedFolder, it.uri) } }
        val body = ShareLogic.message(saved.map(ChatAttachment::messagePrefix), text)
        return if (target == 0L) startConversation(body) else { sendMessage(target, body); target }
    }

    private fun streams(i: Intent): List<Uri> = when (i.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }

    private fun displayName(u: Uri): String? = runCatching {
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
}

@Composable
private fun TargetRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Text(title, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
    }
}
