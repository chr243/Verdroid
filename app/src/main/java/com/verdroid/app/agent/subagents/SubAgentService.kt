package com.verdroid.app.agent.subagents

import android.content.Context
import com.verdroid.app.agent.tools.ToolRegistry
import com.verdroid.app.data.network.OpenRouterClient
import com.verdroid.app.data.settings.ModelPin
import com.verdroid.app.data.settings.ModelPins
import com.verdroid.app.domain.repository.TaskRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Settings → Sub-agents: model (unset = the chat's own model), concurrency, per-call cap, steps, timeout, tools. On/off = Settings → Tools switch of spawn_subagents. */
@Singleton
class SubAgentPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("subagents", Context.MODE_PRIVATE)
    private val _model = MutableStateFlow(ModelPins.decode(prefs.getString(KEY_MODEL, null)))
    /** null = use the main model (the chat's pin / profile / Model priority, like the main agent). */
    val model: StateFlow<ModelPin?> = _model.asStateFlow()
    private val _concurrency = MutableStateFlow(SubAgentPolicy.clampConcurrency(prefs.getInt(KEY_CONC, SubAgentPolicy.DEFAULT_CONCURRENCY)))
    val concurrency: StateFlow<Int> = _concurrency.asStateFlow()

    private fun intFlow(key: String, def: Int, range: IntRange) = MutableStateFlow(prefs.getInt(key, def).coerceIn(range))
    private val _perCall = intFlow(KEY_PER_CALL, SubAgentPolicy.DEFAULT_PER_CALL, 1..SubAgentPolicy.MAX_TASKS)
    val perCall: StateFlow<Int> = _perCall.asStateFlow()
    private val _steps = intFlow(KEY_STEPS, SubAgentPolicy.MAX_STEPS, SubAgentPolicy.STEPS_RANGE)
    val maxSteps: StateFlow<Int> = _steps.asStateFlow()
    private val _timeout = intFlow(KEY_TIMEOUT, (SubAgentPolicy.TIMEOUT_MS / 1000).toInt(), SubAgentPolicy.TIMEOUT_SEC_RANGE)
    val timeoutSec: StateFlow<Int> = _timeout.asStateFlow()
    private val _tools = MutableStateFlow(prefs.getStringSet(KEY_TOOLS, null)?.toSet() ?: SubAgentPolicy.DEFAULT_TOOLS)
    /** Selected sub-agent tools (subset of [SubAgentPolicy.READ_ONLY_TOOLS]). */
    val tools: StateFlow<Set<String>> = _tools.asStateFlow()

    fun setPerCall(n: Int) { val v = n.coerceIn(1, SubAgentPolicy.MAX_TASKS); prefs.edit().putInt(KEY_PER_CALL, v).apply(); _perCall.value = v }
    fun setMaxSteps(n: Int) { val v = n.coerceIn(SubAgentPolicy.STEPS_RANGE); prefs.edit().putInt(KEY_STEPS, v).apply(); _steps.value = v }
    fun setTimeoutSec(n: Int) { val v = n.coerceIn(SubAgentPolicy.TIMEOUT_SEC_RANGE); prefs.edit().putInt(KEY_TIMEOUT, v).apply(); _timeout.value = v }
    fun setTool(name: String, on: Boolean) {
        if (name !in SubAgentPolicy.READ_ONLY_TOOLS) return
        val v = if (on) _tools.value + name else _tools.value - name
        prefs.edit().putStringSet(KEY_TOOLS, v).apply(); _tools.value = v
    }
    fun resetTools() { prefs.edit().remove(KEY_TOOLS).apply(); _tools.value = SubAgentPolicy.DEFAULT_TOOLS }

    fun setModel(pin: ModelPin?) {
        val p = pin?.takeIf { it.model.isNotBlank() }
        prefs.edit().apply { if (p == null) remove(KEY_MODEL) else putString(KEY_MODEL, ModelPins.encode(p)) }.apply()
        _model.value = p
    }

    fun setConcurrency(n: Int) {
        val v = SubAgentPolicy.clampConcurrency(n)
        prefs.edit().putInt(KEY_CONC, v).apply(); _concurrency.value = v
    }

    private companion object { const val KEY_MODEL = "model"; const val KEY_CONC = "concurrency"
        const val KEY_PER_CALL = "per_call"; const val KEY_STEPS = "max_steps"; const val KEY_TIMEOUT = "timeout_sec"; const val KEY_TOOLS = "tools" }
}

/** Live progress per parent chat while spawn_subagents runs (chat card). */
object SubAgentLive {
    private val _progress = MutableStateFlow<Map<Long, SubAgentProgress>>(emptyMap())
    val progress: StateFlow<Map<Long, SubAgentProgress>> = _progress.asStateFlow()
    fun set(taskId: Long, p: SubAgentProgress?) {
        synchronized(this) { _progress.value = if (p == null) _progress.value - taskId else _progress.value + (taskId to p) }
    }
}

@Singleton
class SubAgentService @Inject constructor(
    private val client: OpenRouterClient,
    private val registry: Provider<ToolRegistry>,
    private val prefs: SubAgentPrefs,
    private val tasks: TaskRepository,
) : SubAgentBackend {
    override suspend fun runAll(parentTaskId: Long, tasks: List<SubAgentTask>, modelOverride: String?): SubAgentBatch {
        val reg = registry.get()
        val allowed = SubAgentPolicy.allowedTools(reg.namesFor(parentTaskId), prefs.tools.value)
        val known = reg.tools.map { it.name }.toSet()
        val allSchemas = reg.schemas(parentTaskId)
        fun schemasFor(names: Set<String>) = JsonArray(allSchemas.filter { s ->
            ((s as? JsonObject)?.get("function") as? JsonObject)?.get("name")?.let { (it as? JsonPrimitive)?.content } in names
        })
        val pin = modelOverride?.let { ModelPin(it, allowFallback = false) } ?: prefs.model.value
        val conc = prefs.concurrency.value
        val maxSteps = prefs.maxSteps.value
        val timeoutMs = prefs.timeoutSec.value * 1000L
        try {
            val results = SubAgentPool.run(tasks, conc,
                onProgress = { p ->
                    SubAgentLive.set(parentTaskId, p)
                    if (parentTaskId > 0) runCatching { this.tasks.updateSubtitle(parentTaskId, p.label) }
                },
                failed = { it.status != SubAgentResult.Status.OK },
            ) { i, t ->
                val choice = SubAgentToolChoice.choose(t.tools, allowed, known)
                SubAgentLoop(
                    model = { msgs, tools -> client.completeSubAgent(msgs, tools, parentTaskId, pin) },
                    execute = { name, args -> reg.execute(name, args, parentTaskId) },
                    toolSchemas = schemasFor(choice.tools), allowed = choice.tools, maxSteps = maxSteps, timeoutMs = timeoutMs,
                ).run(i, t.prompt).copy(tools = choice.tools, rejectedTools = choice.rejected)
            }
            return SubAgentBatch(results, pin?.model, conc, allowed)
        } catch (e: CancellationException) { throw e }
        finally { SubAgentLive.set(parentTaskId, null) }
    }
}
