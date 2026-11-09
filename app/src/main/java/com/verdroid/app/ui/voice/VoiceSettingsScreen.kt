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
import androidx.compose.ui.res.stringResource
import com.verdroid.app.R

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
        TopAppBar(title = { Text(stringResource(R.string.voice)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            @Composable fun row(title: String, sub: String, checked: Boolean, enabled: Boolean = true, set: (Boolean) -> Unit) {
                ListItem(headlineContent = { Text(title) }, supportingContent = { Text(sub) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    trailingContent = { Switch(checked = checked, onCheckedChange = set, enabled = enabled) })
            }
            SettingsGroup(stringResource(R.string.group_stt), listOf(
                { _ -> row(stringResource(R.string.voice_mic), stringResource(R.string.voice_mic_desc), mic, set = p::setMicButton) },
                { _ -> row(stringResource(R.string.voice_auto_send), stringResource(R.string.voice_auto_send_desc), autoSend, set = p::setAutoSend) },
            ))
            SettingsGroup(stringResource(R.string.group_tts), listOf(
                { _ -> row(stringResource(R.string.voice_speak), stringResource(R.string.voice_speak_desc), speak, set = p::setSpeakReplies) },
                { _ -> row(stringResource(R.string.voice_hands_free), stringResource(R.string.voice_hands_free_desc), handsFree, enabled = speak, set = p::setHandsFree) },
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
