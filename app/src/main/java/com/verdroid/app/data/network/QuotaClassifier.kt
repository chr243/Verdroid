package com.verdroid.app.data.network

/**
 * Decides when a rate limit is OpenRouter's *daily free-model quota* (pause until 00:00 UTC).
 * Only :free models with an explicit per-day / free-models-per-day error qualify; paid models,
 * plain 429s, 402 insufficient credits and 5xx outages never do.
 */
object QuotaClassifier {
    private val dailyPatterns = listOf("free-models-per-day", "free models per day", "per-day", "per day")

    fun isDailyFreeQuota(model: String, message: String?): Boolean {
        if (!ClientRateLimiter.isFreeModel(model)) return false
        val m = message?.lowercase() ?: return false
        return dailyPatterns.any { it in m }
    }

    fun allDailyFree(models: List<String>, freeDailyHits: Int, otherRateLimits: Int): Boolean =
        freeDailyHits > 0 && otherRateLimits == 0 && models.isNotEmpty() && models.all { ClientRateLimiter.isFreeModel(it) }
}
