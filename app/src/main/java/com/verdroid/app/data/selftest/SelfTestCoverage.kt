package com.verdroid.app.data.selftest

/**
 * Coverage guard for the on-device self-test (Settings → Self-test). EVERY agent tool must either map to a
 * self-test check id ([TOOL_CHECKS]) or carry an explicit exemption with a reason ([EXEMPT]).
 * `SelfTestCoverageTest` fails the JVM build when a new tool is added without one. New features: register a check.
 */
object SelfTestCoverage {
    /** Check ids run by [SelfTestRunner], in report order. */
    val CHECKS = listOf(
        "tool_registry", "json", "room", "settings", "agents_md", "memory", "skills", "sandbox", "shared_folder",
        "network", "providers", "web_fetch", "web_search", "termux", "python", "pdf", "shizuku", "rish", "accessibility",
        "overlay", "git", "crypto_public", "updater", "notifications",
    )

    val TOOL_CHECKS: Map<String, String> = mapOf(
        "read_file" to "sandbox", "write_file" to "sandbox", "list_dir" to "sandbox",
        "workspace_list" to "shared_folder", "workspace_read" to "shared_folder",
        "workspace_write" to "shared_folder", "workspace_delete" to "shared_folder",
        "web_search" to "web_search", "web_fetch" to "web_fetch",
        "run_shell" to "shizuku", "rish_run" to "rish",
        "termux_run" to "termux", "termux_python" to "python",
        "selenium_open" to "termux", "selenium_page_source" to "termux", "selenium_screenshot" to "termux",
        "ebook_translate" to "python",
        "pdf_info" to "pdf", "pdf_extract_text" to "pdf", "pdf_extract_pages" to "pdf", "pdf_merge" to "pdf", "pdf_annotate" to "pdf",
        "git_status" to "git", "git_commit" to "git", "git_clone" to "git", "git_push" to "git",
        "screen_read" to "accessibility", "screen_tap" to "accessibility", "screen_swipe" to "accessibility",
        "screen_type" to "accessibility", "screen_action" to "accessibility",
        "memory_save" to "room", "memory_search" to "memory", "memory_delete" to "room",
        "skill_list" to "skills", "skill_get" to "skills", "skill_save" to "skills", "skill_edit" to "skills", "skill_delete" to "skills",
        "chart" to "tool_registry",
        "crypto_markets" to "crypto_public", "crypto_ticker" to "crypto_public", "crypto_candles" to "crypto_public",
        "crypto_orderbook" to "crypto_public", "crypto_backtest" to "crypto_public",
    )

    val EXEMPT: Map<String, String> = mapOf(
        "crypto_balance" to "needs the user's Coinbase API key; only instantiated (tool_registry)",
        "crypto_order_status" to "needs the user's Coinbase API key; only instantiated (tool_registry)",
        "crypto_place_order" to "live trading: never executed by a self-test; only instantiated (tool_registry)",
        "crypto_cancel_order" to "live trading: never executed by a self-test; only instantiated (tool_registry)",
    )

    /** Tool names lacking a check or exemption (dynamic MCP tools `mcp__*` are exempt by design). */
    fun uncovered(toolNames: Collection<String>): List<String> =
        toolNames.filter { !it.startsWith("mcp__") && it !in TOOL_CHECKS && it !in EXEMPT }.sorted()

    /** Mapped check ids that don't exist (typos). */
    fun unknownChecks(): List<String> = TOOL_CHECKS.values.filter { it !in CHECKS }.distinct()
}
