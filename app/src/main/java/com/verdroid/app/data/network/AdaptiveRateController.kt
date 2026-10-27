package com.verdroid.app.data.network

/**
 * Adaptive requests/minute per provider+model bucket (v1.0.71-test4).
 *
 * - Headers first: when a response carries a request limit (X-RateLimit-Limit, x-ratelimit-limit-requests, …) the pace is set to
 *   90 % of it (assumed per minute), and when `remaining` hits 0 the bucket is paused until the reset / Retry-After.
 * - No headers → AIMD: start at a conservative default, +1 rpm after every [INCREASE_EVERY] successes (up to [MAX_RPM]),
 *   halve on every 429.
 * - Learned rates are persisted ([save]) so the next run starts at the learned pace. A manual override (> 0) wins over all of it.
 *
 * Pure (no Android) so it can be unit-tested; [ClientRateLimiter.shared] does the actual pacing.
 */
class AdaptiveRateController(
    initial: Map<String, Int> = emptyMap(),
    private val save: (Map<String, Int>) -> Unit = {},
    private val override: () -> Int = { 0 },
) {
    private val rates = HashMap<String, Double>().apply { initial.forEach { (k, v) -> put(k, v.toDouble().coerceIn(1.0, MAX_RPM.toDouble())) } }
    private val headerCap = HashMap<String, Int>()
    private val streak = HashMap<String, Int>()

    @Synchronized fun rpm(bucket: String): Int {
        override().takeIf { it > 0 }?.let { return it }
        return (rates[bucket] ?: default(bucket).toDouble()).toInt().coerceAtLeast(1)
    }

    /** A successful call; [h] = that response's rate-limit headers (may be empty). */
    @Synchronized fun onSuccess(bucket: String, h: RateLimitHeaders = RateLimitHeaders.EMPTY) {
        val cap = h.limit?.takeIf { it > 0 }
        if (cap != null) {
            val target = (cap * 0.9).toInt().coerceIn(1, MAX_RPM)
            headerCap[bucket] = target
            if (rates[bucket]?.toInt() != target) { rates[bucket] = target.toDouble(); persist() }
            return
        }
        val n = (streak[bucket] ?: 0) + 1
        if (n < INCREASE_EVERY) { streak[bucket] = n; return }
        streak[bucket] = 0
        val ceiling = (headerCap[bucket] ?: MAX_RPM).toDouble()
        val cur = rates[bucket] ?: default(bucket).toDouble()
        val next = (cur + 1).coerceAtMost(ceiling)
        if (next != cur) { rates[bucket] = next; persist() }
    }

    /** A 429: multiplicative decrease (halve, floor 1). Returns the new rpm. */
    @Synchronized fun onRateLimited(bucket: String): Int {
        streak[bucket] = 0
        val cur = rates[bucket] ?: default(bucket).toDouble()
        val next = (cur / 2).coerceAtLeast(1.0)
        rates[bucket] = next; persist()
        return next.toInt()
    }

    @Synchronized fun learned(): Map<String, Int> = rates.mapValues { it.value.toInt() }

    private fun persist() = runCatching { save(learned()) }

    companion object {
        const val MAX_RPM = 60
        const val INCREASE_EVERY = 5
        const val DEFAULT_PAID_RPM = 30

        fun bucket(provider: String, model: String): String =
            if (provider == "openrouter" && ClientRateLimiter.isFreeModel(model)) ClientRateLimiter.FREE_BUCKET else "$provider|$model"

        fun default(bucket: String): Int = if (bucket == ClientRateLimiter.FREE_BUCKET) ClientRateLimiter.DEFAULT_FREE_RPM else DEFAULT_PAID_RPM

        fun encode(m: Map<String, Int>): String = m.entries.joinToString("\n") { "${it.key}\t${it.value}" }
        fun decode(s: String?): Map<String, Int> = s.orEmpty().lines().mapNotNull { l ->
            val p = l.split('\t'); if (p.size == 2) p[1].toIntOrNull()?.let { p[0] to it } else null
        }.toMap()
    }
}

/** `free_model_daily_requests` from OpenRouter GET /api/v1/key (verified in the docs; the per-minute limit is not reported there). */
data class FreeDailyRequests(val used: Int, val limit: Int, val remaining: Int) {
    companion object {
        fun parse(body: String?): FreeDailyRequests? = runCatching {
            val o = kotlinx.serialization.json.Json.parseToJsonElement(body!!) as kotlinx.serialization.json.JsonObject
            val d = (o["data"] as kotlinx.serialization.json.JsonObject)["free_model_daily_requests"] as kotlinx.serialization.json.JsonObject
            fun i(k: String) = (d[k] as kotlinx.serialization.json.JsonPrimitive).content.toDouble().toInt()
            FreeDailyRequests(i("used"), i("limit"), i("remaining"))
        }.getOrNull()
    }
}
