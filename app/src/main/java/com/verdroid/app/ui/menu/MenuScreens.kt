package com.verdroid.app.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.verdroid.app.R
import com.verdroid.app.domain.model.ApiKey
import com.verdroid.app.domain.model.LimitSettings
import com.verdroid.app.ui.Routes
import com.verdroid.app.ui.components.formatFull
import com.verdroid.app.ui.components.SmallBadge
import com.verdroid.app.ui.components.SettingsGroup
import com.verdroid.app.ui.components.Zebra
import com.verdroid.app.ui.components.groupedRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubScreen(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) }, actions = actions,
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
    }, content = content)
}

/** Settings (formerly the Menu tab): opened from the gear on the chat list; Back returns to the chats. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigate: (String) -> Unit, onBack: () -> Unit, unreadNotifications: Int = 0, vm: SettingsViewModel = hiltViewModel()) {
    val kiloNoticeSeen by vm.appPrefs.kiloNoticeSeen.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            fun item(title: String, subtitle: String, route: String): @Composable (Int) -> Unit = { i -> MenuItem(title, subtitle, i) { onNavigate(route) } }
            if (!kiloNoticeSeen) KiloNotice(onDismiss = vm.appPrefs::dismissKiloNotice)
            SettingsGroup(stringResource(R.string.group_activity), listOf(
                item(stringResource(R.string.notifications),
                    if (unreadNotifications > 0) stringResource(R.string.unread_notifications, unreadNotifications)
                    else stringResource(R.string.rate_limit_alerts), Routes.NOTIFICATIONS),
                item(stringResource(R.string.schedules), stringResource(R.string.schedules_sub), Routes.SCHEDULES),
                item(stringResource(R.string.memory), stringResource(R.string.memory_sub), Routes.memory()),
                item(stringResource(R.string.archive), stringResource(R.string.archive_sub), Routes.ARCHIVE),
            ))
            SettingsGroup(stringResource(R.string.group_models), listOf(
                item(stringResource(R.string.llm_providers), stringResource(R.string.llm_providers_sub), Routes.PROVIDER),
                item(stringResource(R.string.model_priority), stringResource(R.string.model_priority_sub), Routes.MODELS),
                item(stringResource(R.string.rate_limits_quota), stringResource(R.string.rate_limits_quota_sub), Routes.LIMITS),
            ))
            SettingsGroup(stringResource(R.string.group_agent_tools), listOf(
                item(stringResource(R.string.tools), stringResource(R.string.tools_sub), Routes.TOOLS),
                item(stringResource(R.string.subagents_full), stringResource(R.string.subagents_sub), Routes.SUBAGENTS),
                item(stringResource(R.string.profiles), stringResource(R.string.profiles_sub), Routes.PROFILES),
                item("AGENTS.md", stringResource(R.string.agents_md_sub), Routes.AGENTS_MD),
                item(stringResource(R.string.skills), stringResource(R.string.skills_sub), Routes.SKILLS),
                item(stringResource(R.string.mcp_servers), stringResource(R.string.mcp_servers_sub), Routes.MCP),
            ))
            SettingsGroup(stringResource(R.string.group_phone), listOf(
                item(stringResource(R.string.permissions), stringResource(R.string.permissions_sub), Routes.PERMISSIONS),
                item(stringResource(R.string.chat_heads), stringResource(R.string.chat_heads_sub), Routes.CHAT_HEADS),
                item(stringResource(R.string.background_battery), stringResource(R.string.background_battery_sub), Routes.KEEP_ALIVE),
                item(stringResource(R.string.shizuku_git), stringResource(R.string.shizuku_git_sub), Routes.SHIZUKU),
            ))
            SettingsGroup(stringResource(R.string.group_appearance), listOf(item(stringResource(R.string.theme), stringResource(R.string.theme_desc), Routes.THEME),
                item(stringResource(R.string.voice), stringResource(R.string.voice_desc), Routes.VOICE),
                { i -> LanguageRow(vm, i) }))
            SettingsGroup(stringResource(R.string.group_app), listOf({ i -> AppUpdateRow(vm.updater, i) },
                { i -> CrashLogRow(i) },
                { i -> LinkItem(stringResource(R.string.feedback_title), stringResource(R.string.feedback_sub), i) { com.verdroid.app.data.feedback.FeedbackLinks.CHOOSER } }))
            Text(stringResource(R.string.version_line, com.verdroid.app.BuildConfig.VERSION_NAME), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 28.dp, top = 16.dp))
        }
    }
}

/** One-time notice: the keyless Kilo Auto Free model is the first default model and may log prompts. */
@Composable
private fun KiloNotice(onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.new_kilo_auto), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.kilo_notice_body), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onDismiss) { Text(stringResource(R.string.got_it)) } }
        }
    }
}

@Composable
private fun MenuItem(title: String, subtitle: String, index: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
        colors = ListItemDefaults.colors(containerColor = Zebra.color(index)),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Settings → Appearance → Language: System default / English / 中文. Applies immediately (activity recreate). */
@Composable
private fun LanguageRow(vm: SettingsViewModel, index: Int) {
    val language by vm.appPrefs.language.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val sysLabel = stringResource(R.string.lang_system)
    val enLabel = stringResource(R.string.lang_english)
    val zhLabel = stringResource(R.string.lang_chinese)
    fun label(tag: String) = when (tag) {
        "en" -> enLabel
        "zh" -> zhLabel
        else -> sysLabel
    }
    val current = label(language)
    ListItem(
        headlineContent = { Text(stringResource(R.string.language)) },
        supportingContent = { Text(current) },
        colors = ListItemDefaults.colors(containerColor = Zebra.color(index)),
        modifier = Modifier.clickable { open = true },
    )
    if (open) {
        AlertDialog(onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.language)) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    listOf("system", "en", "zh").forEach { tag ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = tag == language, role = Role.RadioButton,
                                    onClick = {
                                        vm.appPrefs.setLanguage(tag)
                                        com.verdroid.app.data.prefs.LocaleHelper.apply(tag)
                                        open = false
                                        (context as? android.app.Activity)?.recreate()
                                    })
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = tag == language, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(label(tag), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.close)) } })
    }
}

/** OpenRouter keys (multi-key fallback, primary first). Lives in Settings → LLM providers → OpenRouter (v1.0.45). */
@Composable
fun OpenRouterKeysSection(vm: SettingsViewModel = hiltViewModel()) {
    val keys by vm.apiKeys.collectAsStateWithLifecycle()
    var label by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ApiKey?>(null) }
    run {
        Column(Modifier.fillMaxWidth()) {
            run {
                Text(modifier = Modifier.padding(bottom = 10.dp), text = stringResource(R.string.keys_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            keys.forEachIndexed { i, k ->
                Surface(color = Zebra.color(i), shape = Zebra.shape(i, keys.size), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = k.isPrimary, onClick = { vm.setPrimary(k.id) })
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(k.label.ifBlank { stringResource(R.string.key_n, i + 1) }, fontWeight = FontWeight.SemiBold)
                                if (k.isPrimary) { Spacer(Modifier.width(6.dp)); SmallBadge(stringResource(R.string.primary)) }
                            }
                            Text(k.masked, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { vm.moveKey(k.id, -1) }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.move_up)) }
                        IconButton(onClick = { vm.moveKey(k.id, 1) }, enabled = i < keys.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.move_down)) }
                        IconButton(onClick = { editing = k }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit_label)) }
                        IconButton(onClick = { vm.removeKey(k.id) }) { Icon(Icons.Filled.Delete, stringResource(R.string.remove)) }
                    }
                }
            }
            run {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                    Text(stringResource(R.string.add_key), style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(label, { label = it }, label = { Text(stringResource(R.string.label_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(key, { key = it }, label = { Text(stringResource(R.string.or_key_hint)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Button(onClick = { vm.addKey(label, key); label = ""; key = "" }, enabled = key.isNotBlank()) { Text(stringResource(R.string.add_key)) }
                }
            }
        }
    }
    editing?.let { k ->
        var newLabel by remember(k.id) { mutableStateOf(k.label) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.edit_label)) },
            text = { OutlinedTextField(newLabel, { newLabel = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameKey(k.id, newLabel); editing = null }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun ModelPriorityScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val models by vm.models.collectAsStateWithLifecycle()
    val kiloUsed by vm.kiloUsedThisHour.collectAsStateWithLifecycle()
    var newModel by remember { mutableStateOf("") }
    SubScreen(stringResource(R.string.model_priority), onBack, actions = {
        TextButton(onClick = { vm.resetModels() }) { Text(stringResource(R.string.reset)) }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(modifier = Modifier.padding(bottom = 10.dp), text = stringResource(R.string.model_priority_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(modifier = Modifier.padding(bottom = 10.dp),
                    text = stringResource(R.string.kilo_hourly, kiloUsed, com.verdroid.app.data.network.KiloApi.HOURLY_LIMIT),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(models, key = { i, m -> "$i-$m" }) { i, m ->
                Surface(color = Zebra.color(i), shape = Zebra.shape(i, models.size), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}.", modifier = Modifier.width(28.dp), fontWeight = FontWeight.Bold)
                        Text(com.verdroid.app.data.network.ModelIds.label(m), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.moveModel(i, -1) }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Up") }
                        IconButton(onClick = { vm.moveModel(i, 1) }, enabled = i < models.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.down)) }
                        IconButton(onClick = { vm.removeModel(i) }) { Icon(Icons.Filled.Delete, stringResource(R.string.remove)) }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                    OutlinedTextField(newModel, { newModel = it }, label = { Text(stringResource(R.string.model_id_hint)) }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { vm.addModel(newModel); newModel = "" }, enabled = newModel.isNotBlank()) { Text(stringResource(R.string.add)) }
                }
            }
        }
    }
}

@Composable
fun RateLimitSettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val limits by vm.limits.collectAsStateWithLifecycle()
    val quota by vm.quotaInfo.collectAsStateWithLifecycle()
    val headers by vm.lastHeaders.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    var rpm by remember(limits) { mutableStateOf(limits.requestsPerMinute.toString()) }
    var rpd by remember(limits) { mutableStateOf(limits.requestsPerDay.toString()) }
    var cooldown by remember(limits) { mutableStateOf(limits.modelCooldownSeconds.toString()) }
    var threshold by remember(limits) { mutableStateOf(limits.lowQuotaThreshold.toString()) }
    var budget by remember(limits) { mutableStateOf(limits.contextBudgetTokens.toString()) }
    var steps by remember(limits) { mutableStateOf(limits.maxSteps.toString()) }
    SubScreen(stringResource(R.string.rate_limits_quota), onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField(stringResource(R.string.rpm_label), rpm) { rpm = it }
            NumberField(stringResource(R.string.rpd_label), rpd) { rpd = it }
            NumberField(stringResource(R.string.cooldown_label), cooldown) { cooldown = it }
            NumberField(stringResource(R.string.threshold_label), threshold) { threshold = it }
            NumberField(stringResource(R.string.budget_label), budget) { budget = it }
            NumberField(stringResource(R.string.steps_label), steps) { steps = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val d = LimitSettings()
                    vm.saveLimits(LimitSettings(rpm.toIntOrNull() ?: d.requestsPerMinute, rpd.toIntOrNull() ?: d.requestsPerDay,
                        cooldown.toIntOrNull() ?: d.modelCooldownSeconds, threshold.toIntOrNull() ?: d.lowQuotaThreshold,
                        budget.toIntOrNull() ?: d.contextBudgetTokens, steps.toIntOrNull() ?: d.maxSteps))
                }) { Text(stringResource(R.string.save)) }
                OutlinedButton(onClick = { vm.saveLimits(LimitSettings()) }) { Text(stringResource(R.string.defaults)) }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.free_tier_quota), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { vm.refreshQuota() }) { Icon(Icons.Filled.Refresh, stringResource(R.string.refresh)) }
            }
            val q = quota
            if (q == null) Text(stringResource(R.string.no_quota_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Text(buildString {
                appendLine(stringResource(R.string.quota_key, q.keyLabel))
                appendLine(stringResource(R.string.quota_requests, q.used?.toString() ?: "?", q.limit?.toString() ?: "?", q.remaining?.toString() ?: "?") +
                    if (q.estimated) " " + stringResource(R.string.quota_estimated) else "")
                q.usage?.let { appendLine(stringResource(R.string.quota_credit_usage, it)) }
                q.creditLimit?.let { appendLine(stringResource(R.string.quota_credit_limit, it)) }
                if (q.rateLimitRequests != null) appendLine(stringResource(R.string.quota_rate_limit, q.rateLimitRequests.toString(), q.rateLimitInterval?.toString() ?: "?"))
                q.isFreeTier?.let { appendLine(stringResource(R.string.quota_free_tier, it.toString())) }
                append(stringResource(R.string.quota_fetched, formatFull(q.fetchedAt)))
            }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            if (headers.isNotEmpty()) {
                Text(stringResource(R.string.last_rate_headers), style = MaterialTheme.typography.titleSmall)
                headers.values.forEach { s ->
                    Text("${s.keyLabel} · ${s.model ?: "-"} · HTTP ${s.statusCode}\nlimit=${s.headers.rawLimit} remaining=${s.headers.rawRemaining} " +
                        "reset=${s.headers.resetAtMs?.let { formatFull(it) } ?: s.headers.rawReset}",
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, { v -> onChange(v.filter { it.isDigit() }.take(9)) }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}



/** Settings row that opens a web page (GitHub feedback / feature request). */
@Composable
private fun LinkItem(title: String, subtitle: String, index: Int, url: (android.content.Context) -> String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    MenuItem(title, subtitle, index) {
        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url(context)))) }
    }
}

/** Settings → App → Crash log: the latest crash report (view, copy via selection, share as text). */
@Composable
private fun CrashLogRow(index: Int) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf<Pair<String, String>?>(null) }
    var open by remember { mutableStateOf(false) }
    fun load(): Pair<String, String>? {
        val app = context.applicationContext
        val shared = com.verdroid.app.data.storage.SharedFolder.android()
        val dirs = com.verdroid.app.data.crash.CrashLog.dirs(shared.internal.takeIf { runCatching { shared.hasAccess() }.getOrDefault(false) }, app.filesDir)
        return com.verdroid.app.data.crash.CrashLog.latest(dirs)?.let { f -> f.path to runCatching { f.readText() }.getOrDefault("(unreadable)") }
    }
    MenuItem(stringResource(R.string.crash_log), stringResource(R.string.crash_log_sub), index) { text = load(); open = true }
    if (open) AlertDialog(onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.latest_crash)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                val t = text
                if (t == null) Text(stringResource(R.string.no_crashes))
                else {
                    Text(t.first, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(t.second, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp))
                    }
                }
            }
        },
        confirmButton = {
            text?.let { t ->
                val chooserTitle = stringResource(R.string.share_crash_log)
                TextButton(onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_SUBJECT, "Verdroid crash log").putExtra(android.content.Intent.EXTRA_TEXT, t.second)
                    runCatching { context.startActivity(android.content.Intent.createChooser(send, chooserTitle)) }
                }) { Text(stringResource(R.string.share)) }
            }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.close)) } })
}

/** Settings → Sub-agents: everything about spawn_subagents in one place. */
@Composable
fun SubAgentSettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val P = com.verdroid.app.agent.subagents.SubAgentPolicy
    val sa = vm.subAgents
    val disabled by vm.toolPrefs.disabled.collectAsStateWithLifecycle()
    val enabled = P.TOOL_NAME !in disabled
    val models by vm.models.collectAsStateWithLifecycle()
    val pin by sa.model.collectAsStateWithLifecycle()
    val conc by sa.concurrency.collectAsStateWithLifecycle()
    val perCall by sa.perCall.collectAsStateWithLifecycle()
    val steps by sa.maxSteps.collectAsStateWithLifecycle()
    val timeout by sa.timeoutSec.collectAsStateWithLifecycle()
    val tools by sa.tools.collectAsStateWithLifecycle()
    val rlFallback by sa.fallbackOnRateLimit.collectAsStateWithLifecycle()
    val freeRpm by vm.appPrefs.rpmOverride.collectAsStateWithLifecycle()
    var pick by remember { mutableStateOf(false) }
    val RP = com.verdroid.app.agent.research.ResearchPolicy
    val rp = vm.research
    val researchOn = RP.TOOL_NAME !in disabled
    val rPin by rp.model.collectAsStateWithLifecycle()
    val rStd by rp.standardSources.collectAsStateWithLifecycle()
    val rDeep by rp.deepSources.collectAsStateWithLifecycle()
    val rRounds by rp.deepRounds.collectAsStateWithLifecycle()
    val rStdBudget by rp.standardBudgetMin.collectAsStateWithLifecycle()
    val rDeepBudget by rp.deepBudgetMin.collectAsStateWithLifecycle()
    val rConc by rp.concurrency.collectAsStateWithLifecycle()
    var pickResearch by remember { mutableStateOf(false) }
    @Composable fun rSlider(label: String, value: Int, range: IntRange, set: (Int) -> Unit) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Slider(value = value.toFloat(), onValueChange = { set(Math.round(it)) }, enabled = researchOn,
            valueRange = range.first.toFloat()..range.last.toFloat(), steps = (range.last - range.first - 1).coerceAtLeast(0))
    }
    @Composable fun slider(label: String, value: Int, range: IntRange, set: (Int) -> Unit) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Slider(value = value.toFloat(), onValueChange = { set(Math.round(it)) }, enabled = enabled,
            valueRange = range.first.toFloat()..range.last.toFloat(), steps = (range.last - range.first - 1).coerceAtLeast(0))
    }
    SubScreen(stringResource(R.string.subagents_full), onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.subagents_intro),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.enable_subagents), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { vm.toolPrefs.setEnabled(P.TOOL_NAME, it) })
            }
            Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { pick = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.model), style = MaterialTheme.typography.bodyLarge)
                    Text(pin?.let { com.verdroid.app.data.network.ModelIds.label(it.model) + if (it.allowFallback) " " + stringResource(R.string.plus_fallback) else "" }
                        ?: stringResource(R.string.same_as_main_model), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.change), color = MaterialTheme.colorScheme.primary)
            }
            slider(stringResource(R.string.sa_max_concurrent, conc), conc, P.MIN_CONCURRENCY..P.MAX_CONCURRENCY, sa::setConcurrency)
            slider(stringResource(R.string.sa_max_per_call, perCall), perCall, 1..P.MAX_TASKS, sa::setPerCall)
            slider(stringResource(R.string.sa_max_steps, steps), steps, P.STEPS_RANGE, sa::setMaxSteps)
            slider(stringResource(R.string.sa_timeout, timeout), timeout, P.TIMEOUT_SEC_RANGE) { sa.setTimeoutSec((it / 30) * 30) }
            Text(stringResource(R.string.rate_limits), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            Text(stringResource(R.string.rate_limits_intro, P.FREE_DEFAULT_CONCURRENCY),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.rpm_line, if (freeRpm == 0) stringResource(R.string.rpm_auto) else stringResource(R.string.rpm_manual, freeRpm)), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.rpm_auto_desc), style = MaterialTheme.typography.bodySmall)
            Slider(value = freeRpm.toFloat(), onValueChange = { vm.appPrefs.setRpmOverride(Math.round(it)) }, valueRange = 0f..60f, steps = 59)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.fall_back_main_model), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.fall_back_main_model_desc),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = rlFallback, onCheckedChange = sa::setFallbackOnRateLimit)
            }
            Text(stringResource(R.string.deep_research), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            Text(stringResource(R.string.deep_research_intro),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.enable_deep_research), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = researchOn, onCheckedChange = { vm.toolPrefs.setEnabled(RP.TOOL_NAME, it) })
            }
            Row(Modifier.fillMaxWidth().clickable(enabled = researchOn) { pickResearch = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.worker_model), style = MaterialTheme.typography.bodyLarge)
                    Text(rPin?.let { com.verdroid.app.data.network.ModelIds.label(it.model) + if (it.allowFallback) " " + stringResource(R.string.plus_fallback) else "" }
                        ?: stringResource(R.string.same_as_subagent_model), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.change), color = MaterialTheme.colorScheme.primary)
            }
            rSlider(stringResource(R.string.rs_standard_sources, rStd, RP.STANDARD_QUESTIONS), rStd, RP.SOURCES_RANGE) { rp.setStandardSources((it / 5) * 5) }
            rSlider(stringResource(R.string.rs_deep_sources, rDeep, RP.DEEP_QUESTIONS), rDeep, RP.SOURCES_RANGE) { rp.setDeepSources((it / 5) * 5) }
            rSlider(stringResource(R.string.rs_deep_rounds, rRounds), rRounds, RP.ROUNDS_RANGE, rp::setDeepRounds)
            val B = com.verdroid.app.agent.research.RunBudget
            rSlider(stringResource(R.string.rs_standard_budget, rStdBudget), rStdBudget, B.STANDARD_MIN_RANGE, rp::setStandardBudgetMin)
            rSlider(stringResource(R.string.rs_deep_budget, rDeepBudget), rDeepBudget, B.DEEP_MIN_RANGE, rp::setDeepBudgetMin)
            rSlider(stringResource(R.string.rs_parallel, rConc), rConc, P.MIN_CONCURRENCY..P.MAX_CONCURRENCY, rp::setConcurrency)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                Text(stringResource(R.string.tools_subagents_may_use), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = sa::resetTools) { Text(stringResource(R.string.default_word)) }
            }
            Text(stringResource(R.string.tools_subagents_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            com.verdroid.app.data.tools.ToolGroups.group(P.READ_ONLY_TOOLS.sorted()) { it }.forEach { (g, names) ->
                Text(g.title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                names.forEach { n ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = n in tools, onCheckedChange = { sa.setTool(n, it) }, enabled = enabled)
                        Text(n, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
    if (pick) com.verdroid.app.ui.chat.ChatModelDialog(pin, models,
        onPick = { sa.setModel(it); pick = false }, onUpdate = { sa.setModel(it) }, onDismiss = { pick = false },
        title = stringResource(R.string.subagent_model), defaultLabel = stringResource(R.string.same_as_main_model_default))
    if (pickResearch) com.verdroid.app.ui.chat.ChatModelDialog(rPin, models,
        onPick = { rp.setModel(it); pickResearch = false }, onUpdate = { rp.setModel(it) }, onDismiss = { pickResearch = false },
        title = stringResource(R.string.research_worker_model), defaultLabel = stringResource(R.string.same_as_subagent_default))
}
