package com.verdroid.app.ui.provider

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.verdroid.app.R
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.verdroid.app.data.localllm.LlamaNative
import com.verdroid.app.data.localllm.LocalEngineState
import com.verdroid.app.data.localllm.LiteRtLmEngine
import com.verdroid.app.data.localllm.LocalEngines
import com.verdroid.app.data.localllm.LocalLlmEngine
import com.verdroid.app.data.localllm.LocalModel
import com.verdroid.app.data.localllm.LocalModelStore
import com.verdroid.app.data.localllm.LocalModels
import com.verdroid.app.data.localllm.LocalPrompt
import com.verdroid.app.data.localllm.LocalReply
import com.verdroid.app.data.network.ApiMessage
import com.verdroid.app.data.network.ModelIds
import com.verdroid.app.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LocalLlmViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    val store: LocalModelStore,
    private val engine: LocalEngines,
    private val llama: LocalLlmEngine,
    private val litert: LiteRtLmEngine,
    private val settings: SettingsRepository,
) : ViewModel() {
    val models = store.models
    val state = engine.state(viewModelScope)
    val gpuNote = engine.gpuNote(viewModelScope)
    private val _useGpu = MutableStateFlow(store.useGpu)
    val useGpu = _useGpu.asStateFlow()
    private val _probing = MutableStateFlow(false)
    val probing = _probing.asStateFlow()

    fun setGpu(on: Boolean) = viewModelScope.launch {
        if (!on) { llama.disableGpu(); litert.resetGpuFailure(); if (engine.loadedId?.let(engine::engineFor) === litert) litert.unload(); _useGpu.value = false; _note.value = context.getString(R.string.lm_gpu_off); return@launch }
        _probing.value = true
        _note.value = context.getString(R.string.lm_probing_gpu)
        val (_, msg) = llama.enableGpu()
        litert.resetGpuFailure()
        if (engine.loadedId?.let { engine.engineFor(it) } === litert) litert.unload() // reload on GPU next time
        _useGpu.value = store.useGpu
        _note.value = context.getString(R.string.lm_applies_next, msg)
        _probing.value = false
    }
    fun setGpuLayers(n: Int) { store.gpuLayers = n; _note.value = context.getString(R.string.lm_gpu_layers_note, com.verdroid.app.data.localllm.GpuPolicy.layersLabel(n)) }
    val priority = settings.modelPriority.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _note = MutableStateFlow<String?>(null)
    val note = _note.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private var importJob: Job? = null

    fun import(uri: Uri) {
        importJob = viewModelScope.launch {
            _busy.value = true
            try {
                val m = store.import(uri) { done, total ->
                    _note.value = if (total > 0) context.getString(R.string.lm_copying_pct, done * 100 / total, LocalModels.human(done), LocalModels.human(total))
                        else context.getString(R.string.lm_copying, LocalModels.human(done))
                }
                _note.value = context.getString(R.string.lm_imported, m.name, LocalModels.human(m.sizeBytes))
            } catch (e: kotlinx.coroutines.CancellationException) { _note.value = context.getString(R.string.lm_import_cancelled); throw e
            } catch (e: Exception) { _note.value = context.getString(R.string.lm_import_failed, e.message ?: "") }
            finally { _busy.value = false }
        }
    }
    fun cancelImport() { importJob?.cancel() }

    fun load(id: String) = viewModelScope.launch {
        _note.value = context.getString(R.string.lm_loading)
        _note.value = engine.ensureLoaded(id)?.let { context.getString(R.string.lm_load_failed, it) } ?: context.getString(R.string.lm_loaded)
    }
    fun unload() = viewModelScope.launch { engine.unload(); _note.value = context.getString(R.string.lm_unloaded) }

    fun quickTest(id: String) = viewModelScope.launch {
        _busy.value = true
        _note.value = context.getString(R.string.lm_testing)
        val t0 = System.currentTimeMillis()
        val msgs = listOf(ApiMessage("system", LocalPrompt.SYSTEM), ApiMessage("user", "Reply with one short sentence: what is 2+2?"))
        val (body, err) = engine.chat(id, LocalPrompt.messagesJson(msgs), "", 64)
        val secs = (System.currentTimeMillis() - t0) / 1000.0
        _note.value = when {
            err != null -> context.getString(R.string.lm_test_failed, err)
            else -> when (val r = LocalReply.parse(body.orEmpty(), id)) {
                is LocalReply.Result.Ok -> "OK on ${(engine.current as? LocalEngineState.Loaded)?.backend ?: "?"} in ${"%.1f".format(secs)} s · ${r.completionTokens} tokens " +
                    "(${if (secs > 0) "%.1f".format(r.completionTokens / secs) else "?"} tok/s incl. prompt): " + (r.completion.content ?: "(empty)").take(200)
                is LocalReply.Result.Error -> context.getString(R.string.lm_test_failed, r.message)
            }
        }
        _busy.value = false
    }

    fun addToPriority(id: String) = viewModelScope.launch {
        val mid = ModelIds.local(id)
        settings.setModelPriority(listOf(mid) + settings.currentModels().filter { it != mid })
        _note.value = context.getString(R.string.lm_added_priority)
    }
    fun removeFromPriority(id: String) = viewModelScope.launch {
        settings.setModelPriority(settings.currentModels().filter { it != ModelIds.local(id) })
    }

    fun delete(m: LocalModel) = viewModelScope.launch {
        if (engine.loadedId == m.id) engine.unload()
        store.remove(m.id)
        settings.setModelPriority(settings.currentModels().filter { it != ModelIds.local(m.id) })
        _note.value = "Deleted ${m.name}"
    }

    fun setContext(n: Int) { store.contextSize = n; _note.value = "Context $n (applies on next load)" }
    fun setThreads(n: Int) { store.threads = n; _note.value = "Threads $n (applies on next load)" }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocalProviderPanel(onOpenModels: () -> Unit, vm: LocalLlmViewModel = hiltViewModel()) {
    val models by vm.models.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val priority by vm.priority.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val useGpu by vm.useGpu.collectAsStateWithLifecycle()
    val probing by vm.probing.collectAsStateWithLifecycle()
    val gpuNote by vm.gpuNote.collectAsStateWithLifecycle()
    var gpuLayers by remember { mutableIntStateOf(vm.store.gpuLayers) }
    var nCtx by remember { mutableIntStateOf(vm.store.contextSize) }
    var threads by remember { mutableIntStateOf(vm.store.threads) }
    var confirmDelete by remember { mutableStateOf<LocalModel?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    val engineOk = remember { LlamaNative.available || LiteRtLmEngine.available }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.provider_local), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        AssistChip(onClick = {}, enabled = false, label = { Text(when {
            !engineOk -> stringResource(R.string.not_supported)
            state is LocalEngineState.Loaded -> stringResource(R.string.loaded_state)
            models.isEmpty() -> stringResource(R.string.no_model_yet)
            else -> stringResource(R.string.state_ready)
        }) })
    }
    Text(stringResource(R.string.local_provider_intro),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!engineOk) {
        Text(LlamaNative.unavailableReason + "; " + LiteRtLmEngine.unavailableReason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.import_model)) }
        if (busy) OutlinedButton(onClick = vm::cancelImport) { Text(stringResource(R.string.cancel)) }
    }
    Text(stringResource(R.string.import_model_note),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Text(stringResource(R.string.context_size), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LocalModels.CONTEXT_SIZES.forEach { n -> FilterChip(selected = nCtx == n, onClick = { nCtx = n; vm.setContext(n) }, label = { Text("${n / 1024}K") }) }
    }
    Text(stringResource(R.string.threads_n, threads), style = MaterialTheme.typography.labelLarge)
    Slider(value = threads.toFloat(), onValueChange = { threads = it.toInt() }, onValueChangeFinished = { vm.setThreads(threads) },
        valueRange = 1f..8f, steps = 6)

    ListItem(headlineContent = { Text(stringResource(R.string.use_gpu)) },
        supportingContent = { Text(stringResource(R.string.use_gpu_desc)) },
        trailingContent = { if (probing) CircularProgressIndicator(Modifier.size(24.dp)) else Switch(useGpu, vm::setGpu, enabled = !busy) })
    if (useGpu) {
        Text(stringResource(R.string.gpu_layers), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.verdroid.app.data.localllm.GpuPolicy.LAYER_CHOICES.forEach { n ->
                FilterChip(selected = gpuLayers == n, onClick = { gpuLayers = n; vm.setGpuLayers(n) },
                    label = { Text(com.verdroid.app.data.localllm.GpuPolicy.layersLabel(n)) })
            }
        }
    }
    vm.store.gpuProbe?.let { Text(stringResource(R.string.last_gpu_probe, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    gpuNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    (state as? LocalEngineState.Loaded)?.let { Text(stringResource(R.string.active_backend_label, it.backend), style = MaterialTheme.typography.labelLarge) }

    when (val s = state) {
        is LocalEngineState.Loading -> Text(stringResource(R.string.loading_id, s.id))
        is LocalEngineState.Failed -> Text("${s.id}: ${s.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        else -> {}
    }
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

    models.forEach { m ->
        val loaded = (state as? LocalEngineState.Loaded)?.id == m.id
        val inPriority = ModelIds.local(m.id) in priority
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(m.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    AssistChip(onClick = {}, enabled = false, label = { Text(if (m.format == LocalModels.LITERTLM) "LiteRT" else "GGUF") })
                }
                Text(stringResource(R.string.local_model_line, LocalModels.human(m.sizeBytes), m.id) + (if (loaded) " · " + stringResource(R.string.loaded_on, (state as LocalEngineState.Loaded).backend) else ""),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (loaded) OutlinedButton(onClick = { vm.unload() }) { Text(stringResource(R.string.unload)) }
                    else OutlinedButton(enabled = !busy, onClick = { vm.load(m.id) }) { Text(stringResource(R.string.load)) }
                    OutlinedButton(enabled = !busy, onClick = { vm.quickTest(m.id) }) { Text(stringResource(R.string.quick_test)) }
                    if (inPriority) OutlinedButton(onClick = { vm.removeFromPriority(m.id) }) { Text(stringResource(R.string.remove_from_priority)) }
                    else Button(onClick = { vm.addToPriority(m.id) }) { Text(stringResource(R.string.add_to_model_priority)) }
                    TextButton(enabled = !busy, onClick = { confirmDelete = m }) { Text(stringResource(R.string.delete)) }
                }
            }
        }
    }
    TextButton(onClick = onOpenModels) { Text(stringResource(R.string.choose_model_order)) }

    confirmDelete?.let { m ->
        AlertDialog(onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_model_file, m.name)) },
            text = { Text(stringResource(R.string.delete_model_file_desc, LocalModels.human(m.sizeBytes))) },
            confirmButton = { TextButton(onClick = { vm.delete(m); confirmDelete = null }) { Text(stringResource(R.string.yes)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.no)) } })
    }
}
