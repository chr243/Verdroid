package com.verdroid.app.agent.tools

import com.verdroid.app.data.termux.TermuxRunner
import kotlinx.serialization.json.*

/**
 * Runs a bash command inside Termux through Termux's RUN_COMMAND service (background, as the Termux user, with the
 * packages installed from Settings > Tools). No bridge or browser involved.
 */
class TermuxRunTool(private val termux: TermuxRunner) : AgentTool {
    override val name = "termux_run"
    override val description = "Run a bash command inside Termux (cwd Documents/Verdroid/Internal; commands that run git start in Termux home " +
        "~/verdroid-work instead, because Termux git crashes with any cwd or -C under /storage/emulated. For repos in " +
        "Documents/Verdroid/Repos prefer the app's git_* tools, or run git --git-dir=/storage/emulated/0/Documents/Verdroid/Repos/<name>/.git " +
        "--work-tree=/storage/emulated/0/Documents/Verdroid/Repos/<name> from there) to use Termux packages such as ffmpeg, " +
        "magick (imagemagick), yt-dlp, git, node, jq, curl, pandoc — whichever the user installed in Settings > Tools. " +
        "Returns exit_code, stdout, stderr as soon as the command finishes (timeout_seconds is only a cap, max 600 s; " +
        "background jobs left running are stopped when the command ends). Installing packages (pip/apt/pkg/npm install, " +
        "upgrade) needs the user's OK: such a command first returns needs_install_confirmation; ask the user, and only " +
        "after they agree re-run it with confirm_install=true and the install_id."
    override val parameters = schema(listOf("command"),
        "command" to prop("string", "bash -lc command"),
        "timeout_seconds" to prop("integer", "Max seconds before the command is killed (default 120, max 600); the tool returns as soon as it finishes"),
        InstallConsent.CONFIRM_PROP, InstallConsent.ID_PROP)

    override suspend fun execute(args: JsonObject): String {
        val command = args.str("command")?.takeIf { it.isNotBlank() } ?: return errorJson("command is required")
        val timeout = (args.int("timeout_seconds") ?: 120).coerceIn(1, MAX_TIMEOUT_S)
        if (!termux.isInstalled()) return errorJson("Termux is not installed (F-Droid build). $SETUP")
        if (!termux.hasRunCommandPermission()) return errorJson("Verdroid doesn't have the 'Run commands in Termux' permission. $SETUP")
        gate(name, "the command `" + command.lineSequence().first().take(120) +
            (if (command.length > 120 || command.contains('\n')) "…" else "") + "` installs them", InstallConsent.detectShell(command), args)?.let { return it }
        val r = termux.runAndWait(script(command, timeout), "run-${System.nanoTime()}", (timeout + 15) * 1_000L, label = "Verdroid: termux_run")
            ?: return errorJson("Termux did not answer within ${timeout + 15} s. Check that allow-external-apps = true is set in Termux. $SETUP")
        if (r.err != null && r.err != RESULT_OK && r.exitCode == null) return errorJson("Termux could not run the command: ${r.errmsg ?: "error ${r.err}"}")
        val code = r.exitCode ?: -1
        return buildJsonObject {
            put("exit_code", code)
            if (code == 124) put("timed_out", true)
            put("stdout", r.stdout.takeLast(30_000)); put("stderr", r.stderr.takeLast(10_000))
        }.toString()
    }

    companion object {
        const val MAX_TIMEOUT_S = 600

        /** Consent gate for agent-written commands/scripts: null = run it; otherwise the pending/denied JSON. */
        internal suspend fun gate(tool: String, reason: String, packages: List<PackageSpec>, args: JsonObject): String? =
            when (InstallConsent.resolve(args, tool, packages, tool, reason)) {
                InstallConsent.Decision.Allow -> null
                InstallConsent.Decision.Deny -> InstallConsent.denied(tool, packages).toString()
                InstallConsent.Decision.Ask -> InstallConsent.pending(tool, tool, packages, reason, InstallConsent.retryNote(args)).toString()
            }
        /** Termux plugin API: err == Activity.RESULT_OK (-1) means the command ran. */
        private const val RESULT_OK = -1
        private const val SETUP = "Set it up in Settings > Tools > Termux."

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

        internal fun script(command: String, timeoutS: Int): String =
            workdirFor(command) + capped("bash -lc " + shellQuote(command), timeoutS)

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
