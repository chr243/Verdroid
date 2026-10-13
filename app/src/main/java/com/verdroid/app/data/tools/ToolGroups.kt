package com.verdroid.app.data.tools

/** Settings > Tools sections (same families as AgentLoop.TOOL_GROUPS). Pure, unit-tested. */
object ToolGroups {
    data class Group(val id: String, val title: String)
    enum class Master { ON, OFF, PARTIAL }

    val ORDER = listOf(
        Group("files", "Files"), Group("web", "Web"), Group("termux", "Termux & Python"), Group("pdf", "PDF & documents"),
        Group("system", "Shizuku & phone screen"), Group("git", "Git"), Group("crypto", "Crypto backtesting"),
        Group("memory", "Memory & skills"), Group("other", "Charts & other"), Group("mcp", "MCP servers"),
    )

    fun groupOf(name: String): String = when {
        name.startsWith("mcp__") -> "mcp"
        name in setOf("read_file", "write_file", "list_dir") || name.startsWith("workspace_") -> "files"
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
}
