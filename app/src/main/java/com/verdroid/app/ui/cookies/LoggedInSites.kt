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

/**
 * Settings → Tools → "Web & logins": log in through the in-app WebView ("Log in to a site", with reddit.com / x.com quick
 * picks), list saved sites, per-site Clear. Always visible; [toolsOff] = the cookie_* tools are off (a hint is shown).
 */
@Composable
fun LoggedInSitesSection(toolsOff: Boolean = false, vm: LoggedInSitesViewModel = hiltViewModel()) {
    val sites by vm.sites.collectAsState()
    val context = LocalContext.current
    var ask by remember { mutableStateOf(false) }
    LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.refresh() }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text("Log in once in the in-app browser; cookie_open (including Reddit) and the X tools use that login. Use your username " +
            "and password: Google sign-in doesn't work in in-app browsers. Cookies stay encrypted on this phone; importing a cookie " +
            "file with cookie_import is the fallback.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (toolsOff) Text("Web login tools (cookie_*) are off: turn them on under Tools → Web.",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
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
        var url by remember { mutableStateOf("") }
        val target = CookieLoginBridge.loginUrlFor(url)
        AlertDialog(onDismissRequest = { ask = false },
            title = { Text("Log in to a site") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(url, { url = it }, singleLine = true, label = { Text("Site or login page URL") },
                        placeholder = { Text("reddit.com") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CookieLoginBridge.SUGGESTIONS.forEach { sug -> SuggestionChip(onClick = { url = sug }, label = { Text(sug) }) }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = target != null, onClick = {
                    ask = false
                    target?.let { u -> runCatching { context.startActivity(CookieLoginBridge.intent(context, u)) } }
                }) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { ask = false }) { Text("Cancel") } })
    }
}
