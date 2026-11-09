package com.verdroid.app.ui.device

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdroid.app.shizuku.ShizukuManager
import com.verdroid.app.shizuku.ShizukuState
import com.verdroid.app.ui.components.BackScaffold
import com.verdroid.app.ui.components.CopyButton
import com.verdroid.app.ui.components.CopyableText

/** Settings > Shizuku & Git: setup for run_shell, rish_run and git_* tools. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DeviceControlScreen(onBack: () -> Unit, vm: DeviceControlViewModel = hiltViewModel()) {
    val shizuku by vm.shizukuState.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val backend by vm.backendStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var token by remember { mutableStateOf("") }
    var user by remember(ui.gitUser) { mutableStateOf(ui.gitUser) }
    var name by remember(ui.authorName) { mutableStateOf(ui.authorName) }
    var email by remember(ui.authorEmail) { mutableStateOf(ui.authorEmail) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val rishPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { vm.installRish(it) }
    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    BackScaffold(stringResource(R.string.shizuku_git), onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Section(stringResource(R.string.section_run_shell)) {
                    Text(when (shizuku) {
                        ShizukuState.NOT_INSTALLED -> stringResource(R.string.shizuku_not_installed)
                        ShizukuState.NOT_RUNNING -> stringResource(R.string.shizuku_not_running)
                        ShizukuState.PRE_V11 -> stringResource(R.string.shizuku_too_old)
                        ShizukuState.NO_PERMISSION -> stringResource(R.string.shizuku_needs_permission)
                        ShizukuState.READY -> "✅ Ready (permission granted)."
                    })
                    if (shizuku == ShizukuState.READY) ui.shizukuInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text(stringResource(R.string.active_backend) + " " + when (backend.active) {
                        ShizukuManager.BACKEND_NEW_PROCESS -> stringResource(R.string.backend_new_process)
                        ShizukuManager.BACKEND_SERVICE -> stringResource(R.string.backend_user_service)
                        else -> if (backend.errors.isEmpty()) stringResource(R.string.not_tested_yet) else stringResource(R.string.none_available)
                    }, style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.shizuku_order_note),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    backend.errors.forEach { (k, v) -> Text("• $k: $v", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    if (backend.errors.isNotEmpty()) CopyButton(backend.errors.entries.joinToString("\n") { "${it.key}: ${it.value}" }, label = stringResource(R.string.copy_errors))
                    ui.shizukuBindError?.let { CopyableText(stringResource(R.string.last_bind_error, it), color = MaterialTheme.colorScheme.error) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        when (shizuku) {
                            ShizukuState.NOT_INSTALLED -> Button(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                            }) { Text(stringResource(R.string.get_shizuku)) }
                            ShizukuState.NOT_RUNNING, ShizukuState.PRE_V11 -> Button(onClick = {
                                context.packageManager.getLaunchIntentForPackage(ShizukuManager.SHIZUKU_PACKAGE)?.let { context.startActivity(it) }
                            }) { Text(stringResource(R.string.open_shizuku)) }
                            ShizukuState.NO_PERMISSION -> Button(onClick = vm::requestShizukuPermission) { Text(stringResource(R.string.grant_permission)) }
                            ShizukuState.READY -> Button(onClick = vm::testShell, enabled = !ui.testing) { Text(if (ui.testing) stringResource(R.string.testing_) else stringResource(R.string.test_id)) }
                        }
                        OutlinedButton(onClick = vm::diagnoseShizuku, enabled = !ui.testing && shizuku == ShizukuState.READY) { Text(stringResource(R.string.diagnose)) }
                        OutlinedButton(onClick = vm::refresh) { Text(stringResource(R.string.re_check)) }
                        if (ui.testing) CircularProgressIndicator(Modifier.size(20.dp).align(Alignment.CenterVertically), strokeWidth = 2.dp)
                    }
                    ui.testOutput?.let { CopyableText(it) }
                }

                Section(stringResource(R.string.section_rish)) {
                    Text(stringResource(R.string.rish_intro), style = MaterialTheme.typography.bodySmall)
                    Text(if (ui.rishInstalled) stringResource(R.string.rish_installed, ui.rishInfo ?: "") else stringResource(R.string.not_set_up), style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = vm::findRish) { Text(stringResource(R.string.find_rish)) }
                        OutlinedButton(onClick = { rishPicker.launch(arrayOf("*/*")) }) { Text(if (ui.rishInstalled) stringResource(R.string.pick_again) else stringResource(R.string.pick_rish_file)) }
                    }
                    if (ui.rishInstalled) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = vm::testRish, enabled = !ui.testing) { Text(stringResource(R.string.test_id)) }
                        OutlinedButton(onClick = vm::fixRishPermissions) { Text(stringResource(R.string.fix_rish_permissions)) }
                        OutlinedButton(onClick = vm::removeRish) { Text(stringResource(R.string.remove)) }
                    }
                    if (ui.rishNeedsAccess) TextButton(onClick = { runCatching { context.startActivity(vm.allFilesAccessIntent()) } }) {
                        Text(stringResource(R.string.grant_all_files))
                    }
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku-API/tree/master/rish")))
                    }) { Text(stringResource(R.string.about_rish)) }
                }

                Section(stringResource(R.string.section_git)) {
                    Text(stringResource(R.string.git_repos_note), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.git_token_line, ui.gitTokenMasked ?: stringResource(R.string.not_set)), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(token, { token = it }, label = { Text(stringResource(R.string.git_pat_hint)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.https_username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.author_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.author_email)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { vm.saveGit(token, user, name, email); token = "" }) { Text(stringResource(R.string.save)) }
                        if (ui.gitTokenMasked != null) OutlinedButton(onClick = vm::clearGitToken) { Text(stringResource(R.string.remove_token)) }
                    }
                }

            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}
