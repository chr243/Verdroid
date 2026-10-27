package com.verdroid.app.agent.subagents

import com.verdroid.app.agent.tools.ToolContext
import com.verdroid.app.agent.tools.ToolResult
import com.verdroid.app.data.network.ChatOutcome
import com.verdroid.app.data.network.ParsedCompletion
import com.verdroid.app.data.network.ParsedToolCall
import com.verdroid.app.data.tools.ToolGroups
import com.verdroid.app.data.tools.ToolPreset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SubAgentsTest {
    private fun ok(content: String?, calls: List<ParsedToolCall> = emptyList()) =
        ChatOutcome.Success(ParsedCompletion(content, calls, "stop", "m"), "m", "k")

    // ---- queueing / concurrency ----

    @Test fun poolNeverExceedsCapAndKeepsOrder() = runTest {
        val inFlight = AtomicInteger(); val peak = AtomicInteger()
        val out = SubAgentPool.run((0 until 10).toList(), concurrency = 3) { i, t ->
            val n = inFlight.incrementAndGet(); peak.updateAndGet { maxOf(it, n) }
            delay(100L * (10 - i)); inFlight.decrementAndGet(); "r$t"
        }
        assertEquals((0 until 10).map { "r$it" }, out)
        assertEquals(3, peak.get())
    }

    @Test fun extraTasksAreQueuedFifo() = runTest {
        val started = mutableListOf<Int>()
        val gates = List(5) { CompletableDeferred<Unit>() }
        val job = async { SubAgentPool.run((0 until 5).toList(), concurrency = 2) { i, _ -> started += i; gates[i].await(); i } }
        advanceUntilIdle()
        assertEquals(listOf(0, 1), started)     // 3 tasks waiting
        gates[1].complete(Unit); advanceUntilIdle()
        assertEquals(listOf(0, 1, 2), started)
        gates[0].complete(Unit); advanceUntilIdle()
        assertEquals(listOf(0, 1, 2, 3), started)
        gates.forEach { it.complete(Unit) }
        assertEquals(listOf(0, 1, 2, 3, 4), job.await())
    }

    @Test fun progressCountsDoneAndFailed() = runTest {
        val seen = mutableListOf<SubAgentProgress>()
        SubAgentPool.run(listOf(1, 2, 3, 4), 2, onProgress = { seen += it }, failed = { r: Int -> r % 2 == 0 }) { _, t -> t }
        assertEquals(SubAgentProgress(4, 0, 0, 0), seen.first())
        assertEquals(SubAgentProgress(4, 4, 0, 2), seen.last())
        assertEquals("Sub-agents 4/4 done", seen.last().label)
        assertTrue(seen.all { it.running <= 2 })
    }

    @Test fun concurrencyIsClamped() = runTest {
        assertEquals(1, SubAgentPolicy.clampConcurrency(0)); assertEquals(10, SubAgentPolicy.clampConcurrency(50))
        val peak = AtomicInteger(); val now = AtomicInteger()
        SubAgentPool.run((0 until 30).toList(), 99) { _, _ -> peak.updateAndGet { maxOf(it, now.incrementAndGet()) }; delay(10); now.decrementAndGet() }
        assertEquals(10, peak.get())
    }

    @Test fun cancellingTheCallerCancelsQueuedAndRunning() = runTest {
        val started = AtomicInteger(); val finished = AtomicInteger()
        val job = launch { SubAgentPool.run((0 until 6).toList(), 2) { _, _ -> started.incrementAndGet(); delay(10_000); finished.incrementAndGet() } }
        runCurrent() // the first two start, four wait in the queue
        job.cancel(); advanceUntilIdle()
        assertEquals(2, started.get()); assertEquals(0, finished.get())
    }

    // ---- no recursion / restricted tools ----

    @Test fun subAgentToolsNeverIncludeSpawnOrWriteTools() {
        val parent = setOf("spawn_subagents", "web_search", "web_fetch", "write_file", "run_shell", "screen_tap", "x_post", "workspace_delete", "read_file", "mcp__a__b")
        assertEquals(setOf("web_search", "web_fetch", "read_file"), SubAgentPolicy.allowedTools(parent))
        assertFalse(SubAgentPolicy.TOOL_NAME in SubAgentPolicy.READ_ONLY_TOOLS)
        // A user selection can narrow the set but never add spawn/write tools.
        assertEquals(setOf("web_search"), SubAgentPolicy.allowedTools(parent, setOf("web_search", "spawn_subagents", "write_file")))
        assertFalse("cookie_open" in SubAgentPolicy.DEFAULT_TOOLS)
    }

    @Test fun subAgentCannotSpawnEvenIfModelAsks() = runTest {
        val executed = mutableListOf<String>()
        var turn = 0
        val loop = SubAgentLoop(
            model = { _, _ -> if (turn++ == 0) ok(null, listOf(ParsedToolCall("c1", "spawn_subagents", """{"tasks":["x"]}"""))) else ok("done") },
            execute = { n, _ -> executed += n; ToolResult("{}", false) },
            toolSchemas = JsonArray(emptyList()), allowed = SubAgentPolicy.allowedTools(setOf("web_search", "spawn_subagents")),
            sleep = {},
        )
        val r = loop.run(0, "task")
        assertTrue(executed.isEmpty())
        assertEquals(SubAgentResult.Status.OK, r.status)
    }

    @Test fun toolIsInItsOwnToggleableGroup() {
        assertEquals("agents", ToolGroups.groupOf("spawn_subagents"))
        assertEquals(ToolPreset.AGENTS, ToolPreset.of("spawn_subagents"))
        assertTrue(ToolGroups.ORDER.any { it.id == "agents" })
    }

    // ---- loop ----

    @Test fun loopRunsToolsThenAnswers() = runTest {
        var turn = 0
        val loop = SubAgentLoop(
            model = { msgs, _ ->
                when (turn++) {
                    0 -> ok(null, listOf(ParsedToolCall("c1", "web_search", """{"query":"a"}""")))
                    else -> { assertEquals("tool", msgs.last().role); ok("- finding (https://example.com)") }
                }
            },
            execute = { _, _ -> ToolResult("""{"results":[]}""", false) },
            toolSchemas = JsonArray(emptyList()), allowed = setOf("web_search"), sleep = {},
        )
        val r = loop.run(2, "t")
        assertEquals(SubAgentResult.Status.OK, r.status); assertEquals(2, r.steps); assertEquals(2, r.index)
    }

    @Test fun rateLimitIsRetriedWithBackoff() = runTest {
        var calls = 0; val sleeps = mutableListOf<Long>()
        val loop = SubAgentLoop(
            model = { _, _ -> if (calls++ < 2) ChatOutcome.RateLimited(0, "429") else ok("answer") },
            execute = { _, _ -> ToolResult("{}", false) }, toolSchemas = JsonArray(emptyList()), allowed = emptySet(),
            now = { 0L }, sleep = { sleeps += it }, jitter = { 0L },
        )
        assertEquals(SubAgentResult.Status.OK, loop.run(0, "t").status)
        assertEquals(listOf(2_000L, 4_000L), sleeps)
        assertEquals(120_000L, SubAgentLoop.backoffMs(1, 500_000))
    }

    @Test fun rateLimitGivesUpAfterRetries() = runTest {
        val loop = SubAgentLoop(model = { _, _ -> ChatOutcome.RateLimited(0, "429") }, execute = { _, _ -> ToolResult("{}", false) },
            toolSchemas = JsonArray(emptyList()), allowed = emptySet(), maxRetries = 2, now = { 0L }, sleep = {})
        val r = loop.run(0, "t")
        assertEquals(SubAgentResult.Status.ERROR, r.status); assertTrue(r.findings.contains("Rate limited"))
    }

    @Test fun repeatGuardStopsLoopingSubAgent() = runTest {
        var n = 0
        val loop = SubAgentLoop(
            model = { _, tools -> n++; if (tools == null) ok("final") else ok(null, listOf(ParsedToolCall("c$n", "web_search", """{"query":"same"}"""))) },
            execute = { _, _ -> ToolResult("{}", false) }, toolSchemas = JsonArray(listOf(JsonObject(emptyMap()))), allowed = setOf("web_search"),
            maxSteps = 50, sleep = {})
        val r = loop.run(0, "t")
        assertEquals("final", r.findings); assertEquals(5, r.steps)
    }

    @Test fun stepLimitForcesAnAnswer() = runTest {
        var n = 0
        val loop = SubAgentLoop(
            model = { _, tools -> n++; if (tools == null) ok("final") else ok(null, listOf(ParsedToolCall("c$n", "web_search", """{"query":"$n"}"""))) },
            execute = { _, _ -> ToolResult("{}", false) }, toolSchemas = JsonArray(listOf(JsonObject(emptyMap()))), allowed = setOf("web_search"),
            maxSteps = 3, sleep = {})
        val r = loop.run(0, "t")
        assertEquals("final", r.findings); assertEquals(3, r.steps)
    }

    @Test fun timeoutIsReported() = runTest {
        val loop = SubAgentLoop(model = { _, _ -> delay(10_000); ok("late") }, execute = { _, _ -> ToolResult("{}", false) },
            toolSchemas = JsonArray(emptyList()), allowed = emptySet(), timeoutMs = 1_000)
        assertEquals(SubAgentResult.Status.TIMEOUT, loop.run(0, "t").status)
    }

    // ---- tool ----

    @Test fun toolParsesArgsAndFormatsResults() = runTest {
        var got: Triple<Long, List<SubAgentTask>, String?>? = null
        val tool = SpawnSubagentsTool { id, t, m ->
            got = Triple(id, t, m)
            SubAgentBatch(t.mapIndexed { i, x -> SubAgentResult(i, x.prompt, if (i == 0) SubAgentResult.Status.OK else SubAgentResult.Status.TIMEOUT, "f$i") }, m, 3, setOf("web_search"))
        }
        val out = Json.parseToJsonElement(tool.execute(Json.parseToJsonElement(
            """{"tasks":["a","b"],"context":"ctx","model":"openrouter/free"}""").jsonObject, ToolContext(7))).jsonObject
        assertEquals(7L, got!!.first); assertEquals("openrouter/free", got!!.third)
        assertTrue(got!!.second[0].prompt.contains("ctx") && got!!.second[0].prompt.endsWith("a"))
        assertEquals("2", out["total"]!!.jsonPrimitive.content); assertEquals("1", out["failed"]!!.jsonPrimitive.content)
        assertEquals("Sub-agents 2/2 done · 1 failed", SpawnSubagentsTool.summary(out.toString()))
        assertTrue(tool.execute(Json.parseToJsonElement("""{"tasks":[]}""").jsonObject).contains("error"))
        val capped = SpawnSubagentsTool({ 2 }) { _, _, _ -> error("must not run") }
        assertTrue(capped.execute(Json.parseToJsonElement("""{"tasks":["a","b","c"]}""").jsonObject).contains("At most 2"))
    }

    // ---- URL verification ----

    @Test fun garbledUrlIsCorrectedToSeenOne() {
        val seen = setOf("https://x.com/elonmusk/status/123", "https://example.org/a")
        val r = UrlVerifier.verify("- post (https://x.xcom/elonmusk/status/123).\n- other https://example.org/a", seen)
        assertEquals("- post (https://x.com/elonmusk/status/123).\n- other https://example.org/a", r.text)
        assertEquals(mapOf("https://x.xcom/elonmusk/status/123" to "https://x.com/elonmusk/status/123"), r.corrected)
        assertEquals(listOf("https://x.com/elonmusk/status/123", "https://example.org/a"), r.verified)
        assertTrue(r.unverified.isEmpty())
    }

    @Test fun unseenUrlIsMarkedUnverified() {
        val r = UrlVerifier.verify("See https://made-up.example/news and https://www.Example.org/a/", setOf("https://example.org/a"))
        assertEquals("See https://made-up.example/news [unverified] and https://example.org/a", r.text)
        assertEquals(listOf("https://made-up.example/news"), r.unverified)
        assertEquals(listOf("https://example.org/a"), r.verified)   // host/www/slash-normalized match
    }

    @Test fun differentPathIsNotCorrected() {
        val r = UrlVerifier.verify("https://example.org/article-2024", setOf("https://example.org/article-1999"))
        assertEquals(listOf("https://example.org/article-2024"), r.unverified)
    }

    @Test fun extractHandlesJsonEscapesAndPunctuation() {
        assertEquals(listOf("https://a.com/x", "https://b.org/y?q=1"),
            UrlVerifier.extract("""{"u":"https:\/\/a.com\/x","t":"see https://b.org/y?q=1."}"""))
        assertEquals(1, UrlVerifier.distance("x.com", "x.xcom"))
    }

    @Test fun loopRecordsUrlsFromToolResultsAndVerifiesFindings() = runTest {
        var turn = 0
        val loop = SubAgentLoop(
            model = { _, _ -> if (turn++ == 0) ok(null, listOf(ParsedToolCall("c1", "web_fetch", """{"url":"https://news.site/a"}""")))
                else ok("- a (https://news.site/a)\n- b (https://links.site/pagee)\n- c (https://ghost.site/z)") },
            execute = { _, _ -> ToolResult("""{"text":"see https://links.site/page for more"}""", false) },
            toolSchemas = JsonArray(emptyList()), allowed = setOf("web_fetch"), sleep = {})
        val r = loop.run(0, "t")
        assertEquals(listOf("https://news.site/a", "https://links.site/page"), r.sources)
        assertEquals(listOf("https://ghost.site/z"), r.unverified)
        assertTrue(r.findings.contains("https://ghost.site/z [unverified]") && r.findings.contains("(https://links.site/page)"))
        assertTrue(SubAgentPolicy.systemPrompt(setOf("web_fetch")).contains("never retype"))
    }

    // ---- per-task tools ----

    @Test fun perTaskToolsAreIntersectedWithCeilingAndRejectionsReported() {
        val ceiling = setOf("web_search", "web_fetch", "read_file")
        val known = setOf("web_search", "web_fetch", "read_file", "write_file", "run_shell", "spawn_subagents", "pdf_info")
        val c = SubAgentToolChoice.choose(listOf("web_search", "write_file", "run_shell", "spawn_subagents", "nope", "pdf_info"), ceiling, known)
        assertEquals(setOf("web_search"), c.tools)
        assertEquals(setOf("write_file", "run_shell", "spawn_subagents", "nope", "pdf_info"), c.rejected.keys)
        assertEquals("unknown tool", c.rejected["nope"])
        assertTrue(c.rejected["pdf_info"]!!.contains("turned off"))
        // A ceiling that somehow contains unsafe tools is still filtered.
        assertEquals(setOf("web_search"), SubAgentToolChoice.choose(listOf("web_search", "write_file"), setOf("web_search", "write_file", "spawn_subagents"), known).tools)
    }

    @Test fun argsAcceptTaskObjectsWithTools() {
        val p = SubAgentArgs.parse(Json.parseToJsonElement(
            """{"tasks":["plain",{"task":"web one","tools":["web_search","web_fetch"]},{"task":"csv","tools":"read_file, pdf_info"}]}""").jsonObject).getOrThrow()
        assertEquals(listOf("plain", "web one", "csv"), p.tasks)
        assertEquals(listOf(null, listOf("web_search", "web_fetch"), listOf("read_file", "pdf_info")), p.tools)
    }

    @Test fun descriptionListsAllowedToolsAndResultCarriesSources() = runTest {
        val tool = SpawnSubagentsTool({ 10 }, { setOf("web_search", "write_file") }) { _, t, _ ->
            SubAgentBatch(t.mapIndexed { i, x -> SubAgentResult(i, x.prompt, SubAgentResult.Status.OK, "f", sources = listOf("https://a.com"),
                unverified = listOf("https://b.com"), rejectedTools = mapOf("run_shell" to "not allowed")) }, null, 3, emptySet())
        }
        assertTrue(tool.description.contains("Allowed names: web_search."))
        assertTrue(tool.description.contains("ALWAYS give every task a minimal tools list"))
        val item = tool.parameters["properties"]!!.jsonObject["tasks"]!!.jsonObject["items"]!!.jsonObject
        assertEquals(listOf("task", "tools"), (item["required"] as JsonArray).map { it.jsonPrimitive.content })
        val out = Json.parseToJsonElement(tool.execute(Json.parseToJsonElement("""{"tasks":[{"task":"a","tools":["run_shell"]}]}""").jsonObject)).jsonObject
        val r = (out["results"] as JsonArray)[0].jsonObject
        assertEquals("https://a.com", (r["sources"] as JsonArray)[0].jsonPrimitive.content)
        assertTrue(r.containsKey("unverified_urls") && r.containsKey("rejected_tools"))
        assertTrue(out.containsKey("allowed_tools") && !out.containsKey("tools"))
    }

    @Test fun omittedToolsFallBackToWebSearchAndFetchOnly() {
        val known = setOf("web_search", "web_fetch", "read_file", "cookie_open", "x_search", "selenium_open")
        val big = setOf("web_search", "web_fetch", "read_file", "cookie_open", "x_search", "selenium_open")
        assertEquals(setOf("web_search", "web_fetch"), SubAgentToolChoice.choose(null, big, known).tools)
        assertEquals(setOf("web_search", "web_fetch"), SubAgentToolChoice.choose(emptyList(), big, known).tools)
        // Fallback is intersected with the ceiling.
        assertEquals(setOf("web_fetch"), SubAgentToolChoice.choose(null, setOf("web_fetch", "read_file"), known).tools)
        assertEquals(emptySet<String>(), SubAgentToolChoice.choose(null, setOf("read_file"), known).tools)
        // An explicit list is honoured within the ceiling, beyond the fallback.
        assertEquals(setOf("read_file"), SubAgentToolChoice.choose(listOf("read_file"), big, known).tools)
    }
}
