package com.verdroid.app.ui.menu

import kotlinx.coroutines.async
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdroid.app.chathead.ChatHeadController
import com.verdroid.app.data.network.RateLimitHeaderStore
import com.verdroid.app.domain.model.ChatHeadMode
import com.verdroid.app.domain.model.LimitSettings
import com.verdroid.app.domain.repository.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val keys: ApiKeyRepository,
    private val settings: SettingsRepository,
    private val quota: QuotaRepository,
    private val rateLimits: RateLimitRepository,
    headerStore: RateLimitHeaderStore,
    val chatHeads: ChatHeadController,
    val appPrefs: com.verdroid.app.data.prefs.AppPrefs,
    private val kiloUsage: com.verdroid.app.data.network.KiloUsage,
    val updater: com.verdroid.app.data.update.AppUpdater,
    val subAgents: com.verdroid.app.agent.subagents.SubAgentPrefs,
    val research: com.verdroid.app.agent.research.ResearchPrefs,
    val toolPrefs: com.verdroid.app.data.tools.ToolPrefs,
    val tracker: com.verdroid.app.data.network.KeyHealthTracker,
    private val cooldowns: com.verdroid.app.data.network.CooldownTracker,
    private val providerStore: com.verdroid.app.data.network.CustomProviderStore,
    private val customLlm: com.verdroid.app.data.network.CustomLlmClient,
) : ViewModel() {
    /** Settings → OpenRouter key → Reset: clears its cooldowns and status. */
    fun resetOpenRouterKey(id: String) { cooldowns.unblockKey(id); tracker.reset(com.verdroid.app.data.network.KeyHealthTracker.OPENROUTER, id) }

    /** Model picker: models listed by each configured OpenAI-compatible provider (GET /models), merged. */
    val discovered = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val discovering = MutableStateFlow(false)
    val discoverErrors = MutableStateFlow<List<String>>(emptyList())
    fun providerName(id: String) = com.verdroid.app.data.network.ProviderNames.short(id)
    fun discover() = viewModelScope.launch { kotlinx.coroutines.coroutineScope {
        discovering.value = true
        val errs = ArrayList<String>(); val out = LinkedHashMap<String, List<String>>()
        try {
            val ps = providerStore.allProviders().filter { providerStore.isConfigured(it.id) }
            val results = ps.map { p -> p to async { customLlm.listModels(p.id) } }
            for ((p, r) in results) r.await().fold({ if (it.isNotEmpty()) out[p.id] = it }, { errs += "${p.shortName}: ${it.message}" })
            if (ps.isEmpty()) errs += "No other providers set up (Settings → LLM providers)"
        } finally { discovered.value = out; discoverErrors.value = errs; discovering.value = false } }
    }
    fun addProviderModel(providerId: String, model: String) = viewModelScope.launch {
        val id = com.verdroid.app.data.network.ModelIds.prov(providerId, model)
        if (id !in models.value) settings.setModelPriority(models.value + id)
    }
    /** Kilo requests in the last hour (in-app counter, refreshed every 5 s while visible). */
    val kiloUsedThisHour: StateFlow<Int> = kotlinx.coroutines.flow.flow {
        while (true) { emit(kiloUsage.counter.used()); kotlinx.coroutines.delay(5_000) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val chatHeadMode = settings.chatHeadMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatHeadMode.AUTO)
    fun setChatHeadMode(mode: ChatHeadMode) = viewModelScope.launch { settings.setChatHeadMode(mode) }

    val apiKeys = keys.keys
    val models = settings.modelPriority.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val limits = settings.limits.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LimitSettings())
    val quotaInfo = quota.quota.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val lastHeaders = headerStore.latest
    val events = rateLimits.observeEvents().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val refreshing = MutableStateFlow(false)

    fun addKey(label: String, key: String) = viewModelScope.launch { keys.add(label, key) }
    fun removeKey(id: String) = viewModelScope.launch { keys.remove(id) }
    fun moveKey(id: String, delta: Int) = viewModelScope.launch { keys.move(id, delta) }
    fun setPrimary(id: String) = viewModelScope.launch { keys.setPrimary(id) }
    fun renameKey(id: String, label: String) = viewModelScope.launch { keys.rename(id, label) }

    fun addModel(model: String) = viewModelScope.launch {
        val m = model.trim()
        if (m.isNotEmpty()) settings.setModelPriority(models.value + m)
    }
    fun removeModel(index: Int) = viewModelScope.launch {
        settings.setModelPriority(models.value.toMutableList().apply { if (index in indices) removeAt(index) })
    }
    fun moveModel(index: Int, delta: Int) = viewModelScope.launch {
        val list = models.value.toMutableList()
        val j = index + delta
        if (index in list.indices && j in list.indices) { list.add(j, list.removeAt(index)); settings.setModelPriority(list) }
    }
    fun resetModels() = viewModelScope.launch { settings.resetModelPriority() }

    fun saveLimits(l: LimitSettings) = viewModelScope.launch { settings.updateLimits { l } }

    fun refreshQuota() = viewModelScope.launch {
        refreshing.value = true
        try { quota.refresh() } finally { refreshing.value = false }
    }

    fun clearLog() = viewModelScope.launch { rateLimits.clear() }
}
