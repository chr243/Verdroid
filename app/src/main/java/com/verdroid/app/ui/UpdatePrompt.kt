package com.verdroid.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdroid.app.data.update.AppUpdater
import com.verdroid.app.data.update.UpdateLogic
import com.verdroid.app.data.update.UpdateState

/**
 * v1.0.40: after the silent start-up check, a newer GitHub release shows this panel once per session.
 * Update downloads via the existing updater and opens the system installer when ready; Later dismisses until restart.
 */
@Composable
fun StartupUpdatePrompt(updater: AppUpdater) {
    val state by updater.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dismissed by remember { mutableStateOf(updater.startupPromptDismissed) }
    var installWhenReady by remember { mutableStateOf(false) }

    LaunchedEffect(state, installWhenReady) {
        val st = state
        if (installWhenReady && st is UpdateState.ReadyToInstall) {
            installWhenReady = false
            runCatching {
                context.startActivity(if (updater.canInstall()) updater.installIntent(st.file) else updater.unknownSourcesIntent())
            }
        }
    }

    val enabled by updater.enabled.collectAsStateWithLifecycle()
    val st = state
    if (!enabled || dismissed || st !is UpdateState.Available) return
    val r = st.release
    fun dismiss() { updater.startupPromptDismissed = true; dismissed = true }
    AlertDialog(
        onDismissRequest = { dismiss() },
        title = { Text("Update available: v${r.version}") },
        text = {
            val notes = UpdateLogic.notesSnippet(r.notes)
            Text("Installed: v${updater.currentVersion}" + if (notes.isNotBlank()) "\n\n$notes" else "")
        },
        confirmButton = {
            TextButton(enabled = r.asset != null, onClick = {
                dismiss(); installWhenReady = true; updater.startDownload(r)
            }) { Text("Update") }
        },
        dismissButton = { TextButton(onClick = { dismiss() }) { Text("Later") } },
    )
}
