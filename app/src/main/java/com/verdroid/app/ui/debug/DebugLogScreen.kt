package com.verdroid.app.ui.debug

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verdroid.app.chathead.HeadLog
import com.verdroid.app.data.a11y.VerdroidAccessibilityService
import kotlinx.coroutines.delay

/** Settings → App → Debug log: recent VerdroidHead lines (floating window / Home signal), with Copy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(onBack: () -> Unit) {
    var lines by remember { mutableStateOf(HeadLog.snapshot()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); lines = HeadLog.snapshot() } }
    val clip = LocalClipboardManager.current
    Scaffold(topBar = {
        TopAppBar(title = { Text("Debug log") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                TextButton(onClick = { clip.setText(AnnotatedString(lines.joinToString("\n"))) }) { Text("Copy") }
                TextButton(onClick = { HeadLog.clear(); lines = emptyList() }) { Text("Clear") }
            })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            Text("Floating window events since the app started. Accessibility service: " +
                if (VerdroidAccessibilityService.isRunning) "connected" else "NOT connected",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (lines.isEmpty()) Text("Nothing yet. Open the floating window, press Home, then come back here.")
            SelectionContainer {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(lines.asReversed()) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                }
            }
        }
    }
}
