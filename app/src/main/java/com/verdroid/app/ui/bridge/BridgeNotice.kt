package com.verdroid.app.ui.bridge

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Bridge build only (BuildConfig.BRIDGE): old debug-signed installs (<= v1.0.39) can't update to the signed releases
 * (v1.0.40+, different signing key). Shown on every start; "Later" hides it until the next start.
 */
@Composable
fun BridgeNotice() {
    var show by remember { mutableStateOf(true) }
    if (!show) return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Please reinstall Verdroid") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Verdroid now ships signed release builds. This copy uses the old test key, so it can't update to them and won't get updates anymore.")
                Text("To switch: 1) export anything you want to keep (chats, memories, files in Documents/Verdroid stay). " +
                    "2) Uninstall Verdroid. 3) Install the latest Verdroid-v….apk from GitHub.")
                Text("Uninstalling deletes the app's chats, settings and API keys. Note your keys first.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LATEST_RELEASE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text("Open latest release") }
        },
        dismissButton = { TextButton(onClick = { show = false }) { Text("Later") } },
    )
}

const val LATEST_RELEASE = "https://github.com/chr243/Verdroid/releases/latest"
