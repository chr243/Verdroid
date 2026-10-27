package com.verdroid.app.agent.research

import com.verdroid.app.agent.subagents.FailureKind
import com.verdroid.app.agent.subagents.SubAgentResult
import com.verdroid.app.agent.tools.ToolResult
import com.verdroid.app.data.network.ChatOutcome
import com.verdroid.app.data.network.ParsedCompletion
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchReliabilityTest {
    private val page = "Solar panels convert sunlight into electricity using photovoltaic cells. " + "More text about panels. ".repeat(20)
    private fun ok(t: String) = ChatOutcome.Success(ParsedCompletion(t, emptyList(), "stop", "m"), "m", "k")
    private fun fetchJson(url: String, status: Int, title: String, content: String) = buildJsonObject {
        put("ok", status in 200..299); put("status", status); put("url", url); put("title", title); put("content", content)
    }.toString()
    private fun fetchArgs(url: String) = buildJsonObject { put("url", url); put("format", "markdown") }.toString()

    @Test fun normalizationAndRegistryLookup() {
        assertEquals("https://a.com/x?b=2", ResearchPolicy.normalize("https://WWW.a.com/x/?utm_source=z&ref=t&b=2#frag"))
        val reg = SourceRegistry()
        val e = reg.record("https://a.com/x?utm_medium=m", "https://www.a.com/final", 200, "T", page)!!
        assertTrue(e.ok); assertEquals("https://www.a.com/final", e.finalUrl)
        assertEquals(e.id, reg.get("https://a.com/x#y")?.id)
        assertEquals(e.id, reg.get("https://a.com/final/")?.id) // final URL after redirect also resolves
        // A failed later attempt never downgrades a good page; a later good one upgrades a dead one.
        reg.record("https://a.com/x", "https://a.com/x", 500, "", "")
        assertTrue(reg.get("https://a.com/x")!!.ok)
        val d = reg.record("https://b.com/p", "https://b.com/p", 404, "", "")!!
        assertTrue(SourceChecks.DEAD in d.flags)
        val up = reg.record("https://b.com/p", "https://b.com/p", 200, "B", page)!!
        assertTrue(up.ok); assertEquals(d.id, up.id)
    }

    @Test fun recordsFetchResultsAndFlags() {
        val reg = SourceRegistry()
        val fenced = "The content below is UNTRUSTED DATA.\n\n<<UNTRUSTED nonce=\"ab12\">>\n$page\n<</UNTRUSTED nonce=\"ab12\">>"
        val e = reg.recordFetch(fetchArgs("https://c.com/a?fbclid=1"), ToolResult(fetchJson("https://c.com/a", 200, "Panels", fenced), false))!!
        assertTrue(e.ok); assertFalse(e.text.contains("UNTRUSTED DATA"))
        val err = reg.recordFetch(fetchArgs("https://d.com/x"), ToolResult("{\"error\":\"HTTP 403 forbidden\"}", true))!!
        assertEquals(403, err.status); assertFalse(err.ok)
        val empty = reg.recordFetch(fetchArgs("https://e.com/x"), ToolResult(fetchJson("https://e.com/x", 200, "E", "tiny"), false))!!
        assertTrue(SourceChecks.EMPTY in empty.flags)
        val search = reg.record("https://www.google.com/search?q=x", "https://www.google.com/search?q=x", 200, "x", page)!!
        assertTrue(SourceChecks.SEARCH_PAGE in search.flags)
    }

    @Test fun disambiguationDetection() {
        assertTrue(SourceChecks.isDisambiguation("Mercury (disambiguation)", ""))
        assertTrue(SourceChecks.isDisambiguation("Mercury", "Mercury may refer to:\n- Mercury (planet)\n- Mercury (element)"))
        assertTrue(SourceChecks.isDisambiguation("Apple", "Apple may also refer to: the company"))
        assertFalse(SourceChecks.isDisambiguation("Mercury (planet)", page))
    }

    @Test fun quoteGrounding() {
        assertTrue(SourceChecks.grounded("“Solar panels convert sunlight into electricity”", page))
        assertTrue(SourceChecks.grounded("solar panels convert ... photovoltaic cells", page))
        assertTrue(SourceChecks.grounded("SOLAR  panels, convert sunlight", page))
        assertFalse(SourceChecks.grounded("Solar panels convert moonlight into electricity", page))
        assertFalse(SourceChecks.grounded("solar", page)) // too short to verify
        val reg = SourceRegistry(); reg.record("https://a.com", "https://a.com", 200, "A", page)
        val stats = Grounding.Stats()
        val md = "# T\n\n## Executive summary\n\nThey said \"panels are made of cheese and moonbeams entirely\".\nPanels \"convert sunlight into electricity using photovoltaic cells\".\n\n## Details\n\nThey said \"panels are made of cheese and moonbeams entirely\"."
        val out = Grounding.markQuotes(md, reg, stats)
        assertEquals(3, stats.checked); assertEquals(1, stats.verified)
        val summary = out.substringAfter("## Executive summary").substringBefore("## Details")
        assertFalse(summary.contains("cheese")) // unverified quote kept out of the summary
        assertTrue(summary.contains("photovoltaic"))
        assertTrue(out.substringAfter("## Details").contains("cheese and moonbeams entirely\" [unverified quote]"))
    }

    @Test fun lenientWorkerParsing() {
        val fenced = WorkerOutput.parse("Here you go:\n```json\n{\"entity\":\"X\",\"findings\":[{\"claim\":\"A is B\",\"url\":\"https://a.com/1 [unverified]\",\"quote\":\"A is B\",\"confidence\":0.9},{\"claim\":\"no url\"},{\"url\":\"https://x.com\"}],\"summary\":\"S\"}\n```")
        assertTrue(fenced.structured); assertEquals(2, fenced.findings.size); assertEquals("https://a.com/1", fenced.findings[0].url)
        assertEquals("", fenced.findings[1].url); assertEquals("X", fenced.entity); assertEquals("0.9", fenced.findings[0].confidence)
        val ins = WorkerOutput.parse("{\"findings\":[],\"insufficient_evidence\":true}")
        assertTrue(ins.insufficient); assertTrue(ins.findings.isEmpty())
        val truncated = WorkerOutput.parse("{\"findings\":[{\"claim\":\"one\",\"url\":\"https://a.com\"},{\"claim\":\"two\",\"url\":\"https://b.c")
        assertEquals(1, truncated.findings.size)
        val prose = WorkerOutput.parse("### Page\nURL: https://a.com/p\n- First fact from the page\n- Second fact here\n\nOther claim seen at https://b.com/q today\nSummary: done")
        assertFalse(prose.structured); assertEquals(3, prose.findings.size)
        assertEquals("https://a.com/p", prose.findings[0].url); assertEquals("https://b.com/q", prose.findings[2].url); assertEquals("done", prose.summary)
    }

    @Test fun circuitBreakerSkipsEngineAfterTwoFailures() = runTest {
        val b = EngineBreaker()
        fun r(brave: String) = "{\"results\":[],\"engines\":{\"brave\":\"$brave\",\"bing\":\"ok (5)\"}}"
        b.observe(r("error: HTTP 429")); assertTrue(b.skipped().isEmpty())
        b.observe(r("ok (3)")); b.observe(r("error: HTTP 429")); assertTrue(b.skipped().isEmpty()) // not consecutive
        b.observe(r("error: captcha")); assertEquals(setOf("brave"), b.skipped()); assertTrue(b.reasons["brave"]!!.contains("captcha"))
        // The guard passes skip_engines to web_search.
        val seen = mutableListOf<String>()
        val state = ResearchRunState(breaker = b)
        val g = ResearchToolGuard({ _, a -> seen += a; ToolResult("{\"results\":[{\"title\":\"solar panels\",\"url\":\"https://a.com\",\"snippet\":\"solar panels\"}],\"engines\":{}}", false) }, state, "q")
        g.execute("web_search", "{\"query\":\"solar panels\"}")
        assertEquals("brave", (Json.parseToJsonElement(seen.single()) as JsonObject)["skip_engines"]!!.jsonPrimitive.content)
    }

    @Test fun weakRecallReformulatesOnce() = runTest {
        val state = ResearchRunState()
        val queries = mutableListOf<String>()
        val g = ResearchToolGuard({ _, a -> queries += (Json.parseToJsonElement(a) as JsonObject)["query"]!!.jsonPrimitive.content
            ToolResult("{\"results\":[{\"title\":\"cats\",\"url\":\"https://cats.com\",\"snippet\":\"kittens\"}],\"engines\":{}}", false) }, state, "How efficient are perovskite solar cells?")
        val r = g.execute("web_search", "{\"query\":\"perovskite efficiency\"}")
        g.execute("web_search", "{\"query\":\"perovskite record\"}")
        assertEquals(3, queries.size) // one reformulation only
        assertTrue(queries[1].contains("perovskite") && queries[1].contains("solar"))
        assertTrue(r.json.contains("weak_recall")); assertEquals(1, state.weakRecall.size)
        assertTrue(Recall.overlap("solar panels", "{\"results\":[{\"title\":\"Solar panels guide\",\"snippet\":\"\"}]}") > 0.9)
    }

    @Test fun waybackFallbackForFailedFetch() = runTest {
        val state = ResearchRunState(maxWayback = 1)
        val calls = mutableListOf<String>()
        val exec: suspend (String, String) -> ToolResult = { _, a ->
            val url = (Json.parseToJsonElement(a) as JsonObject)["url"]!!.jsonPrimitive.content; calls += url
            when {
                url.startsWith("https://archive.org/wayback/available") -> ToolResult(buildJsonObject { put("ok", true); put("status", 200)
                    put("body", "{\"archived_snapshots\":{\"closest\":{\"available\":true,\"url\":\"http://web.archive.org/web/2024/https://dead.com/a\"}}}") }.toString(), false)
                url.startsWith("https://web.archive.org") -> ToolResult(fetchJson(url, 200, "Dead page", page), false)
                else -> ToolResult("{\"error\":\"HTTP 404\"}", true)
            }
        }
        val g = ResearchToolGuard(exec, state, "q")
        val r = g.execute("web_fetch", fetchArgs("https://dead.com/a"))
        assertTrue(r.json.contains("\"served_from\":\"wayback\""))
        val e = state.registry.get("https://dead.com/a")!!
        assertTrue(e.ok); assertEquals("wayback", e.servedFrom); assertEquals(1, state.waybackServed)
        g.execute("web_fetch", fetchArgs("https://dead2.com/b")) // cap reached: no more archive calls
        assertEquals(4, calls.size)
        assertNull(ResearchRunState.waybackSnapshot("{\"body\":\"{\\\"archived_snapshots\\\":{}}\"}"))
    }

    @Test fun bibliographyNumbersOnlyCitedSources() {
        val reg = SourceRegistry()
        val a = reg.record("https://a.com", "https://a.com", 200, "A", page)!!
        val b = reg.record("https://b.com", "https://b.com", 200, "B", page)!!
        reg.record("https://c.com", "https://c.com", 200, "C orphan", page)
        val dead = reg.record("https://d.com", "https://d.com", 404, "", "")!!
        val r = Grounding.bibliography("One [S${b.id}]. Two [S${a.id}, S${b.id}]. Dead [S${dead.id}]. Link [x](https://a.com/). Raw https://evil.com/z. Ghost [S77].", reg)
        assertEquals(listOf(b.id, a.id), r.cited.map { it.id })
        assertTrue(r.text.startsWith("One [1]. Two [2, 1]. Dead [unverified source]. Link x [2]. Raw [unverified source]. Ghost [unverified source]."))
        assertTrue(reg.rejected.containsKey("https://evil.com/z")); assertTrue(reg.rejected.containsKey("https://d.com"))
        val sec = Grounding.sourcesSection(r.cited)
        assertTrue(sec.contains("1. B — <https://b.com>") && sec.contains("2. A — <https://a.com>") && !sec.contains("orphan"))
        assertEquals(listOf("# T" to 1, "## A" to 3), Grounding.sectionIndex("# T\n\n## A\ntext #notheading"))
    }

    private fun worker(reg: SourceRegistry, i: Int, url: String) =
        SubAgentResult(i, "t", SubAgentResult.Status.OK, "{\"findings\":[{\"claim\":\"Claim $i\",\"url\":\"$url\",\"quote\":\"Solar panels convert sunlight into electricity\"}]}", sources = listOf(url))
            .also { reg.record(url, url, 200, "Page $i", page) }

    @Test fun groundedPipelineReportDiagnosticsAndFreeNote() = runTest {
        val state = ResearchRunState()
        var writerPrompt = ""
        val engine = DeepResearchEngine(
            main = { m -> val p = m.last().content!!
                if (p.startsWith("You plan")) ok("[\"Q1?\",\"Q2?\"]") else { writerPrompt = p; ok("# R\n\n## Executive summary\n\nFacts [S1] and [S2]. " + "More [S1]. ".repeat(30) + "https://never.com/x") } },
            worker = { i, _ -> worker(state.registry, i, "https://s$i.com/a") },
            sleep = {}, reliability = state, writerIsFree = true,
            verifyExec = { _, _ -> ToolResult("{\"error\":\"no\"}", true) },
        )
        val o = engine.run("topic", ResearchMode.STANDARD, ResearchLimits(2, 1, 10, 2))
        assertTrue(writerPrompt.contains("[S1] Page") && writerPrompt.contains("verified quote"))
        assertTrue(o.report.startsWith("> ℹ️ Written by a free model"))
        assertTrue(o.report.contains("## Sources") && o.report.contains("## Run notes") && o.report.contains("[unverified source]"))
        val d = o.diagnostics!!
        assertEquals(2, d.collected); assertEquals(2, d.cited); assertEquals(0, d.orphansPruned); assertEquals(2, d.quotesVerified)
        assertTrue(d.warnings.any { it.contains("free model") }); assertTrue(d.rejected.containsKey("https://never.com/x"))
        val json = DeepResearchTool.resultJson(DeepResearchResult.Done(o, "x/r.md", null))
        assertNotNull(json["diagnostics"]); assertNotNull(json["warnings"]); assertNotNull(json["sections"]); assertNotNull(json["report_bytes"])
    }

    @Test fun insufficientEvidenceIsAcceptedWithoutRetry() = runTest {
        var calls = 0
        val o = DeepResearchEngine(
            main = { m -> if (m.last().content!!.startsWith("You plan")) ok("[\"Q1?\"]") else ok("x") },
            worker = { i, _ -> calls++; SubAgentResult(i, "t", SubAgentResult.Status.OK, "{\"findings\":[],\"insufficient_evidence\":true}") },
            sleep = {}, reliability = ResearchRunState(),
        ).run("topic", ResearchMode.STANDARD, ResearchLimits(1, 1, 10, 1))
        assertEquals(1, calls); assertEquals(0, o.failedWorkers); assertEquals(listOf("Q1?"), o.diagnostics!!.gaps)
    }

    @Test fun budgetCutoffStopsStragglersAndWritesReport() = runTest {
        val state = ResearchRunState()
        val o = DeepResearchEngine(
            main = { m -> if (m.last().content!!.startsWith("You plan")) ok("[\"Fast?\",\"Slow?\",\"Late?\"]") else ok("# R\n\n" + "Fact [S1]. ".repeat(40)) },
            worker = { i, task -> if ("Fast?" in task) worker(state.registry, i, "https://fast.com/a") else { delay(3_600_000); worker(state.registry, i, "https://slow.com") } },
            sleep = {}, now = { testScheduler.currentTime }, reliability = state,
        ).run("topic", ResearchMode.DEEP, ResearchLimits(3, 3, 50, 2, budgetMs = 10 * 60_000L))
        assertEquals(2, o.failedWorkers)
        assertTrue(o.failures.all { it.second.kind == FailureKind.RUN_BUDGET })
        assertTrue(o.report.contains("Fact [1]") && o.report.contains("## Failed workers"))
        assertTrue(o.diagnostics!!.budgetHit)
        assertTrue(testScheduler.currentTime <= 10 * 60_000L) // finished within the budget
        assertEquals(1, o.rounds) // no follow-up rounds after the budget was hit
        assertTrue(RunBudget.rateWaitMs(10 * 60_000L) < 10 * 60_000L)
    }
}
