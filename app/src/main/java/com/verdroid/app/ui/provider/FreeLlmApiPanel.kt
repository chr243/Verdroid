package com.verdroid.app.ui.provider

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.verdroid.app.data.network.CustomLlmClient
import com.verdroid.app.data.network.CustomProviderStore
import com.verdroid.app.data.termux.FreeLlmApi
import com.verdroid.app.data.termux.TermuxManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FreeLlmApiViewModel @Inject constructor(
    private val termux: TermuxManager,
    private val client: CustomLlmClient,
    private val store: CustomProviderStore,
) : ViewModel() {
    private val _status = MutableStateFlow("Not checked")
    val status = _status.asStateFlow()
    private val _note = MutableStateFlow<String?>(null)
    val note = _note.asStateFlow()
    private val _log = MutableStateFlow<String?>(null)
    val log = _log.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private fun termuxProblem(): String? = when {
        !termux.isInstalled() -> "Termux isn't installed. Set it up in Settings → Tools → Termux."
        !termux.hasRunCommandPermission() -> "Verdroid can't run Termux commands yet. Set it up in Settings → Tools → Termux."
        else -> null
    }

    fun install() {
        termuxProblem()?.let { _note.value = it; return }
        _note.value = if (termux.runInTerminal(FreeLlmApi.installScript(), "FreeLLMAPI install"))
            "Installing in Termux… follow it there. When it says DONE, come back and tap Start." else "Couldn't open Termux."
    }

    private fun bg(script: String, tag: String, timeout: Long, then: (String?) -> String) = viewModelScope.launch {
        termuxProblem()?.let { _note.value = it; return@launch }
        _busy.value = true
        _note.value = "Working…"
        _note.value = then(termux.runAndWait(script, "$tag-${System.nanoTime()}", timeout, "FreeLLMAPI")?.stdout)
        _busy.value = false
    }

    fun startInTerminal() {
        termuxProblem()?.let { _note.value = it; return }
        _note.value = if (termux.runInTerminal(FreeLlmApi.startInTerminalScript(), "FreeLLMAPI"))
            "Running in a Termux window. Keep it open, then tap Check status." else "Couldn't open Termux."
    }
    fun start() = bg(FreeLlmApi.startScript(), "fla-start", 30_000) { FreeLlmApi.startMessage(it) }
    fun stop() = bg(FreeLlmApi.stopScript(), "fla-stop", 15_000) {
        _status.value = "Not running"; if (it?.contains(FreeLlmApi.STOPPED) == true) "Stopped." else "Stop: Termux didn't answer."
    }
    fun showLog() = bg(FreeLlmApi.logScript(), "fla-log", 15_000) { _log.value = it?.ifBlank { "(empty)" } ?: "Termux didn't answer."; "" }
    fun closeLog() { _log.value = null }

    /** Health: GET /v1/models with the saved key. */
    fun check() = viewModelScope.launch {
        _status.value = "Checking…"
        if (!store.isConfigured(FreeLlmApi.PROVIDER_ID)) {
            // No key yet: still tell running vs not by whether the port answers at all.
            _status.value = client.listModels(FreeLlmApi.PROVIDER_ID).fold({ "Running" }, { e ->
                if (com.verdroid.app.data.network.ProviderProbe.isUnreachable(e)) "Not running" else "Running (paste the dashboard key below)" })
            return@launch
        }
        _status.value = client.listModels(FreeLlmApi.PROVIDER_ID).fold({ "Running · ${it.size} models" }, { e ->
            if (com.verdroid.app.data.network.ProviderProbe.isUnreachable(e)) "Not running" else "Error: ${e.message?.take(120)}" })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FreeLlmApiPanel(open: (String) -> Unit, vm: FreeLlmApiViewModel = hiltViewModel()) {
    val status by vm.status.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.check() }

    Text("FreeLLMAPI in Termux (experimental)", style = MaterialTheme.typography.titleMedium)
    Text(FreeLlmApi.HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("Status: $status", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy, onClick = vm::install) { Text("Install / Update in Termux") }
        OutlinedButton(enabled = !busy, onClick = { vm.start() }) { Text("Start") }
        OutlinedButton(enabled = !busy, onClick = vm::startInTerminal) { Text("Start in Termux window") }
        OutlinedButton(enabled = !busy, onClick = { vm.stop() }) { Text("Stop") }
        OutlinedButton(onClick = { open(FreeLlmApi.DASHBOARD_URL) }) { Text("Open dashboard") }
        TextButton(onClick = { vm.check() }) { Text("Check status") }
        TextButton(enabled = !busy, onClick = { vm.showLog() }) { Text("View log") }
    }
    note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))

    log?.let { text ->
        AlertDialog(onDismissRequest = vm::closeLog, title = { Text("FreeLLMAPI log") },
            text = { Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = vm::closeLog) { Text("Close") } })
    }
}
