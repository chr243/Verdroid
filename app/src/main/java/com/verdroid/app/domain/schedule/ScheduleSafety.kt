package com.verdroid.app.domain.schedule

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Scheduled runs have nobody watching, so anything that normally needs the user's Yes (or acts as the user, or controls
 * the phone) is refused with a clear tool error. The run can still say in its summary what it would like to do.
 * Pure + unit-tested; enforced in ToolRegistry.execute (plus [HeadlessRun] for consent prompts deeper in tools).
 */
object ScheduleSafety {
    /** First line of the user message a scheduled run posts into its chat (persisted, so resumes stay headless). */
    const val MARKER = "⏰ Scheduled run"

    /** Token a run replies with when "only tell me if something changed" and nothing did (no notification then). */
    const val NO_CHANGE = "NO_CHANGE"

    fun isScheduledMessage(content: String?): Boolean = content?.trimStart()?.startsWith(MARKER) == true

    /** Schedule name from a scheduled-run message (`⏰ Scheduled run "name" (…)`), "" if absent; null = not a scheduled message. */
    fun scheduleName(content: String?): String? {
        if (!isScheduledMessage(content)) return null
        return Regex("""^\s*${Regex.escape(MARKER)}\s*"([^"\n]*)"""").find(content!!)?.groupValues?.get(1).orEmpty()
    }

    /**
     * Blocked tools the user can still allow unattended through their own "Always allow" setting (checked live):
     * x_post with "Post to X without asking" / the dialog's Always allow. The rate cap and audit log still apply.
     */
    val ALWAYS_ALLOWABLE: Set<String> = setOf("x_post")

    /** Always refused in a scheduled run. */
    val BLOCKED: Set<String> = setOf(
        "x_post",                                                               // posts publicly as the user
        "screen_tap", "screen_swipe", "screen_type", "screen_action",           // phone control (Accessibility)
        "run_shell", "rish_run",                                                // Shizuku shell = device control
        "git_push", "git_commit",                                               // sends / changes repos
        "workspace_delete", "skill_delete", "memory_delete",                    // deletes
        "cookie_login", "cookie_clear", "cookie_import",                        // login UI / credential changes
        "schedule_reminder", "schedule_task", "schedule_cancel", "schedule_pause", "schedule_resume", // no self-scheduling
    )

    private val MCP_WRITE = Regex("send|post|publish|create|delete|remove|write|update|edit|move|rename|message|mail|comment|push|merge|pay|buy|order|transfer|trade",
        RegexOption.IGNORE_CASE)
    private val TERMUX_SENDS = Regex("""\btermux-(sms-send|telephony-call|share|open|open-url|notification|clipboard-set|wallpaper|toast)\b|\b(pkg|apt|apt-get|pip3?|npm|gem|cargo)\s+(install|upgrade|uninstall|remove)\b""")

    /** null = allowed; else the reason this tool call is refused in a scheduled run. */
    fun blockReason(tool: String, args: kotlinx.serialization.json.JsonObject, alwaysAllowed: (String) -> Boolean = { false }): String? {
        fun bool(k: String) = (args[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.lowercase() == "true"
        fun str(k: String) = (args[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        return when {
            tool == "x_post" -> if (alwaysAllowed(tool)) null else "x_post needs the user's Post tap. (Turning on Always allow for X posts — " +
                "Settings → Tools → Twitter → Post to X without asking — permits posting from scheduled runs, still within the hourly/daily cap)"
            tool == "cookie_login" -> "logging in needs the user. Assume the saved X/site login works; if a tool says NOT_LOGGED_IN, " +
                "say in your summary that the user should open Verdroid and log in again (Settings → Tools → Logged-in sites)"
            tool in BLOCKED -> "$tool needs the user (confirmation, posting/sending, phone control, deleting or scheduling)"
            tool.startsWith("mcp__") && MCP_WRITE.containsMatchIn(tool) -> "MCP tool $tool may change or send something"
            bool("confirm_install") -> "installing packages needs the user's yes"
            tool == "ebook_translate" && bool("confirmed") -> "a full ebook translation needs the user's confirmation"
            (tool == "termux_run" && TERMUX_SENDS.containsMatchIn(str("command"))) ||
                (tool == "termux_python" && TERMUX_SENDS.containsMatchIn(str("code"))) -> "sending, opening apps or installing from Termux needs the user"
            else -> null
        }
    }

    fun blockedJson(tool: String, reason: String): String = kotlinx.serialization.json.buildJsonObject {
        put("error", kotlinx.serialization.json.JsonPrimitive("BLOCKED_IN_SCHEDULED_RUN: $reason. This is an unattended scheduled run, " +
            "so it can't be done now. Don't retry or work around it; finish the task without it and say in your summary what you " +
            "would like to do so the user can do it or ask you in the chat."))
        put("blocked_in_scheduled_run", kotlinx.serialization.json.JsonPrimitive(true))
        put("tool", kotlinx.serialization.json.JsonPrimitive(tool))
    }.toString()
}

/** Coroutine-context flag set around tool execution in a scheduled run: consent prompts answer "no" instead of waiting. */
class HeadlessRun(val taskId: Long, val scheduleName: String = "") : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<HeadlessRun>
}

/** The small divider shown instead of a scheduled run's prompt (pure, unit-tested). */
object ScheduledLabel {
    /** True for the scheduler's prompt: the dedicated kind, or an older run's USER message that starts with the marker. */
    fun isScheduledPrompt(m: com.verdroid.app.domain.model.ChatMessage): Boolean =
        m.kind == com.verdroid.app.domain.model.MessageKind.SCHEDULED_PROMPT ||
            (m.role == com.verdroid.app.domain.model.MessageRole.USER && m.kind == com.verdroid.app.domain.model.MessageKind.NORMAL &&
                ScheduleSafety.isScheduledMessage(m.content))

    /** "⏰ Scheduled: CNN summary · 10:00" (local time of the run), or null when [m] isn't a scheduled prompt. No prompt text. */
    fun of(m: com.verdroid.app.domain.model.ChatMessage, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String? {
        if (!isScheduledPrompt(m)) return null
        val name = ScheduleSafety.scheduleName(m.content).orEmpty().ifBlank { "task" }.take(60)
        val t = java.time.Instant.ofEpochMilli(m.createdAt).atZone(zone)
        return "⏰ Scheduled: $name · %02d:%02d".format(t.hour, t.minute)
    }
}
