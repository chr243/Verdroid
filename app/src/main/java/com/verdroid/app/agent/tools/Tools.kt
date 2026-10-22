package com.verdroid.app.agent.tools

import kotlinx.serialization.json.*

/** Per-call context handed to tools (e.g. so a tool can pause its task on session expiry). */
data class ToolContext(val taskId: Long)

interface AgentTool {
    val name: String
    val description: String
    val parameters: JsonObject
    /** Returns a JSON string result. Errors are returned as {"error": "..."} rather than thrown. */
    suspend fun execute(args: JsonObject): String

    /** Context-aware variant; defaults to [execute]. */
    suspend fun execute(args: JsonObject, ctx: ToolContext): String = execute(args)
}

data class ToolResult(val json: String, val isError: Boolean)

internal fun schema(required: List<String>, vararg props: Pair<String, JsonObject>): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", JsonObject(props.toMap()))
    put("required", JsonArray(required.map { JsonPrimitive(it) }))
}

internal fun prop(type: String, description: String): JsonObject = buildJsonObject {
    put("type", type); put("description", description)
}

internal fun errorJson(message: String): String = buildJsonObject { put("error", message) }.toString()

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

class ReadFileTool(private val sandbox: WorkspaceSandbox, private val shared: com.verdroid.app.data.storage.SharedFolder? = null) : AgentTool {
    private val roots = FileRoots(sandbox, shared)
    override val name = "read_file"
    override val description = "Read a UTF-8 text file from the agent's private workspace. " + FileRoots.NOTE
    override val parameters = schema(listOf("path"),
        "path" to prop("string", "File path relative to the private workspace root (Repos/… or a repo name → Documents/Verdroid/Repos)"),
        "max_bytes" to prop("integer", "Maximum bytes to return (default 65536)"))

    override suspend fun execute(args: JsonObject): String {
        val t = roots.resolve(args.str("path") ?: return errorJson("path is required")) ?: return errorJson(FileRoots.NO_ACCESS)
        val f = t.file
        if (!f.exists()) return errorJson("File not found: ${t.rel} (${t.rootLabel})")
        if (f.isDirectory) return errorJson("Is a directory: ${t.rel}")
        val max = (args.int("max_bytes") ?: 65_536).coerceIn(1, 1_000_000)
        val size = f.length()
        val head = readHead(f, max)
        return buildJsonObject {
            put("path", t.rel); put("root", t.rootLabel); put("absolute_path", f.path)
            put("size", size)
            put("truncated", size > head.size)
            put("content", String(head, Charsets.UTF_8))
        }.toString()
    }

    companion object {
        /** At most [max] bytes from the start of [f], streamed (never loads a whole big file: OOM on large logs). */
        fun readHead(f: java.io.File, max: Int): ByteArray = f.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream(minOf(max.toLong(), f.length().coerceAtLeast(1L)).toInt())
            val chunk = ByteArray(8192)
            while (out.size() < max) {
                val r = input.read(chunk, 0, minOf(chunk.size, max - out.size()))
                if (r < 0) break
                out.write(chunk, 0, r)
            }
            out.toByteArray()
        }
    }
}

class WriteFileTool(private val sandbox: WorkspaceSandbox, private val shared: com.verdroid.app.data.storage.SharedFolder? = null) : AgentTool {
    private val roots = FileRoots(sandbox, shared)
    override val name = "write_file"
    override val description = "Create or overwrite (or append to) a text file in the agent's private workspace (scratch; the user can't see it). " +
        "Parent folders are created. " + FileRoots.NOTE
    override val parameters = schema(listOf("path", "content"),
        "path" to prop("string", "File path relative to the private workspace root (Repos/… or a repo name → Documents/Verdroid/Repos)"),
        "content" to prop("string", "Text content to write"),
        "append" to prop("boolean", "Append instead of overwrite (default false)"))

    override suspend fun execute(args: JsonObject): String {
        val t = roots.resolve(args.str("path") ?: return errorJson("path is required")) ?: return errorJson(FileRoots.NO_ACCESS)
        val f = t.file
        if (f == sandbox.root || f.isDirectory || (t.shared && f == shared?.root?.canonicalFile)) return errorJson("Path is a directory")
        val content = args.str("content") ?: return errorJson("content is required")
        f.parentFile?.mkdirs()
        if (args.bool("append") == true) f.appendText(content) else f.writeText(content)
        return buildJsonObject {
            put("ok", true); put("path", t.rel); put("root", t.rootLabel); put("absolute_path", f.path); put("bytes", f.length())
        }.toString()
    }
}

/**
 * One rule for read_file/write_file/list_dir: paths are in the private workspace (filesDir/workspace) unless they name the
 * shared tree ([SharedFolderSandbox.claimsShared]: absolute Documents/Verdroid path, Repos/…, or "<repo>/…" for a repo in
 * Documents/Verdroid/Repos). So "Repos/x/a.txt" and "x/a.txt" are the same file for write_file, workspace_write and git_*.
 */
internal class FileRoots(private val sandbox: WorkspaceSandbox, private val shared: com.verdroid.app.data.storage.SharedFolder?) {
    class Target(val file: java.io.File, val rel: String, val shared: Boolean) {
        val rootLabel get() = if (shared) com.verdroid.app.data.storage.SharedFolder.DISPLAY_PATH else "private workspace"
    }

    /** null: the path names the shared tree but All files access is missing. */
    fun resolve(path: String): Target? {
        val sh = shared
        if (sh != null && SharedFolderSandbox.claimsShared(path, sh.root, sandbox.root)) {
            if (!sh.hasAccess()) return null
            val sb = SharedFolderSandbox(sh)
            val f = sb.resolve(path)
            return Target(f, sb.relativePath(f), true)
        }
        val f = sandbox.resolve(path)
        return Target(f, sandbox.relativePath(f), false)
    }

    companion object {
        const val NOTE = "Paths starting with Repos/ (or <repo-name>/ for a repo cloned into Documents/Verdroid/Repos) and absolute " +
            "Documents/Verdroid paths go to the shared folder instead, the same files workspace_* and git_* tools see."
        const val NO_ACCESS = "That path is in Documents/Verdroid, but All files access is not granted (Settings > Tools > Shared folder)."
    }
}

class ListDirTool(private val sandbox: WorkspaceSandbox, private val shared: com.verdroid.app.data.storage.SharedFolder? = null) : AgentTool {
    private val roots = FileRoots(sandbox, shared)
    override val name = "list_dir"
    override val description = "List files and folders in a private-workspace directory. " + FileRoots.NOTE
    override val parameters = schema(emptyList(), "path" to prop("string", "Directory relative to the workspace root (default '.')"))

    override suspend fun execute(args: JsonObject): String {
        val t = roots.resolve(args.str("path") ?: ".") ?: return errorJson(FileRoots.NO_ACCESS)
        val dir = t.file
        if (!dir.exists()) return errorJson("Directory not found: ${t.rel}")
        if (!dir.isDirectory) return errorJson("Not a directory: ${t.rel}")
        val entries = dir.listFiles().orEmpty().sortedWith(compareBy({ !it.isDirectory }, { it.name })).take(500)
        return buildJsonObject {
            put("path", t.rel); put("root", t.rootLabel); put("absolute_path", dir.path)
            put("entries", JsonArray(entries.map { e ->
                buildJsonObject {
                    put("name", e.name)
                    put("type", if (e.isDirectory) "dir" else "file")
                    if (e.isFile) put("size", e.length())
                }
            }))
        }.toString()
    }
}

/** Registered with a real schema so models learn the API, but not implemented until a later phase. */
class StubTool(override val name: String, override val description: String, override val parameters: JsonObject) : AgentTool {
    override suspend fun execute(args: JsonObject): String = NOT_AVAILABLE

    companion object {
        val NOT_AVAILABLE = errorJson("not available yet (coming in a later phase)")

        /** Phase 6: every tool is implemented now; kept so later experimental tools can be stubbed again. */
        fun all(): List<AgentTool> = emptyList()
    }
}

/**
 * All tools + the user's on/off switches (Settings > Tools). Disabled tools are left out of [schemas] / [names] (so the
 * model never sees them) and refused by [execute] if called anyway.
 */
class ToolRegistry(val tools: List<AgentTool>, private val switches: com.verdroid.app.data.tools.ToolSwitches =
    com.verdroid.app.data.tools.ToolSwitches.ALL_ON,
    /** Tools that come and go at runtime (remote MCP servers: mcp__<server>__<tool>). */
    private val dynamic: () -> List<AgentTool> = { emptyList() },
    /** Per-chat tool presets (chat ⋮ → Tool presets); all on unless the user turned a preset off for that chat. */
    private val chatFilter: com.verdroid.app.data.tools.ChatToolFilter = com.verdroid.app.data.tools.ChatToolFilter.ALL,
    /** Yes/No before deleting/moving/renaming or overwriting files (null = no gate, e.g. unit tests). */
    private val fileGuard: FileChangeGuard? = null,
    /** Non-null (the schedule's name) while [taskId] is an unattended scheduled run (its last user message is the scheduler's): risky tools are refused. */
    private val headless: suspend (taskId: Long) -> String? = { null },
    /** Live "Always allow" state of the blocked tools that have one ([com.verdroid.app.domain.schedule.ScheduleSafety.ALWAYS_ALLOWABLE]). */
    private val alwaysAllowed: (tool: String) -> Boolean = { false },
    /** Shell safety ([ShellSafety]): tool names of the results since the user's latest message (taint), the setting, the Yes/No. */
    private val toolsSinceUser: suspend (taskId: Long) -> List<String?> = { emptyList() },
    private val shellAskAlways: () -> Boolean = { false },
    private val shellConfirmer: FileChangeConfirmer? = null) {
    private var nextShellId = 1_000_000L
    private val staticByName = tools.associateBy { it.name }
    private val allTools: List<AgentTool> get() = tools + dynamic().filter { it.name !in staticByName }
    val enabledTools: List<AgentTool> get() = allTools.filter { switches.isEnabled(it.name) }
    val names: Set<String> get() = enabledTools.map { it.name }.toSet()

    /** Tools effectively on in this chat: the chat's preset override if set, else Settings → Tools. */
    fun enabledFor(taskId: Long): List<AgentTool> = allTools.filter { chatFilter.allowed(taskId, it.name, switches.isEnabled(it.name)) }
    fun namesFor(taskId: Long): Set<String> = enabledFor(taskId).map { it.name }.toSet()

    fun schemas(): JsonArray = schemasOf(enabledTools)
    fun schemas(taskId: Long): JsonArray = schemasOf(enabledFor(taskId))

    private fun schemasOf(list: List<AgentTool>): JsonArray = JsonArray(list.map { t ->
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", t.name); put("description", t.description); put("parameters", t.parameters)
            })
        }
    })

    suspend fun execute(name: String, argumentsJson: String, taskId: Long = 0L): ToolResult {
        val tool = staticByName[name] ?: dynamic().firstOrNull { it.name == name } ?: return ToolResult(errorJson("Unknown tool: $name"), true)
        val globallyOn = switches.isEnabled(name)
        if (!chatFilter.allowed(taskId, name, globallyOn)) {
            if (!globallyOn) return ToolResult(errorJson("The tool $name is turned off by the user (Settings > Tools). Do not call it; use another approach or tell the user."), true)
            val preset = com.verdroid.app.data.tools.ToolPreset.of(name)?.label ?: "its"
            return ToolResult(errorJson("The tool $name is turned off in this chat (the $preset tool preset is off, by the chat menu → Tool presets or by this chat's profile in Settings → Profiles). Do not call it or work around it; tell the user to turn the preset on if it's needed."), true)
        }
        val args = runCatching { Json.parseToJsonElement(argumentsJson.ifBlank { "{}" }) as? JsonObject }.getOrNull()
            ?: return ToolResult(errorJson("Arguments must be a JSON object"), true)
        // Stored login cookies in a script follow the same switch as cookie_open (Settings → Tools / chat preset).
        if (name == "termux_python" && TermuxPythonTool.cookieSites(args).isNotEmpty() &&
            !chatFilter.allowed(taskId, "cookie_open", switches.isEnabled("cookie_open")))
            return ToolResult(errorJson("cookies_for needs the logged-in browsing tools (cookie_*), which are turned off. Run the script without cookies_for or ask the user to turn them on."), true)
        val scheduleName = if (taskId == 0L) null else try { headless(taskId) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        val unattended = scheduleName != null
        if (unattended) {
            (com.verdroid.app.domain.schedule.ScheduleSafety.blockReason(name, args, alwaysAllowed) ?: fileGuard?.headlessReason(taskId, name, args))
                ?.let { return ToolResult(com.verdroid.app.domain.schedule.ScheduleSafety.blockedJson(name, it), true) }
        }
        val fileAsked = fileGuard?.inspect(name, args) != null
        fileGuard?.let { g ->
            try { g.check(taskId, name, args) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { FileChangeGuard.denied(name, listOf("confirmation failed: ${e.message}")) }
        }?.let { return ToolResult(it, true) }
        if (name in ShellSafety.SHELL_TOOLS) {
            val tainted = try { ShellSafety.tainted(toolsSinceUser(taskId)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { false }
            when (val d = ShellSafety.decide(name, args, tainted, shellAskAlways(), unattended)) {
                is ShellSafety.Decision.Block -> return ToolResult(com.verdroid.app.domain.schedule.ScheduleSafety.blockedJson(name, d.reason), true)
                is ShellSafety.Decision.Ask -> if (!fileAsked || tainted) {
                    val shown = ((args["command"] ?: args["code"]) as? JsonPrimitive)?.contentOrNull
                    val ok = shellConfirmer?.let { c ->
                        try { c.confirm(FileChangeRequest(synchronized(this) { nextShellId++ }, taskId, name, "Run this command?", d.reasons, shown?.take(4000), kind = FileChangeRequest.Kind.SHELL)) }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { false }
                    } ?: true
                    if (!ok) return ToolResult(ShellSafety.deniedJson(name, d.reasons), true)
                }
                ShellSafety.Decision.Allow -> {}
            }
        }
        return try {
            val out = if (unattended) kotlinx.coroutines.withContext(com.verdroid.app.domain.schedule.HeadlessRun(taskId, scheduleName.orEmpty())) { tool.execute(args, ToolContext(taskId)) }
                else tool.execute(args, ToolContext(taskId))
            ToolResult(out, out.trimStart().startsWith("{\"error\""))
        } catch (e: SecurityException) {
            ToolResult(errorJson(e.message ?: "Access denied"), true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(errorJson("${e.javaClass.simpleName}: ${e.message}"), true)
        }
    }
}
