package com.verdroid.app.ui.provider

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
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
import com.verdroid.app.ui.menu.OpenRouterKeysSection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Built-in providers shown before the OpenAI-compatible presets. */
private data class BuiltIn(val id: String, val label: String)
private val BUILT_IN = listOf(BuiltIn("openrouter", "OpenRouter"), BuiltIn("kilo", "Kilo"))

@HiltViewModel
class ProviderViewModel @Inject constructor(
    val store: CustomProviderStore,
    private val client: CustomLlmClient,
    private val settings: SettingsRepository,
    val appPrefs: com.verdroid.app.data.prefs.AppPrefs,
) : ViewModel() {
    val priority = settings.modelPriority.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _models = MutableStateFlow<List<String>>(emptyList())
    val models = _models.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()

    /** Saves this provider and makes it the one `custom:` models use. Returns an error message or null. */
    fun save(presetId: String, base: String, key: String, headers: String): String? {
        val url = if (base.isBlank()) "" else ProviderUrls.normalize(base) ?: return "Invalid base URL"
        if (url.isNotEmpty() && !ProviderUrls.cleartextAllowed(url)) return "http:// only for localhost/LAN; use https"
        store.save(presetId, url, headers, key)
        _models.value = emptyList()
        _status.value = "Saved; ${ProviderPresets.byId(presetId).label} is now used for Custom models" + if (url.isNotEmpty()) " ($url)" else ""
        return null
    }

    fun fetch() = viewModelScope.launch {
        _status.value = "Fetching models…"
        client.listModels().onSuccess { _models.value = it; _status.value = if (it.isEmpty()) "No models listed; enter an id manually" else "${it.size} models" }
            .onFailure { _status.value = "Fetch failed (${it.message}); enter an id manually" }
    }

    fun addModel(model: String, first: Boolean) = viewModelScope.launch {
        if (model.isBlank()) return@launch
        val id = ModelIds.CUSTOM_PREFIX + model.trim()
        val cur = settings.currentModels().filter { it != id }
        settings.setModelPriority(if (first) listOf(id) + cur else cur + id)
        _status.value = "Added ${ModelIds.label(id)} ${if (first) "at the top" else "at the end"} of Model priority"
    }

    fun remove(id: String) = viewModelScope.launch { settings.setModelPriority(settings.currentModels().filter { it != id }) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProviderScreen(onBack: () -> Unit, initial: String? = null, vm: ProviderViewModel = hiltViewModel()) {
    val cfg by vm.store.config.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(initial ?: "openrouter") }
    val context = LocalContext.current
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("LLM providers") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BUILT_IN.forEach { b -> FilterChip(selected == b.id, { selected = b.id }, { Text(b.label) }) }
                ProviderPresets.ALL.forEach { p ->
                    FilterChip(selected == p.id, { selected = p.id }, {
                        Text(p.label + if (cfg.configured && cfg.presetId == p.id) " ✓" else "")
                    })
                }
            }
            HorizontalDivider()
            when (selected) {
                "openrouter" -> {
                    Text("OpenRouter", style = MaterialTheme.typography.titleMedium)
                    Text("Many free models (ids ending in :free). Add one or more keys: the primary is used first, the others " +
                        "when a key is rate-limited. Stored encrypted (Android Keystore).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { open("https://openrouter.ai/keys") }) { Text("Get key (openrouter.ai/keys)") }
                    OpenRouterKeysSection()
                }
                "kilo" -> {
                    val memToKilo by vm.appPrefs.sendMemoriesToKilo.collectAsStateWithLifecycle()
                    Text("Kilo Gateway", style = MaterialTheme.typography.titleMedium)
                    Text("Free models (e.g. kilo:kilo-auto/free) work without a key, about 200 requests/hour per IP. " +
                        "Add kilo:<model> ids in Model priority.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { open("https://kilo.ai") }) { Text("Open kilo.ai") }
                    ListItem(headlineContent = { Text("Send memories to Kilo models") },
                        supportingContent = { Text("Kilo Auto Free may log prompts") },
                        trailingContent = { Switch(memToKilo, vm.appPrefs::setSendMemoriesToKilo) })
                }
                else -> CompatibleProviderPanel(selected, vm, ::open)
            }
        }
    }
}

@Composable
private fun CompatibleProviderPanel(presetId: String, vm: ProviderViewModel, open: (String) -> Unit) {
    val preset = ProviderPresets.byId(presetId)
    val cfg by vm.store.config.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val priority by vm.priority.collectAsStateWithLifecycle()
    var base by remember(presetId) { mutableStateOf(vm.store.baseFor(presetId).ifBlank { preset.baseUrl }) }
    var key by remember(presetId) { mutableStateOf(vm.store.keyFor(presetId).orEmpty()) }
    var headers by remember(presetId) { mutableStateOf(vm.store.headersFor(presetId)) }
    var manual by remember { mutableStateOf("") }
    var error by remember(presetId) { mutableStateOf<String?>(null) }
    val active = cfg.configured && cfg.presetId == presetId

    Text(preset.label, style = MaterialTheme.typography.titleMedium)
    Text((if (active) "Active: Custom models in Model priority use this provider. " else "") +
        "OpenAI-compatible API; its models join Model priority as \"model (Custom)\". One provider is active at a time; " +
        "each keeps its own saved key.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    preset.note.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    preset.keyUrl?.let { u -> OutlinedButton(onClick = { open(u) }) { Text("Get key") } }
    OutlinedTextField(base, { base = it }, label = { Text("Base URL") }, singleLine = true,
        placeholder = { Text("https://api.example.com/v1/") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(key, { key = it }, label = { Text(if (preset.needsKey) "API key (stored encrypted)" else "API key (optional)") },
        singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(headers, { headers = it }, label = { Text("Extra headers (optional, Name: value per line)") },
        minLines = 2, modifier = Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { error = vm.save(presetId, base, key, headers) }) { Text(if (active) "Save" else "Save & use") }
        OutlinedButton(enabled = active, onClick = { vm.fetch() }) { Text("Fetch models") }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (active) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(manual, { manual = it }, label = { Text("Model id") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(enabled = manual.isNotBlank(), onClick = { vm.addModel(manual, true); manual = "" }) { Text("Add") }
        }
        if (models.isNotEmpty()) {
            Text("Available models (tap to add at the top of Model priority)", style = MaterialTheme.typography.titleSmall)
            models.take(200).forEach { m -> Text(m, Modifier.fillMaxWidth().clickable { vm.addModel(m, true) }.padding(vertical = 6.dp)) }
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
