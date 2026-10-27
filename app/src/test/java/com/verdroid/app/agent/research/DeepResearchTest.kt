package com.verdroid.app.agent.research

import com.verdroid.app.agent.subagents.SubAgentResult
import com.verdroid.app.data.network.ChatOutcome
import com.verdroid.app.data.network.ParsedCompletion
import com.verdroid.app.data.tools.ToolGroups
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DeepResearchTest {
    private fun ok(t: String) = ChatOutcome.Success(ParsedCompletion(t, emptyList(), "stop", "m"), "m", "k")
    private fun res(i: Int, vararg urls: String) = SubAgentResult(i, "t", SubAgentResult.Status.OK, "### P$i\nURL: ${urls.firstOrNull()}\n- fact $i", sources = urls.toList())

    @Test fun urlFilterAndDedupe() {
        assertFalse(ResearchPolicy.usable("https://www.google.com/search?q=x"))
        assertFalse(ResearchPolicy.usable("https://www.pinterest.com/pin/1"))
        assertFalse(ResearchPolicy.usable("https://example.com/logo.png"))
        assertFalse(ResearchPolicy.usable("ftp://example.com/a"))
        assertTrue(ResearchPolicy.usable("https://en.wikipedia.org/wiki/Solar_panel"))
        assertTrue(ResearchPolicy.usable("https://blogin.example.com/a")) // "login." prefix only
        assertEquals("https://a.com/x?b=2", ResearchPolicy.normalize("https://WWW.a.com/x/?utm_source=z&b=2#frag"))
        val d = ResearchPolicy.dedupe(listOf("https://a.com/x", "https://www.a.com/x/", "https://b.com", "https://c.com", "https://d.com"), 2, listOf("https://b.com/"))
        assertEquals(listOf("https://a.com/x", "https://c.com"), d)
    }

    @Test fun parseQuestionsToleratesFormats() {
        assertEquals(listOf("A?", "B?"), ResearchPolicy.parseQuestions("```json\n[\"A?\", \"B?\", \"a?\"]\n```", 5))
        assertEquals(listOf("What is the first thing?"), ResearchPolicy.parseQuestions("1. What is the first thing?\nnoise", 5))
        assertEquals(1, ResearchPolicy.parseQuestions("[\"A?\",\"B?\"]", 1).size)
        assertTrue(ResearchPolicy.parseQuestions(null, 3).isEmpty())
    }

    @Test fun limitsPerMode() {
        val s = ResearchPolicy.limits(ResearchMode.STANDARD, 25, 100, 2, 3)
        assertEquals(1, s.rounds); assertEquals(25, s.maxSources); assertEquals(5, s.perWorkerSources)
        val d = ResearchPolicy.limits(ResearchMode.DEEP, 25, 500, 9, 3)
        assertEquals(3, d.rounds); assertEquals(150, d.maxSources); assertEquals(ResearchPolicy.DEEP_QUESTIONS, d.questions)
        assertEquals(ResearchMode.DEEP, ResearchMode.of(" Deep ")); assertEquals(ResearchMode.STANDARD, ResearchMode.of(null))
        assertEquals("agents", ToolGroups.groupOf("deep_research"))
    }

    @Test fun pipelinePlansResearchesDedupesAndCites() = runTest {
        val prompts = mutableListOf<String>()
        val engine = DeepResearchEngine(
            main = { m -> val p = m.last().content!!; prompts += p
                when { p.startsWith("You plan") -> ok("[\"Q1?\",\"Q2?\",\"Q3?\"]"); else -> ok("# R\n" + "text [1]. ".repeat(40)) } },
            worker = { i, _ -> res(i, "https://s$i.com/a", "https://s0.com/a/", "https://www.google.com/search?q=1") },
            sleep = {},
        )
        val o = engine.run("topic", ResearchMode.STANDARD, ResearchLimits(5, 1, 25, 2))
        assertEquals(listOf("Q1?", "Q2?", "Q3?"), o.questions)
        assertEquals(listOf("https://s0.com/a", "https://s1.com/a", "https://s2.com/a"), o.sources)
        assertFalse(o.reportFallback)
        assertTrue(o.report.contains("## Sources") && o.report.contains("3. <https://s2.com/a>"))
        assertTrue(prompts.last().contains("[3] https://s2.com/a"))
    }

    @Test fun deepModeRunsFollowUpRoundAndRespectsSourceCap() = runTest {
        var round = 0
        val engine = DeepResearchEngine(
            main = { m -> val p = m.last().content!!
                when { p.startsWith("You plan") -> ok("[\"A?\",\"B?\"]"); p.startsWith("Research topic") -> { round++; ok("[\"C?\",\"A?\"]") }
                    else -> ok("# R\n" + "x [1] ".repeat(60)) } },
            worker = { i, task -> res(i, "https://${task.hashCode().toUInt()}.com/1", "https://${task.hashCode().toUInt()}.com/2") },
            sleep = {},
        )
        val o = engine.run("t", ResearchMode.DEEP, ResearchLimits(4, 2, 5, 2))
        assertEquals(1, round); assertEquals(2, o.rounds)
        assertEquals(listOf("A?", "B?", "C?"), o.questions) // the repeated "A?" follow-up is dropped
        assertEquals(5, o.sources.size)
    }

    @Test fun rateLimitedMainModelBacksOffThenFallsBackToNotes() = runTest {
        val sleeps = mutableListOf<Long>()
        val engine = DeepResearchEngine(
            main = { m -> if (m.last().content!!.startsWith("You plan")) ok("[\"A?\"]") else ChatOutcome.RateLimited(0, "429") },
            worker = { i, _ -> res(i, "https://a.com") }, sleep = { sleeps += it }, now = { 0L },
        )
        val o = engine.run("t", ResearchMode.STANDARD, ResearchLimits(2, 1, 10, 1))
        assertEquals(4, sleeps.size); assertTrue(sleeps.all { it in 1_000L..60_000L })
        assertTrue(o.reportFallback); assertTrue(o.report.contains("fact 0") && o.report.contains("## Sources"))
    }

    @Test fun failedWorkersAreCounted() = runTest {
        val engine = DeepResearchEngine(
            main = { m -> if (m.last().content!!.startsWith("You plan")) ok("[\"A?\",\"B?\"]") else ok("# R\n" + "y ".repeat(200)) },
            worker = { i, _ -> if (i == 0) SubAgentResult(i, "t", SubAgentResult.Status.TIMEOUT, "late") else res(i, "https://b.com") }, sleep = {},
        )
        val o = engine.run("t", ResearchMode.STANDARD, ResearchLimits(2, 1, 10, 2))
        assertEquals(1, o.failedWorkers); assertEquals(listOf("https://b.com"), o.sources)
    }

    @Test fun cancelStopsWorkers() = runTest {
        val started = AtomicInteger(); val finished = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val engine = DeepResearchEngine(
            main = { ok("[\"A?\",\"B?\",\"C?\"]") },
            worker = { i, _ -> started.incrementAndGet(); gate.complete(Unit); delay(100_000); finished.incrementAndGet(); res(i) }, sleep = {},
        )
        val job = launch { engine.run("t", ResearchMode.STANDARD, ResearchLimits(3, 1, 10, 2)) }
        gate.await(); job.cancel(); job.join()
        assertEquals(0, finished.get()); assertTrue(started.get() <= 2)
    }

    @Test fun toolJsonAndErrors() = runTest {
        val out = ResearchOutcome("t", ResearchMode.DEEP, "# R", listOf("https://a.com"), listOf("q"), 2, 0, false)
        var got: Triple<Long, String, ResearchMode>? = null
        val tool = DeepResearchTool { id, topic, mode, _ -> got = Triple(id, topic, mode); DeepResearchResult.Done(out, "/x/Output/Research/r.md", "openrouter/free") }
        val r = Json.parseToJsonElement(tool.execute(buildJsonObject { put("topic", "solar"); put("mode", "deep") }, com.verdroid.app.agent.tools.ToolContext(7))) as JsonObject
        assertEquals(Triple(7L, "solar", ResearchMode.DEEP), got)
        assertEquals("1", r["sources_count"]!!.jsonPrimitive.content)
        assertTrue(DeepResearchTool.summary(r.toString())!!.contains("r.md"))
        assertTrue(tool.execute(buildJsonObject { }).contains("error"))
        assertEquals("20261027-0700-solar-panels-in-egypt.md", ResearchPolicy.fileName("Solar panels in Egypt?!", "20261027-0700"))
    }
}
