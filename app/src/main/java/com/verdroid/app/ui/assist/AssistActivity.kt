package com.verdroid.app.ui.assist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.verdroid.app.MainActivity
import com.verdroid.app.domain.assist.AssistLogic
import com.verdroid.app.domain.usecase.StartConversationUseCase
import com.verdroid.app.ui.theme.VerdroidTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Default "Digital assistant app" entry (ASSIST / VOICE_COMMAND). A compact prompt card over the current app; Send starts
 * a NEW chat and shows it as a floating chat head when "Display over other apps" is allowed, else opens it in the app.
 */
@AndroidEntryPoint
class AssistActivity : ComponentActivity() {
    @Inject lateinit var startConversation: StartConversationUseCase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VerdroidTheme(transparentWindow = true) {
                var text by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                fun submit() {
                    val p = AssistLogic.prompt(text) ?: return
                    if (busy) return
                    busy = true; error = null
                    lifecycleScope.launch {
                        runCatching { startConversation(p) }
                            .onSuccess { id -> open(id); finishAndRemoveTask() }
                            .onFailure { error = it.message ?: "Could not start the chat"; busy = false }
                    }
                }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finishAndRemoveTask() }
                    .systemBarsPadding().imePadding(), contentAlignment = Alignment.BottomCenter) {
                    Surface(Modifier.fillMaxWidth().padding(8.dp).clickable(enabled = false) {},
                        shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Ask Verdroid", style = MaterialTheme.typography.titleLarge)
                            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 220.dp).focusRequester(focus),
                                placeholder = { Text("Message") },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { submit() }))
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { startActivity(MainActivity.launchIntent(this@AssistActivity)); finishAndRemoveTask() }) { Text("Open app") }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { finishAndRemoveTask() }) { Text("Cancel") }
                                Button(enabled = !busy && AssistLogic.prompt(text) != null, onClick = { submit() }) {
                                    Text(if (busy) "Sending…" else "Send")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun open(id: Long) {
        if (AssistLogic.route(android.provider.Settings.canDrawOverlays(this)) == AssistLogic.Route.MAIN_APP) {
            startActivity(MainActivity.openTaskIntent(this, id)); return
        }
        val i = com.verdroid.app.chathead.ChatHeadService.startIntent(this, id)
        runCatching { androidx.core.content.ContextCompat.startForegroundService(this, i) }
            .onSuccess { com.verdroid.app.chathead.ShareHead.taskId = id }
            .onFailure { startActivity(MainActivity.openTaskIntent(this, id)) }
    }
}
