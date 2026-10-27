package com.verdroid.app.ui.provider

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.verdroid.app.data.network.ProviderPreset
import com.verdroid.app.data.network.ProviderPresets
import com.verdroid.app.data.network.ProviderUrls
import com.verdroid.app.domain.repository.ApiKeyRepository
import com.verdroid.app.domain.repository.SettingsRepository
import com.verdroid.app.ui.menu.OpenRouterKeysSection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Result line under a provider ("Connected: 42 models" / error). */
data class ProviderCheck(val ok: Boolean, val text: String)

@HiltViewModel
class ProviderViewModel @Inject constructor(
    val store: CustomProviderStore,
    private val client: CustomLlmClient,
    private val settings: SettingsRepository,
    apiKeys: ApiKeyRepository,
    val appPrefs: com.verdroid.app.data.prefs.AppPrefs,
    private val localModels: com.verdroid.app.data.localllm.LocalModelStore,
) : ViewModel() {
    val hasLocalModel: Boolean get() = localModels.models.value.isNotEmpty()
    val priority = settings.modelPriority.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val hasOpenRouterKey = apiKeys.keys.map { it.isNotEmpty() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    private val _available = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val available = _available.asStateFlow()
    private val _check = MutableStateFlow<Map<String, ProviderCheck>>(emptyMap())
    val check = _check.asStateFlow()

    /** Validates and saves one provider. Returns an error message, or null when saved. */
    fun save(p: ProviderPreset, base: String, key: String, headers: String): String? {
        val url = if (base.isBlank()) "" else ProviderUrls.normalize(base)
            ?: return "That address doesn't look right. Example: https://api.example.com/v1"
        if (url.isNotEmpty() && !ProviderUrls.cleartextAllowed(url)) return "Plain http:// only works for this phone (localhost) or *.local names. Use https://."
        if (p.id == "custom" && url.isEmpty()) return "Enter the server address"
        store.save(p.id, url, headers, key)
        _check.value = _check.value + (p.id to ProviderCheck(true, "Saved"))
        return null
    }

    /** Test connection = list models; on success also fills the "Add models" list. */
    fun test(p: ProviderPreset) = viewModelScope.launch {
        _check.value = _check.value + (p.id to ProviderCheck(true, "Checking…"))
        client.listModels(p.id).onSuccess {
            _available.value = _available.value + (p.id to it)
            _check.value = _check.value + (p.id to ProviderCheck(true,
                if (it.isEmpty()) "Connected. This server doesn't list its models; type a model name below." else "Connected · ${it.size} models available"))
        }.onFailure { _check.value = _check.value + (p.id to ProviderCheck(false, "Couldn't connect: ${it.message}")) }
    }

    fun addModel(providerId: String, model: String) = viewModelScope.launch {
        if (model.isBlank()) return@launch
        val id = ModelIds.prov(providerId, model)
        val cur = settings.currentModels().filter { it != id }
        settings.setModelPriority(listOf(id) + cur)
    }

    fun remove(id: String) = viewModelScope.launch { settings.setModelPriority(settings.currentModels().filter { it != id }) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProviderScreen(onBack: () -> Unit, onOpenModels: () -> Unit = {}, initial: String? = null, vm: ProviderViewModel = hiltViewModel()) {
    val version by vm.store.version.collectAsStateWithLifecycle()
    val hasOrKey by vm.hasOpenRouterKey.collectAsStateWithLifecycle()
    var selected by rememberSaveableString(initial ?: "openrouter")
    val context = LocalContext.current
    val open: (String) -> Unit = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }

    Scaffold(topBar = {
        TopAppBar(title = { Text("LLM providers") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Pick a provider to set it up. Models you add show up in Model priority, which decides the order " +
                "Verdroid tries them in.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // key(version): re-read "ready" state after every save
                key(version) {
                    ProviderChip("OpenRouter", "openrouter", selected, ready = hasOrKey) { selected = it }
                    ProviderChip("Kilo", "kilo", selected, ready = true) { selected = it }
                    ProviderPresets.ALL.forEach { p -> ProviderChip(p.shortName, p.id, selected, vm.store.isConfigured(p.id)) { selected = it } }
                    ProviderChip("Local (on-device)", "local", selected, ready = vm.hasLocalModel) { selected = it }
                }
            }
            Text("✓ = ready to use", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            when (selected) {
                "openrouter" -> {
                    SectionTitle("OpenRouter", if (hasOrKey) "Ready" else "Needs a key")
                    Help("Gives access to many models; the ones ending in \":free\" cost nothing. You can add several keys: " +
                        "Verdroid starts with the primary one and switches to the next if a key hits its limit. Keys are stored encrypted on this phone.")
                    OutlinedButton(onClick = { open("https://openrouter.ai/keys") }) { Text("Get a free key at openrouter.ai") }
                    OpenRouterKeysSection()
                    ModelPriorityHint(onOpenModels)
                }
                "kilo" -> {
                    val memToKilo by vm.appPrefs.sendMemoriesToKilo.collectAsStateWithLifecycle()
                    SectionTitle("Kilo", "Ready, no key needed")
                    Help("Kilo's free models work without an account or key (about 200 requests per hour). " +
                        "Kilo Auto Free is in Model priority by default.")
                    ListItem(headlineContent = { Text("Share memories with Kilo models") },
                        supportingContent = { Text("Off keeps your saved memories out of prompts sent to Kilo (its free model may log prompts).") },
                        trailingContent = { Switch(memToKilo, vm.appPrefs::setSendMemoriesToKilo) })
                    OutlinedButton(onClick = { open("https://kilo.ai") }) { Text("About Kilo") }
                    ModelPriorityHint(onOpenModels)
                }
                "local" -> LocalProviderPanel(onOpenModels)
                else -> key(selected, version) { CompatibleProviderPanel(ProviderPresets.byId(selected), vm, open, onOpenModels) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun rememberSaveableString(initial: String) = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initial) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderChip(label: String, id: String, selected: String, ready: Boolean, onSelect: (String) -> Unit) {
    FilterChip(
        selected = selected == id,
        onClick = { onSelect(id) },
        label = { Text(label) },
        leadingIcon = if (ready) ({ Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
        modifier = Modifier.semantics { contentDescription = "$label, ${if (ready) "ready" else "not set up"}" },
    )
}

@Composable
private fun SectionTitle(title: String, state: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        AssistChip(onClick = {}, label = { Text(state) }, enabled = false)
    }
}

@Composable
private fun Help(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun ModelPriorityHint(onOpenModels: () -> Unit) {
    TextButton(onClick = onOpenModels) { Text("Choose the order of models in Model priority →") }
}

@Composable
private fun CompatibleProviderPanel(p: ProviderPreset, vm: ProviderViewModel, open: (String) -> Unit, onOpenModels: () -> Unit) {
    val priority by vm.priority.collectAsStateWithLifecycle()
    val available by vm.available.collectAsStateWithLifecycle()
    val checks by vm.check.collectAsStateWithLifecycle()
    val ready = vm.store.isConfigured(p.id)
    val savedBase = vm.store.baseFor(p.id)
    var base by remember { mutableStateOf(savedBase) }
    var key by remember { mutableStateOf(vm.store.keyFor(p.id).orEmpty()) }
    var headers by remember { mutableStateOf(vm.store.headersFor(p.id)) }
    var advanced by remember { mutableStateOf(headers.isNotBlank() || (p.needsKey && savedBase != p.baseUrl)) }
    var error by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf("") }
    val dirty = base != savedBase || key != vm.store.keyFor(p.id).orEmpty() || headers != vm.store.headersFor(p.id)

    SectionTitle(p.label, if (ready) "Ready" else if (p.needsKey) "Needs a key" else "Needs an address")
    Help(when {
        p.needsKey -> "Paste your ${p.shortName} API key. It's stored encrypted on this phone and only sent to ${p.shortName}."
        p.id == "custom" -> "Any service that speaks the OpenAI API. Enter its address and, if it needs one, a key."
        else -> "${p.note}. No key needed. On this phone use http://localhost; another device needs an https address or a *.local name."
    })
    p.keyUrl?.let { u -> OutlinedButton(onClick = { open(u) }) { Text("Get a ${p.shortName} key") } }

    if (p.needsKey || p.id == "custom") {
        OutlinedTextField(key, { key = it; error = null }, label = { Text(if (p.needsKey) "API key" else "API key (optional)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    }
    if (p.urlEditable || advanced) {
        OutlinedTextField(base, { base = it; error = null }, label = { Text("Server address") }, singleLine = true,
            placeholder = { Text(p.baseUrl.ifBlank { "https://api.example.com/v1" }) },
            supportingText = { Text("The API's base address, usually ending in /v1") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
    }
    TextButton(onClick = { advanced = !advanced }) {
        Icon(if (advanced) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
        Text(if (advanced) "Hide advanced" else "Advanced")
    }
    if (advanced) {
        if (!p.urlEditable) Help("Default address: ${p.baseUrl}. Only change it if you use a proxy.")
        OutlinedTextField(headers, { headers = it }, label = { Text("Extra headers (optional)") }, minLines = 2,
            supportingText = { Text("One per line, like  X-Org-Id: 123. Rarely needed.") }, modifier = Modifier.fillMaxWidth())
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = dirty || !ready, onClick = { error = vm.save(p, base, key, headers) }) { Text("Save") }
        OutlinedButton(enabled = ready && !dirty, onClick = { vm.test(p) }) { Text("Test connection") }
    }
    checks[p.id]?.let { c ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (c.ok) Icons.Filled.Check else Icons.Filled.Close, null,
                tint = if (c.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(c.text, style = MaterialTheme.typography.bodySmall, color = if (c.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
        }
    }

    // Models from this provider (stored as prov:<id>:<model>; they show as "model (Name)" in Model priority).
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text("Models from ${p.shortName}", style = MaterialTheme.typography.titleSmall)
    val mine = priority.filter { ModelIds.isCustom(it) && (ModelIds.providerIdOf(it) ?: "") == p.id }
    if (mine.isEmpty()) Help(if (ready) "None yet. Add one below." else "Save this provider first, then add models.")
    mine.forEach { id ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ModelIds.bare(id), Modifier.weight(1f))
            TextButton(onClick = { vm.remove(id) }, modifier = Modifier.semantics { contentDescription = "Remove ${ModelIds.bare(id)}" }) { Text("Remove") }
        }
    }
    if (ready) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(manual, { manual = it }, label = { Text("Model name") }, singleLine = true,
                placeholder = { Text(MODEL_EXAMPLES[p.id] ?: "model-name") }, modifier = Modifier.weight(1f))
            Button(enabled = manual.isNotBlank(), onClick = { vm.addModel(p.id, manual); manual = "" }) { Text("Add") }
        }
        val list = available[p.id].orEmpty().filter { m -> mine.none { ModelIds.bare(it) == m } }
        if (list.isEmpty()) TextButton(onClick = { vm.test(p) }) { Text("Show available models") }
        else {
            Help("Tap a model to add it (it goes to the top of Model priority):")
            list.take(150).forEach { m ->
                AssistChip(onClick = { vm.addModel(p.id, m) }, label = { Text(m) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    ModelPriorityHint(onOpenModels)
}

private val MODEL_EXAMPLES = mapOf(
    "openai" to "gpt-4o-mini", "groq" to "llama-3.1-8b-instant", "deepseek" to "deepseek-chat",
    "together" to "meta-llama/Llama-3.3-70B-Instruct-Turbo", "ollama" to "llama3.2", "lmstudio" to "the loaded model's id",
)
