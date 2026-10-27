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
        "overlay", "git", "updater", "notifications", "font_scale", "feedback", "internal_folder", "crypto_history", "backtest_python", "file_guard", "share_target", "chat_ui", "cookies", "cookie_webview", "x_posts", "reddit", "chat_seen", "overwrite_policy", "x_post_guard", "crash_log", "twitter_tos", "model_pins",
        "oom_hardening", "install_prompt", "x_post_limits", "ebook_resume", "update_hint", "chat_links", "assistant", "scheduling", "git_access", "git_repos", "shared_deletes", "tools_layout", "show_image", "ideas_prompt", "local_llm", "prompt_safety", "shell_safety", "update_digest", "repeat_calls", "key_wipe_notice", "profiles", "deep_research", "key_rotation",
    )

    val TOOL_CHECKS: Map<String, String> = mapOf(
        "read_file" to "sandbox", "write_file" to "sandbox", "list_dir" to "sandbox",
        "workspace_list" to "shared_folder", "workspace_read" to "shared_folder",
        "workspace_write" to "shared_folder", "workspace_delete" to "shared_folder",
        "web_search" to "web_search", "web_fetch" to "web_fetch",
        "run_shell" to "shizuku", "rish_run" to "rish",
        "termux_run" to "termux", "termux_python" to "python",
        "selenium_open" to "termux", "selenium_page_source" to "termux", "selenium_screenshot" to "termux",
        "cookie_import" to "cookies", "cookie_list" to "cookies", "cookie_clear" to "cookies", "cookie_open" to "cookies", "cookie_login" to "cookie_webview", "x_timeline" to "x_posts", "x_user_posts" to "x_posts", "x_search" to "x_posts", "x_post" to "x_post_guard",
        "ebook_translate" to "python",
        "pdf_info" to "pdf", "pdf_extract_text" to "pdf", "pdf_extract_pages" to "pdf", "pdf_merge" to "pdf", "pdf_annotate" to "pdf",
        "git_status" to "git", "git_commit" to "git", "git_clone" to "git", "git_push" to "git",
        "git_repos" to "git_repos", "git_pull" to "git_repos", "git_log" to "git_repos", "git_diff" to "git_repos", "git_branch" to "git_repos",
        "screen_read" to "accessibility", "screen_tap" to "accessibility", "screen_swipe" to "accessibility",
        "screen_type" to "accessibility", "screen_action" to "accessibility",
        "memory_save" to "room", "memory_search" to "memory", "memory_delete" to "room",
        "skill_list" to "skills", "skill_get" to "skills", "skill_save" to "skills", "skill_edit" to "skills", "skill_delete" to "skills",
        "chart" to "tool_registry", "show_image" to "show_image",
        "crypto_backtest" to "crypto_history",
        "schedule_reminder" to "scheduling", "schedule_task" to "scheduling", "schedule_list" to "scheduling",
        "schedule_cancel" to "scheduling", "schedule_pause" to "scheduling", "schedule_resume" to "scheduling",
        "crypto_history_download" to "crypto_history", "crypto_backtest_script" to "backtest_python", "crypto_history_list" to "crypto_history",
    )

    val EXEMPT: Map<String, String> = mapOf(
        "self_test" to "runs the self-test itself; excluded to avoid recursion",
    )

    /** Tool names lacking a check or exemption (dynamic MCP tools `mcp__*` are exempt by design). */
    fun uncovered(toolNames: Collection<String>): List<String> =
        toolNames.filter { !it.startsWith("mcp__") && it !in TOOL_CHECKS && it !in EXEMPT }.sorted()

    /** Mapped check ids that don't exist (typos). */
    fun unknownChecks(): List<String> = TOOL_CHECKS.values.filter { it !in CHECKS }.distinct()
}
