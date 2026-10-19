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
interface FileChangeConsentEntryPoint { fun consent(): FileChangeConsent; fun xPost(): com.verdroid.app.data.consent.XPostConsent
    fun install(): com.verdroid.app.data.consent.InstallPrompt; fun xPostGuard(): com.verdroid.app.data.consent.XPostGuard }

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
                if (p.req.sharedDeletesOnly) {
                    Text("Everything affected is inside Documents/Verdroid. Skip this question for deletes/moves/renames there:",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { consent.allowSharedDeletes(p.req.id, thisChatOnly = true) }) { Text("This chat") }
                        OutlinedButton(onClick = { consent.allowSharedDeletes(p.req.id, thisChatOnly = false) }) { Text("Always allow") }
                    }
                }
                if (pending.size > 1) Text("${pending.size - 1} more waiting", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(onClick = { consent.answer(p.req.id, true) }) { Text(if (p.req.overwriteOnly) "Yes" else "Allow") } },
        dismissButton = { Button(onClick = { consent.answer(p.req.id, false) }) { Text(if (p.req.overwriteOnly) "No" else "Deny") } },
    )
}

/** Install / Don't install for agent-driven package installs (main app + bubble). Back/outside tap does nothing. */
@Composable
fun InstallDialog() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prompt = remember { EntryPointAccessors.fromApplication(context.applicationContext, FileChangeConsentEntryPoint::class.java).install() }
    val pending by prompt.pending.collectAsState()
    val p = pending.firstOrNull() ?: return
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Install packages?") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${p.tool} needs to install:", style = MaterialTheme.typography.titleSmall)
                SelectionContainer { Text(com.verdroid.app.agent.tools.InstallConsent.describe(p.packages), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                Text("Why: ${p.reason}", style = MaterialTheme.typography.bodyMedium)
                Text(p.estimate, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (pending.size > 1) Text("${pending.size - 1} more waiting", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(onClick = { prompt.answer(p.id, true) }) { Text("Install") } },
        dismissButton = { Button(onClick = { prompt.answer(p.id, false) }) { Text("Don't install") } },
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
            Text("The agent may replace existing files outside Internal/ without a Yes/No. Deleting, moving and renaming ask (see below).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = { prefs.setAlways(it) })
    }
}

/** Settings → Tools: "Always allow delete/move/rename in Documents/Verdroid" (off by default; never in scheduled runs). */
@Composable
fun SharedDeleteSetting(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { EntryPointAccessors.fromApplication(context.applicationContext, FileChangeConsentEntryPoint::class.java).consent().deletes }
    val on by prefs.always.collectAsState()
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Always allow delete/move/rename in Documents/Verdroid", style = MaterialTheme.typography.bodyLarge)
            Text("No Yes/No when every affected file is provably inside Documents/Verdroid (Internal, Repos, Output, …). " +
                "Anything outside, or that can't be proven, still asks. Scheduled runs still can't delete.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = { prefs.setAlways(it) })
    }
}

/** Post / Cancel before x_post publishes (main app + bubble). Shows the exact text and reply target. */
@Composable
fun XPostDialog() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val consent = remember { EntryPointAccessors.fromApplication(context.applicationContext, FileChangeConsentEntryPoint::class.java).xPost() }
    val pending by consent.pending.collectAsState()
    val p = pending.firstOrNull() ?: return
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (p.replyTo != null) "Post this reply on X?" else "Post this on X?") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                p.replyTo?.let { Text("Replying to $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                SelectionContainer { Text(p.text, style = MaterialTheme.typography.bodyLarge) }
                Text("${com.verdroid.app.agent.tools.XText.weightedLength(p.text)}/280 · posts publicly as you",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { consent.answer(p.id, post = true, always = true) }) { Text("Always allow") }
            }
        },
        confirmButton = { Button(onClick = { consent.answer(p.id, post = true) }) { Text("Post") } },
        dismissButton = { TextButton(onClick = { consent.answer(p.id, post = false) }) { Text("Cancel") } },
    )
}

/** Settings → Tools: "Post to X without asking" (off by default). */
@Composable
fun XPostSetting(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val consent = remember { EntryPointAccessors.fromApplication(context.applicationContext, FileChangeConsentEntryPoint::class.java).xPost() }
    val on by consent.always.collectAsState()
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Post to X without asking", style = MaterialTheme.typography.bodyLarge)
            Text("x_post publishes immediately, without showing you the text first. Posts are public and can't be unsent.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = { consent.setAlways(it) })
    }
}

/** Settings → Tools → Twitter: "Limit X posts" (on by default; per hour / per day, 0 = no cap for that window) and the audit log viewer. */
@Composable
fun XPostLimitsSetting(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val guard = remember { EntryPointAccessors.fromApplication(context.applicationContext, FileChangeConsentEntryPoint::class.java).xPostGuard() }
    val perHour by guard.perHour.collectAsState()
    val perDay by guard.perDay.collectAsState()
    val limitOn by guard.limitOn.collectAsState()
    var showLog by remember { mutableStateOf(false) }
    val (h, d) = remember(perHour, perDay, showLog) { guard.usage() }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Limit X posts", style = MaterialTheme.typography.bodyLarge)
                Text((if (limitOn) "On: x_post stops at the limits below, in chats and scheduled runs, even with \"Always allow\". 0 = no limit for that window."
                    else "Off: no limit on x_post (chats and scheduled runs). Every post is still logged.") +
                    " Used: $h in the last hour, $d in the last 24 h.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = limitOn, onCheckedChange = guard::setLimitOn)
        }
        if (limitOn) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Per hour", Modifier.width(72.dp))
                OutlinedButton(onClick = { guard.setPerHour(perHour - 1) }, enabled = perHour > 0) { Text("−") }
                Text("$perHour", Modifier.width(32.dp))
                OutlinedButton(onClick = { guard.setPerHour(perHour + 1) }) { Text("+") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Per day", Modifier.width(72.dp))
                OutlinedButton(onClick = { guard.setPerDay(perDay - 1) }, enabled = perDay > 0) { Text("−") }
                Text("$perDay", Modifier.width(32.dp))
                OutlinedButton(onClick = { guard.setPerDay(perDay + 1) }) { Text("+") }
            }
        }
        TextButton(onClick = { showLog = true }) { Text("View post log") }
    }
    if (showLog) {
        val text = remember { guard.tail().ifBlank { "No x_post attempts logged yet." } }
        AlertDialog(onDismissRequest = { showLog = false },
            title = { Text("x_post log") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("Internal/${com.verdroid.app.agent.tools.XPostAudit.FILE}", style = MaterialTheme.typography.labelSmall)
                    SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                }
            },
            confirmButton = { TextButton(onClick = { showLog = false }) { Text("Close") } })
    }
}

/** Shown every time the Twitter tools are switched on (globally or forced on in a chat). Cancel leaves them off. */
@Composable
fun TwitterTosDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel,
        title = { Text("Turn on Twitter tools?") },
        text = { Text(com.verdroid.app.data.tools.ToolGroups.TWITTER_TOS_WARNING) },
        confirmButton = { Button(onClick = onConfirm) { Text("I understand, turn on") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })
}
