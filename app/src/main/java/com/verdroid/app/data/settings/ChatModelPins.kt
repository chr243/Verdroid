package com.verdroid.app.data.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** One chat's pinned model (any Model priority id: OpenRouter, kilo:…, prov:<provider>:…); [allowFallback] off by default. */
data class ModelPin(val model: String, val allowFallback: Boolean = false)

/** Pure rules for per-chat model pins (unit-tested). */
object ModelPins {
    /** Models to try for a chat: global list when not pinned; only the pin (or pin first, then global) when pinned. */
    fun resolve(global: List<String>, pin: ModelPin?): List<String> = when {
        pin == null || pin.model.isBlank() -> global
        pin.allowFallback -> (listOf(pin.model.trim()) + global).distinct()
        else -> listOf(pin.model.trim())
    }

    /** Error text for a pinned chat whose model failed (no silent fallback). */
    fun failureMessage(pin: ModelPin, label: String, reason: String): String =
        "This chat is pinned to $label and it failed: $reason. Nothing else was tried. Change it in ⋮ → Model (or allow fallback)."

    fun encode(pin: ModelPin): String = (if (pin.allowFallback) "1|" else "0|") + pin.model.trim()
    fun decode(raw: String?): ModelPin? {
        val r = raw?.takeIf { it.length > 2 && it[1] == '|' } ?: return null
        return ModelPin(r.substring(2), r[0] == '1').takeIf { it.model.isNotBlank() }
    }
}

/** Per-chat model pins (chat ⋮ → Model). Not stored = follows Settings → Model priority (the default for new chats). */
interface ChatModelPinStore {
    fun pin(taskId: Long): ModelPin?
    fun set(taskId: Long, pin: ModelPin?)
    fun observe(taskId: Long): Flow<ModelPin?>

    class InMemory : ChatModelPinStore {
        private val state = MutableStateFlow<Map<Long, ModelPin>>(emptyMap())
        override fun pin(taskId: Long) = state.value[taskId]
        override fun set(taskId: Long, pin: ModelPin?) { state.value = if (pin == null) state.value - taskId else state.value + (taskId to pin) }
        override fun observe(taskId: Long): Flow<ModelPin?> = state.map { it[taskId] }
    }
}

@Singleton
class ChatModelPins @Inject constructor(@ApplicationContext context: Context) : ChatModelPinStore {
    private val prefs = context.getSharedPreferences("chat_model_pins", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(prefs.all.mapNotNull { (k, v) ->
        k.removePrefix("pin_").toLongOrNull()?.let { id -> ModelPins.decode(v as? String)?.let { id to it } }
    }.toMap())

    override fun pin(taskId: Long): ModelPin? = state.value[taskId]
    override fun observe(taskId: Long): Flow<ModelPin?> = state.map { it[taskId] }

    @Synchronized
    override fun set(taskId: Long, pin: ModelPin?) {
        if (taskId == 0L) return
        prefs.edit().apply { if (pin == null) remove("pin_$taskId") else putString("pin_$taskId", ModelPins.encode(pin)) }.apply()
        state.value = if (pin == null) state.value - taskId else state.value + (taskId to pin)
    }
}
