package com.verdroid.app.ui.cookies

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import com.verdroid.app.data.cookies.BrowserCookies
import com.verdroid.app.data.cookies.CookieVault
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class LoggedInSitesViewModel @Inject constructor(private val vault: CookieVault) : ViewModel() {
    private val _sites = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    val sites = _sites.asStateFlow()
    fun refresh() { _sites.value = BrowserCookies.sites(vault.all()) }
    fun clear(site: String) { vault.save(vault.all().filter { BrowserCookies.siteOf(it.host) != site }); refresh() }
}

/** Settings → Tools: "Logged-in sites (beta)" — log in through an in-app WebView, list saved sites, per-site Clear. */
@Composable
fun LoggedInSitesSection(vm: LoggedInSitesViewModel = hiltViewModel()) {
    val sites by vm.sites.collectAsState()
    val context = LocalContext.current
    var ask by remember { mutableStateOf(false) }
    LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.refresh() }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text("Logged-in sites (beta)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
        Text("For the Browser cookies tools: log in once in an in-app browser and cookie_open can read those pages. " +
            "Cookies are stored encrypted on this phone. Some sites (e.g. Google) block this kind of login; " +
            "you can also import a cookie export file with cookie_import.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { ask = true }, modifier = Modifier.padding(vertical = 8.dp)) { Text("Log in to a site") }
        if (sites.isEmpty()) Text("No saved sites", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        sites.forEach { (site, n) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(site, style = MaterialTheme.typography.bodyLarge)
                    Text("$n cookies", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { vm.clear(site) }) { Text("Clear") }
            }
        }
    }
    if (ask) {
        var url by remember { mutableStateOf(CookieLoginBridge.DEFAULT_URL) }
        AlertDialog(onDismissRequest = { ask = false },
            title = { Text("Log in to a site") },
            text = { OutlinedTextField(url, { url = it }, singleLine = true, label = { Text("Login page URL") }) },
            confirmButton = {
                TextButton(onClick = {
                    ask = false
                    val u = url.trim().let { if (it.startsWith("http")) it else "https://$it" }
                    runCatching { context.startActivity(CookieLoginBridge.intent(context, u)) }
                }) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { ask = false }) { Text("Cancel") } })
    }
}
