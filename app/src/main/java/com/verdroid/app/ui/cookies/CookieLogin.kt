package com.verdroid.app.ui.cookies

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
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
data class CookieLoginResult(val saved: Boolean, val sites: List<String>, val count: Int)

/** Lets cookie_login wait for the login screen (one at a time). */
object CookieLoginBridge {
    @Volatile var pending: CompletableDeferred<CookieLoginResult>? = null

    fun intent(context: Context, url: String): Intent = Intent(context, CookieLoginActivity::class.java)
        .putExtra(EXTRA_URL, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    const val EXTRA_URL = "url"
    const val DEFAULT_URL = "https://x.com/login"

    /** Reads the WebView's cookies for the URL's site (+ siblings) and stores them in [vault]. */
    fun capture(url: String, vault: CookieVault, read: (String) -> String?): CookieLoginResult {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return CookieLoginResult(false, emptyList(), 0)
        val sites = BrowserCookies.relatedSites(host)
        val fresh = sites.flatMap { s -> BrowserCookies.fromCookieHeader(s, read("https://$s/") ?: read("https://www.$s/")) }
        if (fresh.isEmpty()) return CookieLoginResult(false, sites, 0)
        vault.save(BrowserCookies.replaceSites(vault.all(), sites.filter { s -> fresh.any { BrowserCookies.siteOf(it.host) == s } }, fresh))
        return CookieLoginResult(true, fresh.map { BrowserCookies.siteOf(it.host) }.distinct(), fresh.size)
    }
}

/** Full-screen WebView: the user logs in normally, taps Done, the cookies go into the encrypted CookieStore. */
class CookieLoginActivity : ComponentActivity() {
    private var finished = false

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
                        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, u: String?) { title = u.orEmpty() }
                                }
                                loadUrl(url)
                            }
                        })
                    }
                }
            }
        }
    }

    private fun done(url: String, save: Boolean) {
        if (finished) return
        finished = true
        val result = if (save) {
            CookieManager.getInstance().flush()
            CookieLoginBridge.capture(url, CookieStore(applicationContext)) { CookieManager.getInstance().getCookie(it) }
        } else CookieLoginResult(false, emptyList(), 0)
        android.widget.Toast.makeText(this,
            if (result.saved) "Saved ${result.count} cookies for ${result.sites.joinToString()}" else if (save) "No cookies found — are you logged in?" else "Cancelled",
            android.widget.Toast.LENGTH_LONG).show()
        CookieLoginBridge.pending?.complete(result)
        finish()
    }

    override fun onDestroy() {
        if (!finished && isFinishing) CookieLoginBridge.pending?.complete(CookieLoginResult(false, emptyList(), 0))
        super.onDestroy()
    }
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())
