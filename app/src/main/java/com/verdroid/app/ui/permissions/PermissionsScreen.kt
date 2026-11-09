package com.verdroid.app.ui.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.verdroid.app.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdroid.app.data.a11y.VerdroidAccessibilityService
import com.verdroid.app.data.storage.SharedFolder
import com.verdroid.app.data.termux.TermuxManager
import com.verdroid.app.shizuku.ShizukuManager
import com.verdroid.app.shizuku.ShizukuState
import com.verdroid.app.ui.components.BackScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import androidx.compose.ui.res.stringResource

/** One permission row: whether it is granted and what granting does. */
data class PermissionRow(val id: String, val title: String, val why: String, val granted: Boolean, val action: String?)

/** Pure status → rows mapping (unit-tested); [PermissionsViewModel] gathers the status from Android. */
object PermissionCatalog {
    data class Status(
        val allFiles: Boolean, val notifications: Boolean, val termuxInstalled: Boolean, val termuxRunCommand: Boolean,
        val shizuku: ShizukuState, val accessibility: Boolean, val overlay: Boolean, val battery: Boolean, val installApps: Boolean,
    )

    fun rows(ctx: Context, s: Status): List<PermissionRow> = listOf(
        PermissionRow("all_files", ctx.getString(R.string.perm_all_files), ctx.getString(R.string.perm_all_files_desc), s.allFiles, ctx.getString(R.string.grant)),
        PermissionRow("notifications", ctx.getString(R.string.notifications), ctx.getString(R.string.perm_notifications_desc), s.notifications, ctx.getString(R.string.grant)),
        PermissionRow("termux", ctx.getString(R.string.perm_termux), ctx.getString(R.string.perm_termux_desc),
            s.termuxRunCommand, if (!s.termuxInstalled) ctx.getString(R.string.perm_get_termux) else ctx.getString(R.string.grant)),
        PermissionRow("shizuku", ctx.getString(R.string.perm_shizuku), ctx.getString(R.string.perm_shizuku_desc), s.shizuku == ShizukuState.READY, when (s.shizuku) {
            ShizukuState.NOT_INSTALLED -> ctx.getString(R.string.get_shizuku)
            ShizukuState.NOT_RUNNING, ShizukuState.PRE_V11 -> ctx.getString(R.string.open_shizuku)
            ShizukuState.NO_PERMISSION -> ctx.getString(R.string.grant)
            ShizukuState.READY -> null
        }),
        PermissionRow("accessibility", ctx.getString(R.string.perm_accessibility), ctx.getString(R.string.perm_accessibility_desc), s.accessibility, ctx.getString(R.string.open_settings)),
        PermissionRow("overlay", ctx.getString(R.string.perm_overlay), ctx.getString(R.string.perm_overlay_desc), s.overlay, ctx.getString(R.string.grant)),
        PermissionRow("battery", ctx.getString(R.string.perm_battery), ctx.getString(R.string.perm_battery_desc), s.battery, ctx.getString(R.string.grant)),
        PermissionRow("install", ctx.getString(R.string.perm_install), ctx.getString(R.string.perm_install_desc), s.installApps, ctx.getString(R.string.grant)),
    )
}

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val termux: TermuxManager,
    val shizuku: ShizukuManager,
    private val sharedFolder: SharedFolder,
    private val keepAlive: com.verdroid.app.keepalive.KeepAliveController,
    private val chatHeads: com.verdroid.app.chathead.ChatHeadController,
    private val updater: com.verdroid.app.data.update.AppUpdater,
) : ViewModel() {
    private val _rows = kotlinx.coroutines.flow.MutableStateFlow<List<PermissionRow>>(emptyList())
    val rows: kotlinx.coroutines.flow.StateFlow<List<PermissionRow>> = _rows

    init { refresh() }

    fun refresh() {
        val status = PermissionCatalog.Status(
            allFiles = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false),
            notifications = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            termuxInstalled = termux.isInstalled(),
            termuxRunCommand = termux.isInstalled() && termux.hasRunCommandPermission(),
            shizuku = runCatching { shizuku.refresh() }.getOrDefault(ShizukuState.NOT_INSTALLED),
            accessibility = VerdroidAccessibilityService.isRunning,
            overlay = runCatching { chatHeads.canDrawOverlays() }.getOrDefault(false),
            battery = runCatching { keepAlive.isIgnoringBatteryOptimizations() }.getOrDefault(false),
            installApps = runCatching { updater.canInstall() }.getOrDefault(false),
        )
        if (status.allFiles) runCatching { sharedFolder.ensure() }
        _rows.value = PermissionCatalog.rows(context, status)
    }

    /** Settings screen (or app store page) for a row; null = handled by a runtime permission dialog in the UI. */
    fun intentFor(id: String): Intent? = when (id) {
        "all_files" -> SharedFolder.accessIntent(context)
        "termux" -> if (!termux.isInstalled()) termux.installIntent() else null
        "shizuku" -> when (shizuku.refresh()) {
            ShizukuState.NOT_INSTALLED -> Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
            ShizukuState.NOT_RUNNING, ShizukuState.PRE_V11 -> context.packageManager.getLaunchIntentForPackage(ShizukuManager.SHIZUKU_PACKAGE)
            else -> null
        }
        "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "overlay" -> chatHeads.overlayPermissionIntent()
        "battery" -> keepAlive.batteryOptimizationIntent()
        "install" -> updater.unknownSourcesIntent()
        "notifications_settings" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        else -> null
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Settings > Permissions: every permission Verdroid uses, its status and a Grant button, in one place. */
@Composable
fun PermissionsScreen(onBack: () -> Unit, onTermuxSetup: () -> Unit, vm: PermissionsViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    var assistantRefresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh(); assistantRefresh++ }

    fun grant(id: String) {
        when {
            id == "notifications" && Build.VERSION.SDK_INT >= 33 -> runtime.launch(Manifest.permission.POST_NOTIFICATIONS)
            id == "termux" && vm.termux.isInstalled() -> runtime.launch(TermuxManager.PERMISSION_RUN_COMMAND)
            id == "shizuku" && vm.shizuku.state.value == ShizukuState.NO_PERMISSION -> vm.shizuku.requestPermission()
            else -> vm.intentFor(id)?.let { runCatching { context.startActivity(it) } }
        }
    }

    BackScaffold(stringResource(R.string.permissions), onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.permissions_intro),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            rows.forEach { r ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text((if (r.granted) "✅ " else "❌ ") + r.title, style = MaterialTheme.typography.bodyLarge)
                            Text(r.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (r.id == "notifications" && !r.granted) TextButton(onClick = {
                                vm.intentFor("notifications_settings")?.let { runCatching { context.startActivity(it) } }
                            }) { Text(stringResource(R.string.open_notification_settings)) }
                        }
                        if (!r.granted && r.action != null) OutlinedButton(onClick = { grant(r.id) }) { Text(r.action) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            val assistant = remember(assistantRefresh) {
                runCatching { context.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)
            }
            Text(stringResource(R.string.digital_assistant), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.digital_assistant_desc),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text((if (assistant) "✅ " else "❌ ") + if (assistant) stringResource(R.string.assistant_yes) else stringResource(R.string.assistant_no),
                        style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        val opened = listOf(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS, android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                            .any { a -> runCatching { context.startActivity(android.content.Intent(a)) }.isSuccess }
                        if (!opened) context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
                    }) { Text(if (assistant) stringResource(R.string.settings) else stringResource(R.string.set)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.termux_more_steps), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onTermuxSetup) { Text(stringResource(R.string.set_up_termux_dots)) }
        }
    }
}
