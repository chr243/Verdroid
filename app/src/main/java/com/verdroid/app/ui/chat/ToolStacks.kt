package com.verdroid.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verdroid.app.data.network.ReasoningText
import com.verdroid.app.domain.model.ChatMessage
import com.verdroid.app.domain.model.MessageKind
import com.verdroid.app.domain.model.MessageRole
import com.verdroid.app.domain.model.ToolCallRecord
import com.verdroid.app.domain.model.ToolCallStatus

/**
 * Chat rows with consecutive shell-like tool calls (termux_run & co.) stacked into one expandable row (v1.0.24).
 * A row is one visible message plus its tool-call groups; assistant turns that only call the same stackable tool as the
 * previous group are folded into that group instead of getting their own row.
 */
object ToolStacks {
    val STACKABLE = setOf("termux_run", "termux_python", "rish_run", "run_shell")

    data class Row(val message: ChatMessage, val groups: List<List<ToolCallRecord>>) {
        val key: Long get() = message.id
    }

    fun rows(visible: List<ChatMessage>, callsByMessage: Map<Long, List<ToolCallRecord>>): List<Row> {
        val out = mutableListOf<Pair<ChatMessage, MutableList<MutableList<ToolCallRecord>>>>()
        for (m in visible) {
            val calls = if (m.role == MessageRole.ASSISTANT) callsByMessage[m.id].orEmpty() else emptyList()
            val onlyName = calls.map { it.name }.distinct().singleOrNull()
            val foldable = m.role == MessageRole.ASSISTANT && m.kind == MessageKind.NORMAL && m.content.isNullOrBlank() &&
                m.reasoning.isNullOrBlank() && onlyName != null && onlyName in STACKABLE
            val lastGroup = out.lastOrNull()?.second?.lastOrNull()
            if (foldable && lastGroup != null && lastGroup.last().name == onlyName) {
                lastGroup += calls
                continue
            }
            val groups = mutableListOf<MutableList<ToolCallRecord>>()
            for (c in calls) {
                val g = groups.lastOrNull()
                if (g != null && c.name in STACKABLE && g.last().name == c.name) g += c else groups += mutableListOf(c)
            }
            out += m to groups
        }
        return out.map { (m, g) -> Row(m, g) }
    }

    /**
     * Text + thinking to show for an assistant message. Messages saved before v1.0.34 have no reasoning column value but
     * may still carry inline `<think>…</think>` in content; those are split here so old chats look the same.
     */
    fun display(m: ChatMessage): ReasoningText.Split =
        if (!m.reasoning.isNullOrBlank()) ReasoningText.Split(m.content, m.reasoning) else ReasoningText.splitInline(m.content)

    /** Collapsed-row preview: first non-blank line of the thinking, shortened. */
    fun thinkingPreview(reasoning: String, max: Int = 60): String {
        val line = reasoning.lineSequence().map { it.trim().trimStart('#', '*', '-', ' ') }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return if (line.length > max) line.take(max - 1).trimEnd() + "…" else line
    }

    /** Status of a stack: pending if any call runs, else error if any failed, else success. */
    fun status(calls: List<ToolCallRecord>): ToolCallStatus = when {
        calls.any { it.status == ToolCallStatus.PENDING } -> ToolCallStatus.PENDING
        calls.any { it.status == ToolCallStatus.ERROR } -> ToolCallStatus.ERROR
        else -> ToolCallStatus.SUCCESS
    }
}

/** One card per group: single call → [ToolCallCard]; several → "termux_run ×N" that expands into the individual cards. */
@Composable
internal fun ToolCallGroup(calls: List<ToolCallRecord>) {
    if (calls.size == 1) { ToolCallCard(calls.single()); return }
    var expanded by remember { mutableStateOf(false) }
    val sc = com.verdroid.app.ui.theme.LocalStatusColors.current
    val st = ToolStacks.status(calls)
    val (icon, color) = when (st) {
        ToolCallStatus.PENDING -> "⏳" to sc.warn
        ToolCallStatus.SUCCESS -> "✅" to sc.ok
        ToolCallStatus.ERROR -> "⚠️" to sc.error
    }
    val failed = calls.count { it.status == ToolCallStatus.ERROR }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth().padding(end = 32.dp)) {
        Column(Modifier.clickable { expanded = !expanded }.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔧", fontSize = 14.sp); Spacer(Modifier.width(6.dp))
                Text("${calls.first().name} ×${calls.size}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                if (failed > 0) { Text("$failed failed", style = MaterialTheme.typography.labelSmall, color = sc.error); Spacer(Modifier.width(6.dp)) }
                Text(icon); Spacer(Modifier.width(4.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(6.dp))
                Text(if (expanded) "▲" else "▼", fontSize = 12.sp)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    calls.forEach { ToolCallCard(it) }
                }
            }
        }
    }
}

/** Collapsible "Thinking" row (model reasoning), styled like the tool cards: one-line preview, full text on tap. */
@Composable
internal fun ThinkingCard(reasoning: String) {
    var expanded by remember { mutableStateOf(false) }
    val preview = remember(reasoning) { ToolStacks.thinkingPreview(reasoning) }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth().padding(end = 32.dp)) {
        Column(Modifier.clickable { expanded = !expanded }.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("💭", fontSize = 14.sp); Spacer(Modifier.width(6.dp))
                Text("Thinking", fontWeight = FontWeight.SemiBold)
                if (!expanded && preview.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                } else Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                Text(if (expanded) "▲" else "▼", fontSize = 12.sp)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${reasoning.length} chars", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        com.verdroid.app.ui.components.CopyButton(reasoning)
                    }
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface).padding(8.dp)) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(reasoning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
