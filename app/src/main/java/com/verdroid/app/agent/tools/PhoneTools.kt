package com.verdroid.app.agent.tools

import android.accessibilityservice.AccessibilityService
import com.verdroid.app.data.a11y.A11yNode
import com.verdroid.app.data.a11y.VerdroidAccessibilityService
import com.verdroid.app.data.git.GitManager
import com.verdroid.app.shizuku.ShellExecutor
import kotlinx.serialization.json.*

// ---------------------------------------------------------------- Shizuku shell

class RunShellTool(private val shell: ShellExecutor, private val sandbox: WorkspaceSandbox) : AgentTool {
    override val name = "run_shell"
    override val description = "Run a shell command on the phone as the adb shell user via Shizuku (no Termux packages here — use termux_run for those). Returns backend, exit_code, stdout and stderr."
    override val parameters = schema(listOf("command"),
        "command" to prop("string", "Shell command (sh -c)"),
        "workdir" to prop("string", "Working directory relative to the agent workspace (default: workspace root)"),
        "timeout_seconds" to prop("integer", "Timeout in seconds (default 60, max 600)"))

    override suspend fun execute(args: JsonObject): String {
        val command = args.str("command")?.takeIf { it.isNotBlank() } ?: return errorJson("command is required")
        val timeout = (args.int("timeout_seconds") ?: 60).coerceIn(1, 600) * 1000L
        // The shell uid can't read the app's private dir, so the workdir is only applied when it is accessible.
        val dir = args.str("workdir")?.let { sandbox.resolve(it).absolutePath }
        return try {
            val r = shell.exec(command, dir, timeout)
            buildJsonObject {
                put("backend", r.backend); put("exit_code", r.exitCode)
                put("stdout", r.stdout.takeLast(MAX_OUT)); put("stderr", r.stderr.takeLast(MAX_OUT))
                if (r.timedOut) put("timed_out", true)
            }.toString()
        } catch (e: IllegalStateException) { errorJson(e.message ?: "no shell backend available") }
    }

    private companion object { const val MAX_OUT = 30_000 }
}

// ---------------------------------------------------------------- JGit

private fun JsonObjectBuilder.putSet(key: String, set: Set<String>) { put(key, JsonArray(set.sorted().take(300).map { JsonPrimitive(it) })) }

private const val REPO_PATH = "Repo: a name from git_repos (→ Documents/Verdroid/Repos/<name>), a path in Documents/Verdroid, or legacy:<name>. " +
    "Omit when there's exactly one repo in Repos/."
private fun repoProp() = "repo_path" to prop("string", REPO_PATH)
private const val REPO_NOTE = " Works on repos in Documents/Verdroid/Repos (the folder workspace_read/workspace_write use)."

/** Runs a git action; [GitAccessException]s (clear codes, no token) and other failures become JSON errors. */
private suspend fun gitCall(block: suspend () -> String): String = try { block() }
    catch (e: com.verdroid.app.data.git.GitAccessException) { e.json.toString() }
    catch (e: IllegalStateException) { errorJson(e.message ?: "git failed") }
    catch (e: IllegalArgumentException) { errorJson(e.message ?: "git failed") }

private fun JsonObjectBuilder.repo(r: com.verdroid.app.data.git.RepoRef) {
    put("repo_path", r.path); put("absolute_path", r.dir.path); put("root", r.root); if (r.legacy) put("legacy", true)
}

class GitReposTool(private val git: GitManager) : AgentTool {
    override val name = "git_repos"
    override val description = "List the git repos: Documents/Verdroid/Repos/<name> (where git_clone puts them) with remote URL (token removed), " +
        "branch and last commit; plus legacy repos from the old private workspace (marked legacy). Check here before cloning again."
    override val parameters = schema(emptyList())
    override suspend fun execute(args: JsonObject): String = gitCall {
        val list = git.describeRepos()
        buildJsonObject { put("repos_dir", "${com.verdroid.app.data.storage.SharedFolder.DISPLAY_PATH}/Repos"); put("count", list.size); put("repos", list) }.toString()
    }
}

class GitStatusTool(private val git: GitManager) : AgentTool {
    override val name = "git_status"
    override val description = "Show git status of a repository (prints its absolute path)." + REPO_NOTE
    override val parameters = schema(emptyList(), repoProp())
    override suspend fun execute(args: JsonObject): String = gitCall {
        val s = git.status(args.str("repo_path"))
        buildJsonObject {
            put("repo_path", s.path); put("absolute_path", s.absolutePath); put("root", s.root)
            put("branch", s.branch); put("clean", s.clean)
            putSet("added", s.added); putSet("changed", s.changed); putSet("modified", s.modified)
            putSet("removed", s.removed); putSet("missing", s.missing); putSet("untracked", s.untracked); putSet("conflicting", s.conflicting)
        }.toString()
    }
}

class GitCommitTool(private val git: GitManager) : AgentTool {
    override val name = "git_commit"
    override val description = "Stage all changes (including deletions) and commit." + REPO_NOTE +
        " With nothing to commit it fails with NOTHING_TO_COMMIT (no empty commits unless allow_empty=true)."
    override val parameters = schema(listOf("message"), repoProp(),
        "message" to prop("string", "Commit message"),
        "allow_empty" to prop("boolean", "Allow a commit with no changes (default false)"))
    override suspend fun execute(args: JsonObject): String = gitCall {
        val msg = args.str("message")?.takeIf { it.isNotBlank() } ?: return@gitCall errorJson("message is required")
        val r = git.commit(args.str("repo_path"), msg, args.bool("allow_empty") == true)
        buildJsonObject { put("ok", true); put("commit", r.id); put("files", r.files); repo(r.ref) }.toString()
    }
}

class GitCloneTool(private val git: GitManager) : AgentTool {
    override val name = "git_clone"
    override val description = "Clone an https:// git repository into Documents/Verdroid/Repos/<repo-name> (uses the Git token from settings for " +
        "private repos). Already cloned there → reused and pulled; that folder holding something else → <name>-2. Run git_repos first to " +
        "find existing clones. Failures carry code AUTH_FAILED (401), PERMISSION_DENIED (403: token lacks Contents permission), " +
        "REPO_NOT_FOUND (404) with http_status, the server's remote text and a hint."
    override val parameters = schema(listOf("url"),
        "url" to prop("string", "https:// clone URL"),
        "path" to prop("string", "Target folder: a name (→ Repos/<name>) or a path in Documents/Verdroid (default Repos/<repo-name>)"),
        "branch" to prop("string", "Branch to check out"))
    override suspend fun execute(args: JsonObject): String = gitCall {
        val url = args.str("url") ?: return@gitCall errorJson("url is required")
        val r = git.clone(url, args.str("path"), args.str("branch"), null)
        buildJsonObject {
            put("ok", true); put("path", r.ref.path); repo(r.ref); r.branch?.let { put("branch", it) }
            put("reused", r.reused); r.note?.let { put("note", it) }
        }.toString()
    }
}

class GitPushTool(private val git: GitManager) : AgentTool {
    override val name = "git_push"
    override val description = "Push a repository to its remote using the Git token from encrypted settings." + REPO_NOTE +
        " Failures carry code AUTH_FAILED / PERMISSION_DENIED (needs Contents: Read and write) / REPO_NOT_FOUND with http_status and the server's text."
    override val parameters = schema(emptyList(), repoProp(),
        "remote" to prop("string", "Remote name (default origin)"),
        "branch" to prop("string", "Branch/refspec to push (default: current branch)"))
    override suspend fun execute(args: JsonObject): String = gitCall {
        val updates = git.push(args.str("repo_path"), args.str("remote"), args.str("branch"))
        buildJsonObject { put("ok", updates.none { "REJECTED" in it }); put("updates", JsonArray(updates.map { JsonPrimitive(it) })) }.toString()
    }
}

class GitPullTool(private val git: GitManager) : AgentTool {
    override val name = "git_pull"
    override val description = "Fast-forward a repository from its remote (never overwrites local changes; diverged history → PULL_FAILED)." + REPO_NOTE
    override val parameters = schema(emptyList(), repoProp())
    override suspend fun execute(args: JsonObject): String = gitCall {
        val (r, status) = git.pull(args.str("repo_path"))
        buildJsonObject { put("ok", true); put("result", status); repo(r) }.toString()
    }
}

class GitLogTool(private val git: GitManager) : AgentTool {
    override val name = "git_log"
    override val description = "Recent commits of a repository (id, author, time, message)." + REPO_NOTE
    override val parameters = schema(emptyList(), repoProp(), "max" to prop("integer", "Number of commits (default 20, max 200)"))
    override suspend fun execute(args: JsonObject): String = gitCall {
        val (r, list) = git.log(args.str("repo_path"), args.int("max") ?: 20)
        buildJsonObject {
            repo(r)
            putJsonArray("commits") { list.forEach { c -> addJsonObject { put("id", c.id); put("author", c.author); put("time", java.time.Instant.ofEpochMilli(c.time).toString()); put("message", c.message) } } }
        }.toString()
    }
}

class GitDiffTool(private val git: GitManager) : AgentTool {
    override val name = "git_diff"
    override val description = "Uncommitted changes of a repository vs HEAD as a unified diff." + REPO_NOTE
    override val parameters = schema(emptyList(), repoProp(), "max_chars" to prop("integer", "Cap on diff size (default 20000, max 200000)"))
    override suspend fun execute(args: JsonObject): String = gitCall {
        val (r, text, cut) = git.diff(args.str("repo_path"), (args.int("max_chars") ?: 20_000).coerceIn(100, 200_000))
        buildJsonObject { repo(r); put("truncated", cut); put("diff", text) }.toString()
    }
}

class GitBranchTool(private val git: GitManager) : AgentTool {
    override val name = "git_branch"
    override val description = "List a repository's branches (local and remote-tracking) and the current one." + REPO_NOTE
    override val parameters = schema(emptyList(), repoProp())
    override suspend fun execute(args: JsonObject): String = gitCall {
        val (r, current, all) = git.branches(args.str("repo_path"))
        buildJsonObject { repo(r); current?.let { put("current", it) }; put("branches", JsonArray(all.map { JsonPrimitive(it) })) }.toString()
    }
}

// ---------------------------------------------------------------- Accessibility

private const val A11Y_OFF = "Accessibility service is off (enable 'Verdroid agent control' in Settings > Shizuku & accessibility setup). " +
    "It is only needed to control OTHER Android apps on the phone screen."
private const val PHONE = "Phone screen (accessibility): controls other Android apps on the phone's display. "

private fun A11yNode.toJson(): JsonObject = buildJsonObject {
    cls?.let { put("class", it.substringAfterLast('.')) }
    text?.takeIf { it.isNotBlank() }?.let { put("text", it.take(300)) }
    desc?.takeIf { it.isNotBlank() }?.let { put("desc", it.take(200)) }
    viewId?.let { put("id", it) }
    if (clickable) put("clickable", true)
    if (editable) put("editable", true)
    put("bounds", JsonArray(bounds.map { JsonPrimitive(it) }))
    if (children.isNotEmpty()) put("children", JsonArray(children.map { it.toJson() }))
}

class ScreenReadTool : AgentTool {
    override val name = "screen_read"
    override val description = PHONE + "Read the current screen's UI tree (class, text, description, view id, bounds [l,t,r,b], clickable/editable)."
    override val parameters = schema(emptyList(), "max_depth" to prop("integer", "Max tree depth (default 25)"))
    override suspend fun execute(args: JsonObject): String {
        val svc = VerdroidAccessibilityService.instance ?: return errorJson(A11Y_OFF)
        val tree = svc.screenTree((args.int("max_depth") ?: 25).coerceIn(1, 60)) ?: return errorJson("No active window")
        val s = tree.toJson().toString()
        if (s.contains("com.verdroid.app")) return buildJsonObject { put("note", "This is Verdroid's own UI."); put("tree", s.take(60_000)) }.toString()
        return if (s.length > 60_000) buildJsonObject { put("truncated", true); put("tree", s.take(60_000)) }.toString() else s
    }
}

class ScreenTapTool : AgentTool {
    override val name = "screen_tap"
    override val description = PHONE + "Tap the screen at pixel coordinates (use the centre of a node's bounds from screen_read)."
    override val parameters = schema(listOf("x", "y"), "x" to prop("number", "X in pixels"), "y" to prop("number", "Y in pixels"))
    override suspend fun execute(args: JsonObject): String {
        val svc = VerdroidAccessibilityService.instance ?: return errorJson(A11Y_OFF)
        val x = args.num("x") ?: return errorJson("x is required")
        val y = args.num("y") ?: return errorJson("y is required")
        return buildJsonObject { put("ok", svc.tap(x.toFloat(), y.toFloat())) }.toString()
    }
}

class ScreenSwipeTool : AgentTool {
    override val name = "screen_swipe"
    override val description = PHONE + "Swipe from (x1,y1) to (x2,y2) in pixels."
    override val parameters = schema(listOf("x1", "y1", "x2", "y2"),
        "x1" to prop("number", "Start X"), "y1" to prop("number", "Start Y"),
        "x2" to prop("number", "End X"), "y2" to prop("number", "End Y"),
        "duration_ms" to prop("integer", "Duration (default 300)"))
    override suspend fun execute(args: JsonObject): String {
        val svc = VerdroidAccessibilityService.instance ?: return errorJson(A11Y_OFF)
        val c = listOf("x1", "y1", "x2", "y2").map { args.num(it) ?: return errorJson("$it is required") }
        val ok = svc.swipe(c[0].toFloat(), c[1].toFloat(), c[2].toFloat(), c[3].toFloat(), (args.int("duration_ms") ?: 300).toLong())
        return buildJsonObject { put("ok", ok) }.toString()
    }
}

class ScreenTypeTool : AgentTool {
    override val name = "screen_type"
    override val description = PHONE + "Set the text of the focused/first editable field on screen."
    override val parameters = schema(listOf("text"), "text" to prop("string", "Text to enter"))
    override suspend fun execute(args: JsonObject): String {
        val svc = VerdroidAccessibilityService.instance ?: return errorJson(A11Y_OFF)
        val text = args.str("text") ?: return errorJson("text is required")
        return buildJsonObject { put("ok", svc.setText(text)) }.toString()
    }
}

class ScreenGlobalActionTool : AgentTool {
    override val name = "screen_action"
    override val description = PHONE + "Perform a global action: back, home, recents, notifications, quick_settings, lock_screen, screenshot."
    override val parameters = schema(listOf("action"), "action" to prop("string", "back | home | recents | notifications | quick_settings | lock_screen | screenshot"))
    override suspend fun execute(args: JsonObject): String {
        val svc = VerdroidAccessibilityService.instance ?: return errorJson(A11Y_OFF)
        val action = when (args.str("action")) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "screenshot" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return errorJson("unknown action")
        }
        return buildJsonObject { put("ok", svc.performGlobalAction(action)) }.toString()
    }
}

internal fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
