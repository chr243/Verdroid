package com.verdroid.app.agent.tools

import com.verdroid.app.data.cookies.BrowserCookies
import com.verdroid.app.data.cookies.CookieVault
import com.verdroid.app.data.storage.SharedFolder
import com.verdroid.app.data.termux.TermuxRunner
import kotlinx.serialization.json.*
import java.util.Base64

/** Asks the user before posting. true = Post, false = Cancel, null = no answer in time. */
fun interface XPostConfirmer {
    suspend fun confirm(taskId: Long, text: String, replyTo: String?): Boolean?
}

/** X length rules, approximated: URLs count 23, CJK/emoji (non-BMP or wide) count 2, everything else 1. Pure, unit-tested. */
object XText {
    const val LIMIT = 280
    private val URL = Regex("""https?://\S+""")

    fun weightedLength(text: String): Int {
        var n = 0
        val noUrls = URL.replace(text) { n += 23; "" }
        var i = 0
        while (i < noUrls.length) {
            val cp = noUrls.codePointAt(i)
            n += if (cp > 0xFFFF || cp in 0x1100..0x115F || cp in 0x2E80..0xA4CF || cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF || cp in 0xFF00..0xFF60) 2 else 1
            i += Character.charCount(cp)
        }
        return n
    }

    /** Helper output without a "posted" field (timeout, crash) after the click may have happened → posted=uncertain. */
    fun markUncertain(out: String): String {
        val o = runCatching { Json.parseToJsonElement(out).jsonObject }.getOrNull() ?: return out
        if ("posted" in o || o["error"]?.jsonPrimitive?.contentOrNull?.startsWith("NOT_LOGGED_IN") == true) return out
        return JsonObject(o + ("posted" to JsonPrimitive("uncertain")) +
            ("instruction" to JsonPrimitive("Unknown whether it was posted. Do NOT retry; ask the user to check X."))).toString()
    }

    fun replyUrl(u: String?): String? {
        val s = u?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val m = Regex("""^https?://(?:www\.|mobile\.)?(?:x|twitter)\.com/([A-Za-z0-9_]{1,15})/status/(\d+)""").find(s) ?: return ""
        return "https://x.com/${m.groupValues[1]}/status/${m.groupValues[2]}"
    }
}

class XPostTool(termux: TermuxRunner, folder: SharedFolder, private val vault: CookieVault,
                private val policy: XPostPolicy = XPostPolicy.None, private val clock: () -> Long = System::currentTimeMillis,
                private val confirmer: XPostConfirmer) :
    TermuxSeleniumBase(termux, folder) {
    override val name = "x_post"
    override val description = "Beta, READ-WRITE: publish ONE post (or a reply with reply_to_url) on X as the user. One post per call; never call it " +
        "repeatedly or in a loop unless the user explicitly asked for several. The user sees the exact text and must tap Post (unless they " +
        "allowed posting without asking). Max 280 characters (URLs count 23). Assume the X login is saved; only if the result says " +
        "NOT_LOGGED_IN call cookie_login once and retry. If the result says posted=uncertain, do NOT retry: ask the user to check X. Posts are limited " +
        "per hour/day while the user's \"Limit posts\" setting (Settings → Tools → X (Twitter)) is on (default); on RATE_LIMITED stop and tell the user."
    override val parameters = schema(listOf("text"),
        "text" to prop("string", "Exact post text"),
        "reply_to_url" to prop("string", "Optional x.com/<user>/status/<id> URL to reply to"))

    override suspend fun execute(args: JsonObject): String = execute(args, ToolContext(0L))

    override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
        val text = args.str("text")?.trim().orEmpty()
        if (text.isEmpty()) return errorJson("text is required")
        val len = XText.weightedLength(text)
        if (len > XText.LIMIT) return buildJsonObject {
            put("error", "Text is $len characters by X's count; the limit is ${XText.LIMIT}. Nothing was posted. Shorten it (don't cut it silently) or split it if the user wants a thread.")
            put("length", len)
        }.toString()
        val reply = XText.replyUrl(args.str("reply_to_url"))
        if (reply == "") return errorJson("reply_to_url must be an x.com/<user>/status/<id> link")
        val cookies = BrowserCookies.forX(vault.all())
        if (cookies.none { it.name == "auth_token" })
            return errorJson("NOT_LOGGED_IN: no X login saved (no auth_token). Call cookie_login once (the user logs in), then retry this same tool.")
        // Scheduled (unattended) run: only reached with Always allow; every log line says so, with the schedule's name.
        val run = kotlin.coroutines.coroutineContext[com.verdroid.app.domain.schedule.HeadlessRun]
        val sched = run?.let { "scheduled: ${it.scheduleName.ifBlank { "chat #${it.taskId}" }}" }
        fun audit(r: XPostAudit.Result, url: String? = null, note: String? = null) =
            runCatching { policy.audit(XPostAudit.Entry(clock(), r, text, reply, url, listOfNotNull(sched, note).joinToString("; ").ifEmpty { null })) }
        policy.blockReason(clock())?.let { why ->
            audit(XPostAudit.Result.BLOCKED, note = "rate cap")
            return buildJsonObject { put("error", why); put("rate_limited", true); put("posted", false) }.toString()
        }
        when (confirmer.confirm(ctx.taskId, text, reply)) {
            true -> {}
            false -> { audit(XPostAudit.Result.CANCELLED)
                return buildJsonObject { put("error", "The user CANCELLED this post. Nothing was posted; do not retry unless they ask."); put("cancelled_by_user", true) }.toString() }
            null -> { audit(XPostAudit.Result.NO_ANSWER)
                return errorJson("No answer to the Post / Cancel prompt within 5 min (open Verdroid to see it). Nothing was posted.") }
        }
        // Re-check: another post may have gone out while the dialog was open.
        policy.blockReason(clock())?.let { why ->
            audit(XPostAudit.Result.BLOCKED, note = "rate cap")
            return buildJsonObject { put("error", why); put("rate_limited", true); put("posted", false) }.toString()
        }
        policy.recordAttempt(clock())
        val (file, preamble) = cookiePreamble(cookies)
        val cli = listOf("xpost", reply ?: "https://x.com/compose/post", "--wait", "0", "--cookies", file,
            "--text-b64", Base64.getEncoder().encodeToString(text.toByteArray()))
        val out = BrowserCookies.scrub(runHelper(cli, 120, null, preamble), cookies)
        val result = XText.markUncertain(out)
        val (r, url) = XPostAudit.resultOf(result)
        audit(r, url)
        return result
    }
}
