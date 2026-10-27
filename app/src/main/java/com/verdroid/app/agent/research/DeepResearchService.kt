package com.verdroid.app.agent.research

import android.content.Context
import com.verdroid.app.agent.subagents.SubAgentLoop
import com.verdroid.app.agent.subagents.SubAgentPolicy
import com.verdroid.app.agent.subagents.SubAgentPrefs
import com.verdroid.app.agent.tools.ToolRegistry
import com.verdroid.app.data.network.OpenRouterClient
import com.verdroid.app.data.settings.ModelPin
import com.verdroid.app.data.settings.ModelPins
import com.verdroid.app.data.storage.SharedFolder
import com.verdroid.app.domain.repository.TaskRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Settings → Sub-agents → Deep research: source caps, deep rounds, concurrency and the worker model (unset = sub-agent model). */
@Singleton
class ResearchPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("deep_research", Context.MODE_PRIVATE)
    private fun flow(key: String, def: Int, r: IntRange) = MutableStateFlow(prefs.getInt(key, def).coerceIn(r))
    private val _std = flow("std_sources", ResearchPolicy.STANDARD_SOURCES, ResearchPolicy.SOURCES_RANGE)
    val standardSources: StateFlow<Int> = _std.asStateFlow()
    private val _deep = flow("deep_sources", ResearchPolicy.DEEP_SOURCES, ResearchPolicy.SOURCES_RANGE)
    val deepSources: StateFlow<Int> = _deep.asStateFlow()
    private val _rounds = flow("deep_rounds", ResearchPolicy.DEEP_ROUNDS, ResearchPolicy.ROUNDS_RANGE)
    val deepRounds: StateFlow<Int> = _rounds.asStateFlow()
    private val _conc = flow("concurrency", SubAgentPolicy.DEFAULT_CONCURRENCY, SubAgentPolicy.MIN_CONCURRENCY..SubAgentPolicy.MAX_CONCURRENCY)
    val concurrency: StateFlow<Int> = _conc.asStateFlow()
    /** false until the user moves the slider: free worker models then default to SubAgentPolicy.FREE_DEFAULT_CONCURRENCY. */
    val concurrencyUserSet: Boolean get() = prefs.contains("concurrency")
    private val _model = MutableStateFlow(ModelPins.decode(prefs.getString("model", null)))
    /** null = Settings → Sub-agents → Model (which itself defaults to the main model). */
    val model: StateFlow<ModelPin?> = _model.asStateFlow()

    private fun set(f: MutableStateFlow<Int>, key: String, v: Int, r: IntRange) { val c = v.coerceIn(r); prefs.edit().putInt(key, c).apply(); f.value = c }
    fun setStandardSources(v: Int) = set(_std, "std_sources", v, ResearchPolicy.SOURCES_RANGE)
    fun setDeepSources(v: Int) = set(_deep, "deep_sources", v, ResearchPolicy.SOURCES_RANGE)
    fun setDeepRounds(v: Int) = set(_rounds, "deep_rounds", v, ResearchPolicy.ROUNDS_RANGE)
    fun setConcurrency(v: Int) = set(_conc, "concurrency", v, SubAgentPolicy.MIN_CONCURRENCY..SubAgentPolicy.MAX_CONCURRENCY)
    fun setModel(pin: ModelPin?) {
        val p = pin?.takeIf { it.model.isNotBlank() }
        prefs.edit().apply { if (p == null) remove("model") else putString("model", ModelPins.encode(p)) }.apply()
        _model.value = p
    }
}

/** Live progress per chat while deep_research runs. */
object ResearchLive {
    private val _progress = MutableStateFlow<Map<Long, ResearchProgress>>(emptyMap())
    val progress: StateFlow<Map<Long, ResearchProgress>> = _progress.asStateFlow()
    fun set(taskId: Long, p: ResearchProgress?) {
        synchronized(this) { _progress.value = if (p == null) _progress.value - taskId else _progress.value + (taskId to p) }
    }
}

/** Saved report: [display] = path the user sees (Documents/Verdroid/Output/Research/…). */
data class SavedReport(val file: File, val display: String)

@Singleton
class DeepResearchService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OpenRouterClient,
    private val registry: Provider<ToolRegistry>,
    private val subPrefs: SubAgentPrefs,
    private val prefs: ResearchPrefs,
    private val shared: SharedFolder,
    private val tasks: TaskRepository,
) : DeepResearchBackend {
    override suspend fun research(taskId: Long, topic: String, mode: ResearchMode, language: String?): DeepResearchResult {
        val reg = registry.get()
        val tools = SubAgentPolicy.allowedTools(reg.namesFor(taskId), subPrefs.tools.value) intersect ResearchPolicy.WORKER_TOOLS.toSet()
        if ("web_search" !in tools) return DeepResearchResult.Error("web_search is off for this chat or for sub-agents (Settings → Tools / Sub-agents → Tools); deep research needs web_search and web_fetch.")
        val schemas = JsonArray(reg.schemas(taskId).filter { s ->
            ((s as? JsonObject)?.get("function") as? JsonObject)?.get("name")?.let { (it as? JsonPrimitive)?.content } in tools
        })
        val pin = prefs.model.value ?: subPrefs.model.value
        val conc = SubAgentPolicy.effectiveConcurrency(prefs.concurrency.value, prefs.concurrencyUserSet, client.firstModelIsFree(pin))
        val limits = ResearchPolicy.limits(mode, prefs.standardSources.value, prefs.deepSources.value, prefs.deepRounds.value, conc)
        val fallback: (suspend (List<com.verdroid.app.data.network.ApiMessage>, JsonArray?) -> com.verdroid.app.data.network.ChatOutcome)? =
            if (subPrefs.fallbackOnRateLimit.value && pin != null) { m, t -> client.complete(m, t, taskId) } else null
        val waits = com.verdroid.app.agent.subagents.RateWaitCounter()
        val steps = subPrefs.maxSteps.value.coerceAtLeast(limits.perWorkerSources + 4)
        val timeoutMs = maxOf(subPrefs.timeoutSec.value, 300) * 1000L
        try {
            val out = DeepResearchEngine(
                main = { msgs -> client.complete(msgs, null, taskId) },
                worker = { i, task ->
                    SubAgentLoop(
                        model = { m, t -> client.completeSubAgent(m, t, taskId, pin) },
                        execute = { name, args -> reg.execute(name, args, taskId) },
                        toolSchemas = schemas, allowed = tools, maxSteps = steps, timeoutMs = timeoutMs,
                        maxFindingsChars = ResearchPolicy.WORKER_FINDINGS_CHARS,
                        fallback = fallback, onWait = waits::set, rateWaitBudgetMs = SubAgentPolicy.RATE_WAIT_BUDGET_MS,
                    ).run(i, task)
                },
                waits = waits,
                onProgress = { p ->
                    ResearchLive.set(taskId, p)
                    if (taskId > 0) runCatching { tasks.updateSubtitle(taskId, p.label) }
                },
            ).run(topic, mode, limits, language)
            val saved = withContext(Dispatchers.IO) { save(out) }
            return DeepResearchResult.Done(out, saved?.display, pin?.model)
        } finally { ResearchLive.set(taskId, null) }
    }

    private fun save(o: ResearchOutcome): SavedReport? = runCatching {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val name = ResearchPolicy.fileName(o.topic, stamp)
        val (dir, display) = if (shared.hasAccess() && shared.ensure()) File(shared.output, "Research") to "${SharedFolder.DISPLAY_PATH}/Output/Research"
            else File(context.filesDir, "research") to "app storage/research"
        dir.mkdirs()
        var f = File(dir, name); var n = 2
        while (f.exists()) { f = File(dir, name.removeSuffix(".md") + " ($n).md"); n++ } // never overwrite
        f.writeText(o.report)
        SavedReport(f, "$display/${f.name}")
    }.getOrNull()
}
