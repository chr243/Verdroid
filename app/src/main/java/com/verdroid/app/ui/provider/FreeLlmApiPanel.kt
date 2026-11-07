package com.verdroid.app.ui.provider

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.verdroid.app.R
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
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val termux: TermuxManager,
    private val client: CustomLlmClient,
    private val store: CustomProviderStore,
) : ViewModel() {
    private val _status = MutableStateFlow("")
    val status = _status.asStateFlow()
    private val _note = MutableStateFlow<String?>(null)
    val note = _note.asStateFlow()
    private val _log = MutableStateFlow<String?>(null)
    val log = _log.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private fun termuxProblem(): String? = when {
        !termux.isInstalled() -> context.getString(R.string.fla_no_termux)
        !termux.hasRunCommandPermission() -> context.getString(R.string.fla_no_termux_perm)
        else -> null
    }

    fun install() {
        termuxProblem()?.let { _note.value = it; return }
        _note.value = if (termux.runInTerminal(FreeLlmApi.installScript(), "FreeLLMAPI install"))
            context.getString(R.string.fla_installing) else context.getString(R.string.fla_cant_open_termux)
    }

    private fun bg(script: String, tag: String, timeout: Long, then: (String?) -> String) = viewModelScope.launch {
        termuxProblem()?.let { _note.value = it; return@launch }
        _busy.value = true
        _note.value = context.getString(R.string.fla_working)
        _note.value = then(termux.runAndWait(script, "$tag-${System.nanoTime()}", timeout, "FreeLLMAPI")?.stdout)
        _busy.value = false
    }

    fun startInTerminal() {
        termuxProblem()?.let { _note.value = it; return }
        _note.value = if (termux.runInTerminal(FreeLlmApi.startInTerminalScript(), "FreeLLMAPI"))
            context.getString(R.string.fla_running_window) else context.getString(R.string.fla_cant_open_termux)
    }
    fun start() = bg(FreeLlmApi.startScript(), "fla-start", 30_000) { FreeLlmApi.startMessage(it) }
    fun stop() = bg(FreeLlmApi.stopScript(), "fla-stop", 15_000) {
        _status.value = context.getString(R.string.fla_not_running); if (it?.contains(FreeLlmApi.STOPPED) == true) context.getString(R.string.fla_stopped) else context.getString(R.string.fla_stop_no_answer)
    }
    fun showLog() = bg(FreeLlmApi.logScript(), "fla-log", 15_000) { _log.value = it?.ifBlank { "(empty)" } ?: context.getString(R.string.fla_no_answer); "" }
    fun closeLog() { _log.value = null }

    /** Health: GET /v1/models with the saved key. */
    fun check() = viewModelScope.launch {
        _status.value = context.getString(R.string.fla_checking)
        if (!store.isConfigured(FreeLlmApi.PROVIDER_ID)) {
            // No key yet: still tell running vs not by whether the port answers at all.
            _status.value = client.listModels(FreeLlmApi.PROVIDER_ID).fold({ context.getString(R.string.fla_running) }, { e ->
                if (com.verdroid.app.data.network.ProviderProbe.isUnreachable(e)) context.getString(R.string.fla_not_running) else context.getString(R.string.fla_running_paste_key) })
            return@launch
        }
        _status.value = client.listModels(FreeLlmApi.PROVIDER_ID).fold({ context.getString(R.string.fla_running_models, it.size) }, { e ->
            if (com.verdroid.app.data.network.ProviderProbe.isUnreachable(e)) context.getString(R.string.fla_not_running) else context.getString(R.string.fla_error, e.message?.take(120) ?: "") })
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

    Text(stringResource(R.string.freellmapi_title), style = MaterialTheme.typography.titleMedium)
    Text(FreeLlmApi.HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.status_colon, status), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy, onClick = vm::install) { Text(stringResource(R.string.install_update_termux)) }
        OutlinedButton(enabled = !busy, onClick = { vm.start() }) { Text(stringResource(R.string.start)) }
        OutlinedButton(enabled = !busy, onClick = vm::startInTerminal) { Text(stringResource(R.string.start_in_termux)) }
        OutlinedButton(enabled = !busy, onClick = { vm.stop() }) { Text(stringResource(R.string.stop)) }
        OutlinedButton(onClick = { open(FreeLlmApi.DASHBOARD_URL) }) { Text(stringResource(R.string.open_dashboard)) }
        TextButton(onClick = { vm.check() }) { Text(stringResource(R.string.check_status)) }
        TextButton(enabled = !busy, onClick = { vm.showLog() }) { Text(stringResource(R.string.view_log)) }
    }
    note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))

    log?.let { text ->
        AlertDialog(onDismissRequest = vm::closeLog, title = { Text(stringResource(R.string.freellmapi_log)) },
            text = { Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = vm::closeLog) { Text(stringResource(R.string.close)) } })
    }
}
