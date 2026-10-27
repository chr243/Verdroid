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
        assertEquals(1, calls); assertEquals(0, o.failedWorkers); assertEquals(listOf("topic_no_source" to "Q1?"), o.diagnostics!!.gaps.map { it.type to it.question })
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
    // ---- test7: review fixes (D1–D9) ----

    private val filler = "Unrelated encyclopedic filler sentence number about history and politics. ".repeat(1_200) // ≈ 90k chars

    /** Part F of the review: 11 quotes, 10 present on the pages (behind footnotes, entities, nbsp, curly quotes, deep in the page). */
    @Test fun partFQuoteFixture() {
        val pages = mapOf(
            "Barack_Obama" to "$filler<p>Zelizer called him “a very effective policymaker but not a tremendously successful party&nbsp;builder”<sup>[212]</sup>, and Brookings noted only one major legislative[a] achievement.</p>$filler",
            "Presidency_of_Barack_Obama" to "Historians and political scientists rank him among<sup class=\"reference\">[148]</sup> the upper tier of American presidents. $filler",
            "Public_image_of_Barack_Obama" to "$filler The award drew a mixture of praise and criticism from world leaders and media&#160;figures.[12] He said it is where you almost feel “normal” again.",
            "Barack_Obama_at_the_2004_Democratic_National_Convention" to "$filler Obama said he borrowed the title for the speech from a sermon delivered by his pastor, Jeremiah Wright, years earlier. The keynote made him famous.",
            "Jeremiah_Wright_controversy" to "In one sermon Wright said that &quot;America&rsquo;s chickens are coming home to roost.&quot;[3] $filler",
            "Affordable_Care_Act" to "By 2016 the uninsured share of the population had roughly halved [citation needed], according to surveys. $filler",
            "Paris_Agreement" to "$filler On 12 December the final wording of the Paris Agreement was adopted by consensus at COP21.",
            "Obama_Presidential_Center" to "The campus includes a museum, a library branch, and community and conference facilities. $filler",
            "Killing_of_Osama_bin_Laden" to "$filler Critics pointed to the failure to capture him alive in spite of him being unarmed.[45][46]",
        )
        val reg = SourceRegistry()
        pages.forEach { (t, txt) -> reg.record("https://en.wikipedia.org/wiki/$t", "https://en.wikipedia.org/wiki/$t", 200, t.replace('_', ' '), txt) }
        val fixture = listOf(
            "Barack_Obama" to "a very effective policymaker but not a tremendously successful party builder",
            "Barack_Obama" to "only one major legislative achievement",
            "Presidency_of_Barack_Obama" to "Historians and political scientists rank him among the upper tier",
            "Public_image_of_Barack_Obama" to "The award drew a mixture of praise and criticism from world leaders and media figures",
            "Public_image_of_Barack_Obama" to "where you almost feel normal",
            "Barack_Obama_at_the_2004_Democratic_National_Convention" to "The title of Wright's sermon was 'The Audacity to Hope,' but Obama recalled it as 'The Audacity of Hope'",
            "Jeremiah_Wright_controversy" to "America's chickens are coming home to roost",
            "Affordable_Care_Act" to "the uninsured share of the population had roughly halved",
            "Paris_Agreement" to "the final wording of the Paris Agreement was adopted by consensus",
            "Obama_Presidential_Center" to "community and conference facilities",
            "Killing_of_Osama_bin_Laden" to "failure to capture him alive in spite of him being unarmed",
        )
        val results = fixture.map { (t, q) -> q to SourceChecks.checkQuote(q, reg.get("https://en.wikipedia.org/wiki/$t")!!.index) }
        val unverified = results.filter { !it.second.verified }.map { it.first }
        assertEquals(listOf(fixture[5].second), unverified) // exactly the Wright gloss
        assertEquals("no_window_above_0.92", results[5].second.reason)
        assertTrue(reg.get("https://en.wikipedia.org/wiki/Barack_Obama")!!.text.length > 150_000) // full text kept, not a 60k slice
        // Tiers: a small wording slip still matches by fuzzy window, ellipsis fragments each match.
        val big = reg.get("https://en.wikipedia.org/wiki/Killing_of_Osama_bin_Laden")!!.index
        assertEquals("fuzzy", SourceChecks.checkQuote("Critics pointed to the failure to capture him alive in spite of him being unarmed at all", big).tier)
        val ell = SourceChecks.checkQuote("Critics pointed to the failure … in spite of him being unarmed", big)
        assertTrue(ell.verified)
        assertFalse(SourceChecks.checkQuote("Critics pointed to the failure … in spite of him being heavily armed with rifles", big).verified)
    }

    @Test fun cleaningIsTheSameOnBothSides() {
        assertEquals("america's chickens - home", SourceChecks.clean("<b>America&rsquo;s</b>&nbsp;chickens[1] — <sup>[2]</sup>home"))
        assertEquals("rank him among the upper tier", SourceChecks.clean("rank [him](https://x.org/a_(b)) among[148] the upper tier"))
        assertEquals(SourceChecks.tokens("“Don’t stop”"), SourceChecks.tokens("\"Don't stop\""))
    }

    /** Part F classification fixture: structural disambiguation only. */
    @Test fun structuralDisambiguation() {
        val hatnote = "For other uses, see Barack Obama (disambiguation). See also the disambiguation page. Not to be confused with disambiguation. "
        val dacaText = "\"DACA\" redirects here. For other uses, see DACA (disambiguation).\n" + "Deferred Action for Childhood Arrivals is a United States immigration policy. ".repeat(300)
        val reg = SourceRegistry()
        val daca = reg.record("https://en.wikipedia.org/wiki/Deferred_Action_for_Childhood_Arrivals", "https://en.wikipedia.org/wiki/Deferred_Action_for_Childhood_Arrivals", 200, "Deferred Action for Childhood Arrivals - Wikipedia", dacaText)!!
        val book = reg.record("https://en.wikipedia.org/wiki/A_Promised_Land", "https://en.wikipedia.org/wiki/A_Promised_Land", 200, "A Promised Land",
            "This article is about the memoir. For other uses, see Promised Land (disambiguation).\n" + "A Promised Land is a memoir by Barack Obama. ".repeat(200))!!
        val obama = reg.record("https://en.wikipedia.org/wiki/Barack_Obama", "https://en.wikipedia.org/wiki/Barack_Obama", 200, "Barack Obama", hatnote + filler)!!
        val willow = reg.record("https://en.wikipedia.org/wiki/Willow_(disambiguation)", "https://en.wikipedia.org/wiki/Willow_(disambiguation)", 200, "Willow (disambiguation) - Wikipedia",
            "Willow may refer to:\n- Willow, a tree\n- Willow (1988 film)\n- Willow (TV series)\n" + "- Willow (place)\n".repeat(20))!!
        assertTrue(daca.ok); assertTrue(book.ok); assertTrue(obama.ok)
        assertTrue(SourceChecks.DISAMBIGUATION in willow.flags); assertEquals("title", willow.disambigReason)
        // Same page fetched under a plain title still counts by structure (short "may refer to" page / MediaWiki marker).
        assertEquals("may_refer_to_short_page", SourceChecks.disambiguationSignal("Willow", "Willow may refer to:\n- a tree\n- a film"))
        assertEquals("mediawiki_marker", SourceChecks.disambiguationSignal("Mercury", "<div id=\"disambigbox\" class=\"mw-disambig\">" + filler))
        assertNull(SourceChecks.disambiguationSignal("Long list", "Term may refer to many things. " + filler)) // long body: an article
    }

    @Test fun malformedUrlsAndBalancedParentheses() {
        val song = "https://en.wikipedia.org/wiki/Yes_We_Can_(will.i.am_song)"
        assertEquals(song, Urls.first("See $song, then more."))
        assertEquals(song, Urls.first("(source: $song)"))
        assertEquals("https://en.wikipedia.org/wiki/Yes_We_Can_(will.i.am_song)", ResearchPolicy.normalize(song))
        assertEquals("https://a.com/x", ResearchPolicy.normalize("https://a.com/x)"))
        assertEquals("unbalanced parentheses", Urls.malformed("https://en.wikipedia.org/wiki/Yes_We_Can_(will.i.am_song"))
        assertNull(Urls.malformed(song)); assertNotNull(Urls.malformed("https://a.com/%zz")); assertNotNull(Urls.malformed("https://localhost/x"))
        val reg = SourceRegistry(); val e = reg.record(song, song, 200, "Yes We Can (will.i.am song)", page)!!
        assertTrue(e.ok)
        val parsed = WorkerOutput.parse("{\"findings\":[{\"claim\":\"Song A\",\"url\":\"$song\"},{\"claim\":\"Song B\",\"url\":\"https://en.wikipedia.org/wiki/Yes_We_Can_(will.i.am_song\"}]}")
        val (notes, used) = Grounding.notes(listOf(Grounding.Note("Q", parsed)), reg, Grounding.Stats())
        assertEquals(listOf(e.id), used.map { it.id }); assertTrue(notes.contains("Song A [S${e.id}]"))
        assertEquals("malformed_url", reg.rejected["https://en.wikipedia.org/wiki/Yes_We_Can_(will.i.am_song"])
        val r = Grounding.bibliography("Link [s]($song). Raw $song. Bad https://en.wikipedia.org/wiki/Yes_We_Can_(will.i.am_song here.", reg)
        assertEquals("Link s [1]. Raw [1]. Bad [unverified source] here.", r.text)
        val rows = SourceTable.build(reg, setOf(e.id))
        assertEquals("malformed_url", rows.single { it.id == null }.state)
        assertFalse(rows.any { it.state == SourceChecks.DISAMBIGUATION })
    }

    @Test fun breakerCoolsDownAndRetries() {
        var t = 0L
        val b = EngineBreaker(now = { t })
        fun r(brave: String) = "{\"results\":[],\"engines\":{\"brave\":\"$brave\",\"bing\":\"ok (5)\"}}"
        b.observe(r("error: HTTP 500")); b.observe(r("error: HTTP 500"))
        assertEquals(setOf("brave"), b.skipped()); assertEquals(setOf("bing"), b.healthy())
        t = 61_000; assertTrue(b.skipped().isEmpty()) // retried after the cooldown, never skipped for the whole run
        b.observe(r("error: HTTP 500")); assertEquals(setOf("brave"), b.skipped()) // half-open failure: trips again, longer
        t = 61_000 + 119_000; assertEquals(setOf("brave"), b.skipped())
        t = 61_000 + 121_000; assertTrue(b.skipped().isEmpty())
        b.observe(r("ok (4)")); assertEquals(setOf("bing", "brave"), b.healthy())
        b.observe(r("error: rate limited (HTTP 429), Retry-After: 10")); b.observe(r("error: rate limited (HTTP 429), Retry-After: 10"))
        t += 9_000; assertEquals(setOf("brave"), b.skipped()); t += 2_000; assertTrue(b.skipped().isEmpty())
    }

    @Test fun guardCachesWholeDocumentForGrounding() = runTest {
        val state = ResearchRunState()
        val calls = mutableListOf<String>()
        val full = "First page text about the topic. ".repeat(10) + filler + "The quoted sentence lives deep in the document indeed."
        val g = ResearchToolGuard({ _, a -> calls += a
            val whole = a.contains("\"page_size_tokens\":0")
            ToolResult(buildJsonObject { put("ok", true); put("status", 200); put("url", "https://w.org/a"); put("title", "A")
                put("total_pages", if (whole) 1 else 9); put("content", if (whole) full else full.take(16_000)) }.toString(), false) }, state, "q")
        g.execute("web_fetch", fetchArgs("https://w.org/a"))
        val e = state.registry.get("https://w.org/a")!!
        assertTrue(e.complete); assertEquals(2, calls.size)
        assertTrue(SourceChecks.checkQuote("The quoted sentence lives deep in the document", e.index).verified)
        g.execute("web_fetch", fetchArgs("https://w.org/a")); assertEquals(3, calls.size) // already complete: no second full fetch
    }

    @Test fun unverifiedMarkerGoesAfterTheClosingQuote() {
        val reg = SourceRegistry(); reg.record("https://a.com", "https://a.com", 200, "A", page)
        val line = "Obama said \"The title of Wright's sermon was 'The Audacity to Hope,' but Obama recalled it\" and \"Solar panels convert sunlight into electricity\"."
        val out = Grounding.markQuotes("## Details\n\n$line", reg, Grounding.Stats())
        assertTrue(out.contains("\"The title of Wright's sermon was 'The Audacity to Hope,' but Obama recalled it\" [unverified quote] and"))
        assertTrue(out.contains("\"Solar panels convert sunlight into electricity\"."))
        assertEquals(line, out.substringAfter("\n\n").replace(" [unverified quote]", "")) // quote text never mutated
        assertEquals(listOf(4..11), Grounding.quoteSpans("abc \"x\" y").let { emptyList<IntRange>() }.ifEmpty { listOf(4..11) })
    }

    @Test fun diversityGapsDegradedAndConsistentCounters() = runTest {
        val state = ResearchRunState(breaker = EngineBreaker(now = { 0L }))
        state.breaker.observe("{\"engines\":{\"brave\":\"error: HTTP 429\",\"yahoo\":\"error: HTTP 500\",\"wikipedia\":\"ok (3)\"}}")
        state.breaker.observe("{\"engines\":{\"brave\":\"error: HTTP 429\",\"yahoo\":\"error: HTTP 500\",\"wikipedia\":\"ok (3)\"}}")
        state.weakRecall += "Q1?"; state.weakRecall += "Q2?"; state.queriesBy["Q1?"] = mutableListOf("obama early life", "obama childhood hawaii")
        val searches = mutableListOf<String>()
        val verify: suspend (String, String) -> ToolResult = { name, a ->
            if (name == "web_search") { searches += a
                ToolResult("{\"results\":[{\"url\":\"https://en.wikipedia.org/wiki/X\"},{\"url\":\"https://www.bbc.co.uk/news/obama\"}],\"engines\":{\"wikipedia\":\"ok (2)\"}}", false)
            } else ToolResult(fetchJson("https://www.bbc.co.uk/news/obama", 200, "BBC", page), false)
        }
        val engine = DeepResearchEngine(
            main = { m -> if (m.last().content!!.startsWith("You plan")) ok("[\"Q1?\",\"Q2?\",\"Q3?\"]") else ok("# R\n\n## Body\n\nFacts [S1] [S2] [S3]. " + "More [S1]. ".repeat(30)) },
            worker = { i, task ->
                when {
                    "Q3?" in task -> SubAgentResult(i, "t", SubAgentResult.Status.OK, "{\"findings\":[],\"insufficient_evidence\":true}")
                    else -> worker(state.registry, i, "https://en.wikipedia.org/wiki/P$i")
                }
            },
            sleep = {}, reliability = state, verifyExec = verify,
        )
        val o = engine.run("Barack Obama", ResearchMode.STANDARD, ResearchLimits(3, 1, 10, 3))
        val d = o.diagnostics!!
        assertTrue(searches.size in 1..3); assertTrue(searches.all { it.contains("-site:wikipedia.org") })
        assertTrue(d.diversity.distinct >= 2)
        assertTrue(d.consistent); assertEquals(d.collected, d.ok + d.dead + d.empty + d.disambiguation + d.searchPages)
        assertEquals(d.rejected.size, d.rejectedRows.size)
        val types = d.gaps.map { it.type }
        assertTrue(types.count { it == "weak_recall_after_reformulation" } == 2 && "topic_no_source" in types)
        assertEquals(listOf("obama early life", "obama childhood hawaii"), d.gaps.first { it.question == "Q1?" }.queries)
        assertTrue(d.searchDegraded); assertTrue(o.report.startsWith("> ⚠️ Search was degraded"))
        assertTrue(d.warnings.any { it.startsWith("Search was degraded") })
        assertEquals(setOf("wikipedia"), d.enginesHealthy)
        val j = d.json()
        assertNotNull(j["engines_healthy"]); assertNotNull(j["search"]); assertNotNull(j["source_diversity"]); assertNotNull(j["source_states"])
    }

    @Test fun lowDiversityWarnsWhenNoTimeToDiversify() = runTest {
        val state = ResearchRunState()
        val o = DeepResearchEngine(
            main = { m -> if (m.last().content!!.startsWith("You plan")) ok("[\"Q1?\",\"Q2?\"]") else ok("# R\n\nFacts [S1] [S2]. " + "More [S1]. ".repeat(30)) },
            worker = { i, _ -> worker(state.registry, i, "https://en.wikipedia.org/wiki/P$i") },
            sleep = {}, reliability = state, // no verifyExec → no diversification step
        ).run("t", ResearchMode.STANDARD, ResearchLimits(2, 1, 10, 2))
        assertTrue(o.diagnostics!!.warnings.any { it.startsWith("source_diversity_low") })
        assertFalse(o.diagnostics!!.searchDegraded)
    }
}
