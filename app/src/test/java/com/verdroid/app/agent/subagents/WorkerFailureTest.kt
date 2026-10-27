package com.verdroid.app.agent.subagents

import com.verdroid.app.agent.research.DeepResearchEngine
import com.verdroid.app.agent.research.DeepResearchTool
import com.verdroid.app.agent.research.DeepResearchResult
import com.verdroid.app.agent.research.ResearchLimits
import com.verdroid.app.agent.research.ResearchMode
import com.verdroid.app.agent.tools.ToolResult
import com.verdroid.app.data.network.ApiMessage
import com.verdroid.app.data.network.ChatOutcome
import com.verdroid.app.data.network.ParsedCompletion
import com.verdroid.app.data.network.ParsedToolCall
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerFailureTest {
    private fun ok(content: String?, calls: List<ParsedToolCall> = emptyList()) =
        ChatOutcome.Success(ParsedCompletion(content, calls, "stop", "m"), "m", "k")
    private fun loop(
        model: suspend (List<ApiMessage>, JsonArray?) -> ChatOutcome,
        fallback: (suspend (List<ApiMessage>, JsonArray?) -> ChatOutcome)? = null,
        execute: suspend (String, String) -> ToolResult = { _, _ -> ToolResult("{}", false) },
        maxSteps: Int = 5,
    ) = SubAgentLoop(model = model, execute = execute, toolSchemas = JsonArray(listOf(kotlinx.serialization.json.JsonObject(emptyMap()))), allowed = setOf("web_fetch", "web_search"),
        maxSteps = maxSteps, maxRetries = 2, now = { 0L }, sleep = {}, jitter = { 0L }, fallback = fallback)

    @Test fun sanitizeRedactsKeys() {
        val s = FailureText.sanitize("401 Bearer sk-or-v1-abcdef123456 bad; api_key=XYZ123 and token: abc.def " + "a".repeat(45))
        assertFalse(s, s.contains("sk-or")); assertFalse(s.contains("XYZ123")); assertFalse(s.contains("abc.def")); assertFalse(s.contains("a".repeat(45)))
        assertTrue(s.contains("401"))
        assertTrue(FailureText.sanitize("x".repeat(30) + " " + "y ".repeat(400)).length <= FailureText.MAX_DETAIL)
    }

    @Test fun httpFailureKeepsStatusAndMessage() = runTest {
        val r = loop({ _, _ -> ChatOutcome.Failure("google/gemma:free: HTTP 400: Provider returned error") }).run(0, "t")
        assertEquals(SubAgentResult.Status.ERROR, r.status)
        val f = r.failure!!
        assertEquals(FailureKind.HTTP, f.kind); assertEquals(400, f.httpStatus)
        assertEquals(3, f.attempts) // first try + 2 retries
        assertTrue(r.findings.contains("Provider returned error"))
        assertEquals("not configured", f.json()["fallback"].toString().trim('"'))
    }

    @Test fun rateLimitThenFallbackResultIsReported() = runTest {
        val rl = ChatOutcome.RateLimited(0, "m: HTTP 429: Rate limit exceeded: free-models-per-min")
        val r = loop({ _, _ -> rl }, fallback = { _, _ -> rl }).run(0, "t")
        val f = r.failure!!
        assertEquals(FailureKind.RATE_LIMIT, f.kind); assertEquals(429, f.httpStatus)
        assertTrue(f.fallback!!, f.fallback!!.contains("tried") && f.fallback!!.contains("also failed"))
        assertTrue(f.summary.contains("model calls"))
    }

    @Test fun fallbackSuccessIsNoted() = runTest {
        val r = loop({ _, _ -> ChatOutcome.RateLimited(0, "x", dailyQuota = true) }, fallback = { _, _ -> ok("answer") }).run(0, "t")
        assertEquals(SubAgentResult.Status.OK, r.status); assertTrue(r.usedFallback)
    }

    @Test fun dailyQuotaKind() = runTest {
        val r = loop({ _, _ -> ChatOutcome.RateLimited(0, "per-day limit", dailyQuota = true) }).run(0, "t")
        assertEquals(FailureKind.DAILY_QUOTA, r.failure!!.kind)
    }

    @Test fun emptyOutputRetriedOnceThenReported() = runTest {
        var n = 0
        val r = loop({ _, _ -> n++; ok("") }).run(0, "t")
        assertEquals(2, n)
        assertEquals(FailureKind.EMPTY_OUTPUT, r.failure!!.kind)
        var m = 0
        val r2 = loop({ _, _ -> m++; ok(if (m == 1) "" else "found it") }).run(0, "t")
        assertEquals(SubAgentResult.Status.OK, r2.status)
    }

    @Test fun stepLimitAndToolErrorsReported() = runTest {
        var i = 0
        val r = loop({ _, t -> if (t == null) ok("") else ok(null, listOf(ParsedToolCall("c${i++}", "web_fetch", """{"url":"https://www.example.com/p${i}"}"""))) },
            execute = { _, _ -> ToolResult("""{"error":"HTTP 403 Forbidden"}""", true) }, maxSteps = 2).run(0, "t")
        val f = r.failure!!
        assertEquals(FailureKind.STEP_LIMIT, f.kind)
        assertTrue(f.toolErrors.toString(), f.toolErrors.any { it.startsWith("web_fetch example.com: HTTP 403") })
    }

    @Test fun loopGuardWithoutAnswerIsFailure() = runTest {
        val r = loop({ _, t -> if (t == null) ok("") else ok(null, listOf(ParsedToolCall("c", "web_search", """{"query":"same"}"""))) }, maxSteps = 20).run(0, "t")
        assertEquals(FailureKind.LOOP_GUARD, r.failure!!.kind)
    }

    @Test fun timeoutKind() = runTest {
        var t = 0L
        val l = SubAgentLoop(model = { _, _ -> t += 100_000; ok(null, listOf(ParsedToolCall("c$t", "web_search", """{"query":"q$t"}"""))) },
            execute = { _, _ -> ToolResult("{}", false) }, toolSchemas = JsonArray(emptyList()), allowed = setOf("web_search"),
            maxSteps = 50, timeoutMs = 150_000, now = { t }, sleep = {}, jitter = { 0L }, rateWaitBudgetMs = 60_000)
        val r = l.run(0, "x")
        assertEquals(SubAgentResult.Status.TIMEOUT, r.status)
        assertEquals(FailureKind.WORKER_TIMEOUT, r.failure!!.kind)
    }

    @Test fun spawnResultListsFailedWorkers() {
        val f = WorkerFailure(FailureKind.RATE_LIMIT, "HTTP 429", 7, "not configured")
        val b = SubAgentBatch(listOf(SubAgentResult(0, "a", SubAgentResult.Status.OK, "ok"),
            SubAgentResult(1, "b", SubAgentResult.Status.ERROR, f.summary, failure = f)), "m", 2, emptySet())
        val json = SpawnSubagentsTool.resultJson(listOf("task a", "task b"), b).toString()
        val failed = SpawnSubagentsTool.failedWorkers(json)
        assertEquals(1, failed.size); assertEquals("task b", failed[0].first); assertTrue(failed[0].second.startsWith("Rate limited"))
        assertTrue(json.contains("\"failure\""))
    }

    @Test fun researchReportAndResultIncludeFailedWorkers() = runTest {
        val logged = mutableListOf<Boolean>()
        val engine = DeepResearchEngine(
            main = { msgs -> if (msgs.last().content!!.startsWith("You plan")) ok("[\"Q one?\",\"Q two?\"]") else ok("# R\n" + "x [1] ".repeat(60)) },
            worker = { i, task -> if (task.contains("Q two")) SubAgentResult(i, task, SubAgentResult.Status.ERROR, "x",
                failure = WorkerFailure(FailureKind.NETWORK, "Network error: timeout", 2)) else
                SubAgentResult(i, task, SubAgentResult.Status.OK, "notes", sources = listOf("https://a.example.org/x")) },
            sleep = {}, onWorkerFailed = { _, _, final -> logged += final },
        )
        val out = engine.run("topic", ResearchMode.STANDARD, ResearchLimits(2, 1, 10, 2))
        assertEquals(1, out.failures.size)
        run {
            assertTrue(out.report.contains("## Failed workers"))
            assertTrue(out.report.contains("Network error"))
            assertEquals(listOf(false, true), logged)
            val json = DeepResearchTool.resultJson(DeepResearchResult.Done(out, "p/r.md", null)).toString()
            assertEquals(out.failures.size, DeepResearchTool.failedWorkers(json).size)
            assertTrue(DeepResearchTool.summary(json)!!.contains("failed"))
        }
    }

    @Test fun markdownSectionEmptyWhenNoFailures() {
        assertEquals("", FailureText.markdownSection(emptyList()))
        assertTrue(FailureText.cardLines(listOf("q" to "r")).startsWith("1. q — r"))
    }
}
