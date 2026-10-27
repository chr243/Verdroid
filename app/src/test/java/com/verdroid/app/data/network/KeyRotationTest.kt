package com.verdroid.app.data.network

import org.junit.Assert.*
import org.junit.Test

class KeyRotationTest {
    private fun err(kind: ApiErrorKind, code: Int, msg: String) = DetectedApiError(kind, code, msg, null)

    @Test fun retryAfterHeaderSecondsAndDate() {
        assertEquals(30_000L, RetryAfter.parse({ if (it == "Retry-After") "30" else null }, null, 0))
        val now = 1_700_000_000_000L
        val date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.Instant.ofEpochMilli(now + 120_000).atZone(java.time.ZoneOffset.UTC))
        assertEquals(120_000L, RetryAfter.parse({ if (it == "Retry-After") date else null }, null, now))
        assertEquals(1_500L, RetryAfter.parse({ if (it == "retry-after-ms") "1500" else null }, null, 0))
        assertNull(RetryAfter.parse({ null }, "{\"error\":{\"message\":\"nope\"}}", 0))
        assertEquals(RetryAfter.MAX_MS, RetryAfter.parse({ if (it == "Retry-After") "999999999" else null }, null, 0))
    }

    @Test fun retryAfterBodyFormats() {
        assertEquals(12_000L, RetryAfter.fromBody("{\"retry_after\": 12}"))
        assertEquals(4_000L, RetryAfter.fromBody("{\"error\":{\"metadata\":{\"retry_after_seconds\":4}}}"))
        assertEquals(33_000L, RetryAfter.fromBody("{\"error\":{\"code\":429,\"details\":[{\"@type\":\"x\"},{\"retryDelay\":\"33s\"}]}}"))
        assertEquals(90_000L, RetryAfter.fromBody("{\"error\":{\"message\":\"Rate limited. Please try again in 1m30s.\"}}"))
        assertEquals(20_000L, RetryAfter.fromBody("Too many requests, retry after 20 seconds"))
        assertEquals(250L, RetryAfter.duration("250ms"))
        assertNull(RetryAfter.fromBody("{\"error\":{\"message\":\"slow down\"}}"))
    }

    @Test fun backoffDoublesAndCaps() {
        assertEquals(5_000L, KeyPolicy.backoff(1)); assertEquals(10_000L, KeyPolicy.backoff(2)); assertEquals(40_000L, KeyPolicy.backoff(4))
        assertEquals(KeyPolicy.BACKOFF_MAX_MS, KeyPolicy.backoff(30))
        assertEquals(KeyPolicy.BACKOFF_MAX_MS, KeyPolicy.backoff(Int.MAX_VALUE))
    }

    @Test fun classification() {
        assertEquals(KeyFault.RATE_LIMIT, KeyPolicy.classify(err(ApiErrorKind.RATE_LIMIT, 429, "Rate limit exceeded")))
        assertEquals(KeyFault.CREDITS, KeyPolicy.classify(err(ApiErrorKind.PAYMENT, 402, "Payment required")))
        assertEquals(KeyFault.CREDITS, KeyPolicy.classify(err(ApiErrorKind.RATE_LIMIT, 429, "You exceeded your current quota")))
        assertEquals(KeyFault.INVALID, KeyPolicy.classify(err(ApiErrorKind.AUTH, 401, "Invalid API key")))
        assertEquals(KeyFault.INVALID, KeyPolicy.classify(err(ApiErrorKind.OTHER, 400, "This account has been deactivated")))
        assertEquals(KeyFault.MODEL, KeyPolicy.classify(err(ApiErrorKind.OTHER, 404, "No endpoints found for x/y")))
        assertEquals(KeyFault.MODEL, KeyPolicy.classify(err(ApiErrorKind.AUTH, 403, "You do not have access to the model gpt-x")))
        assertEquals(KeyFault.SERVER, KeyPolicy.classify(err(ApiErrorKind.UPSTREAM, 503, "overloaded")))
    }

    @Test fun rotationSkipsCoolingKeysAndComesBack() {
        var t = 0L
        val h = KeyHealth(clock = { t })
        val w = h.onFailure("s", "a", "m", KeyFault.RATE_LIMIT, "429", retryAfterMs = null, minRateLimitMs = 0)
        assertEquals(KeyPolicy.BACKOFF_BASE_MS, w)
        assertEquals(listOf("b", "c"), h.order("s", listOf("a", "b", "c"), { it }, false))
        // second consecutive 429 on the same key backs off longer
        t += w; assertEquals(2 * KeyPolicy.BACKOFF_BASE_MS, h.onFailure("s", "a", "m", KeyFault.RATE_LIMIT, "429"))
        // Retry-After wins over the backoff
        assertEquals(42_000L, h.onFailure("s", "b", "m", KeyFault.RATE_LIMIT, "429", retryAfterMs = 42_000))
        assertEquals(listOf("c"), h.order("s", listOf("a", "b", "c"), { it }, false))
        assertEquals(t + 2 * KeyPolicy.BACKOFF_BASE_MS, h.nextFree("s", listOf("a", "b")))
        t += 60_000
        assertEquals(listOf("a", "b", "c"), h.order("s", listOf("a", "b", "c"), { it }, false))
        h.onSuccess("s", "a"); assertEquals(0, h.status("s", "a").consecutive429)
        h.onRequest("s", "a"); assertEquals(1, h.status("s", "a").requests); assertEquals(t, h.status("s", "a").lastUsed)
    }

    @Test fun longCooldownsForCreditsAndInvalid() {
        var t = 0L; val h = KeyHealth(clock = { t })
        assertEquals(KeyPolicy.CREDITS_MS, h.onFailure("s", "a", "m", KeyFault.CREDITS, "402"))
        assertEquals(KeyPolicy.INVALID_MS, h.onFailure("s", "b", "m", KeyFault.INVALID, "401"))
        t += KeyPolicy.CREDITS_MS + 1
        assertEquals(listOf("a"), h.order("s", listOf("a", "b"), { it }, false))
        h.reset("s", "b"); assertEquals(listOf("a", "b"), h.order("s", listOf("a", "b"), { it }, false))
    }

    @Test fun modelLockDoesNotCoolTheKey() {
        val h = KeyHealth(clock = { 0 })
        h.onFailure("s", "a", "bad-model", KeyFault.MODEL, "model not found")
        assertEquals(listOf("a"), h.order("s", listOf("a"), { it }, false))
        assertTrue((h.status("s", "a").modelLocks["bad-model"] ?: 0) > 0)
        assertFalse("good" in h.status("s", "a").modelLocks)
    }

    @Test fun roundRobinRotatesStart() {
        val h = KeyHealth(clock = { 0 })
        val k = listOf("a", "b", "c")
        assertEquals(listOf("a", "b", "c"), h.order("s", k, { it }, true))
        assertEquals(listOf("b", "c", "a"), h.order("s", k, { it }, true))
        assertEquals(listOf("c", "a", "b"), h.order("s", k, { it }, true))
        assertEquals(listOf("a", "b", "c"), h.order("s", k, { it }, false))
        assertEquals(listOf("a", "b", "c"), h.order("other", k, { it }, true))
    }

    @Test fun circuitBreakerOpensHalfOpensAndCloses() {
        var t = 0L; val b = CircuitBreaker(threshold = 3, baseMs = 30_000, maxMs = 600_000, clock = { t })
        b.failure("p"); b.failure("p"); assertTrue(b.allow("p"))
        b.failure("p"); assertFalse(b.allow("p")); assertEquals(30_000L, b.openUntil("p"))
        assertEquals(setOf("p"), b.openProviders().keys)
        t = 30_000; assertTrue(b.allow("p"))
        b.failure("p"); assertFalse(b.allow("p")); assertEquals(90_000L, b.openUntil("p")) // half-open failure re-opens longer
        t = 90_000; b.success("p"); assertTrue(b.allow("p")); b.failure("p"); assertTrue(b.allow("p"))
        assertTrue(b.allow("other"))
    }

    @Test fun breakerIdsAndStrictRuns() {
        assertEquals("openrouter", CircuitBreaker.providerOf("openai/gpt-4o"))
        assertEquals("prov:groq", CircuitBreaker.providerOf("prov:groq:llama"))
        assertEquals(listOf(listOf("a/x", "b/y"), listOf("prov:g:1"), listOf("c/z")), FallbackOrder.runs(listOf("a/x", "b/y", "prov:g:1", "c/z")))
        val f = ChatOutcome.Failure("x"); val r1 = ChatOutcome.RateLimited(10, "a"); val r2 = ChatOutcome.RateLimited(5, "b")
        assertEquals(r1, FallbackOrder.better(f, r1)); assertEquals(r2, FallbackOrder.better(r1, r2)); assertEquals(r1, FallbackOrder.better(r1, f))
    }

    @Test fun providerKeyNeverPrintsSecret() {
        val k = ProviderKey("k1", "Main", "sk-supersecret-1234")
        assertFalse(k.toString().contains("supersecret"))
        assertFalse(k.masked.contains("supersecret"))
    }
}
