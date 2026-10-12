package com.verdroid.app.agent.tools

import com.verdroid.app.data.selftest.SelfTestCoverage
import com.verdroid.app.data.selftest.SelfTestRunner
import kotlinx.serialization.json.*

/**
 * `self_test`: runs the on-device self-test (same checks as Settings → Self-test) and returns the Markdown report.
 * The runner is resolved lazily (it depends on the tool registry). Exempt from coverage to avoid recursion.
 */
class SelfTestTool(private val runner: () -> SelfTestRunner) : AgentTool {
    override val name = NAME
    override val description = "Run Verdroid's on-device self-test (safe, non-destructive feature checks: storage, network, " +
        "Termux, Python/PDF, Shizuku/rish, accessibility, Git, updater…) and return a pass/fail/skip report. " +
        "Use when a tool misbehaves or the user asks what works on this phone. Optional `only`: comma-separated check names."
    override val parameters = schema(emptyList(),
        "only" to prop("string", "Comma-separated check names to run (default all): " + SelfTestCoverage.CHECKS.joinToString(", ")))

    override suspend fun execute(args: JsonObject): String = try {
        val only = args["only"]?.jsonPrimitive?.contentOrNull?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val unknown = only.orEmpty() - SelfTestCoverage.CHECKS.toSet()
        if (unknown.isNotEmpty()) errorJson("unknown checks: $unknown") else {
            val r = runner()
            val results = r.runAll(only = only)
            val md = r.report(results)
            buildJsonObject {
                put("report", md)
                r.save(md)?.let { put("saved_to", it.absolutePath) }
            }.toString()
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        errorJson(e.message ?: "self_test failed")
    }

    companion object { const val NAME = "self_test" }
}
