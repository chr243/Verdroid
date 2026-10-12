package com.verdroid.app.ui.provider

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.verdroid.app.data.network.CustomLlmClient
import com.verdroid.app.data.network.CustomProviderStore
import com.verdroid.app.data.network.ModelIds
import com.verdroid.app.data.network.ProviderPresets
import com.verdroid.app.data.network.ProviderUrls
import com.verdroid.app.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProviderViewModel @Inject constructor(
    val store: CustomProviderStore,
    private val client: CustomLlmClient,
    private val settings: SettingsRepository,
) : ViewModel() {
    val priority = settings.modelPriority.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _models = MutableStateFlow<List<String>>(emptyList())
    val models = _models.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()

    /** Returns an error message, or null when saved. */
    fun save(presetId: String, base: String, key: String, headers: String): String? {
        val url = if (base.isBlank()) "" else ProviderUrls.normalize(base) ?: return "Invalid base URL"
        if (url.isNotEmpty() && !ProviderUrls.cleartextAllowed(url)) return "http:// only for localhost/LAN; use https"
        store.save(presetId, url, headers)
        store.apiKey = key
        _status.value = "Saved" + if (url.isNotEmpty()) ": $url" else ""
        return null
    }

    fun fetch() = viewModelScope.launch {
        _status.value = "Fetching models…"
        client.listModels().onSuccess { _models.value = it; _status.value = if (it.isEmpty()) "No models listed; enter an id manually" else "${it.size} models" }
            .onFailure { _status.value = "Fetch failed (${it.message}); enter an id manually" }
    }

    fun addModel(model: String, first: Boolean) = viewModelScope.launch {
        val id = ModelIds.CUSTOM_PREFIX + model.trim()
        if (model.isBlank()) return@launch
        val cur = settings.currentModels().filter { it != id }
        settings.setModelPriority(if (first) listOf(id) + cur else cur + id)
        _status.value = "Added ${ModelIds.label(id)} ${if (first) "at the top" else "at the end"} of Model priority"
    }

    fun remove(id: String) = viewModelScope.launch { settings.setModelPriority(settings.currentModels().filter { it != id }) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProviderScreen(onBack: () -> Unit, vm: ProviderViewModel = hiltViewModel()) {
    val cfg by vm.store.config.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val priority by vm.priority.collectAsStateWithLifecycle()
    var preset by remember { mutableStateOf(cfg.presetId) }
    var base by remember { mutableStateOf(cfg.baseUrl) }
    var key by remember { mutableStateOf(vm.store.apiKey.orEmpty()) }
    var headers by remember { mutableStateOf(vm.store.headersText) }
    var manual by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("LLM provider") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("OpenRouter (API keys) and Kilo (keyless free models) are built in. Add any OpenAI-compatible API here; " +
                "its models appear in Model priority as \"model (Custom)\" and are used in that order.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderPresets.ALL.forEach { p ->
                    FilterChip(selected = preset == p.id, onClick = {
                        preset = p.id; if (p.baseUrl.isNotEmpty()) base = p.baseUrl
                    }, label = { Text(p.label) })
                }
            }
            ProviderPresets.byId(preset).note.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(base, { base = it }, label = { Text("Base URL") }, singleLine = true,
                placeholder = { Text("https://api.example.com/v1/") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(key, { key = it }, label = { Text("API key (stored encrypted; optional for local)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(headers, { headers = it }, label = { Text("Extra headers (optional, Name: value per line)") },
                minLines = 2, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { error = vm.save(preset, base, key, headers) }) { Text("Save") }
                OutlinedButton(enabled = cfg.configured, onClick = { vm.fetch() }) { Text("Fetch models") }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(manual, { manual = it }, label = { Text("Model id") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(enabled = manual.isNotBlank() && cfg.configured, onClick = { vm.addModel(manual, true); manual = "" }) { Text("Add") }
            }
            if (models.isNotEmpty()) {
                Text("Available models (tap to add at the top of Model priority)", style = MaterialTheme.typography.titleSmall)
                models.take(200).forEach { m ->
                    Text(m, Modifier.fillMaxWidth().clickable { vm.addModel(m, true) }.padding(vertical = 6.dp))
                }
            }
            val mine = priority.filter(ModelIds::isCustom)
            if (mine.isNotEmpty()) {
                HorizontalDivider()
                Text("Custom models in Model priority", style = MaterialTheme.typography.titleSmall)
                mine.forEach { id ->
                    Row { Text(ModelIds.label(id), Modifier.weight(1f).padding(vertical = 10.dp)); TextButton(onClick = { vm.remove(id) }) { Text("Remove") } }
                }
            }
        }
    }
}
