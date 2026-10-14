package com.verdroid.app.ui.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.verdroid.app.domain.share.ShareLogic
import com.verdroid.app.domain.usecase.StartConversationUseCase
import com.verdroid.app.ui.theme.VerdroidTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * "Share to Verdroid" (ACTION_SEND / SEND_MULTIPLE; text and documents, never images). Always starts a NEW chat; the user picks
 * optionally a quick action that prefills the message, edits it and taps Send. Files are copied into
 * Documents/Verdroid/Input exactly like the chat "+" attach ([ChatAttachment]); All files access is asked for if missing.
 */
@AndroidEntryPoint
class ShareActivity : ComponentActivity() {
    @Inject lateinit var sharedFolder: SharedFolder
    @Inject lateinit var startConversation: StartConversationUseCase
    @Inject lateinit var appPrefs: com.verdroid.app.data.prefs.AppPrefs

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
        val mode = ShareLogic.effectiveOpenMode(appPrefs.shareOpenMode.value, android.provider.Settings.canDrawOverlays(this))
        val compact = mode != ShareLogic.OpenMode.FULL_APP
        setContent {
            VerdroidTheme {
                var draft by remember { mutableStateOf(com.verdroid.app.domain.share.ShareDraft.initial(text, files.isNotEmpty())) }
                val msg = draft.text
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                val needsAccess = files.isNotEmpty() && !hasAccess.value
                // Full app: opaque full-screen sheet. Floating/Bubble: compact card over the (visible) source app; tap outside = Cancel.
                Box(Modifier.fillMaxSize().then(if (compact) Modifier.background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f))
                    .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { finish() } else Modifier)
                    .systemBarsPadding().imePadding(), contentAlignment = Alignment.BottomCenter) {
                Surface(if (compact) Modifier.fillMaxWidth().padding(8.dp).clickable(enabled = false) {} else Modifier.fillMaxSize(),
                    shape = if (compact) MaterialTheme.shapes.extraLarge else androidx.compose.ui.graphics.RectangleShape, tonalElevation = if (compact) 6.dp else 0.dp) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Share to Verdroid", style = MaterialTheme.typography.titleLarge)
                        if (files.isNotEmpty()) Text("📎 " + files.joinToString { it.name }, style = MaterialTheme.typography.bodyMedium)
                        if (files.isEmpty() && text.isBlank()) Text("Nothing Verdroid can use was shared (images are not supported).",
                            color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ShareLogic.QuickAction.entries.forEach { a ->
                                FilterChip(selected = draft.selected == a, onClick = { draft = draft.select(a) }, label = { Text(a.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary))
                            }
                        }
                        OutlinedTextField(msg, { draft = draft.edit(it) }, Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 220.dp),
                            label = { Text("Message") })
                        Text("Sharing always starts a new chat.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!compact) Spacer(Modifier.weight(1f))
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
                                    runCatching { send(files, msg) }
                                        // Open the new chat in Verdroid's own task (warm: onNewIntent; cold: onCreate) and drop the sheet so Back skips it.
                                        // Floating/Bubble: stay over the source app; the chat head service shows the chat (panel or head + badge).
                                        .onSuccess { id -> openChat(mode, id); finish() }
                                        .onFailure { error = it.message ?: "Could not share"; busy = false }
                                }
                            }) { Text(if (busy) "Sending…" else "Send") }
                        }
                    }
                }
                }
            }
        }
    }

    private fun openChat(mode: ShareLogic.OpenMode, id: Long) {
        if (mode == ShareLogic.OpenMode.FULL_APP) { startActivity(MainActivity.openTaskIntent(this, id)); return }
        val i = com.verdroid.app.chathead.ChatHeadService.startIntent(this, id)
            .putExtra(com.verdroid.app.chathead.ChatHeadService.EXTRA_EXPAND, mode == ShareLogic.OpenMode.FLOATING)
        runCatching { androidx.core.content.ContextCompat.startForegroundService(this, i) }
            .onFailure { startActivity(MainActivity.openTaskIntent(this, id)) }
    }

    /** Sharing always creates a NEW chat (no picker, no Direct Share into existing chats). */
    private suspend fun send(files: List<Shared>, text: String): Long {
        val saved = withContext(Dispatchers.IO) { files.map { ChatAttachment.saveToInput(this@ShareActivity, sharedFolder, it.uri) } }
        val body = ShareLogic.message(saved.map(ChatAttachment::messagePrefix), text)
        return startConversation(body)
    }

    /** Every shared file: EXTRA_STREAM (single or list) plus ClipData items, de-duplicated, order kept. */
    private fun streams(i: Intent): List<Uri> {
        val extra = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        val clip = i.clipData?.let { c -> (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri } }.orEmpty()
        return ShareLogic.mergeStreams(extra, clip)
    }

    private fun displayName(u: Uri): String? = runCatching {
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
}
