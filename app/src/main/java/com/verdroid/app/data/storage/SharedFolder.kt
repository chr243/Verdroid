package com.verdroid.app.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * The user-visible shared folder `/storage/emulated/0/Documents/Verdroid` with `Input/` (files the user hands to Verdroid)
 * and `Output/` (Verdroid's deliverables), plus `Internal/` (agent scripts, temp and intermediate files; keeps the root clean). Needs "All files access" (MANAGE_EXTERNAL_STORAGE) on Android 11+.
 * [hasAccess] is injectable so the folder logic is testable on a temp dir.
 */
class SharedFolder(val root: File, private val access: () -> Boolean) {

    fun hasAccess(): Boolean = runCatching(access).getOrDefault(false)

    val input: File get() = File(root, INPUT)
    val output: File get() = File(root, OUTPUT)
    val internal: File get() = File(root, INTERNAL)

    /** Whether Documents/Verdroid, Input/, Output/ and Internal/ all exist. */
    fun exists(): Boolean = root.isDirectory && input.isDirectory && output.isDirectory && internal.isDirectory

    /** Creates Documents/Verdroid, Input/, Output/ and Internal/ when missing. Returns true when all exist afterwards. */
    fun ensure(): Boolean {
        if (!hasAccess()) return false
        listOf(root, input, output, internal).forEach { if (!it.isDirectory) it.mkdirs() }
        return exists()
    }

    /**
     * One-time tidy-up (v1.0.49): moves loose files the agent left in the root into Internal/. Conservative: only direct
     * children with [AGENT_EXTENSIONS] (scripts/temp/logs); folders and everything else (user files) stay. Never overwrites
     * (adds " (2)" etc.). Runs once (marker in Internal/). Returns the names moved.
     */
    fun migrateStrayRootFiles(): List<String> {
        if (!hasAccess() || !ensure()) return emptyList()
        val marker = File(internal, MIGRATION_MARKER)
        if (marker.exists()) return emptyList()
        val moved = mutableListOf<String>()
        root.listFiles().orEmpty().filter { it.isFile && isAgentScratch(it.name) }.forEach { f ->
            val dest = uniqueTarget(internal, f.name)
            if (f.renameTo(dest) || runCatching { f.copyTo(dest); f.delete() }.getOrDefault(false)) moved += f.name
        }
        runCatching { marker.writeText("moved ${moved.size} file(s)\n" + moved.joinToString("\n")) }
        return moved
    }

    companion object {
        const val INTERNAL = "Internal"
        const val MIGRATION_MARKER = ".root-tidy-v1"
        /** Extensions only the agent produces in the root (scripts, temp, logs, compiled python). */
        val AGENT_EXTENSIONS = setOf("py", "pyc", "sh", "tmp", "temp", "log", "part")

        fun isAgentScratch(name: String): Boolean =
            !name.startsWith(".") && name.substringAfterLast('.', "").lowercase() in AGENT_EXTENSIONS

        internal fun uniqueTarget(dir: File, name: String): File {
            var f = File(dir, name); var i = 2
            val base = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
            while (f.exists()) { f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"); i++ }
            return f
        }

        const val INPUT = "Input"
        const val OUTPUT = "Output"
        /** Path as the user sees it (primary external storage). */
        const val DISPLAY_PATH = "/storage/emulated/0/Documents/Verdroid"

        fun android(): SharedFolder = SharedFolder(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Verdroid"),
        ) { Environment.isExternalStorageManager() }

        /** Opens the system "All files access" screen for Verdroid (falls back to the global list). */
        fun accessIntent(context: Context): Intent {
            val own = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName))
            return if (own.resolveActivity(context.packageManager) != null) own
            else Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }
}
