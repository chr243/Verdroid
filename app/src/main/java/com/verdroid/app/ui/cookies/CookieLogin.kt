package com.verdroid.app.ui.cookies

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.verdroid.app.data.cookies.BrowserCookies
import com.verdroid.app.data.cookies.CookieStore
import com.verdroid.app.data.cookies.CookieVault
import com.verdroid.app.ui.theme.VerdroidTheme
import kotlinx.coroutines.CompletableDeferred

/** Outcome of a WebView login: sites saved and how many cookies (never values). */
data class CookieLoginResult(
    val saved: Boolean, val sites: List<String>, val count: Int,
    /** Cookie NAMES saved (names are not secret; values never leave the store). */
    val names: List<String> = emptyList(),
    /** The site's login marker was present (X: auth_token); false = probably guest cookies only. */
    val loggedIn: Boolean = false,
)

/** Lets cookie_login wait for the login screen (one at a time). */
object CookieLoginBridge {
    @Volatile var pending: CompletableDeferred<CookieLoginResult>? = null

    fun intent(context: Context, url: String): Intent = Intent(context, CookieLoginActivity::class.java)
        .putExtra(EXTRA_URL, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    const val EXTRA_URL = "url"
    const val DEFAULT_URL = "https://x.com/i/flow/login"

    /** WebView UA → plain mobile Chrome UA (drop "; wv" and "Version/4.0"); X serves a blank page to WebView UAs. */
    fun chromeUserAgent(ua: String): String =
        ua.replace("; wv)", ")").replace(Regex("\\s*Version/\\d+(\\.\\d+)*"), "").replace(Regex("\\s{2,}"), " ").trim()

    /** URLs read from CookieManager for a site (cookies can be scoped to subdomains like api.x.com). */
    fun urlsFor(site: String): List<String> =
        listOf("https://$site/", "https://www.$site/", "https://api.$site/", "https://mobile.$site/")

    /** Login marker cookie for known sites (null = no known marker). */
    fun markerFor(site: String): String? = when (site) {
        "x.com", "twitter.com" -> "auth_token"
        "reddit.com" -> com.verdroid.app.data.cookies.RedditJson.LOGIN_COOKIE // token_v2 is set for guests too
        else -> null
    }
    const val REDDIT_URL = "https://www.reddit.com/login/"

    /** Reads the WebView's cookies for the URL's site (+ siblings, + subdomains) and stores them in [vault]. */
    fun capture(url: String, vault: CookieVault, read: (String) -> String?): CookieLoginResult {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return CookieLoginResult(false, emptyList(), 0)
        val sites = BrowserCookies.relatedSites(host)
        val fresh = sites.flatMap { s ->
            BrowserCookies.fromCookieHeader(s, urlsFor(s).mapNotNull { runCatching { read(it) }.getOrNull() }.joinToString("; "))
        }
        if (fresh.isEmpty()) return CookieLoginResult(false, sites, 0)
        vault.save(BrowserCookies.replaceSites(vault.all(), sites.filter { s -> fresh.any { BrowserCookies.siteOf(it.host) == s } }, fresh))
        val names = fresh.map { it.name }.distinct().sorted()
        val marker = markerFor(BrowserCookies.siteOf(host))
        return CookieLoginResult(true, fresh.map { BrowserCookies.siteOf(it.host) }.distinct(), fresh.size, names,
            loggedIn = marker == null || marker in names)
    }
}

/** Decides when a WebView login has finished (pure, unit-tested). Only cookie names/counts, never values. */
object LoginDetector {
    enum class Verdict { NOT_YET, WAIT, DONE }
    private val loginPath = Regex("login|signin|sign-in|sign_in|/flow/|/auth|oauth|session", RegexOption.IGNORE_CASE)

    fun isLoginUrl(url: String?): Boolean = url != null && loginPath.containsMatchIn(runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault(url))

    /**
     * [names]: cookie names now set for the site; [baseline]: cookie count when the screen opened;
     * [markerAgeMs]: how long the login marker has been seen (0 = just now).
     */
    fun check(site: String, url: String?, names: Set<String>, baseline: Int, markerAgeMs: Long): Verdict {
        if (site == "x.com" || site == "twitter.com") {
            if ("auth_token" !in names) return Verdict.NOT_YET
            val settled = ("ct0" in names && "twid" in names) || (url != null && !url.contains("/i/flow/")) || markerAgeMs >= 1_500
            return if (settled) Verdict.DONE else Verdict.WAIT
        }
        if (site == "reddit.com") {
            if (com.verdroid.app.data.cookies.RedditJson.LOGIN_COOKIE !in names) return Verdict.NOT_YET
            return if ((url != null && !isLoginUrl(url)) || markerAgeMs >= 1_500) Verdict.DONE else Verdict.WAIT
        }
        if (url == null || isLoginUrl(url) || names.size <= baseline) return Verdict.NOT_YET
        return if (markerAgeMs >= 1_000) Verdict.DONE else Verdict.WAIT
    }
}

/** Full-screen WebView: the user logs in normally, taps Done, the cookies go into the encrypted CookieStore. */
class CookieLoginActivity : ComponentActivity() {
    private var finished = false
    private var baseline = -1
    private var markerSince = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun names(site: String): Set<String> =
        CookieLoginBridge.urlsFor(site).flatMap { u -> BrowserCookies.parseCookieHeader(runCatching { CookieManager.getInstance().getCookie(u) }.getOrNull()) }
            .map { it.first }.toSet()

    /** Auto-close: called on page start/finish/history change; saves and closes once the site shows a login cookie. */
    private fun autoCheck(loginUrl: String, current: String?) {
        if (finished) return
        val host = runCatching { java.net.URI(loginUrl).host }.getOrNull() ?: return
        val site = BrowserCookies.siteOf(host)
        val n = names(site)
        if (baseline < 0) baseline = n.size
        val now = android.os.SystemClock.elapsedRealtime()
        val v = LoginDetector.check(site, current, n, baseline, if (markerSince == 0L) 0 else now - markerSince)
        when (v) {
            LoginDetector.Verdict.NOT_YET -> markerSince = 0L
            LoginDetector.Verdict.WAIT -> {
                if (markerSince == 0L) markerSince = now
                handler.postDelayed({ autoCheck(loginUrl, current) }, 600)
            }
            LoginDetector.Verdict.DONE -> done(loginUrl, save = true)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(CookieLoginBridge.EXTRA_URL)?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: CookieLoginBridge.DEFAULT_URL
        CookieManager.getInstance().setAcceptCookie(true)
        setContent {
            VerdroidTheme {
                var title by remember { mutableStateOf(url) }
                var progress by remember { mutableIntStateOf(0) }
                var error by remember { mutableStateOf<String?>(null) }
                Scaffold(topBar = {
                    TopAppBar(
                        title = { Text("Log in, then tap Done", style = MaterialTheme.typography.titleMedium) },
                        navigationIcon = { TextButton(onClick = { done(url, save = false) }) { Text("Cancel") } },
                        actions = { Button(onClick = { done(url, save = true) }) { Text("Done") } },
                    )
                }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        Text("Cookies are saved encrypted on this phone for cookie_open. Some sites (e.g. Google) block logins in this screen. $title",
                            style = MaterialTheme.typography.bodySmall, maxLines = 2,
                            modifier = Modifier.padding(horizontal = 12.dp(), vertical = 4.dp()))
                        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp()))
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp(), vertical = 4.dp())) }
                        AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                @Suppress("DEPRECATION")
                                settings.databaseEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                settings.javaScriptCanOpenWindowsAutomatically = true
                                settings.setSupportMultipleWindows(true) // window.open → onCreateWindow → same WebView
                                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                settings.userAgentString = CookieLoginBridge.chromeUserAgent(settings.userAgentString)
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) { progress = newProgress }
                                    override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                                        val main = view ?: return false
                                        // Popup (e.g. X's login flow): catch its first URL and load it in this WebView instead.
                                        val tmp = WebView(main.context)
                                        tmp.webViewClient = object : WebViewClient() {
                                            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                                                r?.url?.toString()?.let { main.loadUrl(it) }; tmp.destroy(); return true
                                            }
                                            override fun onPageStarted(v: WebView?, u: String?, f: android.graphics.Bitmap?) {
                                                if (!u.isNullOrBlank() && u != "about:blank") { main.loadUrl(u); tmp.stopLoading(); tmp.destroy() }
                                            }
                                        }
                                        (resultMsg?.obj as? WebView.WebViewTransport)?.webView = tmp
                                        resultMsg?.sendToTarget()
                                        return true
                                    }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, u: String?, favicon: android.graphics.Bitmap?) { error = null; autoCheck(url, u) }
                                    override fun onPageFinished(view: WebView?, u: String?) { title = u.orEmpty(); progress = 100; autoCheck(url, u) }
                                    override fun doUpdateVisitedHistory(view: WebView?, u: String?, isReload: Boolean) { autoCheck(url, u) }
                                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, err: WebResourceError?) {
                                        if (request?.isForMainFrame == true) error = "Couldn't load page: ${err?.description ?: "error"}"
                                    }
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        val scheme = request?.url?.scheme ?: return false
                                        return scheme != "http" && scheme != "https" // keep web pages here; ignore intent:/app links
                                    }
                                }
                                loadUrl(url)
                            }
                        })
                    }
                }
            }
        }
    }

    private fun done(url: String, save: Boolean, attempt: Int = 0) {
        if (finished) return
        handler.removeCallbacksAndMessages(null)
        if (!save) { finish(url, CookieLoginResult(false, emptyList(), 0), "Cancelled"); return }
        val cm = CookieManager.getInstance()
        cm.flush()
        val site = runCatching { BrowserCookies.siteOf(java.net.URI(url).host) }.getOrDefault("")
        val marker = CookieLoginBridge.markerFor(site)
        val seen = names(site)
        // Cookies can land a moment late (async CookieManager load / redirects): retry a few times before giving up.
        if ((seen.isEmpty() || (marker != null && marker !in seen)) && attempt < 3) {
            handler.postDelayed({ done(url, save, attempt + 1) }, 400); return
        }
        if (marker != null && marker !in seen) {
            // Guest cookies only: don't save them as a "login"; keep the screen open so the user can finish logging in.
            android.widget.Toast.makeText(this, "Not logged in yet (no $marker cookie). Finish logging in, then tap Done.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val result = CookieLoginBridge.capture(url, CookieStore(applicationContext)) { cm.getCookie(it) }
        android.util.Log.i("CookieLogin", "saved cookie names for ${result.sites}: ${result.names}") // names only, never values
        finish(url, result, if (result.saved) "Saved ${result.count} cookies for ${result.sites.joinToString()}" +
            (if (marker != null) " (logged in)" else "") else "No cookies found — are you logged in?")
    }

    private fun finish(@Suppress("UNUSED_PARAMETER") url: String, result: CookieLoginResult, toast: String) {
        finished = true
        android.widget.Toast.makeText(this, toast, android.widget.Toast.LENGTH_LONG).show()
        CookieLoginBridge.pending?.complete(result)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (!finished && isFinishing) CookieLoginBridge.pending?.complete(CookieLoginResult(false, emptyList(), 0))
        super.onDestroy()
    }
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())
