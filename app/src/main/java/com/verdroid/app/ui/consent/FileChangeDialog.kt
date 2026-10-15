package com.verdroid.app.ui.consent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verdroid.app.data.consent.FileChangeConsent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FileChangeConsentEntryPoint { fun consent(): FileChangeConsent }

/** Shows the oldest pending file-change question (Allow / Deny). Back/outside tap does nothing: the user must choose. */
@Composable
fun FileChangeDialog() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val consent = remember { EntryPointAccessors.fromApplication(context.applicationContext, FileChangeConsentEntryPoint::class.java).consent() }
    val pending by consent.pending.collectAsState()
    val p = pending.firstOrNull() ?: return
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Allow this file change?") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p.req.summary, style = MaterialTheme.typography.titleSmall)
                Text("Chat: ${p.chatTitle ?: "#${p.req.taskId}"} · tool ${p.req.tool}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                p.req.details.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                p.req.command?.let { c ->
                    SelectionContainer { Text(c, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                }
                if (p.req.overwriteOnly) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { consent.allowOverwrites(p.req.id, thisChatOnly = true) }) { Text("This chat") }
                    OutlinedButton(onClick = { consent.allowOverwrites(p.req.id, thisChatOnly = false) }) { Text("Always allow") }
                }
                if (pending.size > 1) Text("${pending.size - 1} more waiting", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(onClick = { consent.answer(p.req.id, true) }) { Text(if (p.req.overwriteOnly) "Yes" else "Allow") } },
        dismissButton = { Button(onClick = { consent.answer(p.req.id, false) }) { Text(if (p.req.overwriteOnly) "No" else "Deny") } },
    )
}

/** Settings → Tools: "Allow overwriting files without asking" (overwrites only; deletes/moves/renames always ask). */
@Composable
fun OverwriteSetting(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { EntryPointAccessors.fromApplication(context.applicationContext, FileChangeConsentEntryPoint::class.java).consent().overwrite }
    val on by prefs.always.collectAsState()
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Allow overwriting files without asking", style = MaterialTheme.typography.bodyLarge)
            Text("The agent may replace existing files outside Internal/ without a Yes/No. Deleting, moving and renaming always ask.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = { prefs.setAlways(it) })
    }
}
