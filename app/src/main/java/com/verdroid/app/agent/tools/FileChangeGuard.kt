package com.verdroid.app.agent.tools

import kotlinx.serialization.json.*
import java.io.File

/**
 * Conservative detection of file DELETE / MOVE / RENAME and of OVERWRITES of existing files outside
 * Documents/Verdroid/Internal/ in agent tool calls (pure, unit-tested). "When in doubt, ask": false positives only cost
 * one extra Yes/No. Agent-owned places need no prompt: Internal/, the private scratch workspace and relative paths in
 * shells whose cwd is agent-owned (termux_run → Internal/, run_shell/rish → private workspace) unless the command `cd`s.
 */
object DestructiveDetector {
    const val INTERNAL = "/Documents/Verdroid/Internal/"

    private val SHELL_DELETE = Regex("""(^|[\s;&|(`'"$])(rm|rmdir|unlink|shred|srm|wipe|mv|rename|truncate)(\s|$)""")
    private val FIND_DELETE = Regex("""\bfind\b[^;&|]*\s(-delete\b|-exec(dir)?\s+(rm|mv|unlink|shred)\b)""")
    private val GIT_DESTRUCTIVE = Regex("""\bgit\s+(?:-C\s+\S+\s+)?(rm|mv|clean|checkout\s+--|restore|reset\s+--hard)\b""")
    private val RSYNC_DELETE = Regex("""\brsync\b[^;&|]*--(delete|remove-source-files)""")
    private val SED_INPLACE = Regex("""\bsed\s+(-[a-zA-Z]*i|--in-place)""")
    private val REDIRECT = Regex("""(?<![>&0-9])(?:[12])?>(?!>|&)\s*("[^"]+"|'[^']+'|[^\s;&|)]+)""")
    private val CP_LIKE = Regex("""(^|[\s;&|(])(cp|install|ln)\s+([^;&|]+)""")
    private val DD_OF = Regex("""\bdd\b[^;&|]*\bof=("[^"]+"|'[^']+'|\S+)""")
    private val TEE = Regex("""\btee\s+((?:-[^a\s]\S*\s+)*)("[^"]+"|'[^']+'|[^\s;&|-]\S*)""")

    private val PY_DELETE = Regex("""\b(os\.(remove|unlink|rmdir|removedirs|rename|renames|replace)|shutil\.(rmtree|move)|send2trash)\s*\(|\.(unlink|rmdir|rename)\s*\(""")
    private val PY_SHELL_RM = Regex("""["'](rm|mv|rmdir|unlink|shred)(\s[^"']*)?["']""")
    private val PY_OPEN_W = Regex("""\bopen\s*\(\s*([^,)]+?)\s*,\s*(?:mode\s*=\s*)?["']([rwabx+t]*)["']""")
    private val PY_WRITE_PATH = Regex("""Path\s*\(\s*([^)]+?)\s*\)\s*\.write_(text|bytes)\s*\(""")
    private val PY_COPY = Regex("""\bshutil\.(copy|copy2|copyfile|copytree)\s*\(\s*[^,]+,\s*([^,)]+)""")

    /** Reasons this shell command deletes/moves/renames or may overwrite user files. [cwdOwned]: relative paths land in an agent-owned dir. */
    fun shell(cmd: String, cwdOwned: Boolean = true): List<String> {
        val out = mutableListOf<String>()
        val c = cmd.replace(Regex("""(?m)^\s*#.*$"""), " ")
        val relativeIsOwned = cwdOwned && !Regex("""(^|[\s;&|(])(cd|pushd)\s""").containsMatchIn(c)
        SHELL_DELETE.findAll(c).forEach { out += "${it.groupValues[2]} (delete/move/rename)" }
        if (FIND_DELETE.containsMatchIn(c)) out += "find -delete/-exec rm"
        GIT_DESTRUCTIVE.find(c)?.let { out += "git ${it.groupValues[1].trim()}" }
        if (RSYNC_DELETE.containsMatchIn(c)) out += "rsync --delete"
        if (SED_INPLACE.containsMatchIn(c)) out += "sed -i (edits a file in place)"
        REDIRECT.findAll(c).forEach { m -> val t = unquote(m.groupValues[1]); if (!ownedTarget(t, relativeIsOwned)) out += "> $t (may overwrite)" }
        DD_OF.findAll(c).forEach { m -> val t = unquote(m.groupValues[1]); if (!ownedTarget(t, relativeIsOwned)) out += "dd of=$t" }
        TEE.findAll(c).forEach { m -> val t = unquote(m.groupValues[2]); if (!ownedTarget(t, relativeIsOwned)) out += "tee $t (may overwrite)" }
        CP_LIKE.findAll(c).forEach { m ->
            val dest = m.groupValues[3].trim().split(Regex("\\s+")).lastOrNull { !it.startsWith("-") }?.let(::unquote) ?: return@forEach
            if (!ownedTarget(dest, relativeIsOwned)) out += "${m.groupValues[2]} → $dest (may overwrite)"
        }
        return out.distinct()
    }

    /** Reasons this Python source deletes/moves/renames or may overwrite files outside Internal/. Relative paths: [cwdOwned]. */
    fun python(code: String, cwdOwned: Boolean = true): List<String> {
        val out = mutableListOf<String>()
        val c = code.replace(Regex("""(?m)^\s*#.*$"""), " ")
        PY_DELETE.findAll(c).forEach { out += it.value.trimEnd('(', ' ') }
        PY_SHELL_RM.findAll(c).forEach { out += "shell ${it.groupValues[1]}" }
        if (Regex("""\bos\.chdir\s*\(""").containsMatchIn(c)) { /* relative paths no longer known */ }
        val relOwned = cwdOwned && !Regex("""\bos\.chdir\s*\(""").containsMatchIn(c)
        PY_OPEN_W.findAll(c).forEach { m -> if ('w' in m.groupValues[2]) pyTarget(m.groupValues[1], relOwned)?.let { out += "open($it, 'w') (may overwrite)" } }
        PY_WRITE_PATH.findAll(c).forEach { m -> pyTarget(m.groupValues[1], relOwned)?.let { out += "Path($it).write_${m.groupValues[2]} (may overwrite)" } }
        PY_COPY.findAll(c).forEach { m -> pyTarget(m.groupValues[2], relOwned)?.let { out += "shutil.${m.groupValues[1]} → $it (may overwrite)" } }
        return out.distinct()
    }

    /** null = clearly agent-owned (no prompt); else a printable target. */
    private fun pyTarget(expr: String, relOwned: Boolean): String? {
        val e = expr.trim()
        val lit = Regex("""^[rbfu]*["']([^"']*)["']$""").find(e)?.groupValues?.get(1)
        if (lit != null && '{' !in lit) return if (ownedTarget(lit, relOwned)) null else lit
        if ("VERDROID_INTERNAL" in e) return null
        return e.take(80) // variable / f-string: can't tell → ask
    }

    fun ownedTarget(path: String, relativeIsOwned: Boolean): Boolean {
        val p = path.trim()
        if (p.isEmpty() || p == "/dev/null" || p.startsWith("/dev/std") || p.startsWith("&")) return true
        if (INTERNAL in p || p.startsWith("\$VERDROID_INTERNAL") || p.startsWith("\${VERDROID_INTERNAL}")) return true
        if (p.startsWith("\$TMPDIR") || p.startsWith("\${TMPDIR") || p.startsWith("/tmp/") || p.contains("/usr/tmp/")) return true
        if (p.startsWith("/") || p.startsWith("~") || p.startsWith("$")) return false
        return relativeIsOwned && !p.startsWith("..")
    }

    private fun unquote(s: String) = s.trim().removeSurrounding("\"").removeSurrounding("'")

    /** A reason that only overwrites/edits content (no delete/move/rename). */
    fun isOverwriteReason(r: String): Boolean {
        val l = r.lowercase()
        if (Regex("""delete|move|rename|unlink|rmdir|rm\b|shred|git |rsync|find -|trash|remove|replace|rmtree""").containsMatchIn(l)) return false
        return "overwrite" in l || l.startsWith("append to") || l.startsWith("dd of=") || l.startsWith("sed -i")
    }

    /** Every reason is an overwrite (the "Always allow overwriting" setting may skip the prompt). */
    fun overwriteOnly(reasons: List<String>): Boolean = reasons.isNotEmpty() && reasons.all(::isOverwriteReason)
}

/**
 * "Allow overwriting files without asking" (global setting) or "this chat only". Applies to OVERWRITES only: deletes,
 * moves and renames always ask.
 */
fun interface OverwritePolicy {
    fun allowsOverwrite(taskId: Long): Boolean
    companion object { val NEVER = OverwritePolicy { false } }
}

/** One pending Yes/No question shown to the user. */
data class FileChangeRequest(val id: Long, val taskId: Long, val tool: String, val summary: String, val details: List<String>, val command: String?,
    /** Only overwrites: the dialog may offer "Always allow" / "This chat". */
    val overwriteOnly: Boolean = false)

/** Asks the user (dialog / notification). true = Allow. Implementations must default to false (deny) on timeout. */
fun interface FileChangeConfirmer {
    suspend fun confirm(req: FileChangeRequest): Boolean

    companion object {
        /** Tests / registries without UI: everything allowed (production passes the real dialog). */
        val ALLOW_ALL = FileChangeConfirmer { true }
    }
}

/**
 * Central gate run by [ToolRegistry] before every tool call: if the call would delete/move/rename files, or overwrite an
 * existing file outside Internal/, the user must tap Allow. Deny → a tool error the agent must respect.
 * [readScript] returns the Python source a call would run (termux_python path in the private workspace, strategy files);
 * [shared] resolves Documents/Verdroid paths for existence checks.
 */
class FileChangeGuard(
    private val confirmer: FileChangeConfirmer,
    private val shared: File? = null,
    private val overwritePolicy: OverwritePolicy = OverwritePolicy.NEVER,
    /** filesDir/workspace: write_file paths that name the shared tree (Repos/…) are checked like workspace_write. */
    private val privateRoot: File? = null,
    private val readScript: (tool: String, args: JsonObject) -> String? = { _, _ -> null },
) {
    /** Documents/Verdroid-relative path for [p] with the same rules as [SharedFolderSandbox] (absolute prefixes, repo shorthand). */
    private fun sharedRel(root: File, p: String): String {
        val q = p.trim().replace('\\', '/')
        if (q.startsWith("/")) SharedFolderSandbox.prefixes(root).firstOrNull { q == it || q.startsWith("$it/") }?.let { return q.removePrefix(it).trimStart('/') }
        return SharedFolderSandbox.repoShorthand(root, q)
    }

    private var nextId = 1L

    /** Reasons + the command/code to show, or null when no confirmation is needed. Pure apart from file existence. */
    fun inspect(tool: String, args: JsonObject): Pair<List<String>, String?>? {
        fun s(k: String) = (args[k] as? JsonPrimitive)?.contentOrNull
        val reasons: List<String>; var shown: String? = null
        when {
            tool == "workspace_delete" -> { reasons = listOf("delete ${s("path")}" + if ((args["recursive"] as? JsonPrimitive)?.booleanOrNull == true) " (and everything in it)" else "") }
            tool == "skill_delete" -> reasons = listOf("delete skill ${s("id")}")
            tool == "workspace_write" || (tool == "write_file" && shared != null && privateRoot != null &&
                SharedFolderSandbox.claimsShared(s("path").orEmpty(), shared, privateRoot)) -> {
                val mode = if (tool == "write_file") (if ((args["append"] as? JsonPrimitive)?.booleanOrNull == true) "append" else "overwrite") else s("mode") ?: "overwrite"
                val p = shared?.let { sharedRel(it, s("path").orEmpty()) } ?: s("path").orEmpty()
                val f = shared?.let { File(it, p) }
                reasons = if (mode != "create" && (args["directory"] as? JsonPrimitive)?.booleanOrNull != true && f?.isFile == true && !isInternal(p))
                    listOf("${if (mode == "append") "append to" else "overwrite"} existing $p") else emptyList()
            }
            tool == "termux_run" -> {
                shown = s("command")
                val all = DestructiveDetector.shell(shown.orEmpty(), cwdOwned = true)
                // rm/rmdir/unlink/mv proven to stay inside Termux's private home need no question (anything else still asks).
                reasons = if (TermuxHomeDeletes.allInTermuxHome(shown.orEmpty()))
                    all.filterNot { r -> r.endsWith(" (delete/move/rename)") && r.substringBefore(' ') in TermuxHomeDeletes.VERBS } else all
            }
            tool == "run_shell" || tool == "rish_run" -> { shown = s("command"); reasons = DestructiveDetector.shell(shown.orEmpty(), cwdOwned = tool == "run_shell" && s("workdir") == null) }
            tool == "termux_python" || tool == "crypto_backtest_script" -> {
                shown = s("code") ?: readScript(tool, args)
                reasons = shown?.let { DestructiveDetector.python(it) + DestructiveDetector.shell(pyShellStrings(it)) }.orEmpty()
            }
            tool.startsWith("mcp__") && Regex("delete|remove|move|rename|unlink|rmdir|trash", RegexOption.IGNORE_CASE).containsMatchIn(tool) ->
                reasons = listOf("MCP tool $tool may delete/move files")
            else -> {
                // Tools writing a named file into Output/ (selenium save_as, pdf/ebook output_name): ask if it already exists.
                val name = s("save_as") ?: s("output_name")
                val target = name?.let { n -> shared?.let { File(it, if (n.startsWith("Output/")) n else "Output/$n") } }
                reasons = if (target?.isFile == true) listOf("overwrite existing Output/${target.name}") else emptyList()
            }
        }
        return if (reasons.isEmpty()) null else reasons to shown
    }

    /** Scheduled (unattended) run: null = no confirmation would be needed (or overwrites are always allowed); else why it's refused. */
    fun headlessReason(taskId: Long, tool: String, args: JsonObject): String? {
        val (reasons, _) = inspect(tool, args) ?: return null
        if (DestructiveDetector.overwriteOnly(reasons) && overwritePolicy.allowsOverwrite(taskId)) return null
        return "this file change needs the user's Yes (${reasons.joinToString("; ").take(200)})"
    }

    /** null = go ahead; else the error JSON to return instead of running the tool. */
    suspend fun check(taskId: Long, tool: String, args: JsonObject): String? {
        val (reasons, shown) = inspect(tool, args) ?: return null
        val overwriteOnly = DestructiveDetector.overwriteOnly(reasons)
        if (overwriteOnly && overwritePolicy.allowsOverwrite(taskId)) return null
        val req = FileChangeRequest(synchronized(this) { nextId++ }, taskId, tool,
            if (reasons.any { "overwrite" in it || "append" in it } && reasons.none { "delete" in it || "move" in it }) "Overwrite existing file(s)" else "Delete / move / rename files",
            reasons, shown?.take(4000), overwriteOnly)
        return if (confirmer.confirm(req)) null else denied(tool, reasons)
    }

    private fun isInternal(rel: String) = rel.trimStart('/').let { it == "Internal" || it.startsWith("Internal/") }

    /** Shell commands hidden in Python strings (subprocess/os.system) for the shell detector. */
    private fun pyShellStrings(code: String) = Regex("""(?:os\.system|subprocess\.\w+|os\.popen)\s*\(\s*(?:\[)?\s*[rbf]?["']([^"']+)["']""")
        .findAll(code).joinToString("\n") { it.groupValues[1] }

    companion object {
        fun denied(tool: String, reasons: List<String>): String = buildJsonObject {
            put("error", "The user DENIED this file change (${reasons.joinToString("; ").take(300)}). Nothing was changed.")
            put("denied_by_user", true)
            put("tool", tool)
            put("instruction", "Respect the decision: do not retry it, and do not delete, move, rename or overwrite these files through any " +
                "other tool, script or command. Tell the user what you didn't do and continue without it, or ask what they want instead.")
        }.toString()
    }
}
