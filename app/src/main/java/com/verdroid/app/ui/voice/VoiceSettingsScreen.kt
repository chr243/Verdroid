package com.verdroid.app.ui.voice

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdroid.app.data.voice.VoicePrefs
import com.verdroid.app.ui.components.SettingsGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class VoiceSettingsViewModel @Inject constructor(val prefs: VoicePrefs) : ViewModel()

/** Settings → Voice: mic button, auto-send, read replies aloud, hands-free. Uses the phone's default language. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(onBack: () -> Unit, vm: VoiceSettingsViewModel = hiltViewModel()) {
    val p = vm.prefs
    val mic by p.micButton.collectAsStateWithLifecycle()
    val autoSend by p.autoSend.collectAsStateWithLifecycle()
    val speak by p.speakReplies.collectAsStateWithLifecycle()
    val handsFree by p.handsFree.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val recognizer = remember { runCatching { android.speech.SpeechRecognizer.isRecognitionAvailable(ctx) }.getOrDefault(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Voice") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            @Composable fun row(title: String, sub: String, checked: Boolean, enabled: Boolean = true, set: (Boolean) -> Unit) {
                ListItem(headlineContent = { Text(title) }, supportingContent = { Text(sub) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    trailingContent = { Switch(checked = checked, onCheckedChange = set, enabled = enabled) })
            }
            SettingsGroup("Speech to text", listOf(
                { _ -> row("Mic button in chat", "Tap the mic next to the message box and speak. Needs microphone access.", mic, set = p::setMicButton) },
                { _ -> row("Send right away", "Send what you said as soon as you stop talking. Off: the text goes into the message box to edit first.", autoSend, set = p::setAutoSend) },
            ))
            SettingsGroup("Text to speech", listOf(
                { _ -> row("Read replies aloud", "Speak Verdroid's replies (code blocks and formatting are skipped). Stops when you tap the mic or send.", speak, set = p::setSpeakReplies) },
                { _ -> row("Hands-free mode", "After a reply is read aloud, start listening again automatically. Needs Read replies aloud.", handsFree, enabled = speak, set = p::setHandsFree) },
            ))
            Text(
                "Language: ${Locale.getDefault().displayName} (phone default). " +
                    (if (recognizer) "Speech recognition: available." else "Speech recognition service not found; the system voice prompt is used if one exists.") +
                    " Everything uses Android's built-in speech services, no keys or paid APIs.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
            )
        }
    }
}
