package com.verdroid.app.ui.device

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.verdroid.app.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import com.verdroid.app.keepalive.KeepAliveController
import com.verdroid.app.ui.components.BackScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class KeepAliveViewModel @Inject constructor(val controller: KeepAliveController) : ViewModel()

/** Settings > Background & battery: keep-alive service, battery optimisation exemption, HyperOS autostart. */
@Composable
fun KeepAliveScreen(onBack: () -> Unit, vm: KeepAliveViewModel = hiltViewModel()) {
    val c = vm.controller
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(c.enabled) }
    var batteryOk by remember { mutableStateOf(c.isIgnoringBatteryOptimizations()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { batteryOk = c.isIgnoringBatteryOptimizations(); enabled = c.enabled }

    BackScaffold(stringResource(R.string.background_battery), onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.keep_tasks_alive), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.keepalive_desc),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = enabled, onCheckedChange = { c.enabled = it; enabled = it })
                    }
                    Text(stringResource(R.string.keepalive_chathead_note),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.battery_optimisation), style = MaterialTheme.typography.titleSmall)
                    Text(if (batteryOk) stringResource(R.string.battery_unrestricted) else
                        stringResource(R.string.battery_optimised_note))
                    if (!batteryOk) Button(onClick = { runCatching { context.startActivity(c.batteryOptimizationIntent()) } }) { Text(stringResource(R.string.allow)) }
                }
            }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.hyperos_autostart), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.xiaomi_note) + "\n" +
                        "1. Security app → Permissions → Autostart → enable Verdroid (needed for resume after reboot).\n" +
                        "2. Settings → Apps → Verdroid → Battery saver → No restrictions.\n" +
                        "3. Recents: long-press Verdroid → lock (padlock) so it isn't swiped away.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { c.openAutostartSettings { context.startActivity(it) } }) {
                        Text(if (c.isXiaomi) stringResource(R.string.open_autostart) else stringResource(R.string.open_app_settings))
                    }
                }
            }
            Text(stringResource(R.string.reboot_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
