package com.verdroid.app.data.crash

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash log: an UncaughtExceptionHandler writes the stack trace + version + time to
 * Documents/Verdroid/Internal/crash/crash_YYYYMMDD_HHMMSS.txt (app-private files/crash/ without storage access), then
 * chains to the previous handler (so Android still shows/handles the crash). Settings → App → Crash log shows the latest.
 */
object CrashLog {
    fun fileName(now: Date): String = "crash_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now) + ".txt"

    fun format(t: Throwable, version: String, thread: String, now: Date, device: String): String {
        val sw = StringWriter(); t.printStackTrace(PrintWriter(sw))
        return "Verdroid $version crash\nTime: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(now)}\n" +
            "Thread: $thread\nDevice: $device\n\n$sw"
    }

    /** Newest crash file across [dirs] (by name, which sorts by time; then mtime). */
    fun latest(dirs: List<File>): File? = dirs.flatMap { it.listFiles { f -> f.isFile && f.name.startsWith("crash_") && f.name.endsWith(".txt") }?.toList().orEmpty() }
        .maxWithOrNull(compareBy<File>({ it.name }, { it.lastModified() }))

    /** Writes the report into the first writable dir; never throws. Returns the file or null. */
    fun write(dirs: List<File>, name: String, text: String): File? {
        for (d in dirs) {
            val f = runCatching {
                d.mkdirs()
                var target = File(d, name); var i = 2
                while (target.exists()) target = File(d, name.removeSuffix(".txt") + "_${i++}.txt")
                target.writeText(text); target
            }.getOrNull()
            if (f != null) return f
        }
        return null
    }

    /** Dirs in preference order: shared Internal/crash (when accessible), then app-private files/crash. */
    fun dirs(sharedInternal: File?, filesDir: File): List<File> = listOfNotNull(sharedInternal?.let { File(it, "crash") }, File(filesDir, "crash"))

    fun install(dirs: () -> List<File>, version: String, device: String) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { val now = Date(); write(dirs(), fileName(now), format(e, version, thread.name, now, device)) }
            if (previous != null) previous.uncaughtException(thread, e) else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }
}
