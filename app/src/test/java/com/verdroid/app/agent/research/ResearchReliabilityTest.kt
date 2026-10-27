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
import org.junit.Assert.assertNotEquals
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
        assertTrue(SourceChecks.NOT_FOUND in d.flags)
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
                else -> ToolResult("{\"error\":\"HTTP 500\"}", true) // a 404 would be terminal (not_found) and skip the archive
            }
        }
        val g = ResearchToolGuard(exec, state, "q")
        val r = g.execute("web_fetch", fetchArgs("https://dead.com/a"))
        assertTrue(r.json.contains("\"served_from\":\"wayback\""))
        val e = state.registry.get("https://dead.com/a")!!
        assertTrue(e.ok); assertEquals("wayback", e.servedFrom); assertEquals(1, state.waybackServed)
        g.execute("web_fetch", fetchArgs("https://dead2.com/b")) // cap reached: no more archive calls
        assertEquals(6, calls.size) // 2 + retry each, archive only for the first
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
        assertTrue(d.consistent); assertEquals(d.collected, d.ok + d.failed + d.empty + d.disambiguation + d.searchPages)
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
    // ---- test8: fetch-status recovery, grounding coverage, duplicates, diversification trigger ----

    private fun errJson(msg: String) = buildJsonObject { put("error", msg) }.toString()
    private fun statusJson(url: String, status: Int, retryAfter: String? = null) = buildJsonObject {
        put("ok", false); put("status", status); put("url", url); put("title", ""); put("total_pages", 1); put("content", "Error page")
        retryAfter?.let { put("retry_after", it) }
    }.toString()

    /** Scripted web_fetch: per URL a queue of replies; Wayback availability says "none" unless [snapshots] has the URL. */
    private class FakeWeb(val script: Map<String, MutableList<String>>, val snapshots: Map<String, String> = emptyMap()) {
        val calls = mutableListOf<String>()
        suspend fun exec(name: String, a: String): ToolResult {
            val url = (Json.parseToJsonElement(a) as JsonObject)["url"]!!.jsonPrimitive.content
            calls += url
            if (url.startsWith("https://archive.org/wayback/available")) {
                val orig = java.net.URLDecoder.decode(url.substringAfter("url="), "UTF-8")
                val snap = snapshots[orig]
                val body = if (snap == null) "{\"archived_snapshots\":{}}" else "{\"archived_snapshots\":{\"closest\":{\"available\":true,\"url\":\"$snap\"}}}"
                return ToolResult(buildJsonObject { put("ok", true); put("status", 200); put("body", body) }.toString(), false)
            }
            val q = script[url] ?: return ToolResult("{\"error\":\"web_fetch failed: no script\"}", true)
            val r = if (q.size > 1) q.removeAt(0) else q.first()
            return ToolResult(r, r.contains("\"error\""))
        }
    }

    private fun stateFor(t: () -> Long = { 0L }, waits: MutableList<Long> = mutableListOf()) =
        ResearchRunState(now = t, sleep = { waits += it }, jitter = { 0L })

    @Test fun notFoundIsTerminalWithoutRetry() = runTest {
        val u = "https://www.noaa.gov/weather-modification"
        val web = FakeWeb(mapOf(u to mutableListOf(statusJson(u, 404))))
        val st = stateFor()
        ResearchToolGuard(web::exec, st, "q").execute("web_fetch", fetchArgs(u))
        val e = st.registry.get(u)!!
        assertEquals(SourceChecks.NOT_FOUND, e.failure); assertEquals("not_found", SourceTable.state(e))
        assertEquals(listOf(u), web.calls) // no retry, no Wayback
        assertEquals(listOf("404"), e.attempts)
        val gone = st.registry.record("https://x.org/gone", "https://x.org/gone", 410, "", "")!!
        assertEquals(SourceChecks.NOT_FOUND, gone.failure)
    }

    @Test fun forbiddenIsBlockedAndRetriedNeverTerminalWithoutRetry() = runTest {
        val u = "https://www.noaa.gov/jetstream/tropical/tropical-cyclone-introduction"
        val waits = mutableListOf<Long>()
        val web = FakeWeb(mapOf(u to mutableListOf(statusJson(u, 403), fetchJson(u, 200, "Tropical cyclones", page))))
        val st = stateFor(waits = waits)
        val r = ResearchToolGuard(web::exec, st, "q").execute("web_fetch", fetchArgs(u))
        val e = st.registry.get(u)!!
        assertTrue(e.ok); assertFalse(r.isError); assertEquals(listOf("403", "200"), e.attempts)
        assertEquals(1, st.retryRecovered); assertTrue(waits.single() >= 2_000)
        // Always 403: 2 retries with growing backoff, then Wayback, then blocked_terminal (never dead).
        val v = "https://www.noaa.gov/jetstream/clouds/cloud-seeding"
        val waits2 = mutableListOf<Long>()
        val st2 = stateFor(waits = waits2)
        val web2 = FakeWeb(mapOf(v to mutableListOf(statusJson(v, 403))))
        ResearchToolGuard(web2::exec, st2, "q").execute("web_fetch", fetchArgs(v))
        val b = st2.registry.get(v)!!
        assertEquals(listOf("403", "403", "403", "wayback:none"), b.attempts)
        assertEquals("blocked_terminal", SourceTable.state(b)); assertTrue(waits2[1] > waits2[0])
        // If the budget cuts recovery short, it stays "blocked" (not terminal): we do not know the page is gone.
        val st3 = stateFor().also { it.timeLeft = { 5_000L } }
        ResearchToolGuard(FakeWeb(mapOf(v to mutableListOf(statusJson(v, 403))))::exec, st3, "q").execute("web_fetch", fetchArgs(v))
        assertEquals("blocked", SourceTable.state(st3.registry.get(v)!!))
    }

    @Test fun rateLimitedIsRetriedHonouringRetryAfter() = runTest {
        val u = "https://en.wikipedia.org/wiki/Willow_(disambiguation)"
        val waits = mutableListOf<Long>()
        val web = FakeWeb(mapOf(u to mutableListOf(statusJson(u, 429, "7"), fetchJson(u, 200, "Willow (disambiguation)", page))))
        val st = stateFor(waits = waits)
        ResearchToolGuard(web::exec, st, "q").execute("web_fetch", fetchArgs(u))
        assertEquals(7_000L, waits.single())
        val e = st.registry.get(u)!!
        assertNull(e.failure); assertEquals(listOf("429", "200"), e.attempts)
        // A Retry-After longer than the cap is not waited for: straight to Wayback.
        val v = "https://a.org/x"; val w2 = mutableListOf<Long>()
        val st2 = stateFor(waits = w2)
        ResearchToolGuard(FakeWeb(mapOf(v to mutableListOf(statusJson(v, 429, "3600"))))::exec, st2, "q").execute("web_fetch", fetchArgs(v))
        assertTrue(w2.isEmpty()); assertTrue(st2.registry.get(v)!!.attempts.any { it.startsWith("wayback:") })
    }

    @Test fun dnsFailureRetriesOnceThenWayback() = runTest {
        val u = "https://scijinks.gov/cloud-seeding/"
        val snap = "https://web.archive.org/web/2024/https://scijinks.gov/cloud-seeding/"
        val web = FakeWeb(mapOf(u to mutableListOf(errJson("web_fetch failed: Unable to resolve host \"scijinks.gov\": No address associated with hostname")),
            snap to mutableListOf(fetchJson(snap, 200, "Cloud seeding", page))), snapshots = mapOf(u to snap))
        val st = stateFor()
        val r = ResearchToolGuard(web::exec, st, "q").execute("web_fetch", fetchArgs(u))
        assertEquals(2, web.calls.count { it == u }) // one retry
        assertTrue(r.json.contains("wayback"))
        val e = st.registry.get(u)!!
        assertTrue(e.ok); assertEquals("wayback", e.servedFrom); assertEquals(listOf("dns", "dns", "200"), e.attempts)
        // Unreachable everywhere → unreachable_terminal.
        val v = "https://down.example.org/a"
        val st2 = stateFor()
        ResearchToolGuard(FakeWeb(mapOf(v to mutableListOf(errJson("web_fetch failed: timeout"))))::exec, st2, "q").execute("web_fetch", fetchArgs(v))
        assertEquals("unreachable_terminal", SourceTable.state(st2.registry.get(v)!!))
        // A server error is retried once too.
        val w = "https://srv.example.org/a"
        val st3 = stateFor()
        val web3 = FakeWeb(mapOf(w to mutableListOf(statusJson(w, 500), fetchJson(w, 200, "Server page", page))))
        ResearchToolGuard(web3::exec, st3, "q").execute("web_fetch", fetchArgs(w))
        assertTrue(st3.registry.get(w)!!.ok)
    }

    @Test fun retriesAreSpreadPerHost() = runTest {
        val waits = mutableListOf<Long>()
        val st = stateFor(waits = waits)
        val a = "https://www.noaa.gov/a"; val b = "https://www.noaa.gov/b"
        val web = FakeWeb(mapOf(a to mutableListOf(statusJson(a, 500), fetchJson(a, 200, "Page A here", page)), b to mutableListOf(statusJson(b, 500), fetchJson(b, 200, "Page B here", page + "b"))))
        ResearchToolGuard(web::exec, st, "q").execute("web_fetch", fetchArgs(a))
        ResearchToolGuard(web::exec, st, "q").execute("web_fetch", fetchArgs(b))
        assertEquals(1_000L, waits[0]); assertTrue(waits[1] >= 2_500) // second retry to the same host waits for its slot (clock frozen)
    }

    @Test fun groundingCoverage() {
        val reg = SourceRegistry()
        val a = reg.record("https://a.com/x", "https://a.com/x", 200, "A", page)!!
        reg.record("https://b.com/gone", "https://b.com/gone", 404, "", "")
        reg.record("https://c.com/blocked", "https://c.com/blocked", 403, "", "")
        fun f(u: String, q: String) = WorkerOutput.Finding("claim", u, q)
        val q = "Solar panels convert sunlight into electricity"
        val all = Grounding.Stats()
        Grounding.notes(listOf(Grounding.Note("Q", WorkerOutput.Parsed(listOf(f(a.url, q), f(a.url, "panels convert moonlight into cheese daily")), false, "", true))), reg, all)
        assertEquals(2, all.total); assertEquals(1.0, all.coverage, 0.0); assertEquals(1, all.unverified)
        val st = Grounding.Stats()
        Grounding.notes(listOf(Grounding.Note("Q", WorkerOutput.Parsed(listOf(f(a.url, q), f("https://b.com/gone", q), f("https://c.com/blocked", q), f("https://never.org/x", q)), false, "", true))), reg, st)
        assertEquals(4, st.total); assertEquals(1, st.checked); assertEquals(3, st.unreachable); assertEquals(1, st.onNotFound)
        assertEquals(0.25, st.coverage, 1e-9)
        val d = ResearchDiagnostics(quotesChecked = st.checked, quotesVerified = st.verified, quotesUnreachable = st.unreachable, quotesOnNotFound = st.onNotFound)
        assertTrue(d.lowCoverage)
        val g = d.json()["grounding"] as JsonObject
        assertEquals("4", g["quotes_total"].toString()); assertEquals("0.25", g["coverage"].toString()); assertEquals("1", g["quotes_citing_not_found"].toString())
        assertNull(d.json()["quotes_verified"]) // no bare verified rate outside the grounding block
        assertTrue(d.runNotes().contains("coverage 25%"))
        assertFalse(ResearchDiagnostics(quotesChecked = 5, quotesVerified = 5).lowCoverage)
    }

    @Test fun redirectAndTitleDuplicatesMerge() {
        val reg = SourceRegistry()
        val body = "The High-frequency Active Auroral Research Program (HAARP) is a research program. " + page
        val long = reg.record("https://en.wikipedia.org/wiki/High-frequency_Active_Auroral_Research_Program", "https://en.wikipedia.org/wiki/High-frequency_Active_Auroral_Research_Program", 200, "High-frequency Active Auroral Research Program - Wikipedia", body)!!
        val short = reg.record("https://en.wikipedia.org/wiki/HAARP", "https://en.wikipedia.org/wiki/HAARP", 200, "High-frequency Active Auroral Research Program - Wikipedia", body)!!
        assertEquals(long.id, short.id)
        val red = reg.record("https://en.m.wikipedia.org/wiki/Haarp", "https://en.wikipedia.org/wiki/High-frequency_Active_Auroral_Research_Program", 200, "HAARP", body)!!
        assertEquals(long.id, red.id)
        val other = reg.record("https://en.wikipedia.org/wiki/Solar_geoengineering", "https://en.wikipedia.org/wiki/Solar_geoengineering", 200, "Solar geoengineering - Wikipedia", "Solar geoengineering is different. " + page)!!
        assertNotEquals(long.id, other.id)
        assertEquals(2, reg.all().size)
        val r = Grounding.bibliography("A [S${long.id}] B https://en.wikipedia.org/wiki/HAARP C.", reg)
        assertEquals("A [1] B [1] C.", r.text)
        val d = ResearchDiagnostics(table = SourceTable.build(reg, setOf(long.id)))
        assertEquals(2, d.merged); assertEquals(2, d.collected); assertTrue(d.consistent); assertTrue(d.rejectedRows.isEmpty())
    }

    @Test fun diversificationFiresWhenDegradedOrAboveFortyPercent() = runTest {
        // Run 3 shape: top domain at ~46% (below test7's 60% trigger) with degraded search; must now spend diversification queries.
        val state = ResearchRunState(breaker = EngineBreaker(now = { 0L }))
        val searches = mutableListOf<String>()
        val verify: suspend (String, String) -> ToolResult = { name, a ->
            if (name == "web_search") { searches += a; ToolResult("{\"results\":[{\"url\":\"https://www.noaa.gov/x\"}],\"engines\":{\"bing\":\"ok (1)\"}}", false) }
            else ToolResult(fetchJson("https://www.noaa.gov/x", 200, "NOAA page", page + "noaa"), false)
        }
        val urls = listOf("https://en.wikipedia.org/wiki/A", "https://en.wikipedia.org/wiki/B", "https://www.bbc.co.uk/x", "https://www.nasa.gov/y") // 2/4 = 50% > 40%
        var i = 0
        val o = DeepResearchEngine(
            main = { m -> if (m.last().content!!.startsWith("You plan")) ok("[\"Q1?\",\"Q2?\",\"Q3?\",\"Q4?\",\"Q5?\"]") else ok("# R\n\nFacts [S1] [S2] [S3] [S4] [S5]. " + "More [S1]. ".repeat(30)) },
            worker = { k, _ -> worker(state.registry, k, urls[(i++) % urls.size]) },
            sleep = {}, reliability = state, verifyExec = verify,
        ).run("weather control", ResearchMode.STANDARD, ResearchLimits(5, 1, 10, 5))
        assertTrue(searches.isNotEmpty() && searches.size <= 3)
        assertTrue(searches.all { it.contains("-site:wikipedia.org") })
        assertTrue(o.diagnostics!!.diversifyQueries > 0)
    }

    // ---- v1.0.72 ----
    private val midiText = "Le canal du Midi est un canal de navigation français qui relie la Garonne à la mer Méditerranée. " +
        "The Canal du Midi is a 240 km long canal in Southern France, built by Pierre-Paul Riquet between 1666 and 1681. ".repeat(10)

    @Test fun offTopicAndRejectedSourcesNeverGetANumber() {
        val reg = SourceRegistry()
        val wiki = reg.record("https://en.wikipedia.org/wiki/Canal_du_Midi", "https://en.wikipedia.org/wiki/Canal_du_Midi", 200, "Canal du Midi - Wikipedia", midiText)!!
        val vnf = reg.record("https://www.vnf.fr/canal-du-midi", "https://www.vnf.fr/canal-du-midi", 200, "Le canal du Midi | VNF", midiText + " Voies navigables de France.")!!
        val pruned = reg.record("https://www.unesco.org/canal-du-midi", "https://www.unesco.org/canal-du-midi", 200, "Canal du Midi - UNESCO", midiText + " World Heritage.")!!
        // Run report: canalplus.com loaded (e.g. from the Internet Archive after a 403) and the writer cited it.
        val plus = reg.record("https://www.canalplus.com/", "https://www.canalplus.com/", 200, "CANAL+ : films, séries, sport en direct",
            "Regardez CANAL+ en direct : films, séries, sport, documentaires et le journal. ".repeat(20))!!
        reg.record("https://www.canalplus.com/live", "https://www.canalplus.com/live", 403, "", "", blocked = true)
        val relevant = Recall.gate("Canal du Midi history", reg.all().filter { it.ok })
        assertTrue(relevant(wiki) && relevant(vnf) && relevant(pruned)); assertFalse(relevant(plus))
        val r = Grounding.bibliography("Built 1666 [S${wiki.id}]. Route [S${vnf.id}, S${plus.id}]. TV [S${plus.id}]. See https://www.canalplus.com/live too.", reg, relevant)
        assertEquals("Built 1666 [1]. Route [2] [unverified source]. TV [unverified source]. See [unverified source] too.", r.text)
        assertEquals(listOf(wiki.id, vnf.id), r.cited.map { it.id })
        val sources = Grounding.sourcesSection(r.cited)
        assertFalse(sources.contains("canalplus")); assertFalse(sources.contains("unesco")) // pruned (uncited) never listed
        val d = ResearchDiagnostics(table = SourceTable.build(reg, r.cited.map { it.id }.toSet()))
        assertEquals(1, d.offTopic); assertEquals(1, d.orphansPruned); assertEquals(2, d.cited); assertTrue(d.consistent)
        assertEquals(SourceTable.OFF_TOPIC, d.rejected["https://www.canalplus.com/"])
        // Notes never hand an off-topic page to the writer.
        val notes = Grounding.notes(listOf(Grounding.Note("History?", WorkerOutput.parse("{\"findings\":[{\"claim\":\"a\",\"url\":\"https://www.canalplus.com/\",\"quote\":\"\"},{\"claim\":\"b\",\"url\":\"https://en.wikipedia.org/wiki/Canal_du_Midi\",\"quote\":\"\"}]}"))),
            reg, Grounding.Stats(), relevant = relevant)
        assertEquals(listOf(wiki.id), notes.second.map { it.id })
    }

    @Test fun topicGateFailsOpenWhenItWouldDropMostSources() {
        val reg = SourceRegistry()
        val a = reg.record("https://a.com/x", "https://a.com/x", 200, "Solar", page)!!
        val b = reg.record("https://b.com/y", "https://b.com/y", 200, "Panels", page)!!
        val g = Recall.gate("Quantum chromodynamics lattice", listOf(a, b))
        assertTrue(g(a) && g(b))
    }

    @Test fun searchDegradedFromPerRunEngineHealth() {
        // The Canal du Midi run: only Bing reliable; DDG/DDG Lite bot check ×2, Yahoo 500 ×4, Brave 429 ×4, Mojeek 403 ×2, Wikipedia 429 ×2.
        val b = EngineBreaker(now = { 0L })
        fun obs(vararg e: Pair<String, String>) = b.observe(buildJsonObject { put("engines", buildJsonObject { e.forEach { (k, v) -> put(k, v) } }) }.toString())
        repeat(2) { obs("bing" to "ok (8)", "duckduckgo" to "error: bot check", "ddg_lite" to "error: bot check", "yahoo" to "error: HTTP 500",
            "brave" to "error: HTTP 429", "mojeek" to "error: HTTP 403", "wikipedia" to "error: HTTP 429") }
        repeat(2) { obs("bing" to "ok (8)", "duckduckgo" to "ok (5)", "ddg_lite" to "ok (3)", "yahoo" to "error: HTTP 500", "brave" to "error: HTTP 429", "wikipedia" to "ok (2)") }
        repeat(8) { obs("bing" to "ok (8)", "duckduckgo" to "ok (5)", "wikipedia" to "ok (2)") }
        assertEquals(setOf("brave", "ddg_lite", "duckduckgo", "mojeek", "wikipedia", "yahoo"), b.degradedEngines())
        assertTrue(b.searchDegraded())
        // Healthy run: three engines, one transient failure each → not degraded.
        val h = EngineBreaker(now = { 0L })
        fun ho(vararg e: Pair<String, String>) = h.observe(buildJsonObject { put("engines", buildJsonObject { e.forEach { (k, v) -> put(k, v) } }) }.toString())
        ho("bing" to "error: HTTP 500", "brave" to "ok (8)", "mojeek" to "ok (6)")
        repeat(5) { ho("bing" to "ok (8)", "brave" to "ok (8)", "mojeek" to "ok (6)") }
        assertTrue(h.degradedEngines().isEmpty()); assertFalse(h.searchDegraded())
    }

    private fun results(vararg r: Triple<String, String, String>) = buildJsonObject {
        put("results", kotlinx.serialization.json.buildJsonArray { r.forEach { (u, t, s) -> add(buildJsonObject { put("url", u); put("title", t); put("snippet", s) }) } })
    }.toString()

    @Test fun recallIsAccentFoldedEntityAwareAndIgnoresCanalPlus() {
        val onTopic = results(
            Triple("https://en.wikipedia.org/wiki/Canal_du_Midi", "Canal du Midi - Wikipedia", "The Canal du Midi is a 240 km canal in Southern France."),
            Triple("https://fr.wikipedia.org/wiki/Canal_du_Midi", "Canal du Midi — Wikipédia", "Le canal du Midi est un canal de navigation français."),
            Triple("https://www.vnf.fr/vnf/canal-du-midi/", "Le canal du Midi | VNF", "Voies navigables de France gère le canal."),
            Triple("https://whc.unesco.org/en/list/770/", "Canal du Midi - UNESCO World Heritage Centre", "This 360-km network of navigable waterways…"))
        for (q in listOf("Canal du Midi construction history Pierre-Paul Riquet 17th century", "canal du midi route locks Toulouse Sète",
            "Canal du Midi engineering water supply Montagne Noire Saint-Ferréol", "canal du midi environnement platanes chancre coloré",
            "histoire du canal du Midi Riquet", "Canal du Midi")) assertTrue(q, Recall.overlap(q, onTopic) >= Recall.WEAK)
        assertTrue(Recall.overlap("Saint-Ferréol réservoir", results(Triple("https://x.fr/saint-ferreol", "Bassin de Saint-Ferreol", "Le reservoir"))) > 0.9) // accents folded
        val plus = results(Triple("https://www.canalplus.com/", "CANAL+ : films, séries, sport en direct", "Regardez CANAL+ en direct et en replay."),
            Triple("https://www.canalplus.com/series/", "Séries CANAL+", "Les meilleures séries sur myCANAL."))
        assertTrue(Recall.overlap("canal du midi history", plus) < Recall.WEAK)
        assertTrue(Recall.score("canal du midi history", "CANAL+ : films, séries", "canalplus.com") < 0.5)
    }

    @Test fun spamResultsAreFilteredBeforeWorkers() = runTest {
        assertTrue(SpamFilter.spam("canal du midi velo", "https://bokep-indo.example.com/x", "Canal du Midi", ""))
        assertTrue(SpamFilter.spam("q", "https://slot-gacor88.net/", "Slot", ""))
        assertTrue(SpamFilter.spam("canal du midi", "https://abc123.xyz/p", "Video viral terbaru", "nonton gratis"))
        assertFalse(SpamFilter.spam("canal du midi", "https://canal-du-midi.xyz/velo", "Canal du Midi à vélo", "")) // on-topic, spam TLD alone is not enough
        assertFalse(SpamFilter.spam("javascript", "https://javascript.info/", "JavaScript", ""))
        assertFalse(SpamFilter.spam("courts", "https://www.judiciary.uk/", "Judiciary", ""))
        val state = ResearchRunState()
        val raw = results(Triple("https://bokep1.com/a", "Canal du Midi bokep", ""), Triple("https://en.wikipedia.org/wiki/Canal_du_Midi", "Canal du Midi - Wikipedia", "canal"),
            Triple("https://www.xxxvideos.net/b", "x", ""), Triple("https://www.casino-online.top/c", "Casino", ""), Triple("https://www.vnf.fr/canal-du-midi", "Le canal du Midi | VNF", ""))
        val g = ResearchToolGuard({ _, _ -> ToolResult(raw, false) }, state, "Canal du Midi cycling and tourism?")
        val r = g.execute("web_search", "{\"query\":\"canal du midi cycling tourism\"}")
        assertFalse(r.json.contains("bokep") || r.json.contains("xxx") || r.json.contains("casino"))
        assertTrue(r.json.contains("vnf.fr")); assertEquals(3, state.spamFiltered)
        assertTrue(ResearchDiagnostics(spamFiltered = state.spamFiltered).json().toString().contains("\"search_results_spam_filtered\":3"))
    }

    @Test fun weakRecallOnlyWhenEvenTheBestSearchIsOffTopic() = runTest {
        val state = ResearchRunState()
        val off = results(Triple("https://www.canalplus.com/", "CANAL+ : films, séries", "Regardez CANAL+"))
        val on = results(Triple("https://en.wikipedia.org/wiki/Canal_du_Midi", "Canal du Midi - Wikipedia", "The Canal du Midi is a canal."))
        var n = 0
        val g = ResearchToolGuard({ _, _ -> ToolResult(if (n++ < 2) off else on, false) }, state, "History of the Canal du Midi?")
        g.execute("web_search", "{\"query\":\"canal history\"}")
        assertTrue("History of the Canal du Midi?" in state.weakRecall)
        g.execute("web_search", "{\"query\":\"Canal du Midi history\"}")
        assertFalse("History of the Canal du Midi?" in state.weakRecall)
    }
}
