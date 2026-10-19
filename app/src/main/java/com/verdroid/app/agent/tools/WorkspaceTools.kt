package com.verdroid.app.agent.tools

import com.verdroid.app.data.storage.SharedFolder
import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant

/**
 * Confines paths to the shared Documents/Verdroid tree. Relative paths are taken from the folder root; absolute paths are
 * accepted only when they point inside the tree (its real path, [SharedFolder.DISPLAY_PATH] or /sdcard/Documents/Verdroid).
 * Anything that resolves outside (.., symlinks, other absolute paths) is rejected.
 */
class SharedFolderSandbox(private val folder: SharedFolder) {
    val root: File get() = folder.root.canonicalFile

    @Throws(SecurityException::class)
    fun resolve(path: String?): File {
        var p = (path ?: ".").trim().replace('\\', '/').ifEmpty { "." }
        if (p.contains('\u0000')) throw SecurityException("Invalid path")
        if (p.startsWith("/")) {
            val hit = prefixes(folder.root).firstOrNull { p == it || p.startsWith("$it/") }
                ?: throw SecurityException("Path is outside Documents/Verdroid: $path")
            p = p.removePrefix(hit).trimStart('/').ifEmpty { "." }
        } else p = repoShorthand(root, p)
        val r = root
        val candidate = File(r, p).canonicalFile
        if (candidate != r && !candidate.path.startsWith(r.path + File.separator)) {
            throw SecurityException("Path escapes Documents/Verdroid: $path")
        }
        return candidate
    }

    fun relativePath(file: File): String = file.canonicalFile.relativeTo(root).path.ifEmpty { "." }

    companion object {
        /** Cloned repos live in Documents/Verdroid/Repos/<name>. */
        const val REPOS = "Repos"

        /** Absolute spellings of Documents/Verdroid (real path, /storage/emulated/0/…, /sdcard/…). */
        fun prefixes(root: File): List<String> =
            listOf(root.absolutePath, runCatching { root.canonicalPath }.getOrDefault(root.absolutePath), SharedFolder.DISPLAY_PATH, "/sdcard/Documents/Verdroid")
                .map { it.trimEnd('/') }.distinct()

        /**
         * Repo shorthand, the same for every tool: "agent-bridge/x.txt" means Repos/agent-bridge/x.txt when Repos/agent-bridge
         * is a git repo and Documents/Verdroid has no top-level "agent-bridge" of its own. Everything else is unchanged.
         */
        fun repoShorthand(root: File, rel: String): String {
            val first = rel.trimStart('/').substringBefore('/')
            if (first.isEmpty() || first == "." || first == ".." || first == REPOS) return rel
            if (File(root, first).exists()) return rel
            return if (File(File(File(root, REPOS), first), ".git").exists()) "$REPOS/${rel.trimStart('/')}" else rel
        }

        /**
         * Whether a path given to the private-workspace tools (read_file/write_file/list_dir) really names the shared tree:
         * an absolute Documents/Verdroid path, "Repos/…", or the repo shorthand above (when the private workspace has no such
         * top-level entry). Keeps every tool resolving "Repos/x/a.txt" / "x/a.txt" to the same file git sees.
         */
        fun claimsShared(path: String, sharedRoot: File, privateRoot: File): Boolean {
            val p = path.trim().replace('\\', '/')
            if (p.startsWith("/")) return prefixes(sharedRoot).any { p == it || p.startsWith("$it/") }
            val first = p.substringBefore('/')
            if (first == REPOS) return true
            if (first.isEmpty() || File(privateRoot, first).exists()) return false
            return repoShorthand(sharedRoot, p) != p
        }
    }
}

private const val SHARED = "Shared folder ${SharedFolder.DISPLAY_PATH} (Input/ = files from the user, Output/ = put results here, " +
    "Repos/<name> = git repos cloned by git_clone; '<name>/file' is shorthand for Repos/<name>/file). "

/** Common guard: all-files access + folder present; turns sandbox violations into error JSON. */
private suspend fun SharedFolder.guarded(block: suspend () -> String): String {
    if (!hasAccess()) return errorJson("All files access is not granted. Ask the user to open Settings > Tools > Shared folder and tap Grant.")
    if (!ensure()) return errorJson("Could not create ${SharedFolder.DISPLAY_PATH}")
    return try { block() } catch (e: SecurityException) { errorJson(e.message ?: "Path not allowed") }
        catch (e: Exception) { errorJson("${e.javaClass.simpleName}: ${e.message}") }
}

class WorkspaceListTool(private val folder: SharedFolder) : AgentTool {
    private val sandbox = SharedFolderSandbox(folder)
    override val name = "workspace_list"
    override val description = SHARED + "List files and folders (name, type, size, modified)."
    override val parameters = schema(emptyList(),
        "path" to prop("string", "Folder relative to Documents/Verdroid (default: the root)"),
        "recursive" to prop("boolean", "List sub-folders too (default false, max 500 entries)"))

    override suspend fun execute(args: JsonObject): String = folder.guarded {
        val dir = sandbox.resolve(args.str("path"))
        if (!dir.exists()) return@guarded errorJson("Not found: ${sandbox.relativePath(dir)}")
        if (!dir.isDirectory) return@guarded errorJson("Not a folder: ${sandbox.relativePath(dir)}")
        val files = (if (args.bool("recursive") == true) dir.walkTopDown().drop(1) else (dir.listFiles()?.asSequence() ?: emptySequence()))
            .filter { runCatching { sandbox.resolve(sandbox.relativePath(it)) }.isSuccess }
            .sortedWith(compareBy({ !it.isDirectory }, { it.path.lowercase() }))
            .toList()
        buildJsonObject {
            put("path", sandbox.relativePath(dir))
            put("absolute_path", dir.path)
            put("count", files.size)
            put("truncated", files.size > MAX_ENTRIES)
            putJsonArray("entries") {
                files.take(MAX_ENTRIES).forEach { f ->
                    addJsonObject {
                        put("path", sandbox.relativePath(f))
                        put("type", if (f.isDirectory) "dir" else "file")
                        if (f.isFile) put("size", f.length())
                        put("modified", Instant.ofEpochMilli(f.lastModified()).toString())
                    }
                }
            }
        }.toString()
    }

    private companion object { const val MAX_ENTRIES = 500 }
}

class WorkspaceReadTool(private val folder: SharedFolder) : AgentTool {
    private val sandbox = SharedFolderSandbox(folder)
    override val name = "workspace_read"
    override val description = SHARED + "Read a UTF-8 text file."
    override val parameters = schema(listOf("path"),
        "path" to prop("string", "File path relative to Documents/Verdroid, e.g. Input/notes.txt"),
        "offset" to prop("integer", "Byte offset to start from (default 0)"),
        "max_bytes" to prop("integer", "Maximum bytes to return (default 65536, max 1000000)"))

    override suspend fun execute(args: JsonObject): String = folder.guarded {
        val f = sandbox.resolve(args.str("path") ?: return@guarded errorJson("path is required"))
        if (!f.exists()) return@guarded errorJson("File not found: ${sandbox.relativePath(f)}")
        if (f.isDirectory) return@guarded errorJson("Is a folder: ${sandbox.relativePath(f)} (use workspace_list)")
        val size = f.length()
        val offset = (args.int("offset") ?: 0).toLong().coerceIn(0, size)
        val max = (args.int("max_bytes") ?: 65_536).coerceIn(1, 1_000_000)
        val bytes = java.io.RandomAccessFile(f, "r").use { raf ->
            val buf = ByteArray(minOf(max.toLong(), size - offset).toInt())
            raf.seek(offset)
            var n = 0
            while (n < buf.size) { val r = raf.read(buf, n, buf.size - n); if (r < 0) break; n += r }
            if (n == buf.size) buf else buf.copyOf(n)
        }
        val binary = bytes.take(8000).any { it == 0.toByte() }
        buildJsonObject {
            put("path", sandbox.relativePath(f))
            put("size", size)
            put("offset", offset)
            put("truncated", offset + bytes.size < size)
            if (binary) put("binary", true) // content withheld; process binaries with termux_run instead
            else put("content", String(bytes, Charsets.UTF_8))
        }.toString()
    }
}

class WorkspaceWriteTool(private val folder: SharedFolder) : AgentTool {
    private val sandbox = SharedFolderSandbox(folder)
    override val name = "workspace_write"
    override val description = SHARED + "Create or edit a text file (overwrite, append or create-only), or create a folder. " +
        "Put every user-facing deliverable under Output/ (translations, reports, scripts, projects, screenshots saved as text paths, …). Parent folders are created."
    override val parameters = schema(listOf("path"),
        "path" to prop("string", "Path relative to Documents/Verdroid, e.g. Output/report.md"),
        "content" to prop("string", "UTF-8 text to write (omit with directory=true)"),
        "mode" to prop("string", "overwrite (default), append, or create (fails if the file exists)"),
        "directory" to prop("boolean", "Create a folder at path instead of a file"))

    override suspend fun execute(args: JsonObject): String = folder.guarded {
        val f = sandbox.resolve(args.str("path") ?: return@guarded errorJson("path is required"))
        if (f == sandbox.root) return@guarded errorJson("Cannot write to the folder root itself")
        if (args.bool("directory") == true) {
            if (f.isFile) return@guarded errorJson("A file already exists at ${sandbox.relativePath(f)}")
            f.mkdirs()
            return@guarded buildJsonObject { put("ok", true); put("path", sandbox.relativePath(f)); put("type", "dir") }.toString()
        }
        val content = args.str("content") ?: return@guarded errorJson("content is required")
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) return@guarded errorJson("content is larger than ${MAX_BYTES / 1_000_000} MB")
        if (f.isDirectory) return@guarded errorJson("Is a folder: ${sandbox.relativePath(f)}")
        val mode = args.str("mode")?.lowercase() ?: "overwrite"
        val existed = f.exists()
        when (mode) {
            "overwrite" -> { f.parentFile?.mkdirs(); f.writeBytes(bytes) }
            "append" -> { f.parentFile?.mkdirs(); f.appendBytes(bytes) }
            "create" -> {
                if (existed) return@guarded errorJson("File already exists: ${sandbox.relativePath(f)}")
                f.parentFile?.mkdirs(); f.writeBytes(bytes)
            }
            else -> return@guarded errorJson("mode must be overwrite, append or create")
        }
        buildJsonObject {
            put("ok", true)
            put("path", sandbox.relativePath(f))
            put("absolute_path", f.path)
            put("created", !existed)
            put("bytes_written", bytes.size)
            put("size", f.length())
        }.toString()
    }

    private companion object { const val MAX_BYTES = 5_000_000 }
}

class WorkspaceDeleteTool(private val folder: SharedFolder) : AgentTool {
    private val sandbox = SharedFolderSandbox(folder)
    override val name = "workspace_delete"
    override val description = SHARED + "Delete a file, or a folder (non-empty folders need recursive=true). Only delete what the user asked for."
    override val parameters = schema(listOf("path"),
        "path" to prop("string", "Path relative to Documents/Verdroid"),
        "recursive" to prop("boolean", "Delete a non-empty folder and everything in it (default false)"))

    override suspend fun execute(args: JsonObject): String = folder.guarded {
        val f = sandbox.resolve(args.str("path") ?: return@guarded errorJson("path is required"))
        if (f == sandbox.root) return@guarded errorJson("Refusing to delete the Documents/Verdroid root")
        val rel = sandbox.relativePath(f)
        if (!f.exists()) return@guarded errorJson("Not found: $rel")
        val isDir = f.isDirectory
        if (isDir && !f.listFiles().isNullOrEmpty() && args.bool("recursive") != true)
            return@guarded errorJson("Folder is not empty: $rel (pass recursive=true)")
        val ok = if (isDir) deleteTree(f) else f.delete()
        folder.ensure() // keep Input/ and Output/ present
        buildJsonObject { put("ok", ok); put("deleted", rel); put("type", if (isDir) "dir" else "file") }.toString()
    }
}

/** Recursive delete that removes symlinks themselves instead of following them out of the tree. */
internal fun deleteTree(f: File): Boolean {
    val path = f.toPath()
    if (!java.nio.file.Files.isSymbolicLink(path) && f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
    return runCatching { java.nio.file.Files.deleteIfExists(path) }.getOrDefault(false) || !f.exists()
}
