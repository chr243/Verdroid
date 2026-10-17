package com.verdroid.app.ui.schedule

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.verdroid.app.data.local.ScheduleEntity
import com.verdroid.app.data.schedule.ScheduleHealth
import com.verdroid.app.data.schedule.ScheduleManager
import com.verdroid.app.domain.schedule.ScheduleKind
import com.verdroid.app.domain.schedule.ScheduleMath
import com.verdroid.app.domain.schedule.ScheduleSpec
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class SchedulesViewModel @Inject constructor(val manager: ScheduleManager) : ViewModel() {
    val schedules = manager.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun setEnabled(id: Long, on: Boolean) = viewModelScope.launch { manager.setEnabled(id, on) }
    fun delete(id: Long) = viewModelScope.launch { manager.delete(id) }
    fun runNow(id: Long) = viewModelScope.launch { manager.runNow(id) }
    fun create(kind: ScheduleKind, text: String, spec: ScheduleSpec, onlyIfChanged: Boolean, onError: (String) -> Unit, onDone: () -> Unit) =
        viewModelScope.launch {
            runCatching { manager.create(kind, text.take(40), text, spec, onlyIfChanged) }
                .onSuccess { manager.checkHealth(); onDone() }
                .onFailure { onError(it.message ?: "Couldn't create the schedule") }
        }
}

/** Settings → Schedules: reminders and scheduled agent runs (times in device local time). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulesScreen(onBack: () -> Unit, onOpenChat: (Long) -> Unit, onOpenKeepAlive: () -> Unit, vm: SchedulesViewModel = hiltViewModel()) {
    val list by vm.schedules.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val health = remember(refresh, list.size) { vm.manager.health() }
    var confirmDelete by remember { mutableStateOf<ScheduleEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    val zone = remember(refresh) { ZoneId.systemDefault() }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Schedules") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add") }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { HealthBanner(health, vm.manager, onOpenKeepAlive) }
            if (list.isEmpty()) item {
                Text("No schedules yet. Ask in a chat, e.g. \"remind me in 10 minutes about my soup\", \"every day at 10am check cnn.com " +
                    "and give me a summary\" or \"every 15 minutes check this blog, only tell me if there's a new post\" — or tap Add.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            }
            items(list, key = { it.id }) { s ->
                ScheduleCard(s, zone, onToggle = { vm.setEnabled(s.id, it) }, onRunNow = { vm.runNow(s.id) },
                    onDelete = { confirmDelete = s }, onOpenChat = s.chatTaskId?.let { c -> { onOpenChat(c) } })
            }
            item {
                Text("Times are in this phone's time zone (${zone.id}). Repeating runs need at least ${ScheduleSpec.MIN_INTERVAL_MINUTES} minutes " +
                    "between them. Scheduled runs happen without you: tools that need your confirmation (posting, deleting, installing, " +
                    "phone control) are blocked in them.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp, 8.dp, 8.dp, 80.dp))
            }
        }
    }
    confirmDelete?.let { s ->
        AlertDialog(onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this schedule?") },
            text = { Text("“${s.name}” will stop running. Its chat and past results stay.") },
            confirmButton = { TextButton(onClick = { vm.delete(s.id); confirmDelete = null }) { Text("Yes") } },
            dismissButton = { Button(onClick = { confirmDelete = null }) { Text("No") } })
    }
    if (adding) AddScheduleDialog(onDismiss = { adding = false }) { kind, text, spec, only, onError ->
        vm.create(kind, text, spec, only, onError) { adding = false }
    }
}

@Composable
private fun HealthBanner(h: ScheduleHealth, m: ScheduleManager, onOpenKeepAlive: () -> Unit) {
    if (h.ok) return
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Scheduled runs may be late or skipped", style = MaterialTheme.typography.titleSmall)
            h.warnings().forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!h.batteryExempt) OutlinedButton(onClick = { runCatching { context.startActivity(m.batteryIntent()) } }) { Text("Allow background") }
                if (!h.exactAlarms) OutlinedButton(onClick = { runCatching { context.startActivity(m.exactAlarmIntent()) } }) { Text("Allow exact alarms") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!h.notifications) OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }
                }) { Text("Notifications") }
                TextButton(onClick = onOpenKeepAlive) { Text("Background & battery tips") }
            }
        }
    }
}

@Composable
private fun ScheduleCard(s: ScheduleEntity, zone: ZoneId, onToggle: (Boolean) -> Unit, onRunNow: () -> Unit, onDelete: () -> Unit, onOpenChat: (() -> Unit)?) {
    val spec = ScheduleManager.specOf(s)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text((if (s.kind == ScheduleKind.REMINDER.name) "⏰ " else "🗓️ ") + s.name, style = MaterialTheme.typography.titleMedium)
                    Text((if (s.kind == ScheduleKind.REMINDER.name) "Reminder" else "Agent task") + " · " +
                        (spec?.let { ScheduleMath.describe(it, zone) } ?: "invalid") + if (s.onlyIfChanged) " · only if changed" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = s.enabled, onCheckedChange = onToggle)
            }
            if (s.kind == ScheduleKind.TASK.name) Text(s.message, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
            Text("Next: " + (s.nextRunAt?.takeIf { s.enabled }?.let { ScheduleMath.formatLocal(it, zone) } ?: "—"), style = MaterialTheme.typography.bodySmall)
            s.lastRunAt?.let { Text("Last: ${ScheduleMath.formatLocal(it, zone)} · ${s.lastStatus ?: ""}", style = MaterialTheme.typography.bodySmall,
                color = if (s.lastStatus?.startsWith("failed") == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRunNow) { Text("Run now") }
                if (onOpenChat != null) TextButton(onClick = onOpenChat) { Text("Open chat") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun AddScheduleDialog(onDismiss: () -> Unit, onCreate: (ScheduleKind, String, ScheduleSpec, Boolean, (String) -> Unit) -> Unit) {
    var kind by remember { mutableStateOf(ScheduleKind.REMINDER) }
    var text by remember { mutableStateOf("") }
    var mode by remember { mutableIntStateOf(0) } // 0 = in N minutes, 1 = daily at, 2 = every N minutes
    var value by remember { mutableStateOf("10") }
    var only by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("New schedule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow {
                    ScheduleKind.entries.forEachIndexed { i, k ->
                        SegmentedButton(selected = kind == k, onClick = { kind = k }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                            Text(if (k == ScheduleKind.REMINDER) "Reminder" else "Agent task")
                        }
                    }
                }
                OutlinedTextField(text, { text = it }, label = { Text(if (kind == ScheduleKind.REMINDER) "Reminder text" else "Prompt") },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp))
                listOf("In N minutes (once)", "Every day at HH:MM", "Every N minutes").forEachIndexed { i, l ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == i, onClick = { mode = i; value = listOf("10", "10:00", "60")[i] }); Text(l)
                    }
                }
                OutlinedTextField(value, { value = it }, singleLine = true, label = { Text(if (mode == 1) "Time (HH:MM)" else "Minutes") },
                    keyboardOptions = KeyboardOptions(keyboardType = if (mode == 1) KeyboardType.Text else KeyboardType.Number))
                if (kind == ScheduleKind.TASK) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(only, { only = it }); Text("Only notify when something changed")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = text.isNotBlank(), onClick = {
                val now = System.currentTimeMillis()
                val spec: ScheduleSpec? = when (mode) {
                    0 -> value.trim().toLongOrNull()?.takeIf { it in 1..ScheduleSpec.MAX_INTERVAL_MINUTES }?.let { ScheduleSpec.Once(now + it * 60_000) }
                    1 -> ScheduleMath.parseTimeOfDay(value)?.let { (h, m) -> ScheduleSpec.Daily(h, m) }
                    else -> value.trim().toIntOrNull()?.takeIf { it >= ScheduleSpec.MIN_INTERVAL_MINUTES }?.let { ScheduleSpec.Interval(it) }
                }
                if (spec == null) error = when (mode) { 1 -> "Enter a time like 10:00"; 2 -> "At least ${ScheduleSpec.MIN_INTERVAL_MINUTES} minutes"; else -> "Enter minutes" }
                else onCreate(kind, text, spec, only) { error = it }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ScheduleEntryPoint { fun schedules(): ScheduleManager }

/**
 * Shown (main app and bubbles) when a schedule was just created or enabled while battery optimisation / exact alarms /
 * notifications may delay or hide its runs. Declining never blocks the schedule; Settings → Schedules keeps a warning.
 */
@Composable
fun ScheduleHealthDialog() {
    val context = LocalContext.current
    val m = remember { EntryPointAccessors.fromApplication(context.applicationContext, ScheduleEntryPoint::class.java).schedules() }
    val h by m.prompt.collectAsState()
    val health = h ?: return
    AlertDialog(onDismissRequest = m::dismissPrompt,
        title = { Text("Keep schedules on time?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your schedule was saved. Android may delay or skip it:", style = MaterialTheme.typography.bodyMedium)
                health.warnings().forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                if (!health.batteryExempt) OutlinedButton(onClick = { runCatching { context.startActivity(m.batteryIntent()) } }) { Text("Allow background activity") }
                if (!health.exactAlarms) OutlinedButton(onClick = { runCatching { context.startActivity(m.exactAlarmIntent()) } }) { Text("Allow exact alarms") }
                TextButton(onClick = {
                    m.dismissPrompt()
                    runCatching { context.startActivity(com.verdroid.app.MainActivity.openRouteIntent(context, com.verdroid.app.ui.Routes.KEEP_ALIVE)) }
                }) { Text("Tips for Xiaomi, Samsung and others") }
            }
        },
        confirmButton = { TextButton(onClick = m::dismissPrompt) { Text("Done") } },
        dismissButton = { TextButton(onClick = m::dismissPrompt) { Text("Not now") } })
}
