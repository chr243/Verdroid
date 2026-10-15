package com.verdroid.app.data.tools

/** Settings > Tools sections (same families as AgentLoop.TOOL_GROUPS). Pure, unit-tested. */
object ToolGroups {
    /** [description] is the one-line summary shown in Settings > Tools and the chat Tool presets dialog (single source). */
    data class Group(val id: String, val title: String, val description: String)
    enum class Master { ON, OFF, PARTIAL }

    val ORDER = listOf(
        Group("files", "Files", "Documents/Verdroid (workspace_*), private scratch files"),
        Group("web", "Web", "web_search, web_fetch, headless Chromium (selenium_*)"),
        Group("cookies", "Browser cookies (beta)", "Beta for testing logged-in pages: your own cookies in headless Chromium (cookie_*), stored encrypted"),
        Group("termux", "Termux & Python", "termux_run, termux_python"),
        Group("pdf", "PDF & documents", "PDF read/edit (pdf_*), ebook translation"),
        Group("system", "Shizuku & phone screen", "Shizuku shell, rish, screen control (accessibility)"),
        Group("git", "Git", "git_* (JGit)"),
        Group("crypto", "Crypto backtesting", "Backtesting: history download, backtests, scripts"),
        Group("memory", "Memory & skills", "memory_*, skill_*"),
        Group("other", "Charts & other", "chart, self_test"),
        Group("mcp", "MCP servers", "Tools from your MCP servers (mcp__*)"),
    )

    fun groupOf(name: String): String = when {
        name.startsWith("mcp__") -> "mcp"
        name in setOf("read_file", "write_file", "list_dir") || name.startsWith("workspace_") -> "files"
        name.startsWith("cookie_") -> "cookies"
        name.startsWith("web_") || name.startsWith("selenium_") -> "web"
        name == "termux_run" || name == "termux_python" -> "termux"
        name.startsWith("pdf_") || name == "ebook_translate" -> "pdf"
        name == "run_shell" || name == "rish_run" || name.startsWith("screen_") -> "system"
        name.startsWith("git_") -> "git"
        name.startsWith("crypto_") -> "crypto"
        name.startsWith("memory_") || name.startsWith("skill_") -> "memory"
        else -> "other"
    }

    /** Tools grouped in [ORDER]; empty groups dropped; tool order kept. */
    fun <T> group(items: List<T>, name: (T) -> String): List<Pair<Group, List<T>>> {
        val by = items.groupBy { groupOf(name(it)) }
        return ORDER.mapNotNull { g -> by[g.id]?.takeIf { it.isNotEmpty() }?.let { g to it } }
    }

    fun master(names: List<String>, disabled: Set<String>): Master {
        val on = names.count { it !in disabled }
        return when (on) { names.size -> Master.ON; 0 -> Master.OFF; else -> Master.PARTIAL }
    }

    /** Tapping the master: all on unless everything is already on (then all off). */
    fun nextEnabled(m: Master): Boolean = m != Master.ON

    /** Group Switch in Settings > Tools: shown ON while any tool of the group is on (partial = on); flipping it sets all tools. */
    fun switchOn(m: Master): Boolean = m != Master.OFF

    /** "N of M on" line, only for a partially enabled group. */
    fun partialLabel(names: List<String>, disabled: Set<String>): String? =
        if (master(names, disabled) == Master.PARTIAL) "${names.count { it !in disabled }} of ${names.size} on" else null
}
