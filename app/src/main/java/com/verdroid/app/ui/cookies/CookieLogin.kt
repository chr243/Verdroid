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
    /** A real login check passed (X: auth_token; Reddit: reddit_session or a user token_v2); false = cancelled / guest only / unknown. */
    val loggedIn: Boolean = false,
    /** saved | cancelled | timeout | not_logged_in (guest cookies only, nothing saved) | no_cookies | unchecked (site without a known login cookie). */
    val reason: String = if (saved) "saved" else "cancelled",
)

/** Lets cookie_login wait for the login screen (one at a time). */
object CookieLoginBridge {
    @Volatile var pending: CompletableDeferred<CookieLoginResult>? = null

    fun intent(context: Context, url: String): Intent = Intent(context, CookieLoginActivity::class.java)
        .putExtra(EXTRA_URL, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    const val EXTRA_URL = "url"
    /** The login screen and cookie_login both give up after this long (nothing saved). */
    const val TIMEOUT_MS = 10 * 60_000L
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
    const val REDDIT_OLD_URL = "https://old.reddit.com/login"
    /** Empty X-Requested-With allow-list = the header is never sent. False when this WebView can't do it (update WebView). */
    @SuppressLint("RequiresFeature")
    fun dropRequestedWith(settings: android.webkit.WebSettings): Boolean =
        runCatching { androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet()) }.isSuccess

    fun isReddit(url: String): Boolean = runCatching { BrowserCookies.siteOf(java.net.URI(url).host) == "reddit.com" }.getOrDefault(false)

    /** Reads the WebView's cookies for the URL's site (+ siblings, + subdomains) and stores them in [vault]. */
    fun capture(url: String, vault: CookieVault, read: (String) -> String?): CookieLoginResult {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return CookieLoginResult(false, emptyList(), 0)
        val sites = BrowserCookies.relatedSites(host)
        val fresh = sites.flatMap { s ->
            BrowserCookies.fromCookieHeader(s, urlsFor(s).mapNotNull { runCatching { read(it) }.getOrNull() }.joinToString("; "))
        }
        if (fresh.isEmpty()) return CookieLoginResult(false, sites, 0, reason = "no_cookies")
        val names = fresh.map { it.name }.distinct().sorted()
        val site = BrowserCookies.siteOf(host)
        val marker = markerFor(site)
        val loggedIn = loggedIn(site, fresh)
        // Never overwrite the stored cookies with a guest-only set from a login that didn't happen.
        if (marker != null && !loggedIn) return CookieLoginResult(false, sites, 0, names, loggedIn = false, reason = "not_logged_in")
        vault.save(BrowserCookies.replaceSites(vault.all(), sites.filter { s -> fresh.any { BrowserCookies.siteOf(it.host) == s } }, fresh))
        return CookieLoginResult(true, fresh.map { BrowserCookies.siteOf(it.host) }.distinct(), fresh.size, names,
            loggedIn = loggedIn, reason = if (marker == null) "unchecked" else "saved")
    }

    /** Real login check from cookies (values only inspected here): Reddit session / user token, else the site's marker cookie. */
    fun loggedIn(site: String, cookies: List<com.verdroid.app.data.cookies.BrowserCookie>): Boolean {
        if (site == "reddit.com") return com.verdroid.app.data.cookies.RedditJson.loggedIn(cookies)
        val marker = markerFor(site) ?: return false
        return cookies.any { BrowserCookies.siteOf(it.host) == site && it.name == marker && it.value.isNotBlank() }
    }
}

/**
 * Login-screen helpers (pure, unit-tested). There is NO auto-close: the screen closes only on Done (with the real login
 * check, [CookieLoginBridge.capture]), Cancel, back or the 10-minute timeout, never on a page load or cookie change.
 */
object LoginDetector {

    /** [BLANK_JS] result ("\"chars,elements\"") → nothing visible. Shadow-DOM pages have little innerText, so require few elements too. */
    fun isBlank(jsResult: String?): Boolean {
        val parts = jsResult?.trim('"')?.split(',') ?: return false
        val chars = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val elements = parts.getOrNull(1)?.toIntOrNull() ?: return false
        return chars < 5 && elements < 15
    }

    fun blankHint(url: String): String {
        val site = runCatching { BrowserCookies.siteOf(java.net.URI(url).host) }.getOrDefault("")
        return if (site == "reddit.com") "Reddit's login page didn't show. Update Android System WebView (Play Store) and try again, or try old Reddit " +
            "(old.reddit.com/login). Use username + password: Google/Apple sign-in doesn't work in in-app browsers. You can also import cookies from a " +
            "desktop browser export with cookie_import."
        else "The page didn't show. Update Android System WebView (Play Store) and try again, or import cookies from a browser export (cookie_import)."
    }

}

/** Full-screen WebView: the user logs in normally, taps Done, the cookies go into the encrypted CookieStore. */
class CookieLoginActivity : ComponentActivity() {
    private var finished = false
    private var web: WebView? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun names(site: String): Set<String> {
        val pairs = CookieLoginBridge.urlsFor(site).flatMap { u -> BrowserCookies.parseCookieHeader(runCatching { CookieManager.getInstance().getCookie(u) }.getOrNull()) }
        return if (site == "reddit.com") com.verdroid.app.data.cookies.RedditJson.loginNames(pairs) else pairs.map { it.first }.toSet()
    }

    /** Closes the screen (nothing saved) when the user leaves it open this long; cookie_login waits the same time. */
    private val timeout = android.os.Handler(android.os.Looper.getMainLooper())

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(CookieLoginBridge.EXTRA_URL)?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: CookieLoginBridge.DEFAULT_URL
        CookieManager.getInstance().setAcceptCookie(true)
        timeout.postDelayed({ if (!finished) finish(url, CookieLoginResult(false, emptyList(), 0, reason = "timeout"), "Login screen closed after 10 minutes; nothing saved") },
            CookieLoginBridge.TIMEOUT_MS)
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
                        Text("Cookies are saved encrypted on this phone. Use username + password: Google sign-in is blocked in this screen. $title",
                            style = MaterialTheme.typography.bodySmall, maxLines = 2,
                            modifier = Modifier.padding(horizontal = 12.dp(), vertical = 4.dp()))
                        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp()))
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp(), vertical = 4.dp())) }
                        if (error != null && CookieLoginBridge.isReddit(url) && web?.url?.contains("old.reddit.com") != true)
                            TextButton(onClick = { error = null; web?.loadUrl(CookieLoginBridge.REDDIT_OLD_URL) }) { Text("Try old Reddit login") }
                        AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { ctx ->
                            WebView(ctx).also { web = it }.apply {
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
                                // WebView sends "X-Requested-With: <app id>" on every request; Reddit serves WebView logins a blank
                                // page. Drop the header (WebView 110+) so the page sees a normal mobile Chrome.
                                // (Throws UnsupportedOperationException on WebViews without the feature; its feature constant isn't public.)
                                CookieLoginBridge.dropRequestedWith(settings)
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
                                    override fun onPageStarted(view: WebView?, u: String?, favicon: android.graphics.Bitmap?) { error = null }
                                    override fun onPageFinished(view: WebView?, u: String?) {
                                        title = u.orEmpty(); progress = 100
                                        // A login page that is still empty after a few seconds: say why and what to try.
                                        handler.postDelayed({
                                            if (finished) return@postDelayed
                                            view?.evaluateJavascript(BLANK_JS) { r -> if (LoginDetector.isBlank(r)) error = LoginDetector.blankHint(url) }
                                        }, 5_000)
                                    }
                                    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: android.webkit.WebResourceResponse?) {
                                        if (request?.isForMainFrame == true && (response?.statusCode ?: 0) >= 400)
                                            error = "The site answered HTTP ${response?.statusCode} (blocked?). " + LoginDetector.blankHint(url)
                                    }
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

    private companion object {
        /** Visible text length + element count of the page (shadow DOM apps count via their custom elements). */
        const val BLANK_JS = "(function(){var b=document.body;return b?(b.innerText||'').trim().length+','+b.getElementsByTagName('*').length:'0,0';})()"
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
        val missing = marker != null && (if (site == "reddit.com") !com.verdroid.app.data.cookies.RedditJson.isLoggedInNames(seen) else marker !in seen)
        if ((seen.isEmpty() || missing) && attempt < 3) {
            handler.postDelayed({ done(url, save, attempt + 1) }, 400); return
        }
        if (missing) {
            // Guest cookies only: don't save them as a "login"; keep the screen open so the user can finish logging in.
            android.widget.Toast.makeText(this, "Not logged in yet (no $marker cookie). Finish logging in, then tap Done.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val result = CookieLoginBridge.capture(url, CookieStore(applicationContext)) { cm.getCookie(it) }
        if (result.reason == "not_logged_in") {  // guest cookies only: nothing saved, keep the screen open
            android.widget.Toast.makeText(this, "Not logged in yet (no $marker cookie). Finish logging in, then tap Done.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        android.util.Log.i("CookieLogin", "saved cookie names for ${result.sites}: ${result.names}") // names only, never values
        finish(url, result, if (result.saved) "Saved ${result.count} cookies for ${result.sites.joinToString()}" +
            (if (result.loggedIn) " (logged in)" else "") else "No cookies found — are you logged in?")
    }

    private fun finish(@Suppress("UNUSED_PARAMETER") url: String, result: CookieLoginResult, toast: String) {
        finished = true
        timeout.removeCallbacksAndMessages(null)
        android.widget.Toast.makeText(this, toast, android.widget.Toast.LENGTH_LONG).show()
        CookieLoginBridge.pending?.complete(result)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        timeout.removeCallbacksAndMessages(null)
        if (!finished && isFinishing) CookieLoginBridge.pending?.complete(CookieLoginResult(false, emptyList(), 0))
        super.onDestroy()
    }
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())
