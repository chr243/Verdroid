package com.verdroid.app.ui.tools

import com.verdroid.app.data.tools.ToolGroups
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import com.verdroid.app.ui.components.groupedRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Settings > Tools: built-in agent tools (status + persisted on/off), Termux setup and optional Termux add-ons. MCP has its own screen. Text only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(onBack: () -> Unit, onOpenShizuku: () -> Unit = {}, onOpenPermissions: () -> Unit = {}, vm: ToolsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val disabled by vm.prefs.disabled.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { vm.onPermissionResult() }
    val storageLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { vm.refresh() }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    // Sections start collapsed; open ones survive rotation.
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(setOf<String>()) }
    // Twitter ToS warning: the action to run after "I understand, turn on".
    var tosPending by remember { mutableStateOf<(() -> Unit)?>(null) }
    tosPending?.let { act -> com.verdroid.app.ui.consent.TwitterTosDialog(onConfirm = { act(); tosPending = null }, onCancel = { tosPending = null }) }
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                SetupEvent.OpenInstall -> runCatching { context.startActivity(vm.termux.installIntent()) }
                SetupEvent.RequestPermission -> permissionLauncher.launch(com.verdroid.app.data.termux.TermuxManager.PERMISSION_RUN_COMMAND)
                SetupEvent.PasteAllowCommand -> {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(com.verdroid.app.data.termux.TermuxManager.ALLOW_EXTERNAL_APPS_CMD))
                    android.widget.Toast.makeText(context, "Command copied — paste it in Termux and press Enter", android.widget.Toast.LENGTH_LONG).show()
                    vm.termux.openIntent()?.let { runCatching { context.startActivity(it) } }
                }
                SetupEvent.OpenTermux -> vm.termux.openIntent()?.let { runCatching { context.startActivity(it) } }
            }
        }
    }
    // Coming back from Termux / F-Droid / a permission screen continues the setup (or just re-checks).
    var resumedOnce by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (resumedOnce) vm.onReturned() else resumedOnce = true
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Tools") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { TextButton(onClick = vm::refresh, enabled = !state.checking) { Text(if (state.checking) "Checking…" else "Check again") } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            // 1. File safety
            item(key = "file-safety") {
                SectionTitle("File safety")
                com.verdroid.app.ui.consent.OverwriteSetting()
                com.verdroid.app.ui.consent.SharedDeleteSetting()
                Hint("The Yes/No dialog's This chat / Always allow buttons set these too.")
            }
            // 2. Shared folder
            item(key = "shared-folder") {
                SectionTitle("Shared folder")
                SharedFolderCard(state.storage,
                    onGrant = { runCatching { storageLauncher.launch(com.verdroid.app.data.storage.SharedFolder.accessIntent(context)) } })
            }
            // 3. Tools (groups keep their ids; the X ToS warning still gates turning X tools on)
            item(key = "tools-title") {
                SectionTitle("Tools")
                Hint("Off = hidden from the model (and refused if it calls them anyway).")
            }
            ToolGroups.group(state.tools) { it.name }.forEach { (g, tools) ->
                val names = tools.map { it.name }
                val m = ToolGroups.master(names, disabled)
                val open = g.id in expanded
                item(key = "g-" + g.id) {
                    // Same look as the chat Tool presets dialog: title, muted description, Switch; tap the row to expand.
                    val partial = ToolGroups.partialLabel(names, disabled)
                    Row(Modifier.fillMaxWidth().clickable { expanded = if (open) expanded - g.id else expanded + g.id }
                        .semantics { contentDescription = "${g.title}, ${names.count { it !in disabled }} of ${names.size} on, ${if (open) "expanded" else "collapsed"}" }
                        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(g.title, style = MaterialTheme.typography.bodyLarge)
                            Text(g.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            partial?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = ToolGroups.switchOn(m), onCheckedChange = { on ->
                                if (ToolGroups.needsTosWarning(g.id, ToolGroups.switchOn(m), on)) tosPending = { names.forEach { vm.setEnabled(it, true) } }
                                else names.forEach { vm.setEnabled(it, on) }
                            },
                            modifier = Modifier.semantics { contentDescription = "Turn all ${g.title} tools ${if (ToolGroups.switchOn(m)) "off" else "on"}" })
                    }
                }
                if (open) itemsIndexed(tools, key = { _, t -> "t-" + t.name }) { i, t ->
                    val on = t.name !in disabled
                    ListItem(
                        modifier = Modifier.groupedRow(i, tools.size),
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        headlineContent = { Text(t.name, fontFamily = FontFamily.Monospace) },
                        supportingContent = {
                            Column {
                                Text(t.description, maxLines = 2)
                                Text(if (!on) "Off" else t.status.text, style = MaterialTheme.typography.labelMedium,
                                    color = when {
                                        !on -> MaterialTheme.colorScheme.onSurfaceVariant
                                        t.status.ready -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.error
                                    })
                            }
                        },
                        trailingContent = { Switch(checked = on, onCheckedChange = {
                            if (ToolGroups.needsTosWarning(g.id, ToolGroups.switchOn(m), it)) tosPending = { vm.setEnabled(t.name, true) }
                            else vm.setEnabled(t.name, it)
                        }) },
                    )
                }
            }
            val names = state.tools.map { it.name }
            // 4. Web & logins (always visible)
            item(key = "web-logins") {
                SectionTitle("Web & logins")
                com.verdroid.app.ui.cookies.LoggedInSitesSection(toolsOff = ToolsLayout.allOff(names, disabled) { it.startsWith("cookie_") })
            }
            // 5. X (Twitter) (always visible; these switches never turn X tools on, so no ToS gate is needed here)
            item(key = "x-twitter") {
                SectionTitle("X (Twitter)")
                Hint(ToolGroups.TWITTER_TOS_WARNING)
                if (ToolsLayout.allOff(names, disabled) { ToolGroups.groupOf(it) == "twitter" })
                    Hint("X tools are off: turn them on under Tools → X (Twitter).")
                com.verdroid.app.ui.consent.XPostSetting()
                com.verdroid.app.ui.consent.XPostLimitsSetting()
            }
            // 6. Terminal (Termux): setup + Packages / Verdroid add-ons (expandable)
            item(key = "terminal") {
                SectionTitle("Terminal (Termux)")
                TermuxSetupCard(state.termux, state,
                    onSetup = vm::setupTermux, onCancel = vm::cancelSetup,
                    onInstall = { runCatching { context.startActivity(vm.termux.installIntent()) } },
                    onGrant = { permissionLauncher.launch(com.verdroid.app.data.termux.TermuxManager.PERMISSION_RUN_COMMAND) })
                Hint("Installed in the background with pkg (no Termux window); termux_run and the tools above use them.")
                state.packagesNote?.let { Hint(it) }
            }
            for ((key, title, rows) in listOf(
                Triple(ToolsLayout.PACKAGES, "Packages", state.packages.filterNot { com.verdroid.app.data.tools.TermuxPackages.isAddon(it.pkg.pkg) }),
                Triple(ToolsLayout.ADDONS, "Verdroid add-ons", state.packages.filter { com.verdroid.app.data.tools.TermuxPackages.isAddon(it.pkg.pkg) }),
            )) {
                val open = key in expanded
                item(key = "h-$key") {
                    ExpandRow(title, ToolsLayout.installedLabel(rows.count { it.state == PkgState.INSTALLED }, rows.size), open) {
                        expanded = if (open) expanded - key else expanded + key
                    }
                }
                if (open) itemsIndexed(rows, key = { _, p -> "p-" + p.pkg.pkg }) { i, p -> PackageRow(p, i, rows.size, state.packagesNote == null) { vm.install(p.pkg) } }
            }
            // 7. Advanced (collapsed): links to the other setup screens
            val advOpen = ToolsLayout.ADVANCED in expanded
            item(key = "advanced") {
                SectionTitle("Advanced")
                ExpandRow("Shizuku, rish, Git token, permissions", null, advOpen) {
                    expanded = if (advOpen) expanded - ToolsLayout.ADVANCED else expanded + ToolsLayout.ADVANCED
                }
                if (advOpen) Column {
                    ListItem(modifier = Modifier.clickable(onClick = onOpenShizuku),
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        headlineContent = { Text("Shizuku & Git") }, supportingContent = { Text("run_shell, rish, Git token") })
                    ListItem(modifier = Modifier.clickable(onClick = onOpenPermissions),
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        headlineContent = { Text("Permissions") }, supportingContent = { Text("All files, notifications, Termux, Shizuku, accessibility, overlay") })
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Pure helpers for the Tools tab layout (unit-tested). */
object ToolsLayout {
    const val PACKAGES = "pkgs"
    const val ADDONS = "addons"
    const val ADVANCED = "advanced"
    /** Section titles, top to bottom (status/help texts point at these names). */
    val SECTIONS = listOf("File safety", "Shared folder", "Tools", "Web & logins", "X (Twitter)", "Terminal (Termux)", "Advanced")

    /** true when there are tools matching [which] and every one of them is off. */
    fun allOff(names: List<String>, disabled: Set<String>, which: (String) -> Boolean): Boolean =
        names.filter(which).let { it.isNotEmpty() && it.all { n -> n in disabled } }

    fun installedLabel(installed: Int, total: Int): String = "$installed of $total installed"
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
private fun ExpandRow(title: String, subtitle: String?, open: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle)
        .semantics { contentDescription = "$title, ${if (open) "expanded" else "collapsed"}" }
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PackageRow(p: PkgRow, i: Int, size: Int, enabled: Boolean, onInstall: () -> Unit) {
    ListItem(
        modifier = Modifier.groupedRow(i, size),
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        headlineContent = { Text(p.pkg.pkg, fontFamily = FontFamily.Monospace) },
        supportingContent = {
            Column {
                Text(p.pkg.description)
                val s = when (p.state) {
                    PkgState.INSTALLED -> "Installed (${p.pkg.binary})"
                    PkgState.NOT_INSTALLED -> "Not installed"
                    PkgState.INSTALLING -> "Installing…"
                    PkgState.FAILED -> "Install failed"
                    PkgState.UNKNOWN -> "Unknown"
                }
                Text(s, style = MaterialTheme.typography.labelMedium,
                    color = if (p.state == PkgState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                p.detail?.let { d ->
                    Text(d, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 6)
                    if (p.state == PkgState.FAILED) com.verdroid.app.ui.components.CopyButton(d)
                }
            }
        },
        trailingContent = {
            when (p.state) {
                PkgState.INSTALLED -> Text("Installed", style = MaterialTheme.typography.labelLarge)
                PkgState.INSTALLING -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else -> OutlinedButton(onClick = onInstall, enabled = enabled) {
                    Text(if (p.state == PkgState.FAILED) "Retry" else "Install")
                }
            }
        },
    )
}


/** Lightweight ViewModel for the MCP screen (no Termux/Shizuku checks like [ToolsViewModel]). */
@dagger.hilt.android.lifecycle.HiltViewModel
class McpViewModel @javax.inject.Inject constructor(
    val prefs: com.verdroid.app.data.tools.ToolPrefs,
    val mcp: com.verdroid.app.data.mcp.McpManager,
) : androidx.lifecycle.ViewModel() {
    fun setEnabled(name: String, on: Boolean) = prefs.setEnabled(name, on)
}

/** Settings > MCP servers: remote MCP servers, their status and per-tool switches (split out of Tools). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServersScreen(onBack: () -> Unit, vm: McpViewModel = hiltViewModel()) {
    val disabled by vm.prefs.disabled.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(title = { Text("MCP servers") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { McpServersSection(vm.mcp, disabled, vm::setEnabled) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Documents/Verdroid: what it is for, All files access and folder status. */
@Composable
private fun SharedFolderCard(s: StorageSetup, onGrant: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(com.verdroid.app.data.storage.SharedFolder.DISPLAY_PATH, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium)
            Text("Input/ = files for Verdroid, Output/ = results, Repos/ = git clones, Internal/ = scratch. Needs All files " +
                "access (Android 11+); the folders are created once it is granted.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SetupLine("1. All files access", s.access) {
                if (!s.access) OutlinedButton(onClick = onGrant) { Text("Grant") }
            }
            SetupLine("2. Documents/Verdroid with Input/, Output/ and Internal/", s.exists) {}
        }
    }
}

/** Termux setup for termux_run and the package installer: install, RUN_COMMAND permission, allow-external-apps. */
@Composable
private fun TermuxSetupCard(t: TermuxSetup, s: ToolsState, onSetup: () -> Unit, onCancel: () -> Unit, onInstall: () -> Unit, onGrant: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("termux_run and the add-ons run in the Termux app (in the background, no Termux window).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val allDone = com.verdroid.app.data.termux.TermuxSetupFlow.next(t.installed, t.permission, t.answering, t.storage) ==
                com.verdroid.app.data.termux.TermuxSetupStep.DONE
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSetup, enabled = !s.checking) {
                    Text(when { allDone -> "Check setup"; s.setupActive -> "Continue setup"; else -> "Set up Termux" })
                }
                if (s.setupActive) TextButton(onClick = onCancel) { Text("Cancel") }
                if (s.checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Text("One tap walks through everything: install, permission, allow-external-apps (copied for one paste) and storage. " +
                "Come back to Verdroid after each step and it continues.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.setupMessage?.let {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
                }
            }
            SetupLine("1. Termux installed", t.installed) {
                if (!t.installed) OutlinedButton(onClick = onInstall) { Text("Get Termux (F-Droid)") }
            }
            SetupLine("2. Run commands in Termux permission", t.permission) {
                if (t.installed && !t.permission) OutlinedButton(onClick = onGrant) { Text("Grant") }
            }
            SetupLine("3. allow-external-apps in Termux", t.answering == true) {}
            if (t.answering != true) {
                Text("Paste this once into Termux:", style = MaterialTheme.typography.bodySmall)
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                    Text(com.verdroid.app.data.termux.TermuxManager.ALLOW_EXTERNAL_APPS_CMD, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                }
                com.verdroid.app.ui.components.CopyButton(com.verdroid.app.data.termux.TermuxManager.ALLOW_EXTERNAL_APPS_CMD)
            }
            SetupLine("4. Storage access (needed to save into Documents/Verdroid)", t.storage == true) {}
            if (t.answering == true && t.storage != true) {
                Text("For selenium_* and your scrapers to save into Documents/Verdroid/Output, run this once in Termux and allow storage:",
                    style = MaterialTheme.typography.bodySmall)
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                    Text("termux-setup-storage", fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                }
                com.verdroid.app.ui.components.CopyButton("termux-setup-storage")
            }
        }
    }
}

@Composable
private fun SetupLine(label: String, ok: Boolean, action: @Composable () -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text((if (ok) "✅ " else "❌ ") + label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        action()
    }
}
