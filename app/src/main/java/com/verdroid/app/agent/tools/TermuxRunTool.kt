package com.verdroid.app.agent.tools

import com.verdroid.app.data.termux.TermuxRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File

/**
 * Runs a bash command inside Termux through Termux's RUN_COMMAND service (background, as the Termux user, with the
 * packages installed from Settings > Tools). No bridge or browser involved.
 */
class TermuxRunTool(
    private val termux: TermuxRunner,
    /** Writable folder that Termux can also see (Documents/Verdroid/Internal/.live) for live output; null = no streaming. */
    private val liveDir: () -> File? = { null },
) : AgentTool {
    override val name = "termux_run"
    override val description = "Run a bash command inside Termux (cwd Documents/Verdroid/Internal; commands that run git start in Termux home " +
        "~/verdroid-work instead, because Termux git crashes with any cwd or -C under /storage/emulated. For repos in " +
        "Documents/Verdroid/Repos prefer the app's git_* tools, or run git --git-dir=/storage/emulated/0/Documents/Verdroid/Repos/<name>/.git " +
        "--work-tree=/storage/emulated/0/Documents/Verdroid/Repos/<name> from there) to use Termux packages such as ffmpeg, " +
        "magick (imagemagick), yt-dlp, git, node, jq, curl, pandoc — whichever the user installed in Settings > Tools. " +
        "Returns exit_code, stdout, stderr as soon as the command finishes (timeout_seconds is only a cap, default 300, max 1800 s; " +
        "a command that prints nothing for idle_timeout_seconds (default 300) is stopped and returns an error with its last lines; " +
        "for long builds (gradle, cargo, make) pass a large timeout_seconds and prefer verbose/progress flags so output keeps flowing; " +
        "background jobs left running are stopped when the command ends). Installing packages (pip/apt/pkg/npm install, " +
        "upgrade) needs the user's OK: such a command first returns needs_install_confirmation; ask the user, and only " +
        "after they agree re-run it with confirm_install=true and the install_id."
    override val parameters = schema(listOf("command"),
        "command" to prop("string", "bash -lc command"),
        "timeout_seconds" to prop("integer", "Max seconds before the command is killed (default 300, max 1800); the tool returns as soon as it finishes"),
        "idle_timeout_seconds" to prop("integer", "Stop the command after this many seconds without any new output (default 300, min 30, max 1800)"),
        InstallConsent.CONFIRM_PROP, InstallConsent.ID_PROP)

    override suspend fun execute(args: JsonObject): String = execute(args, ToolContext(0L))

    override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
        val command = args.str("command")?.takeIf { it.isNotBlank() } ?: return errorJson("command is required")
        val timeout = (args.int("timeout_seconds") ?: DEFAULT_TIMEOUT_S).coerceIn(1, MAX_RUN_TIMEOUT_S)
        val idle = (args.int("idle_timeout_seconds") ?: DEFAULT_IDLE_S).coerceIn(MIN_IDLE_S, MAX_RUN_TIMEOUT_S)
        if (!termux.isInstalled()) return errorJson("Termux is not installed (F-Droid build). $SETUP")
        if (!termux.hasRunCommandPermission()) return errorJson("Verdroid doesn't have the 'Run commands in Termux' permission. $SETUP")
        gate(name, "the command `" + command.lineSequence().first().take(120) +
            (if (command.length > 120 || command.contains('\n')) "…" else "") + "` installs them", InstallConsent.detectShell(command), args)?.let { return it }
        val id = "run-${System.nanoTime()}"
        val dir = runCatching { liveDir()?.takeIf { (it.isDirectory || it.mkdirs()) && it.canWrite() } }.getOrNull()
        val base = dir?.let { File(it, id).absolutePath }
        val started = System.currentTimeMillis()
        val waitS = timeout + idle.coerceAtMost(60) + 30
        if (dir != null) runCatching { // stale leftovers (e.g. a .stop written after the command had already ended)
            dir.listFiles { f -> f.name.startsWith("run-") && System.currentTimeMillis() - f.lastModified() > 6 * 3_600_000L }?.forEach { it.delete() }
        }
        val r = coroutineScope {
            val tail = launch(Dispatchers.IO) { // no live dir: still shows the elapsed time
                val out = File(base?.let { "$it.out" } ?: "/nonexistent/out"); val err = File(base?.let { "$it.err" } ?: "/nonexistent/err")
                while (isActive) {
                    TermuxLive.set(ctx.taskId, LiveOutput.read(out, err, started, idle))
                    delay(LiveOutput.REFRESH_MS)
                }
            }
            try {
                termux.runAndWait(script(command, timeout, idle, base), id, waitS * 1_000L, label = "Verdroid: termux_run")
            } catch (e: CancellationException) {
                // Stop generation: tell the wrapper to kill the command's process group (it polls for this file every second).
                if (base != null) withContext(NonCancellable) { runCatching { File("$base.stop").writeText("stop") } }
                throw e
            } finally {
                tail.cancel(); TermuxLive.set(ctx.taskId, null)
            }
        } ?: return errorJson("Termux did not answer within $waitS s. Check that allow-external-apps = true is set in Termux. $SETUP")
        if (r.err != null && r.err != RESULT_OK && r.exitCode == null) return errorJson("Termux could not run the command: ${r.errmsg ?: "error ${r.err}"}")
        return result(r.exitCode ?: -1, r.stdout, r.stderr, timeout, idle, System.currentTimeMillis() - started)
    }

    companion object {
        const val MAX_TIMEOUT_S = 600
        /** termux_run only: long builds are fine now that a silent command is stopped after [DEFAULT_IDLE_S]. */
        const val MAX_RUN_TIMEOUT_S = 1800
        const val DEFAULT_TIMEOUT_S = 300
        const val DEFAULT_IDLE_S = 300
        const val MIN_IDLE_S = 30
        internal const val IDLE_MARK = "__VERDROID_IDLE__"
        internal const val STOP_MARK = "__VERDROID_STOPPED__"

        /** Final JSON for the model (same keys/limits as before; idle/overall timeouts add a clear `error` with the last lines). */
        internal fun result(code: Int, stdoutRaw: String, stderrRaw: String, timeoutS: Int, idleS: Int, durationMs: Long = -1): String {
            val idled = stderrRaw.contains(IDLE_MARK)
            val stderr = stderrRaw.lineSequence().filterNot { it.startsWith(IDLE_MARK) || it.startsWith(STOP_MARK) }.joinToString("\n")
            return buildJsonObject {
                put("exit_code", code)
                if (durationMs >= 0) put("duration_ms", durationMs)
                if (code == 124) {
                    put("timed_out", true)
                    val last = LiveOutput.lastLines(stdoutRaw + (if (stderr.isNotBlank()) "\n" + stderr else ""), 15).joinToString("\n")
                    put("error", (if (idled) "Timed out after ${LiveOutput.duration(idleS)} with no output"
                        else "Timed out after ${LiveOutput.duration(timeoutS)} (timeout_seconds cap)") +
                        "; the command was stopped. Last lines: " + last.ifBlank { "(none)" })
                }
                put("stdout", stdoutRaw.takeLast(30_000)); put("stderr", stderr.takeLast(10_000))
            }.toString()
        }

        /** Consent gate for agent-written commands/scripts: null = run it; otherwise the pending/denied JSON. */
        internal suspend fun gate(tool: String, reason: String, packages: List<PackageSpec>, args: JsonObject): String? =
            when (InstallConsent.resolve(args, tool, packages, tool, reason)) {
                InstallConsent.Decision.Allow -> null
                InstallConsent.Decision.Deny -> InstallConsent.denied(tool, packages).toString()
                InstallConsent.Decision.Ask -> InstallConsent.pending(tool, tool, packages, reason, InstallConsent.retryNote(args)).toString()
            }
        /** Termux plugin API: err == Activity.RESULT_OK (-1) means the command ran. */
        private const val RESULT_OK = -1
        private const val SETUP = "Set it up in Settings > Tools > Terminal."

        /**
         * cd into ~/verdroid-work and run [command] under coreutils `timeout` (exit 124 on timeout); the timeout is only a cap.
         *
         * Termux returns the result only when the stdout/stderr pipes reach EOF, not when bash exits. Anything the command
         * (or the login profile of `bash -l`, e.g. sshd/ssh-agent/crond/pulseaudio) leaves running in the background kept
         * those pipes open, so every call waited the FULL timeout until `timeout` killed the group (v1.0.23 bug). Now the
         * command writes to temp files with stdin from /dev/null, the wrapper waits only for the command itself, cleans up
         * leftovers in its process group (timeout's pid == pgid), then prints the captured output and exits at once.
         */
        /** cwd: Documents/Verdroid/Internal (scripts/temp visible but out of the way); Termux home if storage isn't set up. */
        const val WORKDIR = "{ mkdir -p /storage/emulated/0/Documents/Verdroid/Internal 2>/dev/null && " +
            "cd /storage/emulated/0/Documents/Verdroid/Internal; } || { mkdir -p ~/verdroid-work && cd ~/verdroid-work; } || exit 1\n"

        /**
         * Termux git dies whenever its cwd is under /storage/emulated ("fatal: failed to stat '   /storage/emulated'"; also with
         * git -C <sdcard dir>), so commands that run git start in Termux's own home instead ($HOME/verdroid-work).
         */
        const val GIT_WORKDIR = "mkdir -p ~/verdroid-work && cd ~/verdroid-work || exit 1\n"
        private val USES_GIT = Regex("""(^|[\s;&|(`'"/])git([\s'"]|$)""")
        fun usesGit(text: String): Boolean = USES_GIT.containsMatchIn(text)
        fun workdirFor(text: String): String = if (usesGit(text)) GIT_WORKDIR else WORKDIR

        internal fun script(command: String, timeoutS: Int, idleS: Int = DEFAULT_IDLE_S, liveBase: String? = null): String =
            workdirFor(command) + cappedLive("bash -lc " + shellQuote(command), timeoutS, idleS, liveBase)

        /**
         * Like [capped], plus live output and an idle watchdog: stdout/stderr go to [liveBase].out/.err (a folder the app
         * can read, so it tails them into the tool card; falls back to private mktemp files when that isn't writable), and a
         * 1 s loop stops the command's process group when nothing new was written for [idleS] s or when the app creates
         * [liveBase].stop (Stop generation). Either way the output so far is replayed and the exit code is 124.
         */
        internal fun cappedLive(cmdline: String, timeoutS: Int, idleS: Int, liveBase: String?, grace: Int = 5): String {
            val b = liveBase?.let { shellQuote(it) }
            val pick = if (b == null) "fo=${'$'}(mktemp) && fe=${'$'}(mktemp) || exit 1; sf=\n"
                else "if : >$b.out 2>/dev/null && : >$b.err 2>/dev/null; then fo=$b.out; fe=$b.err; sf=$b.stop; rm -f \"${'$'}sf\"\n" +
                    "else fo=${'$'}(mktemp) && fe=${'$'}(mktemp) || exit 1; sf=; fi\n"
            return pick + """
timeout -k $grace $timeoutS $cmdline </dev/null >"${'$'}fo" 2>"${'$'}fe" &
pid=${'$'}!
last=-1; quiet=0; why=
while kill -0 "${'$'}pid" 2>/dev/null; do
  sleep 1
  n=${'$'}(( ${'$'}(wc -c <"${'$'}fo" 2>/dev/null || echo 0) + ${'$'}(wc -c <"${'$'}fe" 2>/dev/null || echo 0) ))
  if [ "${'$'}n" != "${'$'}last" ]; then last=${'$'}n; quiet=0; else quiet=${'$'}((quiet+1)); fi
  if [ -n "${'$'}sf" ] && [ -e "${'$'}sf" ]; then why=STOPPED; break; fi
  if [ "${'$'}quiet" -ge $idleS ]; then why=IDLE; break; fi
done
if [ -n "${'$'}why" ]; then kill -TERM -- "-${'$'}pid" 2>/dev/null; kill -TERM "${'$'}pid" 2>/dev/null; sleep 2; kill -KILL -- "-${'$'}pid" 2>/dev/null; kill -KILL "${'$'}pid" 2>/dev/null; fi
wait "${'$'}pid"; rc=${'$'}?
kill -TERM -- "-${'$'}pid" 2>/dev/null
cat "${'$'}fo"; cat "${'$'}fe" >&2
if [ -n "${'$'}why" ]; then echo "__VERDROID_${'$'}{why}__" >&2; rc=124; fi
rm -f "${'$'}fo" "${'$'}fe" ${'$'}{sf:+"${'$'}sf"}
exit "${'$'}rc"
""".trim()
        }

        /**
         * Runs the shell fragment [cmdline] under `timeout -k [grace] [timeoutS]` so that the Termux result comes back as soon
         * as it exits: output goes to temp files (stdin /dev/null), leftovers in its process group are stopped, then the
         * captured stdout/stderr are replayed and the exit code (124 on timeout) is returned. Must be the script's last step.
         */
        internal fun capped(cmdline: String, timeoutS: Int, grace: Int = 5): String = """
fo=${'$'}(mktemp) && fe=${'$'}(mktemp) || exit 1
timeout -k $grace $timeoutS $cmdline </dev/null >"${'$'}fo" 2>"${'$'}fe" &
pid=${'$'}!
wait "${'$'}pid"; rc=${'$'}?
kill -TERM -- "-${'$'}pid" 2>/dev/null
cat "${'$'}fo"; cat "${'$'}fe" >&2; rm -f "${'$'}fo" "${'$'}fe"
exit "${'$'}rc"
""".trim()

        internal fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }
}
