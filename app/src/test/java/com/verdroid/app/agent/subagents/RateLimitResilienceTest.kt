package com.verdroid.app.agent.subagents

import com.verdroid.app.agent.research.DeepResearchEngine
import com.verdroid.app.agent.research.ResearchLimits
import com.verdroid.app.agent.research.ResearchMode
import com.verdroid.app.agent.tools.ToolResult
import com.verdroid.app.data.network.ChatOutcome
import com.verdroid.app.data.network.ClientRateLimiter
import com.verdroid.app.data.network.ParsedCompletion
import com.verdroid.app.data.network.RateLimitHeaders
import com.verdroid.app.data.network.SharedRatePacer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimitResilienceTest {
    private fun ok(content: String) = ChatOutcome.Success(ParsedCompletion(content, emptyList(), "stop", "m"), "m", "k")
    private fun loop(model: suspend (List<com.verdroid.app.data.network.ApiMessage>, JsonArray?) -> ChatOutcome,
                     fallback: (suspend (List<com.verdroid.app.data.network.ApiMessage>, JsonArray?) -> ChatOutcome)? = null,
                     sleeps: MutableList<Long> = mutableListOf(), waits: MutableList<Boolean> = mutableListOf(), now: Long = 0L, retries: Int = 3) =
        SubAgentLoop(model = model, execute = { _, _ -> ToolResult("{}", false) }, toolSchemas = JsonArray(emptyList()), allowed = emptySet(),
            maxRetries = retries, now = { now }, sleep = { sleeps += it }, jitter = { 0L }, fallback = fallback, onWait = { waits += it })

    // ---- shared limiter pacing ----

    @Test fun pacerSpacesCallsAcrossParallelWorkers() = runTest {
        val p = SharedRatePacer({ currentTime }, { delay(it) })
        val times = (0 until 6).map { async { p.acquire(ClientRateLimiter.FREE_BUCKET, 15); currentTime } }.awaitAll().sorted()
        // 15 rpm → one call every 4 s, never a burst.
        times.zipWithNext().forEach { (a, b) -> assertTrue("gap ${b - a}", b - a >= 4_000) }
        assertEquals(0L, times.first())
    }

    @Test fun pacerNeverExceedsRpmInAnyMinute() = runTest {
        val p = SharedRatePacer({ currentTime }, { delay(it) })
        val times = (0 until 25).map { async { p.acquire("b", 20); currentTime } }.awaitAll().sorted()
        for (t in times) assertTrue(times.count { it in t until t + 60_000 } <= 20)
    }

    @Test fun pacerPauseAfter429HoldsEveryone() = runTest {
        val p = SharedRatePacer({ currentTime }, { delay(it) })
        p.acquire("b", 15)
        p.pause("b", 30_000)
        val t = (0 until 3).map { async { p.acquire("b", 15); currentTime } }.awaitAll()
        assertTrue(t.all { it >= 30_000 })
    }

    @Test fun freeModelDetection() {
        assertTrue(ClientRateLimiter.isFreeModel("deepseek/deepseek-chat:free"))
        assertTrue(ClientRateLimiter.isFreeModel("openrouter/free"))
        assertFalse(ClientRateLimiter.isFreeModel("deepseek/deepseek-chat"))
    }

    // ---- Retry-After ----

    @Test fun retryAfterHeaderAndBodyAreParsed() {
        assertEquals(1_000L + 12_000, RateLimitHeaders.retryAfterAt("12", 1_000))
        assertEquals(1_500L, RateLimitHeaders.retryAfterAt("0.5", 1_000))
        assertEquals(784111777000L, RateLimitHeaders.retryAfterAt("Sun, 06 Nov 1994 08:49:37 GMT", 0))
        assertNull(RateLimitHeaders.retryAfterAt("soon", 0))
        assertEquals(7_000L, RateLimitHeaders.retryAfterFromBody("""{"error":{"code":429,"metadata":{"retry_after":7}}}""", 0))
        assertTrue(RateLimitHeaders.parse { if (it == "Retry-After") "9" else null }.retryAfterAtMs != null)
    }

    @Test fun retryHonoursRetryAfterThenSucceeds() = runTest {
        var n = 0; val sleeps = mutableListOf<Long>(); val waits = mutableListOf<Boolean>()
        val r = loop({ _, _ -> if (n++ < 2) ChatOutcome.RateLimited(resumeAt = 30_000, message = "429", explicitReset = true) else ok("answer") },
            sleeps = sleeps, waits = waits).run(0, "t")
        assertEquals(SubAgentResult.Status.OK, r.status)
        assertEquals(listOf(30_000L, 30_000L), sleeps) // Retry-After (30 s) beats the 2 s / 4 s backoff
        assertEquals(listOf(true, false, true, false), waits) // progress shows "waiting", never a failure
    }

    @Test fun defaultRetriesAreSeveral() { assertTrue(SubAgentLoop.DEFAULT_RATE_RETRIES >= 5); assertEquals(120_000L, SubAgentLoop.backoffMs(0, 500_000)) }

    // ---- fallback ----

    @Test fun fallsBackToNextModelListAfterRetries() = runTest {
        var fb = 0
        val r = loop({ _, _ -> ChatOutcome.RateLimited(0, "429") }, fallback = { _, _ -> fb++; ok("from fallback") }).run(0, "t")
        assertEquals(SubAgentResult.Status.OK, r.status); assertEquals("from fallback", r.findings); assertEquals(1, fb)
    }

    @Test fun dailyQuotaSkipsStraightToFallback() = runTest {
        val sleeps = mutableListOf<Long>()
        val r = loop({ _, _ -> ChatOutcome.RateLimited(0, "429", dailyQuota = true) }, fallback = { _, _ -> ok("fb") }, sleeps = sleeps).run(0, "t")
        assertEquals("fb", r.findings); assertTrue(sleeps.isEmpty())
    }

    @Test fun failsOnlyAfterFallbackAlsoExhausted() = runTest {
        val r = loop({ _, _ -> ChatOutcome.RateLimited(0, "429") }, fallback = { _, _ -> ChatOutcome.RateLimited(0, "429") }, retries = 1).run(0, "t")
        assertEquals(SubAgentResult.Status.ERROR, r.status); assertTrue(r.rateLimited); assertTrue(r.usedFallback)
        assertTrue(r.findings.contains("fallback"))
    }

    // ---- concurrency defaults / progress ----

    @Test fun freeModelsDefaultToFewerWorkers() {
        assertEquals(2, SubAgentPolicy.effectiveConcurrency(3, userSet = false, freeModel = true))
        assertEquals(3, SubAgentPolicy.effectiveConcurrency(3, userSet = false, freeModel = false))
        assertEquals(6, SubAgentPolicy.effectiveConcurrency(6, userSet = true, freeModel = true))
    }

    @Test fun progressLabelShowsWaitingNotFailed() {
        val l = SubAgentProgress(5, 1, 2, 0, waiting = 2).label
        assertTrue(l.contains("2 waiting for rate limit")); assertFalse(l.contains("fail"))
    }

    // ---- requeue ----

    @Test fun failedQuestionsAreRequeuedOnceAtTheEnd() = runTest {
        val attempts = HashMap<String, Int>()
        val engine = DeepResearchEngine(
            main = { m -> if (m.last().content!!.startsWith("You plan")) ok("[\"Alpha?\",\"Beta?\"]") else ok("# R\n" + "z [1] ".repeat(60)) },
            worker = { i, task ->
                val q = if ("Alpha?" in task) "A" else "B"
                val n = (attempts[q] ?: 0) + 1; attempts[q] = n
                if (q == "A" && n == 1) SubAgentResult(i, task, SubAgentResult.Status.ERROR, "Rate limited", rateLimited = true)
                else SubAgentResult(i, task, SubAgentResult.Status.OK, "fact $q https://$q.com", sources = listOf("https://$q.com"))
            }, sleep = {},
        )
        val o = engine.run("t", ResearchMode.STANDARD, ResearchLimits(2, 1, 10, 2))
        assertEquals(0, o.failedWorkers); assertEquals(2, attempts["A"]); assertEquals(1, attempts["B"])
        assertTrue(o.sources.containsAll(listOf("https://A.com", "https://B.com")))
    }

    @Test fun stillFailingAfterRequeueIsCountedOnce() = runTest {
        var calls = 0
        val engine = DeepResearchEngine(
            main = { m -> if (m.last().content!!.startsWith("You plan")) ok("[\"Alpha?\"]") else ok("# R\n" + "z ".repeat(300)) },
            worker = { i, task -> calls++; SubAgentResult(i, task, SubAgentResult.Status.ERROR, "x") }, sleep = {},
        )
        val o = engine.run("t", ResearchMode.STANDARD, ResearchLimits(1, 1, 10, 1))
        assertEquals(1, o.failedWorkers); assertEquals(2, calls)
    }
}
