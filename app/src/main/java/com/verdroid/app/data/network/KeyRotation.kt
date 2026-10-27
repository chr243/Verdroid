package com.verdroid.app.data.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-key rotation (issue #5). Pure rules here (unit-tested); [KeyHealthTracker] keeps per-key state.
 * Ideas from OmniRoute's account fallback (MIT), reimplemented. Keys themselves never appear here: only key ids.
 */
object RetryAfter {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    /** Longest wait we accept from a server hint (anything longer is clamped). */
    const val MAX_MS = 24 * 60 * 60_000L

    /** Wait in ms from a `Retry-After` header (seconds or HTTP date) and/or the error body; null when absent. */
    fun parse(header: (String) -> String?, body: String?, now: Long = System.currentTimeMillis()): Long? =
        (fromHeader(header("Retry-After"), now) ?: header("retry-after-ms")?.trim()?.toDoubleOrNull()?.toLong()
            ?: fromBody(body))?.coerceIn(0, MAX_MS)

    fun fromHeader(v: String?, now: Long = System.currentTimeMillis()): Long? {
        val s = v?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        s.toDoubleOrNull()?.let { return (it * 1000).toLong().coerceAtLeast(0) }
        return runCatching { ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now }
            .getOrNull()?.coerceAtLeast(0)
    }

    /** `retry_after` / `retryAfter` (s), Gemini `retryDelay: "33s"`, or "retry after 20s" / "try again in 1m30s" text. */
    fun fromBody(body: String?): Long? {
        if (body.isNullOrBlank()) return null
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        if (root != null) {
            val err = root["error"]?.asObjectOrNull()
            val scopes = listOfNotNull(root, err, err?.get("metadata")?.asObjectOrNull())
            for (o in scopes) for (k in listOf("retry_after", "retryAfter", "retry_after_seconds")) {
                o[k]?.primitiveContent()?.let { v -> v.toDoubleOrNull()?.let { return (it * 1000).toLong() } ?: duration(v)?.let { return it } }
            }
            for (o in scopes) (o["details"] as? JsonArray)?.forEach { d ->
                d.asObjectOrNull()?.get("retryDelay")?.primitiveContent()?.let { duration(it) }?.let { return it }
            }
        }
        val text = root?.let { (it["error"]?.asObjectOrNull()?.get("message") ?: it["message"])?.primitiveContent() } ?: body
        Regex("(?:retry|try again)\\s+(?:after|in)\\s+((?:\\d+(?:\\.\\d+)?\\s*(?:ms|milliseconds?|s|sec|seconds?|m|min|minutes?|h|hours?)(?![a-z])\\s*)+)", RegexOption.IGNORE_CASE)
            .find(text)?.let { return duration(it.groupValues[1]) }
        return null
    }

    /** "33s", "1.5s", "1m30s", "250ms", "2 minutes" → ms. */
    fun duration(s: String): Long? {
        val parts = Regex("(\\d+(?:\\.\\d+)?)\\s*(ms|milliseconds?|s|sec|seconds?|m|min|minutes?|h|hours?)(?![a-z])", RegexOption.IGNORE_CASE).findAll(s.trim()).toList()
        if (parts.isEmpty()) return s.trim().toDoubleOrNull()?.let { (it * 1000).toLong() }
        return parts.sumOf { m ->
            val n = m.groupValues[1].toDouble()
            val unit = m.groupValues[2].lowercase()
            (n * when { unit.startsWith("ms") || unit.startsWith("milli") -> 1.0; unit.startsWith("h") -> 3_600_000.0; unit == "m" || unit.startsWith("min") -> 60_000.0; else -> 1000.0 }).toLong()
        }
    }
}

/** What a failed request means for the key that sent it. */
enum class KeyFault {
    /** Too many requests: short, growing cooldown (or the server's Retry-After). */
    RATE_LIMIT,
    /** No credits / payment required / quota exhausted: long cooldown. */
    CREDITS,
    /** Invalid, revoked or deactivated key: long cooldown. */
    INVALID,
    /** Only this model is off-limits for this key (not found, not in plan, per-model quota). */
    MODEL,
    /** Server / network trouble: not the key's fault (counts toward the provider's circuit breaker). */
    SERVER,
    /** Bad request etc.: not the key's fault. */
    OTHER,
}

object KeyPolicy {
    const val BACKOFF_BASE_MS = 5_000L
    const val BACKOFF_MAX_MS = 10 * 60_000L
    const val CREDITS_MS = 6 * 60 * 60_000L
    const val INVALID_MS = 24 * 60 * 60_000L
    const val MODEL_LOCK_MS = 60 * 60_000L

    private val DEACTIVATED = listOf("deactivated", "disabled", "suspended", "revoked", "invalid api key", "invalid_api_key",
        "incorrect api key", "api key not valid", "invalid token", "unauthorized", "account has been")
    private val CREDITS = listOf("insufficient_quota", "insufficient credits", "insufficient balance", "out of credits", "credit balance",
        "exceeded your current quota", "billing", "payment required", "quota exceeded for this month", "no credits")
    private val MODEL_SPECIFIC = listOf("model not found", "model_not_found", "does not exist", "not available for your plan",
        "do not have access to the model", "does not have access to model", "unsupported model", "no endpoints found", "is not a valid model")

    fun classify(err: DetectedApiError): KeyFault {
        val m = err.message.lowercase() + " " + err.errorType.orEmpty().lowercase()
        return when {
            err.code == 404 || MODEL_SPECIFIC.any { it in m } -> KeyFault.MODEL
            err.kind == ApiErrorKind.PAYMENT || CREDITS.any { it in m } -> KeyFault.CREDITS
            err.kind == ApiErrorKind.AUTH -> if (err.code == 403 && "model" in m) KeyFault.MODEL else KeyFault.INVALID
            DEACTIVATED.any { it in m } && err.kind != ApiErrorKind.RATE_LIMIT -> KeyFault.INVALID
            err.kind == ApiErrorKind.RATE_LIMIT -> KeyFault.RATE_LIMIT
            err.kind == ApiErrorKind.UPSTREAM -> KeyFault.SERVER
            else -> KeyFault.OTHER
        }
    }

    /** Exponential backoff for the [n]th consecutive rate limit (1-based): base·2^(n-1), capped. */
    fun backoff(n: Int, baseMs: Long = BACKOFF_BASE_MS, maxMs: Long = BACKOFF_MAX_MS): Long =
        (baseMs shl (n - 1).coerceIn(0, 20)).coerceIn(baseMs, maxMs)

    /** Cooldown for a rate limit: the server's Retry-After wins when given, else backoff (at least [minMs]). */
    fun rateLimitCooldown(consecutive: Int, retryAfterMs: Long?, minMs: Long = 0): Long =
        retryAfterMs?.takeIf { it > 0 } ?: maxOf(minMs, backoff(consecutive))
}

/** Per-key status shown in Settings. Persisted (no secrets). */
@Serializable
data class KeyStatus(
    val requests: Int = 0,
    val lastUsed: Long = 0,
    val coolUntil: Long = 0,
    val lastError: String? = null,
    val lastErrorAt: Long = 0,
    val consecutive429: Int = 0,
    val modelLocks: Map<String, Long> = emptyMap(),
) {
    fun coolingDown(now: Long) = coolUntil > now
    fun modelLocked(model: String, now: Long) = (modelLocks[model] ?: 0) > now
}

/** Pure per-key state machine (scope = "openrouter" or "prov:<providerId>"); [KeyHealthTracker] persists it. */
class KeyHealth(initial: Map<String, KeyStatus> = emptyMap(), private val clock: () -> Long = System::currentTimeMillis) {
    private val map = HashMap(initial)
    private val rr = HashMap<String, Int>()
    @Synchronized fun status(scope: String, keyId: String): KeyStatus = map[id(scope, keyId)] ?: KeyStatus()
    @Synchronized fun snapshot(): Map<String, KeyStatus> = HashMap(map)

    @Synchronized fun onRequest(scope: String, keyId: String) {
        val k = id(scope, keyId); val s = map[k] ?: KeyStatus()
        map[k] = s.copy(requests = s.requests + 1, lastUsed = clock())
    }

    @Synchronized fun onSuccess(scope: String, keyId: String) {
        val k = id(scope, keyId); val s = map[k] ?: KeyStatus()
        map[k] = s.copy(consecutive429 = 0, coolUntil = 0)
    }

    /**
     * Records a failure and returns the cooldown applied (0 = none). Rate limits back off per key (or follow Retry-After);
     * credits/invalid get long cooldowns; [KeyFault.MODEL] locks only that model on that key.
     */
    @Synchronized fun onFailure(scope: String, keyId: String, model: String, fault: KeyFault, message: String,
                                retryAfterMs: Long? = null, minRateLimitMs: Long = 0): Long {
        val now = clock(); val k = id(scope, keyId); var s = (map[k] ?: KeyStatus()).copy(lastError = message.take(200), lastErrorAt = now)
        val wait = when (fault) {
            KeyFault.RATE_LIMIT -> { s = s.copy(consecutive429 = s.consecutive429 + 1); KeyPolicy.rateLimitCooldown(s.consecutive429, retryAfterMs, minRateLimitMs) }
            KeyFault.CREDITS -> retryAfterMs?.takeIf { it > KeyPolicy.CREDITS_MS } ?: KeyPolicy.CREDITS_MS
            KeyFault.INVALID -> KeyPolicy.INVALID_MS
            KeyFault.MODEL -> { s = s.copy(modelLocks = s.modelLocks.filterValues { it > now } + (model to now + KeyPolicy.MODEL_LOCK_MS)); 0L }
            KeyFault.SERVER, KeyFault.OTHER -> 0L
        }
        if (wait > 0) s = s.copy(coolUntil = maxOf(s.coolUntil, now + wait))
        map[k] = s
        return if (fault == KeyFault.MODEL) KeyPolicy.MODEL_LOCK_MS else wait
    }

    /** Rate limit on one model only (OpenRouter per-model limits): backoff count still grows per key. */
    @Synchronized fun onModelRateLimit(scope: String, keyId: String, message: String, retryAfterMs: Long?, minMs: Long): Long {
        val now = clock(); val k = id(scope, keyId); val s = (map[k] ?: KeyStatus())
        val n = s.consecutive429 + 1
        map[k] = s.copy(consecutive429 = n, lastError = message.take(200), lastErrorAt = now)
        return KeyPolicy.rateLimitCooldown(n, retryAfterMs, minMs)
    }

    @Synchronized fun block(scope: String, keyId: String, until: Long) {
        val k = id(scope, keyId); val s = map[k] ?: KeyStatus(); map[k] = s.copy(coolUntil = maxOf(s.coolUntil, until))
    }

    @Synchronized fun reset(scope: String, keyId: String) { map.remove(id(scope, keyId)) }
    @Synchronized fun forget(scope: String, keep: Set<String>) { map.keys.removeAll { it.startsWith("$scope|") && it.substringAfter('|') !in keep } }

    /** Usable keys in order: priority (as given) or round-robin; keys cooling down are left out. */
    @Synchronized fun <T> order(scope: String, keys: List<T>, idOf: (T) -> String, roundRobin: Boolean): List<T> {
        val now = clock()
        val usable = keys.filter { !status(scope, idOf(it)).coolingDown(now) }
        if (!roundRobin || usable.size < 2) return usable
        val start = (rr[scope] ?: 0) % usable.size
        rr[scope] = start + 1
        return usable.drop(start) + usable.take(start)
    }

    /** Earliest time a key of [keyIds] is free again (null if one is free now). */
    @Synchronized fun nextFree(scope: String, keyIds: List<String>): Long? {
        val now = clock(); val t = keyIds.map { status(scope, it).coolUntil }
        return if (t.any { it <= now }) null else t.minOrNull()
    }

    private fun id(scope: String, keyId: String) = "$scope|$keyId"
}

/**
 * Circuit breaker per provider ("openrouter", "kilo", "prov:<id>"): after [threshold] failures in a row (server/network
 * errors, not rate limits) the provider is skipped for a while (30 s, doubling per trip, max 10 min). After the wait one
 * request goes through (half-open); success closes it, failure re-opens it longer.
 */
class CircuitBreaker(private val threshold: Int = 3, private val baseMs: Long = 30_000L, private val maxMs: Long = 10 * 60_000L,
                     private val clock: () -> Long = System::currentTimeMillis) {
    private data class S(val fails: Int = 0, val trips: Int = 0, val openUntil: Long = 0)
    private val map = HashMap<String, S>()
    @Synchronized fun allow(provider: String): Boolean = (map[provider]?.openUntil ?: 0) <= clock()
    @Synchronized fun openUntil(provider: String): Long = (map[provider]?.openUntil ?: 0).takeIf { it > clock() } ?: 0
    @Synchronized fun success(provider: String) { map.remove(provider) }
    @Synchronized fun failure(provider: String) {
        val s = map[provider] ?: S(); val f = s.fails + 1
        map[provider] = if (f >= threshold || s.trips > 0 && s.openUntil != 0L && clock() - s.openUntil < maxMs) {
            val trips = s.trips + 1
            S(0, trips, clock() + (baseMs shl (trips - 1).coerceIn(0, 20)).coerceAtMost(maxMs))
        } else s.copy(fails = f)
    }
    @Synchronized fun openProviders(): Map<String, Long> { val now = clock(); return map.filterValues { it.openUntil > now }.mapValues { it.value.openUntil } }
    @Synchronized fun clear() = map.clear()

    companion object {
        /** Breaker id for a model id. */
        fun providerOf(modelId: String): String = when (ModelIds.provider(modelId)) {
            ModelProvider.OPENROUTER -> "openrouter"; ModelProvider.KILO -> "kilo"; ModelProvider.LOCAL -> "local"
            ModelProvider.CUSTOM -> "prov:" + (ModelIds.providerIdOf(modelId) ?: "custom")
        }
    }
}

/** App-wide key health + breaker, persisted in SharedPreferences (ids and counters only, never keys). */
@Singleton
class KeyHealthTracker @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("key_health", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = MapSerializer(String.serializer(), KeyStatus.serializer())
    val health = KeyHealth(runCatching { json.decodeFromString(ser, prefs.getString("status", null) ?: "{}") }.getOrDefault(emptyMap()))
    val breaker = CircuitBreaker()
    private val _version = MutableStateFlow(0)
    /** Bumped after every change so Settings re-reads [status]. */
    val version: StateFlow<Int> = _version.asStateFlow()
    private val _roundRobin = MutableStateFlow(prefs.getBoolean("round_robin", false))
    /** "Spread requests across keys" (round-robin); default off = priority order. */
    val roundRobin: StateFlow<Boolean> = _roundRobin.asStateFlow()
    fun setRoundRobin(v: Boolean) { prefs.edit().putBoolean("round_robin", v).apply(); _roundRobin.value = v }
    private val _strict = MutableStateFlow(prefs.getBoolean("strict_order", false))
    /** "Follow Model priority exactly across providers"; default off = grouped by provider (behaviour before v1.0.71-test2). */
    val strictOrder: StateFlow<Boolean> = _strict.asStateFlow()
    fun setStrictOrder(v: Boolean) { prefs.edit().putBoolean("strict_order", v).apply(); _strict.value = v }

    fun status(scope: String, keyId: String) = health.status(scope, keyId)
    fun changed() { prefs.edit().putString("status", json.encodeToString(ser, health.snapshot())).apply(); _version.value++ }
    fun reset(scope: String, keyId: String) { health.reset(scope, keyId); changed() }

    companion object {
        const val OPENROUTER = "openrouter"
        fun scopeOf(providerId: String) = "prov:$providerId"
        /** "No key" slot for keyless providers (local servers). */
        const val NO_KEY = "-"
    }
}

/** Helpers for the strict (exact) Model priority order. */
object FallbackOrder {
    /** Consecutive entries of the same provider (OpenRouter / Kilo / each `prov:` provider / on-device) form one run. */
    fun runs(models: List<String>): List<List<String>> {
        val out = ArrayList<MutableList<String>>()
        for (m in models) {
            val p = CircuitBreaker.providerOf(m)
            if (out.isNotEmpty() && CircuitBreaker.providerOf(out.last().first()) == p) out.last().add(m) else out.add(mutableListOf(m))
        }
        return out
    }
    /** Keeps the more useful of two non-success outcomes: the earliest rate-limit resume wins over a failure. */
    fun better(a: ChatOutcome?, b: ChatOutcome): ChatOutcome = when {
        a == null -> b
        a is ChatOutcome.RateLimited && b is ChatOutcome.RateLimited -> if (b.resumeAt < a.resumeAt) b else a
        a is ChatOutcome.RateLimited -> a
        else -> b
    }
}
