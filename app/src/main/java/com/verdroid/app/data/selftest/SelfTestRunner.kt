package com.verdroid.app.data.selftest

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.verdroid.app.BuildConfig
import com.verdroid.app.agent.tools.AgentTool
import com.verdroid.app.agent.tools.ToolRegistry
import com.verdroid.app.data.update.AppUpdater
import com.verdroid.app.data.update.UpdateState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class SelfTestStatus { PASS, FAIL, SKIP }
data class SelfTestResult(val id: String, val status: SelfTestStatus, val detail: String, val ms: Long)

/** Thrown inside a check to report SKIP (missing app/permission) instead of FAIL. */
class SkipCheck(reason: String) : Exception(reason)

/**
 * On-device self-test: safe, non-destructive, short-timeout checks per feature ([SelfTestCoverage.CHECKS]).
 * Never installs anything, never trades, never sends chat/model requests (only lists models).
 */
@Singleton
class SelfTestRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: ToolRegistry,
    private val settings: com.verdroid.app.domain.repository.SettingsRepository,
    private val agentsMd: com.verdroid.app.data.instructions.AgentsMdStore,
    private val memory: com.verdroid.app.data.memory.MemoryRepository,
    private val shared: com.verdroid.app.data.storage.SharedFolder,
    private val termux: com.verdroid.app.data.termux.TermuxManager,
    private val updater: AppUpdater,
    private val customLlm: com.verdroid.app.data.network.CustomLlmClient,
    private val customStore: com.verdroid.app.data.network.CustomProviderStore,
    private val cookieVault: com.verdroid.app.data.cookies.CookieVault,
    private val schedules: javax.inject.Provider<com.verdroid.app.data.schedule.ScheduleManager>,
) {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private fun tool(name: String): AgentTool = registry.tools.firstOrNull { it.name == name } ?: throw IllegalStateException("$name not registered")
    private suspend fun run(name: String, args: JsonObject): JsonObject {
        val out = tool(name).execute(args)
        val o = Json.parseToJsonElement(out).jsonObject
        o["error"]?.jsonPrimitive?.contentOrNull?.let { throw IllegalStateException(it.take(200)) }
        return o
    }
    private fun args(vararg p: Pair<String, Any>) = buildJsonObject {
        p.forEach { (k, v) -> when (v) { is Number -> put(k, v); is Boolean -> put(k, v); else -> put(k, v.toString()) } }
    }
    private fun get(url: String): Int = http.newCall(Request.Builder().url(url).header("User-Agent", "Verdroid-selftest").build())
        .execute().use { it.code }
    private fun requireTermux() {
        if (!termux.isInstalled()) throw SkipCheck("Termux not installed")
        if (!termux.hasRunCommandPermission()) throw SkipCheck("RUN_COMMAND permission not granted")
    }
    private suspend fun termuxEcho(cmd: String, timeout: Long = 15_000): String {
        requireTermux()
        val r = termux.runAndWait(cmd, "selftest-${System.nanoTime()}", timeout, "Verdroid self-test")
            ?: throw IllegalStateException("no result from Termux within ${timeout / 1000}s (open Termux once?)")
        return r.toString()
    }

    private val checks: Map<String, suspend () -> String> = mapOf(
        "tool_registry" to {
            val tools = registry.tools
            tools.filter { it.name != "self_test" }.forEach { it.name; it.description; it.parameters } // touches every tool class (R8/reflection); self_test excluded (recursion)
            val missing = SelfTestCoverage.uncovered(tools.map { it.name })
            if (missing.isNotEmpty()) throw IllegalStateException("no self-test for: $missing")
            "${tools.size} tools instantiated"
        },
        "json" to {
            registry.tools.forEach { t ->
                val s = t.parameters.toString()
                check(Json.parseToJsonElement(s) == t.parameters) { "schema roundtrip mismatch: ${t.name}" }
            }
            val probe = buildJsonObject { put("a", 1); putJsonArray("b") { add("x") } }
            check(Json.parseToJsonElement(probe.toString()) == probe)
            "${registry.tools.size} schemas roundtrip"
        },
        "room" to {
            val r = memory.save("verdroid self-test probe ${System.currentTimeMillis()}", listOf("selftest"), null)
            val id = memory.all().firstOrNull { it.text.startsWith("verdroid self-test probe") }?.id
                ?: throw IllegalStateException("temp row not readable ($r)")
            check(memory.delete(id)) { "temp row delete failed" }
            "insert/read/delete ok"
        },
        "settings" to {
            val gs = com.verdroid.app.data.tools.ToolPreset.globalState(listOf("git_status", "web_search", "web_fetch"), setOf("git_status", "web_fetch"))
            check(gs.getValue(com.verdroid.app.data.tools.ToolPreset.GIT).offInSettings && com.verdroid.app.data.tools.ToolPreset.shownOn(com.verdroid.app.data.tools.ToolPreset.WEB, emptyMap(), gs) && !com.verdroid.app.data.tools.ToolPreset.shownOn(com.verdroid.app.data.tools.ToolPreset.GIT, emptyMap(), gs)) { "presets must reflect Settings → Tools" }
            check(com.verdroid.app.data.tools.ToolPreset.entries.all { p -> com.verdroid.app.data.tools.ToolGroups.ORDER.any { it.id == p.groupId && it.description == p.description } }) { "tool group descriptions out of sync" }
            check(!com.verdroid.app.data.network.ModelIds.openRouterUsable(listOf("openrouter/free"), 0)) { "no-key OpenRouter must be skipped" }
            check(com.verdroid.app.domain.model.DefaultModels.list.any(com.verdroid.app.data.network.ModelIds::isKilo)) { "defaults need a keyless kilo fallback" }
            "models=${settings.currentModels().size}, limits ok (${settings.limits.first().javaClass.simpleName}); no-key → kilo" },
        "agents_md" to { "${agentsMd.text.value.length} chars" },
        "memory" to { run("memory_search", args("query" to "selftest")); "${memory.all().size} memories, search ok" },
        "skills" to { run("skill_list", args()); "skill_list ok" },
        "sandbox" to {
            val p = ".selftest/probe.txt"
            run("write_file", args("path" to p, "content" to "ok"))
            val read = run("read_file", args("path" to p)).toString()
            check("ok" in read) { "read back mismatch" }
            run("list_dir", args("path" to ".selftest"))
            File(context.filesDir, "workspace/.selftest").deleteRecursively()
            "write/read/list ok"
        },
        "shared_folder" to {
            if (!shared.hasAccess()) throw SkipCheck("no All-files access")
            check(shared.ensure()) { "can't create Documents/Verdroid" }
            run("workspace_list", args("path" to "Input"))
            val p = "Output/.selftest-probe.txt"
            run("workspace_write", args("path" to p, "content" to "ok"))
            run("workspace_read", args("path" to p))
            File(shared.root, p).delete()
            "Input listable, Output write/read ok"
        },
        "network" to { "github.com HTTP ${get("https://github.com")}" },
        "providers" to {
            val or = get("https://openrouter.ai/api/v1/models")
            val kilo = runCatching { get("https://api.kilo.ai/api/gateway/models") }.getOrElse { -1 }
            check(or in 200..299) { "OpenRouter models HTTP $or" }
            val configured = com.verdroid.app.data.network.ProviderPresets.ALL.filter { customStore.isConfigured(it.id) }
            val custom = if (configured.isEmpty()) "no other providers set up" else buildList {
                for (p in configured) add(customLlm.listModels(p.id).fold({ "${p.shortName}: ${it.size} models" }, { e ->
                    // A local server (Ollama/LM Studio/LAN) that is simply off is skipped, not failed.
                    val base = customStore.baseFor(p.id)
                    if (com.verdroid.app.data.network.ProviderProbe.classify(base, e) == com.verdroid.app.data.network.ProviderProbe.Verdict.SKIP)
                        "skipped: " + com.verdroid.app.data.network.ProviderProbe.skipNote(p.shortName, base)
                    else throw IllegalStateException("${p.shortName}: ${e.message}")
                }))
            }.joinToString(", ")
            "OpenRouter models $or, Kilo models $kilo, $custom"
        },
        "web_fetch" to { run("web_fetch", args("url" to "https://example.com", "format" to "markdown")); "example.com fetched" },
        "web_search" to { run("web_search", args("query" to "android", "max_results" to 3)); "search ok" },
        "termux" to { termuxEcho("echo verdroid-selftest").let { check("verdroid-selftest" in it) { it.take(160) } }; "echo roundtrip ok" },
        "python" to {
            val out = termuxEcho("python -c 'import fitz; print(\"fitz\", fitz.__doc__)' 2>&1 || python -c 'import pypdf; print(\"pypdf\")' 2>&1 || echo nopython")
            when {
                "fitz" in out -> "Python + PyMuPDF present"
                "pypdf" in out -> "Python + pypdf (no PyMuPDF)"
                else -> throw SkipCheck("Python/PDF libs not installed (no install by self-test)")
            }
        },
        "pdf" to {
            requireTermux()
            if (!shared.hasAccess()) throw SkipCheck("no All-files access")
            shared.ensure()
            val f = File(shared.output, ".selftest.pdf"); f.writeBytes(TINY_PDF)
            try {
                val out = tool("pdf_info").execute(args("path" to "Output/.selftest.pdf"))
                when {
                    "confirm_install" in out || "install_id" in out -> throw SkipCheck("PDF deps not installed (consent required)")
                    "\"error\"" in out -> throw IllegalStateException(out.take(200))
                    else -> "pdf_info ok on 1-page PDF"
                }
            } finally { f.delete() }
        },
        "shizuku" to {
            val o = Json.parseToJsonElement(tool("run_shell").execute(args("command" to "id", "timeout_seconds" to 8))).jsonObject
            val err = o["error"]?.jsonPrimitive?.contentOrNull
            if (err != null) throw SkipCheck("Shizuku unavailable: ${err.take(120)}")
            "id: ${o["stdout"]?.jsonPrimitive?.contentOrNull?.take(60) ?: o.toString().take(60)}"
        },
        "rish" to {
            val o = Json.parseToJsonElement(tool("rish_run").execute(args("command" to "id", "timeout_seconds" to 8))).jsonObject
            val err = o["error"]?.jsonPrimitive?.contentOrNull
            if (err != null) throw SkipCheck("rish unavailable: ${err.take(120)}")
            "rish id ok"
        },
        "accessibility" to {
            if (!com.verdroid.app.data.a11y.VerdroidAccessibilityService.isRunning) throw SkipCheck("Accessibility service not enabled")
            "service running"
        },
        "font_scale" to {
            val store = com.verdroid.app.ui.theme.ThemeStore.get(context)
            val v = store.fontScale.value
            check(v in com.verdroid.app.ui.theme.FontScale.MIN..com.verdroid.app.ui.theme.FontScale.MAX) { "stored scale $v out of range" }
            check(com.verdroid.app.ui.theme.FontScale.clamp(5f) == com.verdroid.app.ui.theme.FontScale.MAX) { "clamp broken" }
            "font size ${com.verdroid.app.ui.theme.FontScale.label(v)}"
        },
        "internal_folder" to {
            if (!shared.hasAccess()) throw SkipCheck("All files access not granted")
            check(shared.ensure()) { "could not create Documents/Verdroid/Internal" }
            val probe = java.io.File(shared.internal, ".selftest-probe")
            probe.writeText("ok"); check(probe.readText() == "ok") { "read back mismatch" }; probe.delete()
            "Internal/ exists and is writable"
        },
        "feedback" to {
            val info = com.verdroid.app.data.feedback.FeedbackLinks.current(context)
            val url = com.verdroid.app.data.feedback.FeedbackLinks.CHOOSER
            check(url == "https://github.com/chr243/Verdroid/issues/new/choose") { url }
            check(info.startsWith("Verdroid ")) { info }
            val star = com.verdroid.app.data.feedback.StarPromptStore.get(context)
            "issue links ok; chats created ${star.created}, star prompt ${if (star.done) "done" else "not shown yet"}"
        },
        "overlay" to { if (Settings.canDrawOverlays(context)) "overlay allowed" else throw SkipCheck("overlay permission not granted") },
        "git" to {
            val dir = File(context.cacheDir, "selftest-git-${System.nanoTime()}")
            try {
                org.eclipse.jgit.api.Git.init().setDirectory(dir).call().use { g -> g.status().call() }
                "JGit init + status ok"
            } finally { dir.deleteRecursively() }
        },
        "cookies" to {
            // Pure checks first (no stored cookies are read or changed), then: is the Selenium add-on there?
            val sample = "#HttpOnly_.example.com\tTRUE\t/\tTRUE\t0\tsid\tsecretvalue123\n"
            val parsed = com.verdroid.app.data.cookies.BrowserCookies.parse(sample)
            check(parsed.size == 1 && parsed[0].httpOnly && parsed[0].host == "example.com") { "cookies.txt parse" }
            check("secretvalue123" !in com.verdroid.app.data.cookies.BrowserCookies.summary(parsed).toString()) { "redaction" }
            check("--cookies" in com.verdroid.app.data.termux.VerdroidSeleniumPy.SOURCE) { "helper lacks --cookies" }
            requireTermux()
            val out = termuxEcho("python -c 'import selenium; print(\"selenium-ok\")' 2>&1 || echo noselenium")
            if ("selenium-ok" !in out) throw SkipCheck("chromium-selenium add-on not installed")
            "parse/redaction ok, Selenium present"
        },
        "model_pins" to {
            val g = listOf("a/one:free", "kilo:two")
            val m = com.verdroid.app.data.settings.ModelPins
            check(m.resolve(g, null) == g) { "inherit" }
            check(m.resolve(g, com.verdroid.app.data.settings.ModelPin("paid/x")) == listOf("paid/x")) { "pinned = only the pin" }
            check(m.resolve(g, com.verdroid.app.data.settings.ModelPin("kilo:two", true)) == listOf("kilo:two", "a/one:free")) { "fallback" }
            check(m.decode(m.encode(com.verdroid.app.data.settings.ModelPin("prov:ollama:llama3", true))) == com.verdroid.app.data.settings.ModelPin("prov:ollama:llama3", true)) { "persist" }
            check(com.verdroid.app.data.network.PinnedOutcome.map(com.verdroid.app.data.settings.ModelPin("paid/x"), "paid/x",
                com.verdroid.app.data.network.ChatOutcome.Failure("HTTP 500")).let { it is com.verdroid.app.data.network.ChatOutcome.Failure && "pinned" in it.message }) { "pinned failure message" }
            "inherit / pinned / fallback resolution ok"
        },
        "crash_log" to {
            check(Thread.getDefaultUncaughtExceptionHandler() != null) { "no crash handler installed" }
            val dirs = com.verdroid.app.data.crash.CrashLog.dirs(shared.internal.takeIf { shared.hasAccess() }, context.filesDir)
            val probe = java.io.File(dirs.first().apply { mkdirs() }, ".selftest")
            check(runCatching { probe.writeText("ok"); probe.delete() }.isSuccess) { "crash folder not writable: ${dirs.first()}" }
            "handler installed; crash logs go to ${dirs.first()}" +
                (com.verdroid.app.data.crash.CrashLog.latest(dirs)?.let { "; latest: ${it.name}" } ?: "; no crashes recorded")
        },
        "twitter_tos" to {
            check(com.verdroid.app.data.tools.ToolGroups.needsTosWarning("twitter", false, true) &&
                !com.verdroid.app.data.tools.ToolGroups.needsTosWarning("twitter", true, true)) { "ToS warning rule" }
            check("Terms of Service" in com.verdroid.app.data.tools.ToolGroups.ORDER.first { it.id == "twitter" }.description) { "group description" }
            "warning shown when turning Twitter on"
        },
        "overwrite_policy" to {
            val dir = java.io.File(context.cacheDir, "st-overwrite-${System.nanoTime()}").apply { mkdirs() }
            try {
                java.io.File(dir, "Output").mkdirs(); java.io.File(dir, "Output/a.txt").writeText("x")
                var asked = 0
                val g = com.verdroid.app.agent.tools.FileChangeGuard({ asked++; false }, shared = dir, overwritePolicy = { true })
                val w = g.check(1, "workspace_write", args("path" to "Output/a.txt", "content" to "y"))
                check(w == null && asked == 0) { "overwrite should skip the prompt when allowed" }
                val d = g.check(1, "workspace_delete", args("path" to "Output/a.txt"))
                check(d != null && asked == 1) { "delete must still ask" }
            } finally { dir.deleteRecursively() }
            "overwrite allowed without prompt; delete still asks"
        },
        "x_post_guard" to {
            // Never posts: length rules, reply URL check, and that a Cancel stops before any browser runs.
            check(com.verdroid.app.agent.tools.XText.weightedLength("hi https://example.com/very/long/link") == 26) { "url weighting" }
            check(com.verdroid.app.agent.tools.XText.replyUrl("https://twitter.com/a_b/status/12?s=20") == "https://x.com/a_b/status/12") { "reply url" }
            var ran = false
            val fake = object : com.verdroid.app.data.termux.TermuxRunner {
                override fun isInstalled() = true
                override fun hasRunCommandPermission() = true
                override suspend fun runAndWait(command: String, tag: String, timeoutMs: Long, label: String): com.verdroid.app.data.termux.TermuxResult? { ran = true; return null }
            }
            val vault = com.verdroid.app.data.cookies.CookieVault.InMemory(listOf(com.verdroid.app.data.cookies.BrowserCookie("auth_token", "selftest", ".x.com")))
            val r = com.verdroid.app.agent.tools.XPostTool(fake, shared, vault) { _, _, _ -> false }.execute(args("text" to "self-test"))
            check("CANCELLED" in r && !ran) { "cancel must stop before posting" }
            "length/reply checks ok; Cancel blocks posting (nothing posted)"
        },
        "x_posts" to {
            val sample = kotlinx.serialization.json.Json.parseToJsonElement(
                "[{\"name\":\"N\",\"handle\":\"@h\",\"text\":\"hi\",\"rel\":\"1h\",\"url\":\"https://x.com/h/status/1\",\"likes\":\"3 Likes. Like\"}]")
            val line = com.verdroid.app.data.cookies.XPosts.format(com.verdroid.app.data.cookies.XPosts.parse(sample as kotlinx.serialization.json.JsonArray))
            check(line.startsWith("Post 1: @h (N) - hi - 1h ago")) { line }
            check("xposts" in com.verdroid.app.data.termux.VerdroidSeleniumPy.SOURCE) { "helper lacks xposts" }
            if (com.verdroid.app.data.cookies.BrowserCookies.forX(cookieVault.all()).none { it.name == "auth_token" }) throw SkipCheck("no X login saved (cookie_login)")
            requireTermux()
            val out = termuxEcho("python -c 'import selenium; print(\"selenium-ok\")' 2>&1 || echo noselenium")
            if ("selenium-ok" !in out) throw SkipCheck("chromium-selenium add-on not installed")
            "formatter ok, X login saved, Selenium present"
        },
        "cookie_webview" to {
            check(com.verdroid.app.data.cookies.BrowserCookies.fromCookieHeader("x.com", "a=1; b=2").size == 2) { "cookie header parse" }
            val pkg = runCatching { android.webkit.WebView.getCurrentWebViewPackage() }.getOrNull()
                ?: throw IllegalStateException("No WebView provider installed/enabled")
            runCatching { android.webkit.CookieManager.getInstance() }.getOrElse { throw IllegalStateException("CookieManager unavailable: ${it.javaClass.simpleName}") }
            "WebView ${pkg.packageName} ${pkg.versionName}"
        },
        "share_target" to {
            val pm = context.packageManager
            fun handles(action: String, mime: String) = pm.queryIntentActivities(Intent(action).setType(mime), 0)
                .any { it.activityInfo.packageName == context.packageName }
            check(handles(Intent.ACTION_SEND, "text/plain")) { "text/plain share not registered" }
            check(handles(Intent.ACTION_SEND_MULTIPLE, "application/pdf")) { "PDF share not registered" }
            check(!handles(Intent.ACTION_SEND, "image/jpeg")) { "images must not be a share target" }
            val ai = pm.getActivityInfo(android.content.ComponentName(context, com.verdroid.app.ui.share.ShareActivity::class.java), PackageManager.GET_META_DATA)
            check(ai.metaData?.containsKey("android.service.chooser.chooser_target_service") != true) { "unexpected Direct Share" }
            val launcher = pm.getActivityInfo(android.content.ComponentName(context, com.verdroid.app.MainActivity::class.java), PackageManager.GET_META_DATA)
            check(launcher.metaData?.containsKey("android.app.shortcuts") != true) { "Direct Share shortcuts must stay off (shares always start a new chat)" }
            val msg = com.verdroid.app.domain.share.ShareLogic.message(listOf(com.verdroid.app.domain.model.AttachmentText.prefix("Input/a.pdf", 1)), "Summarize")
            check(com.verdroid.app.domain.model.AttachmentText.forDisplay(msg).text == "Summarize") { "attachment line leaks into the UI" }
            check(com.verdroid.app.domain.share.ShareLogic.sharedText("Link:\nhttps://a.b/c\n\n86\nTitle", null) == "https://a.b/c") { "shared link keeps extra text (title)" }
            val om = com.verdroid.app.domain.share.ShareLogic
            val dr = com.verdroid.app.domain.share.ShareDraft.initial("https://x.com/abc", false)
            check(dr.text == "Who is this person?" && dr.outgoing == "Who is this person? https://x.com/abc") { "shared link must stay out of the message field and be appended on Send" }
            val scoped = om.withScope("Summarize this page.\n\nhttps://www.example.com/a", "https://www.example.com/a")
            check("site:example.com" in scoped && com.verdroid.app.domain.model.AttachmentText.forDisplay(scoped).text == "Summarize this page.\n\nhttps://www.example.com/a") { "share scope note missing or visible" }
            check(!om.shareUsesBubble(false) && om.shareUsesBubble(true)) { "share must use Bubble mode, Full app without overlay permission" }
            check(!om.restoreShareHead(0, false, true) && om.restoreShareHead(1, false, true)) { "share head must only return with an unread/running reply" }
            "text + documents registered, images not, no Direct Share (new chat only); attachment line hidden; links shared without title; " +
                "opens in ${if (om.shareUsesBubble(android.provider.Settings.canDrawOverlays(context))) "Bubble" else "Full app"}"
        },
        "chat_ui" to {
            fun m(id: Long, role: com.verdroid.app.domain.model.MessageRole, c: String?, calls: String? = null) = com.verdroid.app.domain.model.ChatMessage(
                id, 1, role, c, com.verdroid.app.domain.model.MessageKind.NORMAL, calls, null, null, null, false, null, id)
            val a = com.verdroid.app.domain.model.MessageRole.ASSISTANT
            val rec = { id: Long, mid: Long -> com.verdroid.app.domain.model.ToolCallRecord(id, 1, mid, "c$id", "web_fetch", "{}", "{}",
                com.verdroid.app.domain.model.ToolCallStatus.SUCCESS, "native", id, id) }
            val msgs = listOf(m(1, a, null, "[x]"), m(2, a, null, "[x]"), m(3, a, "Done"))
            val rows = com.verdroid.app.ui.chat.ToolStacks.rows(msgs, mapOf(1L to listOf(rec(1, 1)), 2L to listOf(rec(2, 2))))
            val blocks = com.verdroid.app.ui.chat.ToolStacks.blocks(rows, false)
            check(blocks.size == 2 && (blocks[0] as? com.verdroid.app.ui.chat.ToolStacks.Steps)?.stepCount == 2) { "tool steps must collapse into one row" }
            check(msgs.count(com.verdroid.app.domain.model.UnreadPolicy::isUserFacing) == 1) { "unread must count only user-facing replies" }
            "tool steps grouped; unread counts replies only"
        },
        "file_guard" to {
            val D = com.verdroid.app.agent.tools.DestructiveDetector
            check(D.shell("rm -rf /sdcard/Download/x").isNotEmpty()) { "rm not detected" }
            check(D.shell("ls -la && echo hi > out.txt").isEmpty()) { "harmless command flagged" }
            check(D.python("import os\nos.remove('/sdcard/a.txt')").isNotEmpty()) { "os.remove not detected" }
            var asked = false
            val g = com.verdroid.app.agent.tools.FileChangeGuard({ asked = true; false })
            val denied = g.check(0, "termux_run", buildJsonObject { put("command", "mv a b") })
            check(asked && denied != null && "denied_by_user" in denied) { "deny path broken" }
            "delete/move/overwrite detection and Deny path ok"
        },
        "backtest_python" to {
            requireTermux()
            val r = termux.runAndWait(com.verdroid.app.agent.tools.CryptoHistoryTools.PREFLIGHT, "st-btdeps-${System.nanoTime()}", 25_000, label = "Verdroid self-test")
                ?: throw IllegalStateException("Termux did not answer")
            if (!com.verdroid.app.agent.tools.CryptoHistoryTools.preflightOk(r.stdout))
                throw SkipCheck("pandas/numpy/matplotlib not installed (Settings → Tools → crypto-backtest)")
            "pandas, numpy, matplotlib importable"
        },
        "crypto_history" to {
            val d = run("crypto_history_download", args("symbol" to "BTC-USDT", "interval" to "1d", "from" to java.time.LocalDate.now().minusDays(60).toString()))
            check(d["ok"]?.jsonPrimitive?.booleanOrNull == true) { d.toString().take(200) }
            val b = run("crypto_backtest", args("symbol" to "BTC-USDT", "interval" to "1d", "from" to java.time.LocalDate.now().minusDays(60).toString(), "strategy" to "sma_cross", "fast" to 5, "slow" to 20))
            check("total_return_pct" in b) { b.toString().take(200) }
            run("crypto_history_list", args())
            check(com.verdroid.app.agent.tools.CryptoHistoryTools.harnessSource().contains("generate_signals")) { "backtest harness missing from the app" }
            "60d BTC-USDT cached + offline backtest ok; Python harness bundled"
        },
        "updater" to {
            if (!updater.enabled.value) throw SkipCheck("GitHub update checks are off (Settings → App)")
            when (val st = updater.check(silent = true)) {
                is UpdateState.Available -> "latest v${st.release.version} (newer), asset=${st.release.asset?.name}"
                is UpdateState.UpToDate -> "up to date (v${st.latest})"
                is UpdateState.Error -> throw IllegalStateException(st.message)
                else -> st.toString()
            }
        },
        "oom_hardening" to {
            val f = File(context.cacheDir, "selftest-big.bin")
            try {
                f.outputStream().use { o -> val b = ByteArray(64 * 1024); repeat(32) { o.write(b) } } // 2 MB
                check(com.verdroid.app.agent.tools.ReadFileTool.readHead(f, 1000).size == 1000) { "read_file must stop at max_bytes" }
            } finally { f.delete() }
            val W = com.verdroid.app.domain.model.ChatWindow
            check(W.cut("x".repeat(W.CAP + 10))!!.length < W.CAP + 200 && W.cut("short") == "short") { "chat window cut" }
            val ctx = com.verdroid.app.data.crash.CrashBreadcrumbs.section(com.verdroid.app.data.crash.CrashLog.memory(full = true))
            check("Java heap" in ctx && "Last actions" in ctx && "Running tasks" in ctx) { "crash context incomplete" }
            check(com.verdroid.app.data.crash.CrashBreadcrumbs.hasReserve()) { "crash memory reserve missing" }
            "read_file bounded, chat window ${W.PAGE} msgs/${W.CAP} chars, crash context ok"
        },
        "install_prompt" to {
            val pkgs = listOf(com.verdroid.app.agent.tools.PackageSpec("pip", "selftest-pkg"))
            check(com.verdroid.app.agent.tools.InstallConsent.confirmer != null) { "no install dialog registered" }
            val allow = com.verdroid.app.agent.tools.InstallConsent.resolve(args(), "t", pkgs, "t", "r") { _, _, _, _ -> true }
            val deny = com.verdroid.app.agent.tools.InstallConsent.resolve(args(), "t", pkgs, "t", "r") { _, _, _, _ -> false }
            val late = com.verdroid.app.agent.tools.InstallConsent.resolve(args(), "t", pkgs, "t", "r") { _, _, _, _ -> null }
            check(allow == com.verdroid.app.agent.tools.InstallConsent.Decision.Allow && deny == com.verdroid.app.agent.tools.InstallConsent.Decision.Deny &&
                late == com.verdroid.app.agent.tools.InstallConsent.Decision.Ask) { "install gate decisions" }
            "Install / Don't install dialog registered; no answer falls back to ask-in-chat (nothing installed)"
        },
        "x_post_limits" to {
            val L = com.verdroid.app.agent.tools.XPostLimits
            val now = System.currentTimeMillis()
            check(L.blockReason(List(5) { now - it * 60_000L }, now, 5, 20)?.startsWith("RATE_LIMITED") == true) { "hourly cap" }
            check(L.blockReason(listOf(now - 2 * L.HOUR), now, 5, 20) == null) { "old posts must not count per hour" }
            check(L.blockReason(List(30) { now - it * 1_000L }, now, 5, 20, enabled = false) == null) { "\"Limit X posts\" off must never block" }
            val line = com.verdroid.app.agent.tools.XPostAudit.line(com.verdroid.app.agent.tools.XPostAudit.Entry(now,
                com.verdroid.app.agent.tools.XPostAudit.Result.CANCELLED, "a\nb", null, null))
            check("cancelled" in line && "a\\nb" in line && line.count { it == '\n' } == 1) { "audit line" }
            val vault = com.verdroid.app.data.cookies.CookieVault.InMemory(listOf(com.verdroid.app.data.cookies.BrowserCookie("auth_token", "selftest", ".x.com")))
            var asked = false
            val blocked = object : com.verdroid.app.agent.tools.XPostPolicy {
                override fun blockReason(now: Long) = "RATE_LIMITED: selftest"
                override fun recordAttempt(now: Long) {}
                override fun audit(entry: com.verdroid.app.agent.tools.XPostAudit.Entry) {}
            }
            val noTermux = object : com.verdroid.app.data.termux.TermuxRunner {
                override fun isInstalled() = true
                override fun hasRunCommandPermission() = true
                override suspend fun runAndWait(command: String, tag: String, timeoutMs: Long, label: String): com.verdroid.app.data.termux.TermuxResult? = throw IllegalStateException("must not run")
            }
            val r = com.verdroid.app.agent.tools.XPostTool(noTermux, shared, vault, blocked) { _, _, _ -> asked = true; true }.execute(args("text" to "self-test"))
            check("RATE_LIMITED" in r && !asked) { "cap must block before the dialog and the browser" }
            "limit (switch default on) blocks before posting, off = no limit; audit log at Internal/${com.verdroid.app.agent.tools.XPostAudit.FILE}"
        },
        "ebook_resume" to {
            val src = com.verdroid.app.data.ebook.EbookTranslatePy.SOURCE
            check("def append_part" in src && "def resume_parts" in src && "LANG_RETRY_FAILED" in src) { "ebook sidecar/retry fixes missing" }
            "append-only parts file + language-check retry bundled"
        },
        "update_hint" to {
            val U = com.verdroid.app.data.update.UpdateLogic
            check(U.showHint(true, "99.0.0", "1.0.0", true) && !U.showHint(false, "99.0.0", "1.0.0", true) &&
                !U.showHint(true, "99.0.0", "1.0.0", false) && !U.showHint(true, "1.0.0", "1.0.0", true)) { "hint visibility" }
            "hint " + (if (U.showHint(updater.enabled.value, updater.latestCached(), updater.currentVersion, true)) "shown (newer release cached)" else "hidden")
        },
        "chat_links" to {
            val f = com.verdroid.app.ui.components.Links.find("see https://x.com/a_b/status/1). and (https://en.wikipedia.org/wiki/Foo_(bar)), ok")
            check(f.map { it.url } == listOf("https://x.com/a_b/status/1", "https://en.wikipedia.org/wiki/Foo_(bar)")) { "url detection: ${f.map { it.url }}" }
            check(com.verdroid.app.ui.components.Links.MARKDOWN.find("[docs](https://example.com/a)")?.groupValues?.get(2) == "https://example.com/a") { "markdown link" }
            "bare URLs (punctuation trimmed) and [label](url) detected"
        },
        "assistant" to {
            val pm = context.packageManager
            fun handles(action: String) = pm.queryIntentActivities(Intent(action), PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.packageName == context.packageName && it.activityInfo.name == com.verdroid.app.ui.assist.AssistActivity::class.java.name }
            check(handles(Intent.ACTION_ASSIST)) { "ASSIST not registered (won't appear under Digital assistant app)" }
            check(handles(Intent.ACTION_VOICE_COMMAND)) { "VOICE_COMMAND not registered" }
            val ai = pm.getActivityInfo(android.content.ComponentName(context, com.verdroid.app.ui.assist.AssistActivity::class.java), 0)
            check(ai.taskAffinity.isNullOrEmpty() && ai.launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE) { "assist card must run in its own task (else the app opens behind it)" }
            val om = com.verdroid.app.domain.assist.AssistLogic
            check(om.route(false) == com.verdroid.app.domain.assist.AssistLogic.Route.MAIN_APP && om.route(true) == com.verdroid.app.domain.assist.AssistLogic.Route.CHAT_HEAD && om.prompt(" ") == null) { "assist routing" }
            val rm = context.getSystemService(android.app.role.RoleManager::class.java)
            val held = runCatching { rm.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)
            "ASSIST + VOICE_COMMAND registered; default assistant: ${if (held) "Verdroid" else "not set"}; overlay ${if (android.provider.Settings.canDrawOverlays(context)) "→ chat head" else "missing → main app"}"
        },
        "scheduling" to {
            val paris = java.time.ZoneId.of("Europe/Paris")
            fun at(t: String) = java.time.ZonedDateTime.parse(t).toInstant().toEpochMilli()
            val m = com.verdroid.app.domain.schedule.ScheduleMath
            check(m.nextRun(com.verdroid.app.domain.schedule.ScheduleSpec.Daily(10, 0), at("2026-10-17T09:00+02:00[Europe/Paris]"), paris) == at("2026-10-17T10:00+02:00[Europe/Paris]")) { "daily: today 10:00" }
            check(m.nextRun(com.verdroid.app.domain.schedule.ScheduleSpec.Daily(2, 30), at("2026-03-29T00:00+01:00[Europe/Paris]"), paris) == at("2026-03-29T03:30+02:00[Europe/Paris]")) { "DST gap" }
            check(m.nextRun(com.verdroid.app.domain.schedule.ScheduleSpec.Interval(15), 1_000_000L, paris, last = 0L) == 1_800_000L) { "interval cadence" }
            check(m.parseTimeOfDay("10am") == (10 to 0) && m.parseTimeOfDay("7:30 pm") == (19 to 30)) { "time parsing" }
            val safety = com.verdroid.app.domain.schedule.ScheduleSafety
            val prompt = com.verdroid.app.data.schedule.ScheduledPrompt.build("t", "check", true, "now")
            check(safety.isScheduledMessage(prompt) && safety.blockReason("x_post", buildJsonObject {}) != null &&
                safety.blockReason("web_fetch", buildJsonObject {}) == null) { "safety rules" }
            // The real enforcement path: a headless chat can't call a blocked tool.
            val probe = object : AgentTool { override val name = "x_post"; override val description = ""; override val parameters = buildJsonObject {}
                override suspend fun execute(args: JsonObject) = "{\"ok\":true}" }
            val r = ToolRegistry(listOf(probe), headless = { "self-test" }).execute("x_post", "{}", 1L)
            check(r.isError && "BLOCKED_IN_SCHEDULED_RUN" in r.json && "Always allow" in r.json) { "x_post must be blocked in scheduled runs without Always allow" }
            check(!ToolRegistry(listOf(probe), headless = { "self-test" }, alwaysAllowed = { it == "x_post" }).execute("x_post", "{}", 1L).isError) { "x_post with Always allow must run in scheduled runs" }
            check(safety.blockReason("x_timeline", buildJsonObject {}) == null) { "read-only X tools must work unattended" }
            check(safety.scheduleName(prompt) == "t") { "schedule name for the x_post log" }
            val sm = com.verdroid.app.domain.model.ChatMessage(0, 1, com.verdroid.app.domain.model.MessageRole.USER, prompt,
                com.verdroid.app.domain.model.MessageKind.SCHEDULED_PROMPT, null, null, null, null, false, null, System.currentTimeMillis())
            check(com.verdroid.app.domain.schedule.ScheduledLabel.of(sm)?.startsWith("⏰ Scheduled: t · ") == true) { "scheduled prompt divider" }
            run {
                val G = com.verdroid.app.domain.schedule.ScheduleRunGate
                val n = System.currentTimeMillis()
                check(G.decide(false, com.verdroid.app.domain.model.TaskStatus.QUEUED, null, null, n) is com.verdroid.app.domain.schedule.ScheduleRunGate.Start) { "a queued, never-started chat must not block scheduled runs" }
                check(G.decide(true, com.verdroid.app.domain.model.TaskStatus.RUNNING, null, null, n) is com.verdroid.app.domain.schedule.ScheduleRunGate.Skip) { "a running run must not be doubled" }
                check(!com.verdroid.app.agent.AgentLoop.hasUserTurn(emptyList())) { "a chat without a user turn must not call the model" }
            }
            check(com.verdroid.app.agent.context.ContextManager.live(listOf(sm)).size == 1) { "scheduled prompt must stay in the model context" }
            val pm = context.packageManager
            check(pm.queryBroadcastReceivers(Intent(context, com.verdroid.app.data.schedule.ScheduleAlarmReceiver::class.java), 0).isNotEmpty()) { "alarm receiver not registered" }
            val mgr = schedules.get()
            val e = mgr.create(com.verdroid.app.domain.schedule.ScheduleKind.REMINDER, "self-test", "self-test probe",
                com.verdroid.app.domain.schedule.ScheduleSpec.Once(System.currentTimeMillis() + 365L * 24 * 3600_000))
            try { check(mgr.isArmed(e.id)) { "alarm not armed" } } finally { mgr.delete(e.id) }
            check(!mgr.isArmed(e.id)) { "alarm not cancelled on delete" }
            val h = mgr.health()
            "next-run/DST/parsing ok, scheduled-run blocking ok (x_post only with Always allow), prompt shown as a divider, alarm arm/cancel ok; exact alarms ${if (h.exactAlarms) "allowed" else "NOT allowed (may be late)"}, " +
                "battery optimisation ${if (h.batteryExempt) "off" else "ON (runs may be delayed)"}, notifications ${if (h.notifications) "on" else "OFF"}"
        },
        "notifications" to {
            val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (ok) "granted" else throw SkipCheck("notification permission not granted")
        },
    )

    suspend fun runAll(only: Set<String>? = null, onResult: (SelfTestResult) -> Unit = {}): List<SelfTestResult> = withContext(Dispatchers.IO) {
        SelfTestCoverage.CHECKS.filter { only == null || it in only }.map { id ->
            val t0 = System.currentTimeMillis()
            val block = checks[id]
            val r = if (block == null) SelfTestResult(id, SelfTestStatus.FAIL, "no implementation", 0) else try {
                val d = withTimeout(TIMEOUT_MS) { block() }
                SelfTestResult(id, SelfTestStatus.PASS, d, System.currentTimeMillis() - t0)
            } catch (e: SkipCheck) {
                SelfTestResult(id, SelfTestStatus.SKIP, e.message.orEmpty(), System.currentTimeMillis() - t0)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                SelfTestResult(id, SelfTestStatus.FAIL, "timeout after ${TIMEOUT_MS / 1000}s", System.currentTimeMillis() - t0)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                SelfTestResult(id, SelfTestStatus.FAIL, "${e.javaClass.simpleName}: ${e.message}".take(300), System.currentTimeMillis() - t0)
            }
            onResult(r); r
        }
    }

    /** Writes the report to Documents/Verdroid/selftest-<ts>.md (null if no folder access). */
    fun save(markdown: String): File? = runCatching {
        if (!shared.hasAccess()) return null
        shared.root.mkdirs()
        File(shared.root, "selftest-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.md").apply { writeText(markdown) }
    }.getOrNull()

    fun report(results: List<SelfTestResult>): String = SelfTestReport.markdown(results, BuildConfig.VERSION_NAME, BuildConfig.BUILD_TYPE,
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")

    companion object {
        const val TIMEOUT_MS = 25_000L
        /** Minimal valid one-page PDF. */
        val TINY_PDF: ByteArray = SelfTestReport.tinyPdf()
    }
}

object SelfTestReport {
    fun markdown(results: List<SelfTestResult>, version: String, buildType: String, device: String): String = buildString {
        val p = results.count { it.status == SelfTestStatus.PASS }
        val f = results.count { it.status == SelfTestStatus.FAIL }
        val s = results.count { it.status == SelfTestStatus.SKIP }
        appendLine("# Verdroid self-test")
        appendLine("v$version ($buildType) · $device · ${Date()}")
        appendLine()
        appendLine("**$p pass · $f fail · $s skip**")
        appendLine()
        appendLine("| Check | Result | ms | Detail |")
        appendLine("|---|---|---|---|")
        results.forEach { appendLine("| ${it.id} | ${it.status} | ${it.ms} | ${it.detail.replace("|", "/").replace("\n", " ")} |") }
    }

    fun tinyPdf(): ByteArray {
        val objs = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>",
        )
        val sb = StringBuilder("%PDF-1.4\n"); val offs = mutableListOf<Int>()
        objs.forEachIndexed { i, o -> offs += sb.length; sb.append("${i + 1} 0 obj\n$o\nendobj\n") }
        val xref = sb.length
        sb.append("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
        offs.forEach { sb.append(String.format(Locale.US, "%010d 00000 n \n", it)) }
        sb.append("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }
}
