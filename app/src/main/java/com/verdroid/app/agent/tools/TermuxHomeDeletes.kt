package com.verdroid.app.agent.tools

import com.verdroid.app.data.termux.TermuxManager

/**
 * Proves (or fails to prove) that every delete/move in a termux_run command stays inside Termux's private home
 * (/data/data/com.termux/files/home and below). Only then is the "Delete / move / rename files" question skipped.
 * Pure and deliberately conservative: anything it can't model (command substitution, loops, functions, eval, unknown or
 * unset variables, ~user, brace expansion, hidden rm via xargs/sudo/bash -c, a `cd` that might have failed, `..` that
 * climbs out, dot-globs that could match `..`) → false → ask. Symlinks are out of scope. Overwrite rules are separate.
 */
object TermuxHomeDeletes {
    const val HOME = TermuxManager.TERMUX_HOME
    /** Verbs this proof covers; any other delete verb (shred, truncate, rename, find -delete, git rm…) still asks. */
    val VERBS = setOf("rm", "rmdir", "unlink", "mv")
    private val RAW_DELETE = Regex("""(^|[\s;&|(`'"$])(rm|rmdir|unlink|shred|srm|wipe|mv|rename|truncate)(\s|$)""")
    private val FORBIDDEN = listOf("`", "$(", "<(", ">(", "<<", "(", ")", "{", "}")
    private val KEYWORDS = setOf("for", "while", "until", "if", "then", "else", "elif", "fi", "do", "done", "case", "esac", "select",
        "function", "eval", "source", ".", "exec", "alias", "read", "declare", "typeset", "local", "readonly", "unset", "let",
        "mapfile", "readarray", "getopts", "pushd", "popd", "trap", "shopt", "enable", "builtin", "command", "coproc", "time")
    private val RM_OPTS = Regex("""^-[rRfivd]+$""")
    private val RM_LONG = setOf("--recursive", "--force", "--verbose", "--dir", "--interactive=never")
    private val MV_OPTS = Regex("""^-[finvub]+$""")
    private val MV_LONG = setOf("--force", "--no-clobber", "--verbose", "--update")
    private val RMDIR_OPTS = setOf("-p", "-v", "--parents", "--verbose", "--ignore-fail-on-non-empty")
    private val NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    /** [assignOk]: NAME= was unquoted (a real shell assignment, not a quoted word that merely contains '='). */
    private class Word(val text: String, val tildeOk: Boolean, val globbed: Boolean, val assignOk: Boolean = false)
    /** Variables that change how the shell expands or finds things: setting them is never modeled. */
    private val RESERVED = setOf("HOME", "IFS", "GLOBIGNORE", "CDPATH", "PATH", "BASHOPTS", "SHELLOPTS", "BASH_ENV", "ENV", "PWD", "OLDPWD")
    private class Fail : Exception()

    /** true = at least one rm/rmdir/unlink/mv and every one of them only touches paths inside Termux home. */
    fun allInTermuxHome(command: String): Boolean = allInside(command, HOME, ::inHome)

    /**
     * The same proof for any area: true = at least one rm/rmdir/unlink/mv and every path they touch satisfies [inside]
     * (absolute, normalized). [home]: what ~ and $HOME mean in that shell (null = unknown → any ~ / $HOME use fails).
     */
    fun allInside(command: String, home: String?, inside: (String) -> Boolean): Boolean =
        try { check(command, home, inside) } catch (_: Fail) { false }

    private fun check(command: String, home: String?, inside: (String) -> Boolean): Boolean {
        val c = command.replace(Regex("""(?m)^\s*#.*$"""), " ")
        val probe = c.replace(Regex("""\$\{[A-Za-z_][A-Za-z0-9_]*\}"""), "V")   // ${NAME} is fine; other braces are not
        if (FORBIDDEN.any { it in probe }) return false
        if (Regex("""(^|\s)-(delete|exec|execdir|ok)\b|\b(rsync|shred|git)\b""").containsMatchIn(c)) return false   // other deleters: not modeled
        val (cmds, seps) = split(c)
        val vars = if (home != null) mutableMapOf("HOME" to home) else mutableMapOf()
        var cwd: String? = null          // termux_run starts in Documents/Verdroid/Internal (shared storage): relative = unknown
        var cwdSticky = false             // cd guarded by `|| exit` / set -e: stays valid after ; / newline
        var errexit = false
        var validated = 0
        for ((i, raw) in cmds.withIndex()) {
            val before = if (i > 0) seps[i - 1] else ";"
            val after = seps.getOrNull(i) ?: ";"
            // A new list (after ; / newline): a cd only guarded by && no longer holds (it may have failed).
            if (before == ";" && !cwdSticky) cwd = null
            if (before == "||") { if (!cwdSticky) cwd = null }
            if (raw.isBlank()) continue
            val words = words(raw, vars)
            if (words.isEmpty()) continue
            val inPipe = before == "|" || after == "|" || before == "&" || after == "&"
            // Leading NAME=value words.
            val assigns = words.takeWhile { a -> a.assignOk }
            if (assigns.isNotEmpty() && assigns.size < words.size) return false   // W=x cmd …: per-command env, not modeled
            if (assigns.isNotEmpty()) {
                if (inPipe) return false
                assigns.forEach { a -> assign(vars, a, home) }
                continue
            }
            val verb = words[0].text
            val args = words.drop(1)
            if (verb in KEYWORDS) return false
            when (verb) {
                "export" -> {
                    if (inPipe) return false
                    args.forEach { a -> if (a.assignOk) assign(vars, a, home) else if (!NAME.matches(a.text) || a.text in RESERVED) return false }
                }
                "cd" -> {
                    if (inPipe) return false
                    val target = args.filterNot { it.text == "--" }.singleOrNull() ?: return false
                    if (target.text.startsWith("-")) return false
                    cwd = resolve(target, cwd, home)
                    cwdSticky = errexit || (after == "||" && cmds.getOrNull(i + 1)?.trim()?.let { it == "exit" || it.startsWith("exit ") || it == "return" } == true)
                    if (after == "||" && !cwdSticky) return false
                }
                "set" -> args.forEach { a -> if (a.text.startsWith("-") && 'e' in a.text.drop(1)) errexit = true; if (a.text.startsWith("+") && 'e' in a.text) errexit = false }
                "printf" -> if (args.any { it.text == "-v" }) return false
                in VERBS -> { if (!targetsInside(verb, args, cwd, home, inside)) return false; validated++ }
            }
        }
        // Every delete word anywhere in the command must be one of the commands proven above (no xargs rm, sudo rm, bash -c "rm …").
        val rawCount = RAW_DELETE.findAll(c).count()
        return validated > 0 && rawCount == validated
    }

    private fun assign(vars: MutableMap<String, String>, a: Word, home: String?) {
        val name = a.text.substringBefore('=')
        if (name.endsWith("+") || name in RESERVED) throw Fail()
        val value = a.text.substringAfter('=')
        // Only absolute paths (after ~ / $VAR expansion) are kept; anything else makes the variable unknown.
        val v = if (a.tildeOk && (value == "~" || value.startsWith("~/"))) (home ?: throw Fail()) + value.drop(1) else value
        if (v.startsWith("/") && !a.globbed) vars[name] = v else vars.remove(name)
    }

    private fun targetsInside(verb: String, args: List<Word>, cwd: String?, home: String?, inside: (String) -> Boolean): Boolean {
        var endOpts = false
        var paths = 0
        for (a in args) {
            val t = a.text
            if (!endOpts && t == "--") { endOpts = true; continue }
            if (!endOpts && t.startsWith("-") && t.length > 1) {
                val ok = when (verb) {
                    "rm" -> RM_OPTS.matches(t) || t in RM_LONG
                    "mv" -> MV_OPTS.matches(t) || t in MV_LONG
                    "rmdir" -> t in RMDIR_OPTS
                    else -> false
                }
                if (!ok) return false
                continue
            }
            if (Regex("""^\d*>>?""").matches(t) || Regex("""^\d*>>?.+""").matches(t) || Regex("""^\d*>&\d$""").matches(t)) continue
            val p = resolve(a, cwd, home) ?: return false
            if (!inside(p)) return false
            paths++
        }
        return paths > 0
    }

    fun inHome(p: String) = p == HOME || p.startsWith("$HOME/")

    /** Absolute normalized path, or null when it can't be known. `..` is resolved (and so caught when it climbs out). */
    private fun resolve(w: Word, cwd: String?, home: String?): String? {
        var t = w.text
        if (t.isEmpty()) return null
        if (w.tildeOk && (t == "~" || t.startsWith("~/"))) t = (home ?: return null) + t.drop(1)   // HOME can't be reassigned (RESERVED)
        else if (t.startsWith("~")) return null
        val abs = if (t.startsWith("/")) t else (cwd ?: return null) + "/" + t
        val out = ArrayDeque<String>()
        for (seg in abs.split('/')) {
            when {
                seg.isEmpty() || seg == "." -> {}
                seg == ".." -> { if (out.isEmpty()) return null; out.removeLast() }
                w.globbed && seg.startsWith(".") && seg.any { it in "*?[" } -> return null   // .* may match ..
                else -> out.addLast(seg)
            }
        }
        return "/" + out.joinToString("/")
    }

    /** Simple commands and the separators between them (; for newline/;, &&, ||, |, &). Quotes respected. */
    private fun split(c: String): Pair<List<String>, List<String>> {
        val cmds = mutableListOf<String>(); val seps = mutableListOf<String>()
        val cur = StringBuilder(); var i = 0; var q: Char? = null
        while (i < c.length) {
            val ch = c[i]
            if (q != null) { cur.append(ch); if (ch == '\\' && q == '"' && i + 1 < c.length) { cur.append(c[i + 1]); i += 2; continue }; if (ch == q) q = null; i++; continue }
            when {
                ch == '\\' && i + 1 < c.length -> { if (c[i + 1] == '\n') { i += 2; continue }; cur.append(ch).append(c[i + 1]); i += 2; continue }
                ch == '\'' || ch == '"' -> { q = ch; cur.append(ch) }
                ch == ';' || ch == '\n' -> { cmds += cur.toString(); seps += ";"; cur.clear() }
                ch == '&' && c.getOrNull(i + 1) == '&' -> { cmds += cur.toString(); seps += "&&"; cur.clear(); i++ }
                ch == '|' && c.getOrNull(i + 1) == '|' -> { cmds += cur.toString(); seps += "||"; cur.clear(); i++ }
                ch == '|' -> { cmds += cur.toString(); seps += "|"; cur.clear() }
                ch == '&' && c.getOrNull(i - 1) != '>' && c.getOrNull(i + 1) != '>' -> { cmds += cur.toString(); seps += "&"; cur.clear() }
                else -> cur.append(ch)
            }
            i++
        }
        if (q != null) throw Fail()
        cmds += cur.toString()
        return cmds to seps
    }

    /** Words with quotes removed and $VAR / ${VAR} expanded (unknown → Fail). */
    private fun words(s: String, vars: Map<String, String>): List<Word> {
        val out = mutableListOf<Word>()
        val cur = StringBuilder(); var started = false; var tildeOk = false; var globbed = false
        var plain = true   // nothing quoted/escaped/expanded before the first '='
        var i = 0
        fun flush() {
            if (started) {
                val t = cur.toString()
                out += Word(t, tildeOk, globbed, assignOk = plain && '=' in t && NAME.matches(t.substringBefore('=').removeSuffix("+")))
            }
            cur.clear(); started = false; tildeOk = false; globbed = false; plain = true
        }
        fun notPlain() { if ('=' !in cur) plain = false }
        fun variable(): String {
            // at s[i] == '$'
            val rest = s.substring(i + 1)
            val m = Regex("""^\{([A-Za-z_][A-Za-z0-9_]*)\}""").find(rest) ?: Regex("""^([A-Za-z_][A-Za-z0-9_]*)""").find(rest) ?: throw Fail()
            i += 1 + m.value.length
            return vars[m.groupValues[1]] ?: throw Fail()
        }
        while (i < s.length) {
            val ch = s[i]
            when {
                ch.isWhitespace() -> { flush(); i++ }
                ch == '\'' -> { val end = s.indexOf('\'', i + 1); if (end < 0) throw Fail(); if (!started) { started = true }; notPlain(); cur.append(s, i + 1, end); i = end + 1 }
                ch == '"' -> {
                    if (!started) started = true
                    notPlain()
                    i++
                    while (i < s.length && s[i] != '"') {
                        when {
                            s[i] == '\\' && i + 1 < s.length && s[i + 1] in "\"\\$`" -> { cur.append(s[i + 1]); i += 2 }
                            s[i] == '$' -> cur.append(variable())
                            else -> { cur.append(s[i]); i++ }
                        }
                    }
                    if (i >= s.length) throw Fail()
                    i++
                }
                ch == '\\' -> { if (!started) started = true; notPlain(); if (i + 1 < s.length) cur.append(s[i + 1]); i += 2 }
                ch == '$' -> {
                    if (!started) started = true
                    notPlain()
                    val v = variable()
                    // Unquoted: the value is word-split and globbed by the shell → only plain single-word values are modeled.
                    if (v.any { it.isWhitespace() || it in "*?[" }) throw Fail()
                    cur.append(v)
                }
                else -> {
                    if (!started) { started = true; tildeOk = ch == '~' }
                    else if (ch == '~' && cur.endsWith("=") && cur.indexOf("=") == cur.length - 1) tildeOk = true  // W=~/x
                    if (ch in "*?[") globbed = true
                    cur.append(ch); i++
                }
            }
        }
        flush()
        return out
    }
}
