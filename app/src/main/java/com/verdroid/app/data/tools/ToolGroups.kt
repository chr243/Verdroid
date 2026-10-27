package com.verdroid.app.data.tools

/** Settings > Tools sections (same families as AgentLoop.TOOL_GROUPS). Pure, unit-tested. */
object ToolGroups {
    /** [description] is the one-line summary shown in Settings > Tools and the chat Tool presets dialog (single source). */
    data class Group(val id: String, val title: String, val description: String)
    enum class Master { ON, OFF, PARTIAL }

    val ORDER = listOf(
        Group("files", "Files", "Documents/Verdroid (workspace_*), private scratch files, show_image"),
        Group("web", "Web", "Search, fetch, headless Chromium (web_*, selenium_*); beta: logged-in pages (cookie_*, stored encrypted), incl. Reddit via cookie_open"),
        Group("twitter", "X (Twitter)", "Beta: read your X timeline, profiles and search (x_timeline, x_user_posts, x_search) and post with confirmation (x_post), using your saved X login. Against X's Terms of Service: the account may be limited or banned"),
        Group("termux", "Termux & Python", "termux_run, termux_python"),
        Group("pdf", "PDF & documents", "PDF read/edit (pdf_*), ebook translation"),
        Group("system", "Shizuku & phone screen", "Shizuku shell, rish, screen control (accessibility)"),
        Group("git", "Git", "Repos in Documents/Verdroid/Repos (git_*, built-in JGit)"),
        Group("crypto", "Crypto backtesting", "Backtesting: history download, backtests, scripts"),
        Group("scheduling", "Scheduling", "Reminders and scheduled agent runs (schedule_*): once, daily or every N minutes; manage in Settings → Schedules"),
        Group("agents", "Sub-agents & research", "spawn_subagents: parallel research tasks with read-only tools; deep_research: long cited web research reports (standard/deep); configure in Settings → Sub-agents"),
        Group("memory", "Memory & skills", "memory_*, skill_*"),
        Group("other", "Charts & other", "chart, self_test"),
        Group("mcp", "MCP servers", "Tools from your MCP servers (mcp__*)"),
    )

    fun groupOf(name: String): String = when {
        name.startsWith("mcp__") -> "mcp"
        name in setOf("read_file", "write_file", "list_dir", "show_image") || name.startsWith("workspace_") -> "files"
        name in setOf("x_timeline", "x_user_posts", "x_search", "x_post") -> "twitter"
        name.startsWith("web_") || name.startsWith("selenium_") || name.startsWith("cookie_") -> "web"
        name == "termux_run" || name == "termux_python" -> "termux"
        name.startsWith("pdf_") || name == "ebook_translate" -> "pdf"
        name == "run_shell" || name == "rish_run" || name.startsWith("screen_") -> "system"
        name.startsWith("git_") -> "git"
        name.startsWith("crypto_") -> "crypto"
        name.startsWith("schedule_") -> "scheduling"
        name == "spawn_subagents" || name == "deep_research" -> "agents"
        name.startsWith("memory_") || name.startsWith("skill_") -> "memory"
        else -> "other"
    }

    const val TWITTER_TOS_WARNING = "Automating X with your login is against X's Terms of Service and may get your account " +
        "limited or banned. Use at your own risk."

    /** Warn before the Twitter group goes from off to on (Settings switch, a tool switch or a per-chat preset). */
    fun needsTosWarning(groupId: String, wasOn: Boolean, turningOn: Boolean): Boolean = groupId == "twitter" && !wasOn && turningOn

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
