package com.verdroid.app.data.tools

import com.verdroid.app.R

/** Resource ids for [ToolGroups] titles/descriptions (the data itself stays English for tests/self-test). */
object ToolGroupsText {
    fun titleRes(id: String): Int = when (id) {
        "files" -> R.string.tg_files_t
        "web" -> R.string.tg_web_t
        "twitter" -> R.string.tg_twitter_t
        "termux" -> R.string.tg_termux_t
        "pdf" -> R.string.tg_pdf_t
        "system" -> R.string.tg_system_t
        "git" -> R.string.tg_git_t
        "crypto" -> R.string.tg_crypto_t
        "scheduling" -> R.string.tg_scheduling_t
        "agents" -> R.string.tg_agents_t
        "memory" -> R.string.tg_memory_t
        "other" -> R.string.tg_other_t
        "mcp" -> R.string.tg_mcp_t
        else -> R.string.tools
    }

    fun descRes(id: String): Int = when (id) {
        "files" -> R.string.tg_files_d
        "web" -> R.string.tg_web_d
        "twitter" -> R.string.tg_twitter_d
        "termux" -> R.string.tg_termux_d
        "pdf" -> R.string.tg_pdf_d
        "system" -> R.string.tg_system_d
        "git" -> R.string.tg_git_d
        "crypto" -> R.string.tg_crypto_d
        "scheduling" -> R.string.tg_scheduling_d
        "agents" -> R.string.tg_agents_d
        "memory" -> R.string.tg_memory_d
        "other" -> R.string.tg_other_d
        "mcp" -> R.string.tg_mcp_d
        else -> R.string.tools
    }
}
