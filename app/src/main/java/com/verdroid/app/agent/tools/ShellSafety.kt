package com.verdroid.app.agent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Shell safety on top of the delete/move/overwrite confirmations ([FileChangeGuard], unchanged):
 * - **Taint:** once a tool that returns external content (web, X, logged-in pages, PDFs, other apps' screens, shared files,
 *   MCP) ran since the user's last message, the turn is "tainted": a prompt injection may be steering the model.
 * - **Risky commands** (network uploads, package/settings/system changes, …) then need the user's Yes; in an unattended
 *   scheduled run they are refused.
 * - **"Ask before shell commands"** (Settings → Tools → File safety, off by default) asks for every shell command.
 * Pure, JVM-tested.
 */
object ShellSafety {
    val SHELL_TOOLS = setOf("run_shell", "rish_run", "termux_run", "termux_python")

    private val UNTRUSTED_TOOLS = setOf(
        "web_fetch", "web_search", "cookie_open", "cookie_login", "x_search", "x_timeline", "x_user_posts",
        "selenium_open", "selenium_page_source", "pdf_extract_text", "pdf_info", "screen_read", "workspace_read",
    )

    /** Tools whose results carry content an attacker may control. */
    fun returnsUntrusted(tool: String): Boolean = tool in UNTRUSTED_TOOLS || tool.startsWith("mcp__")

    /** [toolsSinceUser] = names of the tool results after the user's latest message. */
    fun tainted(toolsSinceUser: List<String?>): Boolean = toolsSinceUser.any { it != null && returnsUntrusted(it) }

    private val SHELL_RISKY: List<Pair<Regex, String>> = listOf(
        Regex("""\bcurl\b[^;&|]*\s(-F|--form|-T|--upload-file|-d|--data(?:-binary|-raw|-urlencode)?|-X\s*(?:POST|PUT|PATCH))\b""") to "uploads data to the network (curl)",
        Regex("""\bwget\b[^;&|]*--(post-data|post-file|method=(?:POST|PUT))""") to "uploads data to the network (wget)",
        Regex("""(^|[\s;&|(])(nc|ncat|netcat|socat|scp|sftp|ftp|rsync\s+[^;&|]*::|rsync\s+[^;&|]*@)(\s|$)""") to "network transfer (nc/scp/sftp/rsync)",
        Regex("""(^|[\s;&|(])(pm|cmd\s+package)\s+(clear|uninstall|disable|disable-user|hide|suspend|grant|revoke|install|set-installer|reset-permissions)\b""") to "changes installed apps or permissions (pm)",
        Regex("""\bsettings\s+(put|delete|reset)\b""") to "changes system settings",
        Regex("""\bam\s+(force-stop|kill|kill-all|broadcast|start-foreground-service|start|startservice)\b""") to "starts/stops apps or sends broadcasts (am)",
        Regex("""(^|[\s;&|(])svc\s+(wifi|data|bluetooth|power|usb|nfc)\b""") to "toggles radios/power (svc)",
        Regex("""(^|[\s;&|(])(reboot|shutdown|poweroff)\b""") to "reboots or powers off the phone",
        Regex("""(^|[\s;&|(])wm\s+(size|density|overscan)\b""") to "changes the display (wm)",
        Regex("""\b(cmd\s+(?:connectivity|wifi|appops|device_policy|notification|statusbar)|appops\s+set|dpm\s+)""") to "changes system services (cmd/appops/dpm)",
        Regex("""\b(content\s+(insert|update|delete))\b""") to "writes to content providers (contacts, SMS, …)",
        Regex("""\b(ssh|git\s+push|termux-sms-send|termux-share|termux-clipboard-set)\b""") to "sends data out of the phone",
        Regex("""\b(base64)\b[^|]*\|\s*(curl|nc|sh|bash)\b""") to "pipes encoded data to the network or a shell",
        Regex("""(curl|wget)\b[^|;&]*\|\s*(sh|bash|python3?)\b""") to "runs a downloaded script",
        Regex("""(^|[\s;&|(])(perl|ruby|node|php)\s+-[eE]\b""") to "runs inline perl/ruby/node/php code",
        Regex("""(^|[\s;&|(])python3?\s+-c\b""") to "runs inline Python code",
    )

    private val PY_RISKY: List<Pair<Regex, String>> = listOf(
        Regex("""\brequests\.(post|put|patch)\s*\(|\bhttpx\.(post|put|patch)\s*\(|urlopen\s*\([^)]*data\s*=|\.sendall?\s*\(|\bsmtplib\b|\bftplib\b|\bparamiko\b""") to "uploads data to the network (Python)",
        Regex("""\b(os\.system|subprocess\.\w+|os\.popen|os\.exec\w*)\s*\(""") to "runs shell commands from Python",
    )

    /** Why [tool] with [args] is risky (empty = not risky). */
    fun risky(tool: String, args: JsonObject): List<String> {
        fun s(k: String) = (args[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when (tool) {
            "run_shell", "rish_run", "termux_run" -> s("command").let { c -> SHELL_RISKY.filter { it.first.containsMatchIn(c) }.map { it.second } }
            "termux_python" -> s("code").let { c -> PY_RISKY.filter { it.first.containsMatchIn(c) }.map { it.second } +
                SHELL_RISKY.filter { it.first.containsMatchIn(c) }.map { it.second } }.distinct()
            else -> emptyList()
        }
    }

    sealed interface Decision {
        data object Allow : Decision
        data class Ask(val reasons: List<String>) : Decision
        data class Block(val reason: String) : Decision
    }

    fun decide(tool: String, args: JsonObject, tainted: Boolean, askAlways: Boolean, unattended: Boolean): Decision {
        if (tool !in SHELL_TOOLS) return Decision.Allow
        val r = risky(tool, args)
        val taintNote = "this chat just read untrusted content (web, X, files, other apps or MCP), which may be steering the model"
        return when {
            unattended && tainted && r.isNotEmpty() -> Decision.Block("risky command after untrusted content: ${r.joinToString("; ")}")
            unattended -> Decision.Allow // unattended rules live in ScheduleSafety
            tainted && r.isNotEmpty() -> Decision.Ask(r + taintNote)
            askAlways -> Decision.Ask(r.ifEmpty { listOf("run a shell command") })
            else -> Decision.Allow
        }
    }

    fun deniedJson(tool: String, reasons: List<String>): String = kotlinx.serialization.json.buildJsonObject {
        put("error", JsonPrimitive("The user DENIED this command (${reasons.joinToString("; ").take(300)}). Nothing was run."))
        put("denied_by_user", JsonPrimitive(true))
        put("tool", JsonPrimitive(tool))
        put("instruction", JsonPrimitive("Respect the decision: do not retry it or run the same thing through another tool, script or command. " +
            "Tell the user what you didn't do and continue without it, or ask what they want instead."))
    }.toString()
}
