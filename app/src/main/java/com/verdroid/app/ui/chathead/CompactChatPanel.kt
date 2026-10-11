package com.verdroid.app.ui.chathead

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verdroid.app.domain.model.ChatMessage
import com.verdroid.app.domain.model.MessageKind
import com.verdroid.app.domain.model.MessageRole
import com.verdroid.app.domain.model.Task
import com.verdroid.app.domain.model.TaskStatus
import com.verdroid.app.domain.model.TaskType
import com.verdroid.app.domain.model.ToolCallRecord
import com.verdroid.app.ui.chat.AgentBubble
import com.verdroid.app.ui.chat.StatusLine
import com.verdroid.app.ui.chat.SummaryCard
import com.verdroid.app.ui.chat.ToolCallGroup
import com.verdroid.app.ui.chat.ThinkingCard
import com.verdroid.app.ui.chat.rememberFollowBottom
import com.verdroid.app.ui.chat.ToolStacks
import com.verdroid.app.ui.chat.UserBubble
import com.verdroid.app.ui.components.TaskAvatar

/**
 * Compact chat used by both the Bubbles API activity and the overlay chat head.
 * Stateless: callers pass flows' current values and callbacks.
 */
@Composable
fun CompactChatPanel(
    task: Task?,
    messages: List<ChatMessage>,
    toolCalls: List<ToolCallRecord>,
    generating: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onResume: () -> Unit,
    onOpenFull: () -> Unit,
    onCollapse: (() -> Unit)?,
    onSeen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val callsByMessage = remember(toolCalls) { toolCalls.groupBy { it.messageId } }
    val visible = remember(messages) {
        messages.filter { it.role != MessageRole.TOOL && !(it.role == MessageRole.SYSTEM && it.kind == MessageKind.NORMAL) }
    }
    // Consecutive termux_run (& co.) calls fold into one expandable row.
    val rows = remember(visible, callsByMessage) { ToolStacks.rows(visible, callsByMessage) }
    LaunchedEffect(messages.size) { onSeen() }
    // Same stick-to-bottom rule as the full chat: don't yank the list down while the user reads older messages.
    val follow = rememberFollowBottom(listState)
    val newestId = visible.lastOrNull()?.id
    val newestIsUser = visible.lastOrNull()?.role == MessageRole.USER
    val doneCalls = toolCalls.count { it.status != com.verdroid.app.domain.model.ToolCallStatus.PENDING }
    LaunchedEffect(rows.size, newestId, toolCalls.size, doneCalls) {
        if (visible.isEmpty()) return@LaunchedEffect
        if (newestIsUser) follow.value = true
        if (follow.value) listState.scrollToItem(rows.lastIndex.coerceAtLeast(0), com.verdroid.app.ui.chat.ChatScroll.TO_END)
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                task?.let { TaskAvatar(it, size = 32.dp) } ?: TaskAvatar(TaskType.CHAT, null, size = 32.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(task?.title ?: "Verdroid", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    if (task != null) {
                        Text(task.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = onOpenFull) { Text("Open ↗") }
                if (onCollapse != null) TextButton(onClick = onCollapse) { Text("—", fontSize = 18.sp) }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(), state = listState,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(rows, key = { it.key }) { row ->
                    val m = row.message
                    when {
                        m.kind == MessageKind.STATUS -> StatusLine(m)
                        m.kind == MessageKind.SUMMARY -> SummaryCard(m)
                        m.role == MessageRole.USER -> UserBubble(m.content.orEmpty())
                        m.role == MessageRole.ASSISTANT -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val shown = remember(m.content, m.reasoning) { ToolStacks.display(m) }
                            shown.reasoning?.let { ThinkingCard(it) }
                            shown.content?.takeIf { it.isNotBlank() }?.let { AgentBubble(it, m.model) }
                            row.groups.forEach { ToolCallGroup(it) }
                        }
                    }
                }
            }
            val status = task?.status
            if (generating) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    OutlinedButton(onClick = onStop, modifier = Modifier.padding(2.dp)) {
                        Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.error, RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(6.dp))
                        Text("Stop Generation")
                    }
                }
            } else if (status == TaskStatus.PAUSED || status == TaskStatus.RATE_LIMITED || status == TaskStatus.FAILED || status == TaskStatus.CANCELLED) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    FilledTonalButton(onClick = onResume, modifier = Modifier.padding(2.dp)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Resume")
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("Message Verdroid…") }, shape = RoundedCornerShape(24.dp), maxLines = 4,
                )
                Spacer(Modifier.width(6.dp))
                FilledIconButton(
                    onClick = { onSend(input); input = "" },
                    enabled = input.isNotBlank() && !generating && task != null,
                    modifier = Modifier.size(44.dp),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
            }
        }
    }
}
