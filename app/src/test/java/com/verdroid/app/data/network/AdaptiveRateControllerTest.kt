package com.verdroid.app.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveRateControllerTest {
    private val B = "openrouter|x/y"

    @Test fun startsConservativeAndHalvesOn429() {
        val c = AdaptiveRateController()
        assertEquals(30, c.rpm(B)); assertEquals(15, c.rpm(ClientRateLimiter.FREE_BUCKET))
        assertEquals(15, c.onRateLimited(B)); assertEquals(7, c.onRateLimited(B))
        repeat(10) { c.onRateLimited(B) }; assertEquals(1, c.rpm(B))
    }

    @Test fun additiveIncreaseAfterSuccesses() {
        val c = AdaptiveRateController()
        repeat(AdaptiveRateController.INCREASE_EVERY - 1) { c.onSuccess(B) }
        assertEquals(30, c.rpm(B))
        c.onSuccess(B); assertEquals(31, c.rpm(B))
        repeat(1000) { c.onSuccess(B) }; assertEquals(AdaptiveRateController.MAX_RPM, c.rpm(B))
    }

    @Test fun headerLimitSetsPaceAndCapsGrowth() {
        val c = AdaptiveRateController()
        val h = RateLimitHeaders.parseAt({ mapOf("X-RateLimit-Limit" to "20", "X-RateLimit-Remaining" to "5")[it] }, 0)
        c.onSuccess(ClientRateLimiter.FREE_BUCKET, h); assertEquals(18, c.rpm(ClientRateLimiter.FREE_BUCKET))
        repeat(50) { c.onSuccess(ClientRateLimiter.FREE_BUCKET) }; assertEquals(18, c.rpm(ClientRateLimiter.FREE_BUCKET))
    }

    @Test fun learnedRatesPersistAndReload() {
        var saved = emptyMap<String, Int>()
        val c = AdaptiveRateController(save = { saved = it }); c.onRateLimited(B)
        val text = AdaptiveRateController.encode(saved)
        assertEquals(15, AdaptiveRateController(AdaptiveRateController.decode(text)).rpm(B))
    }

    @Test fun manualOverrideWins() {
        val c = AdaptiveRateController(override = { 4 }); c.onRateLimited(B)
        assertEquals(4, c.rpm(B)); assertEquals(4, c.rpm("anything"))
    }

    @Test fun bucketsPerProviderModelWithSharedFree() {
        assertEquals(ClientRateLimiter.FREE_BUCKET, AdaptiveRateController.bucket("openrouter", "a/b:free"))
        assertEquals("openrouter|a/b", AdaptiveRateController.bucket("openrouter", "a/b"))
    }

    @Test fun parsesOpenAiStyleRequestHeaders() {
        val h = RateLimitHeaders.parseAt({ mapOf("x-ratelimit-limit-requests" to "500", "x-ratelimit-remaining-requests" to "0",
            "x-ratelimit-reset-requests" to "6m0s", "Retry-After" to "3")[it] }, 1_000)
        assertEquals(500, h.limit); assertEquals(0, h.remaining)
        assertEquals(1_000L + 360_000, h.resetAtMs); assertEquals(4_000L, h.retryAfterAtMs)
        assertEquals(250L, RateLimitHeaders.durationMs("250ms")); assertEquals(1_500L, RateLimitHeaders.durationMs("1.5s"))
        assertNull(RateLimitHeaders.durationMs("soon"))
    }

    @Test fun parsesOpenRouterHeadersAndKeyInfo() {
        val h = RateLimitHeaders.parseAt({ mapOf("X-RateLimit-Limit" to "20", "X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "1700000060000")[it] }, 0)
        assertEquals(1_700_000_060_000L, h.resetAtMs)
        val k = FreeDailyRequests.parse("""{"data":{"label":"k","free_model_daily_requests":{"used":12,"limit":50,"remaining":38},"rate_limit":{}}}""")
        assertEquals(FreeDailyRequests(12, 50, 38), k)
        assertNull(FreeDailyRequests.parse("""{"data":{}}"""))
    }
}
