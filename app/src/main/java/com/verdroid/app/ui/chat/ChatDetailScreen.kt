package com.verdroid.app.ui.chat

import androidx.compose.ui.graphics.graphicsLayer
import com.verdroid.app.ui.chatContainer
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdroid.app.chathead.ChatHeadResult
import com.verdroid.app.domain.model.*
import com.verdroid.app.ui.chathead.OverlayPermissionDialog
import com.verdroid.app.ui.components.*
import com.verdroid.app.ui.theme.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(onBack: () -> Unit, onChatMemory: (Long) -> Unit = {}, vm: ChatViewModel = hiltViewModel()) {
    val task by vm.task.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val toolCalls by vm.toolCalls.collectAsStateWithLifecycle()
    val generating by vm.isGenerating.collectAsStateWithLifecycle()
    val chatId by vm.currentTaskId.collectAsStateWithLifecycle()
    val window by vm.window.collectAsStateWithLifecycle()
    LaunchedEffect(chatId, messages.size, toolCalls.size) {
        if (chatId != 0L) com.verdroid.app.data.crash.CrashBreadcrumbs.openChat(chatId, "chat screen", messages.size, toolCalls.size)
    }
    DisposableEffect(chatId) { onDispose { com.verdroid.app.data.crash.CrashBreadcrumbs.closeChat(chatId) } }
    // Tell MainActivity (auto chat head on Home) which chat is on screen. Keyed on the REAL id, which changes from 0
    // to the new task id on a fresh chat's first send (the nav argument would stay 0).
    DisposableEffect(chatId) {
        val visible = chatId.takeIf { it != 0L }
        com.verdroid.app.chathead.VisibleChat.taskId = visible
        onDispose { if (com.verdroid.app.chathead.VisibleChat.taskId == visible) com.verdroid.app.chathead.VisibleChat.taskId = null }
    }
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val callsByMessage = remember(toolCalls) { toolCalls.groupBy { it.messageId } }
    val visible = remember(messages) {
        messages.filter { it.role != MessageRole.TOOL && !(it.role == MessageRole.SYSTEM && it.kind == MessageKind.NORMAL) }
    }
    // Consecutive termux_run (& co.) calls fold into one expandable row.
    val rows = remember(visible, callsByMessage) { ToolStacks.rows(visible, callsByMessage) }
    val blocks = remember(rows, generating) { ToolStacks.blocks(rows, generating) }

    // ---- chat head / bubble launch flow (POST_NOTIFICATIONS on 13+, overlay permission dialog)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showOverlayDialog by remember { mutableStateOf(false) }
    var awaitingOverlayPermission by remember { mutableStateOf(false) }
    val launchChatHead: () -> Unit = {
        scope.launch {
            when (vm.openChatHead()) {
                ChatHeadResult.NEEDS_OVERLAY_PERMISSION -> showOverlayDialog = true
                ChatHeadResult.BUBBLES_BLOCKED -> {
                    Toast.makeText(context, "Bubbles are off for Verdroid. Enable them, or pick Overlay in Settings → Chat heads.", Toast.LENGTH_LONG).show()
                    context.startActivity(vm.chatHeads.bubbleSettingsIntent())
                }
                ChatHeadResult.NOTIFICATIONS_BLOCKED ->
                    Toast.makeText(context, "Notifications are blocked, and bubbles need them. Allow notifications or pick Overlay in Settings → Chat heads.", Toast.LENGTH_LONG).show()
                ChatHeadResult.OVERLAY_STARTED ->
                    Toast.makeText(context, "Chat head is floating. Switch apps to keep chatting.", Toast.LENGTH_SHORT).show()
                ChatHeadResult.BUBBLE_SHOWN, null -> Unit
            }
            Unit
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        launchChatHead()
    }
    val onChatHeadClick: () -> Unit = {
        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else launchChatHead()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (awaitingOverlayPermission && vm.chatHeads.canDrawOverlays()) {
            awaitingOverlayPermission = false
            launchChatHead()
        }
    }
    if (showOverlayDialog) {
        OverlayPermissionDialog(
            onOpenSettings = {
                showOverlayDialog = false
                awaitingOverlayPermission = true
                context.startActivity(vm.chatHeads.overlayPermissionIntent())
            },
            onDismiss = { showOverlayDialog = false },
        )
    }

    // Hold DB writes until the container transform settles so nothing re-emits mid-morph.
    val transitioning = com.verdroid.app.ui.navTransitionRunning()
    // Latches true once the enter transition settles (and stays true so the close morph keeps the content).
    var contentReady by remember { mutableStateOf(!transitioning) }
    LaunchedEffect(transitioning) { if (!transitioning) contentReady = true }
    val listAlpha by androidx.compose.animation.core.animateFloatAsState(
        if (contentReady) 1f else 0f, androidx.compose.animation.core.tween(120), label = "chatListFade")
    // v1.0.60: the message list is windowed, so its size stops changing in long chats and new replies were never marked
    // seen (the chat head kept its unread badge). Mark seen whenever this chat is on screen (resumed) and has unread
    // replies, and on every resume, keyed on the unread count itself.
    val unread by vm.unread.collectAsStateWithLifecycle()
    val resumed = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState().value
        .isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(messages.lastOrNull()?.id, messages.size, unread, resumed, transitioning) {
        if (com.verdroid.app.domain.model.ChatSeen.shouldMark(resumed, transitioning, unread, messages.isNotEmpty())) vm.markOpened()
    }
    // Only follow new output when already near the bottom (or the user just sent something); reading older messages
    // while a task runs is no longer interrupted.
    val follow = rememberFollowBottom(listState)
    var openedAtBottom by remember { mutableStateOf(false) }
    val newestId = visible.lastOrNull()?.id
    val newestIsUser = visible.lastOrNull()?.role == MessageRole.USER
    val doneCalls = toolCalls.count { it.status != ToolCallStatus.PENDING }
    LaunchedEffect(blocks.size, newestId, toolCalls.size, doneCalls, generating, contentReady) {
        if (!contentReady || visible.isEmpty()) return@LaunchedEffect
        if (newestIsUser) follow.value = true
        if (follow.value) {
            val last = if (generating) blocks.size else blocks.lastIndex.coerceAtLeast(0)
            // Opening a chat: jump straight to the newest message (no animated trip from the top).
            if (openedAtBottom) listState.animateScrollToItem(last)
            listState.scrollToItem(last, ChatScroll.TO_END) // tall last item: settle at its bottom, not its top
            openedAtBottom = true
        }
    }

    Scaffold(
        modifier = Modifier.chatContainer(chatId),
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val t = task
                        t?.let { TaskAvatar(it, size = 36.dp) } ?: TaskAvatar(TaskType.CHAT, null, size = 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(t?.title ?: "New conversation", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            val pin by vm.modelPin.collectAsStateWithLifecycle()
                            val sub = listOfNotNull(pin?.let { "📌 " + com.verdroid.app.data.network.ModelIds.label(it.model) + if (it.allowFallback) " (+fallback)" else "" }, t?.subtitle)
                                .joinToString(" · ")
                            if (sub.isNotEmpty()) Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            // Profiles: tap the profile name → quick picker (only once a 2nd profile exists).
                            val profiles by vm.profiles.collectAsStateWithLifecycle()
                            val profile by vm.chatProfile.collectAsStateWithLifecycle()
                            if (com.verdroid.app.data.profiles.Profiles.visible(profiles)) {
                                var pick by remember { mutableStateOf(false) }
                                Text(profile.label + " ▾", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { pick = true }.padding(vertical = 2.dp))
                                if (pick) com.verdroid.app.ui.profiles.ProfilePickerDialog(profiles, profile.id,
                                    onPick = { vm.setProfile(it); pick = false }) { pick = false }
                            }
                        }
                    }
                },
                actions = {
                    // Shown on brand-new chats too (task == null until the first send); presets picked there are kept.
                    run {
                        // No chat-head button (v0.9.18): the chat head starts automatically; settings in Settings > Chat heads.
                        var overflow by remember { mutableStateOf(false) }
                        var showPresets by remember { mutableStateOf(false) }
                        var showModel by remember { mutableStateOf(false) }
                        if (showModel) {
                            val pin by vm.modelPin.collectAsStateWithLifecycle()
                            val global by vm.globalModels.collectAsStateWithLifecycle()
                            ChatModelDialog(pin, global, onPick = { vm.setModelPin(it); showModel = false }, onUpdate = { vm.setModelPin(it) }, onDismiss = { showModel = false })
                        }
                        if (showPresets) {
                            val off by vm.presetOverrides.collectAsStateWithLifecycle()
                            val global by vm.presetsGlobal.collectAsStateWithLifecycle()
                            var tosPending by remember { mutableStateOf<(() -> Unit)?>(null) }
                            val tw = com.verdroid.app.data.tools.ToolPreset.TWITTER
                            val twOn = com.verdroid.app.data.tools.ToolPreset.shownOn(tw, off, global)
                            ToolPresetsDialog(off, global,
                                { p, on -> if (p == tw && com.verdroid.app.data.tools.ToolGroups.needsTosWarning("twitter", twOn, on)) tosPending = { vm.setPreset(p, true) } else vm.setPreset(p, on) },
                                { on ->
                                    if (com.verdroid.app.data.tools.ToolGroups.needsTosWarning("twitter", twOn, on)) {
                                        // Everything else on now; Twitter only after the warning.
                                        vm.setAllPresets(true); vm.setPreset(tw, false)
                                        tosPending = { vm.setPreset(tw, true) }
                                    } else vm.setAllPresets(on)
                                }, { showPresets = false }, profileLabel = vm.chatProfile.collectAsStateWithLifecycle().value.label,
                                onReset = { vm.resetPresets() }, profilesOn = vm.profilesEnabled.collectAsStateWithLifecycle().value)
                            tosPending?.let { act -> com.verdroid.app.ui.consent.TwitterTosDialog(onConfirm = { act(); tosPending = null }, onCancel = { tosPending = null }) }
                        }
                        Box {
                            IconButton(onClick = { overflow = true }) {
                                Text("⋮", fontSize = 22.sp, modifier = Modifier.semantics { contentDescription = "More options" })
                            }
                            DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                                DropdownMenuItem(text = { Text("Chat memory (short-term)") }, onClick = {
                                    overflow = false
                                    val id = task?.id
                                    if (id != null) onChatMemory(id)
                                    else Toast.makeText(context, "Chat memory is empty until the first message.", Toast.LENGTH_SHORT).show()
                                })
                                DropdownMenuItem(text = { Text("Model") }, onClick = {
                                    overflow = false; showModel = true
                                })
                                DropdownMenuItem(text = { Text("Tool presets") }, onClick = {
                                    overflow = false; showPresets = true
                                })
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            // Open morph: only a light shell (bars + background) rides the container transform; the message list
            // composes after the nav transition settles, then fades in fast (v1.0.39).
            Box(Modifier.weight(1f).fillMaxWidth()) {
            if (contentReady)
            LazyColumn(Modifier.fillMaxSize().graphicsLayer { alpha = listAlpha }, state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (visible.isEmpty()) {
                    item {
                        Text("Describe a task or ask anything.\nVerdroid can read, write and list files in its sandboxed workspace.",
                            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 48.dp))
                    }
                }
                if (com.verdroid.app.domain.model.ChatWindow.hasMore(messages.size, window)) item("load-earlier") {
                    // Reaching the top loads the previous page; keys keep the scroll position anchored.
                    LaunchedEffect(window, follow.value) { if (!follow.value) vm.loadEarlier() } // only after the user scrolled up
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        TextButton(onClick = vm::loadEarlier) { Text("Load earlier messages") }
                    }
                }
                items(blocks, key = { it.key }) { block ->
                    if (block is ToolStacks.Steps) { StepsCard(block) { t, model -> AgentBubble(t, model) }; return@items }
                    val row = (block as ToolStacks.Single).row
                    val m = row.message
                    when {
                        m.kind == MessageKind.STATUS -> StatusLine(m)
                        m.kind == MessageKind.SUMMARY -> SummaryCard(m)
                        com.verdroid.app.domain.schedule.ScheduledLabel.isScheduledPrompt(m) -> com.verdroid.app.ui.chat.ScheduledDivider(m)
                        m.role == MessageRole.USER -> UserBubble(m.content.orEmpty())
                        m.role == MessageRole.ASSISTANT -> AssistantRowContent(row) { t, model -> AgentBubble(t, model) }
                    }
                }
                if (generating) item("typing") {
                    Text("● ${task?.subtitle ?: "Thinking..."}", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
                }
            }
            }
            val status = task?.status
            if (task?.archived == true) {
                // v1.0.12: archived chats are read-only; restore to continue.
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Archived chat (read-only)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FilledTonalButton(onClick = vm::restore) { Text("Restore") }
                    }
                }
            } else if (generating) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    OutlinedButton(onClick = vm::stop, modifier = Modifier.padding(4.dp)) {
                        Box(Modifier.size(12.dp).background(MaterialTheme.colorScheme.error, RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(8.dp)); Text("Stop Generation")
                    }
                }
            } else if (status in setOf(TaskStatus.PAUSED, TaskStatus.RATE_LIMITED, TaskStatus.QUEUED, TaskStatus.FAILED, TaskStatus.CANCELLED)) {
                val now = rememberNow()
                val resumeAt = task?.resumeAt
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (resumeAt != null && resumeAt > now && status != TaskStatus.CANCELLED && status != TaskStatus.FAILED) {
                        Text("Auto-resume in ${formatDuration(resumeAt - now)}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(4.dp)) {
                        FilledTonalButton(onClick = vm::forceRetry) {
                            Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp))
                            Text(if (resumeAt != null && resumeAt > now && status != TaskStatus.FAILED && status != TaskStatus.CANCELLED) "Force retry now" else "Continue")
                        }
                        if (status == TaskStatus.PAUSED || status == TaskStatus.RATE_LIMITED || status == TaskStatus.QUEUED) {
                            OutlinedButton(onClick = vm::cancelTask) { Text("Cancel") }
                        }
                    }
                }
            }
            if (task?.archived != true) {
                val pending by vm.pendingAttach.collectAsStateWithLifecycle()
                val attachErr by vm.attachError.collectAsStateWithLifecycle()
                val attachPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let(vm::attach)
                }
                val attachAccess by vm.attachNeedsAccess.collectAsStateWithLifecycle()
                val attachCtx = androidx.compose.ui.platform.LocalContext.current
                val accessLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { _ ->
                    vm.retryAttachAfterAccess()
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    pending?.let { p ->
                        AssistChip(
                            onClick = { },
                            label = { Text(p.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = if (com.verdroid.app.domain.model.AttachmentText.isImageName(p.displayName)) {
                                { AttachmentThumb(p.relativePath, 24.dp) }
                            } else null,
                            trailingIcon = {
                                IconButton(onClick = vm::clearPendingAttach, modifier = Modifier.size(18.dp)) {
                                    Icon(Icons.Filled.Close, "Remove attachment")
                                }
                            },
                        )
                    }
                    attachErr?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Row {
                            if (attachAccess != null) TextButton(onClick = {
                                runCatching { accessLauncher.launch(com.verdroid.app.data.storage.SharedFolder.accessIntent(attachCtx)) }
                            }) { Text("Grant access") }
                            TextButton(onClick = vm::clearAttachError) { Text("Dismiss") }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { attachPicker.launch(arrayOf("*/*")) }, enabled = !generating) {
                            Icon(Icons.Filled.Add, "Attach file")
                        }
                        OutlinedTextField(
                            value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                            placeholder = { Text(if (task == null) "Describe a new task…" else "Message Verdroid…") },
                            shape = RoundedCornerShape(24.dp), maxLines = 5,
                        )
                        Spacer(Modifier.width(6.dp))
                        FilledIconButton(
                            onClick = { vm.send(input); input = "" },
                            enabled = (input.isNotBlank() || pending != null) && !generating,
                            modifier = Modifier.size(48.dp),
                        ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
                    }
                }
            }
        }
    }
}

/** Per-chat tool presets: one per Settings → Tools group (memory/skills, charts & other and MCP excluded), plus All. Everything is on by default. */
@Composable
internal fun ToolPresetsDialog(
    off: Map<com.verdroid.app.data.tools.ToolPreset, Boolean>,
    global: Map<com.verdroid.app.data.tools.ToolPreset, com.verdroid.app.data.tools.ToolPreset.Companion.Global>,
    onSet: (com.verdroid.app.data.tools.ToolPreset, Boolean) -> Unit,
    onSetAll: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    profileLabel: String = "",
    onReset: () -> Unit = {},
    /** Profiles off (ProfileStore.enabled=false): no profile label; the reset button reads "Reset to Settings". */
    profilesOn: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = onReset, enabled = off.isNotEmpty()) { Text(toolPresetsResetLabel(profilesOn)) } },
        title = { Text("Tool presets") },
        text = { ToolPresetsContent(off, global, onSet, onSetAll, if (profilesOn) profileLabel else "", profilesOn) },
    )
}

/** Body of the Tool presets dialog (separate so screenshot tests can render it without a dialog window). */
@Composable
internal fun ToolPresetsContent(
    off: Map<com.verdroid.app.data.tools.ToolPreset, Boolean>,
    global: Map<com.verdroid.app.data.tools.ToolPreset, com.verdroid.app.data.tools.ToolPreset.Companion.Global>,
    onSet: (com.verdroid.app.data.tools.ToolPreset, Boolean) -> Unit,
    onSetAll: (Boolean) -> Unit,
    profileLabel: String,
    profilesOn: Boolean = true,
) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (profilesOn && profileLabel.isNotBlank()) Text("From profile: $profileLabel", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Text(toolPresetsIntro(profilesOn), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                PresetRow("All", "Every tool category", com.verdroid.app.data.tools.ToolPreset.allOn(off, global)) { onSetAll(it) }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                com.verdroid.app.data.tools.ToolPreset.entries.forEach { p ->
                    // Effective state: the chat override if set, else Settings → Tools.
                    PresetRow(p.label, p.description, com.verdroid.app.data.tools.ToolPreset.shownOn(p, off, global)) { onSet(p, it) }
                }
            }
}

@Composable
private fun PresetRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked, onChange)
    }
}

@Composable
internal fun UserBubble(text: String) {
    // The stored message keeps the model-facing "Attached file: Input/… use workspace_*…" line; the UI shows a file chip.
    val d = remember(text) { com.verdroid.app.data.storage.ChatAttachment.forDisplay(text) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val b = com.verdroid.app.ui.theme.LocalBubbleColors.current
        d.files.forEach { f -> AttachmentChip(f.name, f.bytes, f.path.takeIf { f.isImage }) } // one chip per file
        if (d.text.isNotBlank() || d.fileName == null) {
            Surface(color = b.user, shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp), modifier = Modifier.widthIn(max = 300.dp)) {
                SelectionContainer { Text(remember(d.text) { com.verdroid.app.ui.components.Links.linked(d.text.trim()) }, color = b.onUser, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) }
            }
        }
    }
}

@Composable
private fun AttachmentChip(name: String, bytes: Long?, imagePath: String?) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(14.dp), modifier = Modifier.widthIn(max = 300.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (imagePath != null) AttachmentThumb(imagePath, 48.dp) else Text("📎", fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                bytes?.let { Text(com.verdroid.app.data.storage.ChatAttachment.humanSize(it), style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
internal fun AgentBubble(text: String, model: String?) {
    val b = com.verdroid.app.ui.theme.LocalBubbleColors.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Surface(color = b.agent, shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
            modifier = Modifier.widthIn(max = 320.dp)) {
            SelectionContainer {
                MarkdownText(text, color = b.onAgent, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
            }
        }
        if (model != null) Text(model, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
    }
}

@Composable
internal fun StatusLine(m: ChatMessage) {
    val now = rememberNow()
    val text = buildString {
        append(m.content.orEmpty())
        m.resumeAt?.let { at ->
            if (at > now) append(" Resuming in ${formatDuration(at - now)}.") else append(" Reset time reached.")
        }
    }
    Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 24.dp))
}

/** Instead of a scheduled run's prompt bubble: a tiny centered, muted "⏰ Scheduled: <name> · 10:00". */
@Composable
internal fun ScheduledDivider(m: ChatMessage) {
    val label = remember(m.id) { com.verdroid.app.domain.schedule.ScheduledLabel.of(m) } ?: return
    Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, maxLines = 1,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp, start = 24.dp, end = 24.dp))
}

@Composable
internal fun SummaryCard(m: ChatMessage) {
    var expanded by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).clickable { expanded = !expanded }) {
        Column(Modifier.padding(10.dp)) {
            Text("📝 Earlier conversation summarized ${if (expanded) "▲" else "▼"}", style = MaterialTheme.typography.labelMedium)
            AnimatedVisibility(expanded) { Text(m.content.orEmpty(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}

private val prettyJson = Json { prettyPrint = true; isLenient = true }

private fun pretty(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return runCatching { prettyJson.encodeToString(JsonElement.serializer(), prettyJson.parseToJsonElement(raw)) }.getOrDefault(raw)
}

@Composable
internal fun ToolCallCard(call: ToolCallRecord) {
    var expanded by remember { mutableStateOf(false) }
    val sc = com.verdroid.app.ui.theme.LocalStatusColors.current
    val (icon, color) = when (call.status) {
        ToolCallStatus.PENDING -> "⏳" to sc.warn
        ToolCallStatus.SUCCESS -> "✅" to sc.ok
        ToolCallStatus.ERROR -> "⚠️" to sc.error
    }
    // Tool cards: surfaceContainer (errors: errorContainer) so they sit between the bubbles and the background.
    Surface(shape = RoundedCornerShape(12.dp),
        color = if (call.status == ToolCallStatus.ERROR) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (call.status == ToolCallStatus.ERROR) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().padding(end = 32.dp)) {
        Column(Modifier.clickable { expanded = !expanded }.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔧", fontSize = 14.sp); Spacer(Modifier.width(6.dp))
                Text(call.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (call.source == "fenced") { SmallBadge("json-fallback", MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.surface); Spacer(Modifier.width(6.dp)) }
                Text(icon, color = color)
                Spacer(Modifier.width(6.dp))
                Text(if (expanded) "▲" else "▼", fontSize = 12.sp)
            }
            if (call.name == com.verdroid.app.agent.subagents.SubAgentPolicy.TOOL_NAME) {
                val live by com.verdroid.app.agent.subagents.SubAgentLive.progress.collectAsState()
                val line = if (call.status == ToolCallStatus.PENDING) live[call.taskId]?.label ?: "Sub-agents starting…"
                    else remember(call.resultJson) { com.verdroid.app.agent.subagents.SpawnSubagentsTool.summary(call.resultJson) }
                line?.let { Text("🤖 $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)) }
                if (call.status != ToolCallStatus.PENDING) FailedWorkersList(remember(call.resultJson) {
                    com.verdroid.app.agent.subagents.SpawnSubagentsTool.failedWorkers(call.resultJson) })
            }
            if (call.name == com.verdroid.app.agent.research.ResearchPolicy.TOOL_NAME) {
                val live by com.verdroid.app.agent.research.ResearchLive.progress.collectAsState()
                val line = if (call.status == ToolCallStatus.PENDING) live[call.taskId]?.label ?: "🔎 Deep research starting…"
                    else remember(call.resultJson) { com.verdroid.app.agent.research.DeepResearchTool.summary(call.resultJson) }
                line?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)) }
                if (call.status != ToolCallStatus.PENDING) FailedWorkersList(remember(call.resultJson) {
                    com.verdroid.app.agent.research.DeepResearchTool.failedWorkers(call.resultJson) })
            }
            if (call.name == com.verdroid.app.agent.tools.ChartTool.NAME && call.status == ToolCallStatus.SUCCESS) {
                remember(call.resultJson) { com.verdroid.app.agent.tools.ChartSpecs.fromResult(call.resultJson) }
                    ?.let { (spec, png) -> com.verdroid.app.ui.chart.ChartCard(spec, png) }
            }
            if (call.name == com.verdroid.app.agent.tools.ShowImageTool.NAME && call.status == ToolCallStatus.SUCCESS) {
                remember(call.resultJson) { ShownImageRef.fromResult(call.resultJson) }?.let { ShownImageCard(it) }
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CodeBlock("arguments", pretty(call.argumentsJson), if (call.name == "run_shell") "json" else null)
                    CodeBlock("result", pretty(call.resultJson).ifEmpty { "…running" }, null)
                }
            }
        }
    }
}

/** "⚠️ 3 workers failed ▼" → tap to list each failed worker with its reason (separate from the card's own expand). */
@Composable
private fun FailedWorkersList(failed: List<Pair<String, String>>) {
    if (failed.isEmpty()) return
    var open by remember(failed) { mutableStateOf(false) }
    Column(Modifier.padding(top = 4.dp)) {
        Text("⚠️ ${failed.size} worker${if (failed.size == 1) "" else "s"} failed " + if (open) "▲" else "▼",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.clickable { open = !open })
        AnimatedVisibility(open) {
            SelectionContainer {
                Text(com.verdroid.app.agent.subagents.FailureText.cardLines(failed), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun CodeBlock(label: String, code: String, language: String?) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            com.verdroid.app.ui.components.CopyButton(code)
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState()).padding(8.dp)) {
            SelectionContainer {
                Text(CodeHighlighter.highlight(code.take(8000), language, com.verdroid.app.ui.components.CodeColors.of(MaterialTheme.colorScheme)), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** Thumbnail of an attached image under Documents/Verdroid ([relativePath] e.g. Input/photo.jpg); 🖼️ until/if it can't load. */
@Composable
internal fun AttachmentThumb(relativePath: String, size: androidx.compose.ui.unit.Dp) {
    var bmp by remember(relativePath) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(relativePath) {
        bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.verdroid.app.data.storage.AttachmentImages.thumbnail(
                    java.io.File(com.verdroid.app.data.storage.SharedFolder.DISPLAY_PATH, relativePath), 256)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) androidx.compose.foundation.Image(b, contentDescription = "Attached image",
            contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text("🖼️", fontSize = (size.value * 0.45f).sp)
    }
}

/**
 * Chat ⋮ → Model: "Use global (default)" (Settings → Model priority with its fallback chain), one of the global models, or any
 * model id (OpenRouter free/paid, kilo:…, prov:<provider>:…). A pinned chat uses only that model unless "Allow fallback".
 */
@Composable
internal fun ChatModelDialog(
    pin: com.verdroid.app.data.settings.ModelPin?,
    global: List<String>,
    onPick: (com.verdroid.app.data.settings.ModelPin?) -> Unit,
    onUpdate: (com.verdroid.app.data.settings.ModelPin?) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Model for this chat",
    defaultLabel: String = "Use global (default)",
) {
    var fallback by remember { mutableStateOf(pin?.allowFallback ?: false) }
    var custom by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                @Composable fun row(label: String, selected: Boolean, onClick: () -> Unit) {
                    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected, onClick = onClick)
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                row(defaultLabel, pin == null) { onPick(null) }
                Text("Pin one model (only it is used; a failure shows an error instead of switching):",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                (global + listOfNotNull(pin?.model)).distinct().forEach { m ->
                    row(com.verdroid.app.data.network.ModelIds.label(m), pin?.model == m) { onPick(com.verdroid.app.data.settings.ModelPin(m, fallback)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(custom, { custom = it }, singleLine = true, label = { Text("Other model id (vendor/model, kilo:…, prov:…)") },
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { onPick(com.verdroid.app.data.settings.ModelPin(custom.trim(), fallback)) }, enabled = custom.isNotBlank()) { Text("Pin") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Allow fallback", style = MaterialTheme.typography.bodyLarge)
                        Text("If the pinned model fails, continue with the global list.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = fallback, onCheckedChange = { fallback = it; pin?.let { p -> onUpdate(p.copy(allowFallback = it)) } })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

/** Intro text of the Tool presets dialog; never mentions profiles when they are off. */
internal fun toolPresetsIntro(profilesOn: Boolean): String =
    (if (profilesOn) "Starts from this chat's profile and Settings → Tools" else "Starts from Settings → Tools") +
        "; changes here apply only to this chat. Memory & skills, charts & other (incl. self-test) and MCP follow Settings."

/** Reset button of the Tool presets dialog: drops this chat's overrides (back to the profile, or Settings → Tools when Profiles are off). */
internal fun toolPresetsResetLabel(profilesOn: Boolean): String = if (profilesOn) "Reset to profile" else "Reset to Settings"
