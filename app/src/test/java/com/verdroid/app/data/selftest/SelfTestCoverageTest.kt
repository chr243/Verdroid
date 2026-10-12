package com.verdroid.app.data.selftest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fails when an agent tool has no self-test check and no exemption (see [SelfTestCoverage]).
 * Tool names are scraped from the tool sources so new tools can't slip through.
 */
class SelfTestCoverageTest {
    private fun toolNames(): Set<String> {
        val dir = listOf("src/main/java/com/verdroid/app/agent/tools", "app/src/main/java/com/verdroid/app/agent/tools")
            .map(::File).first { it.isDirectory }
        val nameRe = Regex("""override val name(?:\s*:\s*String)?\s*=\s*"([a-z0-9_]+)"""")
        val constRe = Regex("""const val NAME\s*=\s*"([a-z0-9_]+)"""")
        return dir.walk().filter { it.extension == "kt" }.flatMap { f ->
            val t = f.readText(); (nameRe.findAll(t) + constRe.findAll(t)).map { it.groupValues[1] }
        }.toSet()
    }

    @Test fun everyToolHasSelfTestOrExemption() {
        val names = toolNames()
        assertTrue("tool scan found too few tools: $names", names.size >= 40)
        assertEquals("Tools without a self-test check or exemption — register one in SelfTestCoverage",
            emptyList<String>(), SelfTestCoverage.uncovered(names))
    }

    @Test fun mappedChecksExist() = assertEquals(emptyList<String>(), SelfTestCoverage.unknownChecks())

    @Test fun noStaleMappings() {
        val names = toolNames()
        val stale = (SelfTestCoverage.TOOL_CHECKS.keys + SelfTestCoverage.EXEMPT.keys).filter { it !in names }
        assertEquals("Self-test mappings for tools that no longer exist", emptyList<String>(), stale)
    }

    @Test fun tinyPdfLooksValid() {
        val s = String(SelfTestReport.tinyPdf(), Charsets.US_ASCII)
        assertTrue(s.startsWith("%PDF-1.4") && s.trimEnd().endsWith("%%EOF") && "startxref" in s)
    }

    @Test fun reportCounts() {
        val md = SelfTestReport.markdown(listOf(SelfTestResult("a", SelfTestStatus.PASS, "x|y", 3),
            SelfTestResult("b", SelfTestStatus.SKIP, "no", 1)), "1", "release", "dev")
        assertTrue("**1 pass · 0 fail · 1 skip**" in md && "x/y" in md)
    }
}
