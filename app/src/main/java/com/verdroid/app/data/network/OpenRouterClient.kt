package com.verdroid.app.data.network

import com.verdroid.app.domain.model.ApiKey
import com.verdroid.app.domain.model.RateLimitEvent
import com.verdroid.app.domain.repository.ApiKeyRepository
import com.verdroid.app.domain.repository.RateLimitRepository
import com.verdroid.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ChatOutcome {
    data class Success(val completion: ParsedCompletion, val model: String, val keyLabel: String) : ChatOutcome
    /** Every model on every key is rate-limited / cooling down. [resumeAt] = earliest time something frees up. */
    data class RateLimited(
        val resumeAt: Long,
        val message: String,
        /** All usable keys are out of daily free quota -> pause until 00:00 UTC. */
        val dailyQuota: Boolean = false,
        /** [resumeAt] comes from an X-RateLimit-Reset header and must be honoured over backoff. */
        val explicitReset: Boolean = false,
    ) : ChatOutcome
    data class Failure(val message: String) : ChatOutcome
    data object NoApiKey : ChatOutcome
}

/** Outcome of a pinned chat (no fallback): failures get a clear "pinned" message; rate limits wait for the same model. */
object PinnedOutcome {
    fun map(pin: com.verdroid.app.data.settings.ModelPin, label: String, out: ChatOutcome): ChatOutcome = when (out) {
        is ChatOutcome.Failure -> ChatOutcome.Failure(com.verdroid.app.data.settings.ModelPins.failureMessage(pin, label, out.message))
        ChatOutcome.NoApiKey -> ChatOutcome.Failure(com.verdroid.app.data.settings.ModelPins.failureMessage(pin, label,
            "no API key for its provider (add one in Settings → LLM providers)"))
        is ChatOutcome.RateLimited -> out.copy(message = "Pinned model $label: ${out.message}")
        is ChatOutcome.Success -> out
    }
}

/**
 * Sends chat completions with Phase-2 fallback:
 *  - 429 / 5xx / upstream / bad-request on a model -> cooldown that model, try the next one in the priority list
 *  - key-wide limits (402, 401/403, daily quota exhausted) -> rotate to the next API key
 *  - all models rate-limited on a key -> rotate key; all keys exhausted -> [ChatOutcome.RateLimited]
 */
@Singleton
class OpenRouterClient @Inject constructor(
    private val api: OpenRouterApi,
    private val apiKeys: ApiKeyRepository,
    private val settings: SettingsRepository,
    private val limiter: ClientRateLimiter,
    private val cooldowns: CooldownTracker,
    private val rateLimitLog: RateLimitRepository,
    private val kilo: KiloProvider,
    private val custom: CustomLlmClient,
    private val local: com.verdroid.app.data.localllm.LocalLlmClient,
    private val appPrefs: com.verdroid.app.data.prefs.AppPrefs,
    private val vision: ModelCapabilities,
    private val pins: com.verdroid.app.data.settings.ChatModelPinStore,
    private val profiles: com.verdroid.app.data.profiles.ProfileLookup,
) {
    /** Keeps images only for models whose metadata lists image input. */
    private suspend fun forModel(id: String, msgs: List<ApiMessage>): List<ApiMessage> =
        if (msgs.none { !it.images.isNullOrEmpty() } || runCatching { vision.supportsImages(id) }.getOrDefault(false)) msgs
        else ImageStrip.strip(msgs)

    private val _lastModel = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** Priority-list id of the model that answered the latest request (drives the quota dot). */
    val lastModel: kotlinx.coroutines.flow.StateFlow<String?> = _lastModel

    /**
     * Provider layer: Kilo models (`kilo:` ids, no key) and OpenRouter models (key rotation). The first entry of the
     * priority list decides which provider goes first; the other one is the fallback. Works with no OpenRouter key as
     * long as a Kilo model is in the list.
     */
    suspend fun complete(
        messages: List<ApiMessage>,
        tools: JsonArray?,
        taskId: Long?,
        status: suspend (String) -> Unit = {},
    ): ChatOutcome {
        val global = settings.currentModels().ifEmpty { com.verdroid.app.domain.model.DefaultModels.list }
        // Per-chat pin (chat ⋮ → Model): only that model, unless the pin allows fallback. No silent swap.
        val pin = taskId?.takeIf { it > 0 }?.let { pins.pin(it) }
        // A profile's model goes first, with fallback to Model priority (a chat pin still wins).
        val profileModel = taskId?.takeIf { it > 0 }?.let { profiles.profileOf(it).model.trim() }.orEmpty()
        val models = com.verdroid.app.data.settings.ModelPins.resolve(global,
            pin ?: profileModel.takeIf { it.isNotEmpty() }?.let { com.verdroid.app.data.settings.ModelPin(it, allowFallback = true) })
        val out = completeWith(models, messages, tools, taskId, status)
        return if (pin != null && !pin.allowFallback) PinnedOutcome.map(pin, ModelIds.label(pin.model), out) else out
    }

    /**
     * Sub-agents (spawn_subagents): [pin] = Settings → Sub-agent model or the call's model override; null = same models as
     * the main agent of [parentTaskId] ([complete]). A pin without fallback uses only that model.
     */
    suspend fun completeSubAgent(
        messages: List<ApiMessage>,
        tools: JsonArray?,
        parentTaskId: Long,
        pin: com.verdroid.app.data.settings.ModelPin?,
    ): ChatOutcome {
        if (pin == null || pin.model.isBlank()) return complete(messages, tools, parentTaskId)
        val global = settings.currentModels().ifEmpty { com.verdroid.app.domain.model.DefaultModels.list }
        return completeWith(com.verdroid.app.data.settings.ModelPins.resolve(global, pin), messages, tools, parentTaskId) {}
    }

    private suspend fun completeWith(
        models: List<String>,
        messages: List<ApiMessage>,
        tools: JsonArray?,
        taskId: Long?,
        status: suspend (String) -> Unit,
    ): ChatOutcome {
        val kiloModels = models.filter(ModelIds::isKilo)
        val customModels = models.filter(ModelIds::isCustom)
        val localModels = models.filter(ModelIds::isLocal)
        val orModels = models.filter(ModelIds::isOpenRouter)
        val keys = apiKeys.orderedForUse()
        if (keys.isEmpty() && kiloModels.isEmpty() && customModels.isEmpty() && localModels.isEmpty()) return ChatOutcome.NoApiKey
        val cooldownMs = settings.currentLimits().modelCooldownSeconds.coerceAtLeast(1) * 1000L
        var kiloMiss: KiloResult.Unavailable? = null

        suspend fun tryKilo(): ChatOutcome? {
            if (kiloModels.isEmpty()) return null
            val msgs = if (appPrefs.sendMemoriesToKilo.value) messages else MemoryRedaction.strip(messages)
            return when (val r = kilo.complete(kiloModels, msgs, tools, cooldownMs, status, ::forModel)) {
                is KiloResult.Success -> { _lastModel.value = r.id; ChatOutcome.Success(r.completion, r.label, KiloApi.KEY_LABEL) }
                is KiloResult.Unavailable -> {
                    kiloMiss = r
                    rateLimitLog.log(RateLimitEvent(0, System.currentTimeMillis(), taskId, kiloModels.joinToString(),
                        KiloApi.KEY_LABEL, if (r.rateLimited) 429 else 0, null, null, r.resumeAt?.toString(),
                        if (r.rateLimited) "kilo_rate_limit" else "kilo_error", r.message, "Kilo unavailable → fallback to OpenRouter"))
                    null
                }
            }
        }

        var customMiss: KiloResult.Unavailable? = null
        suspend fun tryCustom(): ChatOutcome? {
            if (customModels.isEmpty()) return null
            return when (val r = custom.complete(customModels, messages, tools, cooldownMs, status, ::forModel)) {
                is KiloResult.Success -> { _lastModel.value = r.id; ChatOutcome.Success(r.completion, r.label, "Custom provider") }
                is KiloResult.Unavailable -> { customMiss = r; null }
            }
        }
        var localMiss: KiloResult.Unavailable? = null
        suspend fun tryLocal(): ChatOutcome? {
            if (localModels.isEmpty()) return null
            return when (val r = local.complete(localModels, messages, tools, status)) {
                is KiloResult.Success -> { _lastModel.value = r.id; ChatOutcome.Success(r.completion, r.label, "On-device") }
                is KiloResult.Unavailable -> {
                    localMiss = r
                    rateLimitLog.log(RateLimitEvent(0, System.currentTimeMillis(), taskId, localModels.joinToString(),
                        "On-device", 0, null, null, null, "local_unavailable", r.message, "On-device model unavailable → fallback"))
                    null
                }
            }
        }
        // On-device models go first when one heads the priority list (then the rest in order); otherwise they are the last fallback.
        val localFirst = ModelIds.isLocal(models.first())
        if (localFirst) tryLocal()?.let { return it }
        val rest = models.filterNot(ModelIds::isLocal)
        if (rest.isEmpty()) return ChatOutcome.Failure(localMiss?.message ?: "No on-device model available")
        // Custom provider first when it heads the priority list; otherwise it is the last fallback.
        val customFirst = ModelIds.isCustom(rest.first())
        if (customFirst) tryCustom()?.let { return it }
        val kiloFirst = ModelIds.isKilo(rest.first()) || (customFirst && rest.firstOrNull { !ModelIds.isCustom(it) }?.let(ModelIds::isKilo) == true)
        if (kiloFirst) tryKilo()?.let { return it }
        // No OpenRouter key → OpenRouter entries are skipped instantly (no request, no 401, no cooldown) and Kilo/custom are tried.
        val orOutcome = if (ModelIds.openRouterUsable(orModels, keys.size)) completeOpenRouter(keys, orModels, messages, tools, taskId, status) else null
        if (orOutcome is ChatOutcome.Success) return orOutcome
        if (!kiloFirst) tryKilo()?.let { return it }
        if (!customFirst) tryCustom()?.let { return it }
        if (!localFirst) tryLocal()?.let { return it }
        val miss = kiloMiss ?: customMiss ?: localMiss
        return when {
            orOutcome == null && miss == null -> ChatOutcome.NoApiKey
            orOutcome == null -> if (miss!!.resumeAt != null) ChatOutcome.RateLimited(miss.resumeAt!!, miss.message)
                else ChatOutcome.Failure(miss.message)
            orOutcome is ChatOutcome.RateLimited && miss?.resumeAt != null && miss.resumeAt < orOutcome.resumeAt ->
                ChatOutcome.RateLimited(miss.resumeAt, orOutcome.message)
            else -> orOutcome
        }
    }

    private suspend fun completeOpenRouter(
        keys: List<ApiKey>,
        models: List<String>,
        messages: List<ApiMessage>,
        tools: JsonArray?,
        taskId: Long?,
        status: suspend (String) -> Unit,
    ): ChatOutcome {
        val limits = settings.currentLimits()
        val cooldownMs = limits.modelCooldownSeconds.coerceAtLeast(1) * 1000L
        var lastError = "No model available"
        var sawRateLimit = false
        val dailyKeys = HashSet<String>()
        var sawResetHeader = false

        keyLoop@ for (key in keys) {
            val now0 = System.currentTimeMillis()
            if (cooldowns.keyUntil(key.id) > now0) { sawRateLimit = true; continue }
            if (limiter.usedToday(key.id) >= limits.requestsPerDay) {
                cooldowns.blockKey(key.id, ClientRateLimiter.nextUtcMidnight(now0))
                log(taskId, "-", key, 429, RateLimitHeaders.EMPTY, "client_daily_limit",
                    "Local requests/day limit (${limits.requestsPerDay}) reached", "Key paused until 00:00 UTC, rotating key")
                dailyKeys += key.id
                sawRateLimit = true
                continue
            }
            for (model in models) {
                val now = System.currentTimeMillis()
                if (cooldowns.modelUntil(key.id, model) > now) continue
                status("Calling $model…")
                limiter.acquire(key.id, limits.requestsPerMinute)
                val response = try {
                    api.chatCompletions("Bearer ${key.key}", model, key.displayName,
                        ChatRequest(model = model, messages = forModel(model, messages), tools = tools, toolChoice = if (tools != null) "auto" else null))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    lastError = "Network error: ${e.message ?: e.javaClass.simpleName}"
                    continue
                }
                val body = try {
                    (if (response.isSuccessful) response.body() else response.errorBody())?.string()
                } catch (e: IOException) { null }
                val headers = RateLimitHeaders.parse { response.headers()[it] }
                val err = ApiErrorDetector.detect(response.code(), body)
                    ?: run {
                        val parsed = body?.let { CompletionParser.parse(it) }
                        if (parsed != null) { _lastModel.value = model; return ChatOutcome.Success(parsed, parsed.model ?: model, key.displayName) }
                        DetectedApiError(ApiErrorKind.UPSTREAM, 502, "Empty or unparsable completion", null)
                    }
                lastError = "$model: ${err.message}"
                when (err.kind) {
                    ApiErrorKind.RATE_LIMIT -> {
                        sawRateLimit = true
                        val keyWide = (headers.remaining == 0 && (headers.resetAtMs ?: 0) > now + cooldownMs) ||
                            err.message.contains("per-day", ignoreCase = true) ||
                            err.message.contains("per day", ignoreCase = true)
                        if (keyWide) {
                            val daily = err.message.contains("per-day", ignoreCase = true) ||
                                err.message.contains("per day", ignoreCase = true) ||
                                headers.resetAtMs == null || (headers.resetAtMs - now) > 60 * 60_000L
                            if (daily) dailyKeys += key.id
                            if (headers.resetAtMs != null && headers.resetAtMs > now) sawResetHeader = true
                            val until = headers.resetAtMs?.takeIf { it > now } ?: ClientRateLimiter.nextUtcMidnight(now)
                            cooldowns.blockKey(key.id, until)
                            log(taskId, model, key, response.code(), headers, err.errorType, err.message, "Key quota exhausted → rotating to next key")
                            continue@keyLoop
                        }
                        val headerReset = headers.resetAtMs?.takeIf { it - now in 1..(10 * 60_000L) }
                        if (headerReset != null) sawResetHeader = true
                        val until = maxOf(now + cooldownMs, headerReset ?: 0)
                        cooldowns.coolModel(key.id, model, until)
                        log(taskId, model, key, response.code(), headers, err.errorType, err.message,
                            "Model cooldown ${(until - now) / 1000}s → next model")
                    }
                    ApiErrorKind.PAYMENT -> {
                        cooldowns.blockKey(key.id, now + 60 * 60_000L)
                        log(taskId, model, key, response.code(), headers, err.errorType ?: "payment_required", err.message, "402 → key paused 1h, rotating key")
                        continue@keyLoop
                    }
                    ApiErrorKind.AUTH -> {
                        cooldowns.blockKey(key.id, now + 60 * 60_000L)
                        continue@keyLoop
                    }
                    ApiErrorKind.UPSTREAM, ApiErrorKind.BAD_REQUEST, ApiErrorKind.OTHER -> {
                        cooldowns.coolModel(key.id, model, now + cooldownMs)
                    }
                }
            }
        }

        if (sawRateLimit) {
            val now = System.currentTimeMillis()
            val resumeAt = keys.minOf { k ->
                val ku = cooldowns.keyUntil(k.id)
                if (ku > now) ku else models.minOf { m -> cooldowns.modelUntil(k.id, m) }.coerceAtLeast(now + 1000)
            }
            rateLimitLog.log(
                RateLimitEvent(0, now, taskId, "*", "all keys (${keys.size})", 429, null, null, resumeAt.toString(),
                    "all_exhausted", lastError, "All models/keys rate-limited → task paused, auto-resume at reset")
            )
            val allDaily = keys.all { it.id in dailyKeys || cooldowns.keyUntil(it.id) >= ClientRateLimiter.nextUtcMidnight(now) - 1000 }
            return ChatOutcome.RateLimited(resumeAt, lastError, dailyQuota = allDaily, explicitReset = sawResetHeader)
        }
        return ChatOutcome.Failure(lastError)
    }

    private suspend fun log(
        taskId: Long?, model: String, key: ApiKey, code: Int, h: RateLimitHeaders,
        errorType: String?, message: String, outcome: String,
    ) {
        rateLimitLog.log(
            RateLimitEvent(
                id = 0, timestamp = System.currentTimeMillis(), taskId = taskId, model = model,
                keyLabel = key.displayName, statusCode = code, limitHeader = h.rawLimit,
                remainingHeader = h.rawRemaining, resetHeader = h.rawReset, errorType = errorType,
                message = message, outcome = outcome,
            )
        )
    }
}
