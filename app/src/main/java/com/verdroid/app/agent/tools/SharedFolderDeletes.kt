package com.verdroid.app.agent.tools

/**
 * Proofs that a delete/move/rename stays inside the user's shared folder /storage/emulated/0/Documents/Verdroid (any
 * subfolder: Internal/, Repos/, Output/, Input/ …; never the folder itself). Used only when the user switched on "Always
 * allow delete/move/rename in Documents/Verdroid" (or "This chat"). Pure and conservative like [TermuxHomeDeletes]: the
 * shell proof IS that classifier (../ escapes, unresolved variables, command substitution, globs reaching outside,
 * xargs / sudo / bash -c, a cd that may have failed → not proven → ask). Python: only literal absolute paths.
 */
object SharedFolderDeletes {
    /** Spellings of Documents/Verdroid on primary storage (case-sensitive on purpose: anything else asks). */
    val ROOTS = listOf("/storage/emulated/0/Documents/Verdroid", "/sdcard/Documents/Verdroid", "/storage/self/primary/Documents/Verdroid")

    /** Strictly below Documents/Verdroid (absolute, already normalized). */
    fun inside(p: String): Boolean = ROOTS.any { p.startsWith("$it/") && p.length > it.length + 1 }

    /** termux_run: every rm/rmdir/unlink/mv path is in Documents/Verdroid or Termux home (both no-ask areas when on). */
    fun termuxCommand(command: String): Boolean =
        TermuxHomeDeletes.allInside(command, TermuxHomeDeletes.HOME) { inside(it) || TermuxHomeDeletes.inHome(it) }

    /** run_shell / rish_run (adb shell user): ~ is unknown there, so only Documents/Verdroid paths count. */
    fun shellCommand(command: String): Boolean = TermuxHomeDeletes.allInside(command, null, ::inside)

    private val PY_FUNC = Regex("""\b(os\.(?:remove|unlink|rmdir|removedirs|rename|renames|replace)|shutil\.(?:rmtree|move)|send2trash(?:\.send2trash)?)\s*\(""")
    private val PY_METHOD = Regex("""\.(unlink|rmdir|rename)\s*\(""")
    private val PY_PATH_RECV = Regex("""(?:pathlib\.)?Path\s*\(\s*([rR]?(?:"[^"\\]*"|'[^'\\]*'))\s*\)\s*$""")
    private val PY_LIT = Regex("""^[rR]?(?:"([^"\\]*)"|'([^'\\]*)')$""")

    /**
     * termux_python: true when there is at least one delete/move call and EVERY one is os.remove/unlink/rmdir/removedirs/
     * rename/renames/replace, shutil.rmtree/move, send2trash or Path('<lit>').unlink/rmdir/rename with only plain string
     * literal absolute paths inside Documents/Verdroid. Variables, f-strings, keyword args, shell strings, os.chdir → false.
     */
    fun python(code: String): Boolean {
        val c = code.replace(Regex("""(?m)^\s*#.*$"""), " ")
        if (Regex("""["'](rm|mv|rmdir|unlink|shred)(\s[^"']*)?["']""").containsMatchIn(c)) return false   // shell deletes in strings
        val funcs = PY_FUNC.findAll(c).toList()
        val methods = PY_METHOD.findAll(c).filter { m -> funcs.none { f -> m.range.first in f.range } }.toList()
        if (funcs.isEmpty() && methods.isEmpty()) return false
        for (m in funcs) {
            val args = callArgs(c, m.range.last + 1) ?: return false
            if (args.isEmpty() || !args.all(::literalInside)) return false
        }
        for (m in methods) {
            val recv = PY_PATH_RECV.find(c.substring(0, m.range.first)) ?: return false
            if (!literalInside(recv.groupValues[1])) return false
            val args = callArgs(c, m.range.last + 1) ?: return false
            when (m.groupValues[1]) {
                "rename" -> if (args.size != 1 || !literalInside(args[0])) return false
                else -> if (args.any { it.isNotBlank() && it.trim() != "missing_ok=True" }) return false
            }
        }
        return true
    }

    /** Comma-separated arguments from [start] (just after '(') to the matching ')'; null when nested calls/brackets appear. */
    private fun callArgs(c: String, start: Int): List<String>? {
        val out = mutableListOf<String>(); val cur = StringBuilder(); var q: Char? = null; var i = start
        while (i < c.length) {
            val ch = c[i]
            if (q != null) { cur.append(ch); if (ch == '\\') return null; if (ch == q) q = null; i++; continue }
            when (ch) {
                '"', '\'' -> { q = ch; cur.append(ch) }
                ',' -> { out += cur.toString().trim(); cur.clear() }
                ')' -> { val last = cur.toString().trim(); if (last.isNotEmpty() || out.isNotEmpty()) out += last; return out }
                '(', '[', '{' -> return null
                else -> cur.append(ch)
            }
            i++
        }
        return null
    }

    private fun literalInside(arg: String): Boolean {
        val m = PY_LIT.find(arg.trim()) ?: return false
        val v = m.groupValues[1].ifEmpty { m.groupValues[2] }
        val n = normalize(v) ?: return false
        return inside(n)
    }

    /** Absolute path with . and .. resolved; null when relative or when .. climbs past /. */
    fun normalize(p: String): String? {
        if (!p.startsWith("/")) return null
        val out = ArrayDeque<String>()
        for (seg in p.split('/')) when (seg) {
            "", "." -> {}
            ".." -> { if (out.isEmpty()) return null; out.removeLast() }
            else -> out.addLast(seg)
        }
        return "/" + out.joinToString("/")
    }
}
