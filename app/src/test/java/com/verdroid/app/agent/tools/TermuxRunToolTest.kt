package com.verdroid.app.agent.tools

import com.verdroid.app.data.termux.TermuxResult
import com.verdroid.app.data.termux.TermuxRunner
import com.verdroid.app.data.tools.TermuxPackageJobs
import com.verdroid.app.data.tools.TermuxPackages
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TermuxRunToolTest {
    private class FakeTermux(
        var installed: Boolean = true,
        var permission: Boolean = true,
        var answer: (String) -> TermuxResult? = { null },
    ) : TermuxRunner {
        val commands = mutableListOf<Pair<String, Long>>()
        override fun isInstalled() = installed
        override fun hasRunCommandPermission() = permission
        override suspend fun runAndWait(command: String, tag: String, timeoutMs: Long, label: String): TermuxResult? {
            commands += command to timeoutMs
            return answer(tag)
        }
    }

    private fun args(cmd: String, timeout: Int? = null) = buildJsonObject {
        put("command", cmd); if (timeout != null) put("timeout_seconds", timeout)
    }

    @Test fun `runs the command in verdroid-work under a timeout and returns its output`() = runTest {
        val t = FakeTermux(answer = { tag -> TermuxResult(tag, "hi\n", "warn", 0, -1, null) })
        val o = Json.parseToJsonElement(TermuxRunTool(t).execute(args("echo 'hi'", 30))).jsonObject
        assertEquals(0, o["exit_code"]!!.jsonPrimitive.int)
        assertEquals("hi\n", o["stdout"]!!.jsonPrimitive.content)
        assertEquals("warn", o["stderr"]!!.jsonPrimitive.content)
        val (cmd, timeoutMs) = t.commands.single()
        assertTrue(cmd, cmd.contains("timeout -k 5 30 bash -lc 'echo '\\''hi'\\''' </dev/null"))
        assertTrue(cmd.startsWith(TermuxRunTool.WORKDIR))
        assertEquals((30 + 60 + 30) * 1_000L, timeoutMs)
    }

    @Test fun `clear errors for missing Termux, permission, no answer and timeouts`() = runTest {
        val t = FakeTermux(installed = false)
        assertTrue(TermuxRunTool(t).execute(args("ls")).contains("not installed"))
        t.installed = true; t.permission = false
        assertTrue(TermuxRunTool(t).execute(args("ls")).contains("permission"))
        t.permission = true
        assertTrue(TermuxRunTool(t).execute(args("ls")).contains("allow-external-apps"))
        assertTrue(TermuxRunTool(t).execute(args("")).contains("command is required"))
        t.answer = { tag -> TermuxResult(tag, "", "", 124, -1, null) }
        assertTrue(TermuxRunTool(t).execute(args("sleep 999", 9999)).contains("\"timed_out\":true"))
        assertEquals((1800 + 60 + 30) * 1_000L, t.commands.last().second) // capped at 1800 s + grace
        t.answer = { tag -> TermuxResult(tag, "", "", null, 2, "Bad path") }
        assertTrue(TermuxRunTool(t).execute(args("ls")).contains("Bad path"))
    }

    @Test fun `package install job reports success and failure`() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val t = FakeTermux(answer = { tag ->
            if (tag.startsWith("install-jq")) TermuxResult(tag, "Setting up jq\nINSTALLED=1\nRESULT=0\n", "", 0, -1, null)
            else TermuxResult(tag, "E: Unable to locate package\nINSTALLED=0\nRESULT=100\n", "", 0, -1, null)
        })
        val jobs = TermuxPackageJobs(t, scope)
        val jq = TermuxPackages.ALL.first { it.pkg == "jq" }
        val pandoc = TermuxPackages.ALL.first { it.pkg == "pandoc" }
        assertTrue(jobs.install(jq)); assertFalse(jobs.install(jq)) // single-flight per package
        jobs.install(pandoc)
        assertTrue(jobs.jobs.value["jq"]!!.running)
        scope.advanceUntilIdle()
        assertTrue(jobs.jobs.value["jq"]!!.ok)
        val p = jobs.jobs.value["pandoc"]!!
        assertFalse(p.ok || p.running); assertTrue(p.detail!!.contains("Unable to locate package"))
        assertTrue(t.commands.first().first.contains("install jq"))
    }
    /** Like Termux: read stdout/stderr to EOF, then take the exit code. HOME → temp so ~/verdroid-work is harmless. */
    private fun runLikeTermux(script: String): Triple<String, String, Int> {
        val home = java.nio.file.Files.createTempDirectory("tx").toFile()
        val pb = ProcessBuilder("bash", "-c", script)
        pb.environment()["HOME"] = home.path
        val p = pb.start()
        val err = StringBuilder()
        val t = Thread { err.append(p.errorStream.bufferedReader().readText()) }.apply { start() }
        val out = p.inputStream.bufferedReader().readText(); t.join()
        val rc = p.waitFor(); home.deleteRecursively()
        return Triple(out, err.toString(), rc)
    }

    /** v1.0.23 bug: a background job (or a daemon from the login profile) kept the pipes open → full-timeout waits. */
    @Test fun `returns as soon as the command ends even if it leaves a background job holding the pipes`() {
        val t0 = System.nanoTime()
        val (out, err, rc) = runLikeTermux(TermuxRunTool.script("sleep 30 & echo hi; echo oops >&2", 20))
        val secs = (System.nanoTime() - t0) / 1e9
        assertTrue("took $secs s", secs < 8)
        assertEquals("hi\n", out); assertEquals("oops\n", err); assertEquals(0, rc)
        assertEquals(3, runLikeTermux(TermuxRunTool.script("exit 3", 20)).third)
    }

    @Test fun `timeout is still a hard cap with exit 124`() {
        val t0 = System.nanoTime()
        assertEquals(124, runLikeTermux(TermuxRunTool.script("sleep 30", 1)).third)
        assertTrue((System.nanoTime() - t0) / 1e9 < 10)
    }

    @Test fun `git commands start in Termux home, never under storage emulated`() = runTest {
        val t = FakeTermux(answer = { tag -> TermuxResult(tag, "", "", 0, -1, null) })
        TermuxRunTool(t).execute(args("git status"))
        TermuxRunTool(t).execute(args("cd repo && git -C . log"))
        TermuxRunTool(t).execute(args("ls; ffmpeg -version"))
        val (g1, g2, plain) = t.commands.map { it.first }
        for (g in listOf(g1, g2)) {
            assertTrue(g.startsWith(TermuxRunTool.GIT_WORKDIR))
            assertFalse(g.substringBefore("timeout").contains("/storage/emulated"))
        }
        assertTrue(plain.startsWith(TermuxRunTool.WORKDIR))
        assertTrue(TermuxRunTool.usesGit("subprocess.run([\"git\", \"clone\", u])"))
        assertFalse(TermuxRunTool.usesGit("echo digit; cat .gitignore; legit x"))
        assertTrue(TermuxRunTool(t).description.contains("/storage/emulated"))
        assertTrue(TermuxPythonTool.command("a.py", "import subprocess; subprocess.run(['git','status'])".toByteArray(), emptyList(), 30)
            .contains(TermuxRunTool.GIT_WORKDIR))
    }

    @Test fun `idle watchdog stops a silent command and keeps the output so far`() {
        val t0 = System.nanoTime()
        val (out, err, rc) = runLikeTermux(TermuxRunTool.script("echo started; sleep 60", 60, idleS = 2))
        assertTrue((System.nanoTime() - t0) / 1e9 < 15)
        assertEquals(124, rc); assertEquals("started\n", out); assertTrue(err, err.contains(TermuxRunTool.IDLE_MARK))
        val o = Json.parseToJsonElement(TermuxRunTool.result(rc, out, err, 60, 300)).jsonObject
        val e = o["error"]!!.jsonPrimitive.content
        assertTrue(e, e.startsWith("Timed out after 5 min with no output") && e.contains("Last lines: started"))
        assertFalse(o["stderr"]!!.jsonPrimitive.content.contains("__VERDROID"))
    }

    @Test fun `a command that keeps printing is not idle-killed`() {
        val (out, _, rc) = runLikeTermux(TermuxRunTool.script("for i in 1 2 3 4 5; do echo \$i; sleep 1; done", 60, idleS = 3))
        assertEquals(0, rc); assertEquals("1\n2\n3\n4\n5\n", out)
    }

    @Test fun `live files are written while running and a stop file kills the command`() {
        val dir = java.nio.file.Files.createTempDirectory("live").toFile()
        val base = java.io.File(dir, "run-1").path
        var rc = -2
        val th = Thread { rc = runLikeTermux(TermuxRunTool.script("echo one; echo two >&2; sleep 60", 120, 300, base)).third }.apply { start() }
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && !java.io.File("$base.err").let { it.exists() && it.length() > 0 }) Thread.sleep(100)
        val live = LiveOutput.read(java.io.File("$base.out"), java.io.File("$base.err"), System.currentTimeMillis() - 3_000, 300)
        assertEquals(listOf("one", "! two"), live.lines); assertTrue(live.elapsedMs >= 3_000)
        java.io.File("$base.stop").writeText("stop")
        th.join(15_000)
        assertEquals(124, rc)
        assertTrue(dir.listFiles()!!.isEmpty()) // wrapper cleans its own files
        dir.deleteRecursively()
    }

    @Test fun `overall cap error, live tail helpers and durations`() {
        val e = Json.parseToJsonElement(TermuxRunTool.result(124, "a\nb\n", "", 90, 300)).jsonObject["error"]!!.jsonPrimitive.content
        assertTrue(e, e.startsWith("Timed out after 1 min 30 s (timeout_seconds cap)") && e.endsWith("a\nb"))
        assertNull(Json.parseToJsonElement(TermuxRunTool.result(0, "ok", "", 90, 300)).jsonObject["error"])
        assertEquals(listOf("50%", "done"), LiveOutput.lastLines("10%\r50%\n\ndone\n", 5))
        assertEquals("42 s", LiveOutput.duration(42)); assertEquals("5 min", LiveOutput.duration(300))
        assertTrue(LiveOutput.read(java.io.File("/nonexistent/a"), java.io.File("/nonexistent/b"), 0, 300, now = 1_000).lines.isEmpty())
    }
}
