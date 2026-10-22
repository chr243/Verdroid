package com.verdroid.app.ui.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.verdroid.app.data.network.ModelIds
import com.verdroid.app.data.profiles.Profile
import com.verdroid.app.data.profiles.ProfileStore
import com.verdroid.app.data.profiles.Profiles
import com.verdroid.app.data.skills.SkillStore
import com.verdroid.app.data.tools.ToolPreset
import com.verdroid.app.ui.components.BackScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val store: ProfileStore,
    private val skillStore: SkillStore,
    settings: com.verdroid.app.domain.repository.SettingsRepository,
) : ViewModel() {
    val profiles = store.profiles
    val activeId = store.activeId
    val skills = skillStore.skills
    val models = settings.modelPriority.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    init { skillStore.refresh() }

    fun newId(): String = Profiles.newId(store.all().map { it.id })
    /** "New profile": a copy of the active one. */
    fun draftCopy(): Profile = Profiles.copyOf(store.active(), newId(), "New profile")
    fun templates(): List<Profile> = Profiles.templates { newId() + "_" + it }
    fun save(p: Profile) = store.save(p.copy(name = p.name.trim().ifEmpty { "Profile" }.take(40), emoji = p.emoji.trim().take(8)))
    fun delete(id: String) = viewModelScope.launch { store.delete(id) }
    fun activate(id: String) = store.setActive(id)
}

/** Settings → Profiles: list, new (copy of the current one or a template), edit, delete (Default stays). */
@Composable
fun ProfilesScreen(onBack: () -> Unit, startNew: Boolean = false, vm: ProfilesViewModel = hiltViewModel()) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val active by vm.activeId.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Profile?>(null) }
    var confirmDelete by remember { mutableStateOf<Profile?>(null) }
    var newMenu by remember { mutableStateOf(startNew) }

    editing?.let { p ->
        // A profile created from the main screen's "+ Add new" becomes the active one.
        ProfileEditor(p, vm, onDone = { saved -> saved?.let { vm.save(it); if (startNew) vm.activate(it.id) }; editing = null })
        return
    }

    BackScaffold("Profiles", onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text("A profile bundles tools, skills, a model, instructions and memory, so you can switch with one tap at the top of a chat. " +
                    "Each chat remembers its profile. With only Default, nothing changes.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            items(profiles, key = { it.id }) { p ->
                ListItem(
                    modifier = Modifier.clickable { editing = p },
                    headlineContent = { Text(p.label + if (p.id == active) "  · active" else "") },
                    supportingContent = { Text(summary(p), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        Row {
                            if (p.id != active) TextButton(onClick = { vm.activate(p.id) }) { Text("Use") }
                            if (!p.isDefault) TextButton(onClick = { confirmDelete = p }) { Text("Delete") }
                        }
                    },
                )
            }
            item {
                Box(Modifier.padding(16.dp)) {
                    Button(onClick = { newMenu = true }) { Text("New profile") }
                    DropdownMenu(expanded = newMenu, onDismissRequest = { newMenu = false }) {
                        DropdownMenuItem(text = { Text("Copy of current profile") }, onClick = { newMenu = false; editing = vm.draftCopy() })
                        vm.templates().forEach { t ->
                            DropdownMenuItem(text = { Text("Template: ${t.label}") }, onClick = { newMenu = false; editing = t })
                        }
                    }
                }
            }
            item {
                Text(Profiles.SHARED_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    confirmDelete?.let { p ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("Delete profile \"${p.name}\"?") },
            text = { Text("Its chats move to Default and its separate memories become shared. Chats and memories are not deleted.") },
            confirmButton = { TextButton(onClick = { vm.delete(p.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } })
    }
}

private fun summary(p: Profile): String = listOfNotNull(
    if (p.model.isBlank()) "Model priority" else ModelIds.label(p.model),
    if (p.toolGroupsOff.isEmpty()) "all tools" else "${p.toolGroupsOff.size} tool groups off",
    if (p.skills == null) "all skills" else "${p.skills.size} skills",
    if (p.separateMemory) "separate memory" else "shared memory",
    if (p.instructions.isNotBlank()) "instructions" else null,
).joinToString(" · ")

@Composable
private fun ProfileEditor(initial: Profile, vm: ProfilesViewModel, onDone: (Profile?) -> Unit) {
    var p by remember(initial.id) { mutableStateOf(initial) }
    val skills by vm.skills.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    var modelMenu by remember { mutableStateOf(false) }
    BackHandler { onDone(null) }
    BackScaffold(if (initial.isDefault) "Default profile" else "Edit profile", { onDone(null) },
        actions = { TextButton(onClick = { onDone(p) }) { Text("Save") } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Section("Icon")
            EmojiGrid(p.emoji) { p = p.copy(emoji = it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(p.emoji, { p = p.copy(emoji = it.take(8)) }, label = { Text("Any emoji") }, singleLine = true, modifier = Modifier.width(88.dp))
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(p.name, { p = p.copy(name = it.take(40)) }, label = { Text("Name") }, singleLine = true,
                    enabled = !p.isDefault, modifier = Modifier.weight(1f))
            }
            Section("Model")
            Box {
                OutlinedButton(onClick = { modelMenu = true }) { Text(if (p.model.isBlank()) "Follow Model priority" else ModelIds.label(p.model)) }
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    DropdownMenuItem(text = { Text("Follow Model priority") }, onClick = { p = p.copy(model = ""); modelMenu = false })
                    (models + listOfNotNull(p.model.takeIf { it.isNotBlank() })).distinct().forEach { m ->
                        DropdownMenuItem(text = { Text(ModelIds.label(m)) }, onClick = { p = p.copy(model = m); modelMenu = false })
                    }
                }
            }
            Hint("Tried first; if it fails, the rest of Model priority is used. A chat's own pinned model still wins.")

            Section("Tools")
            Hint("Unticked groups are off in this profile. A chat's Tool presets can still override them; Settings → Tools switches still apply.")
            ToolPreset.entries.forEach { t ->
                CheckRow(t.label, t.groupId !in p.toolGroupsOff) { on ->
                    p = p.copy(toolGroupsOff = if (on) p.toolGroupsOff - t.groupId else p.toolGroupsOff + t.groupId)
                }
            }

            Section("Skills")
            if (skills.isEmpty()) Hint("No saved skills yet.")
            else {
                CheckRow("All skills (incl. new ones)", p.skills == null) { on -> p = p.copy(skills = if (on) null else skills.map { it.id }.toSet()) }
                skills.forEach { s ->
                    CheckRow(s.name + if (!s.enabled) " (off in Settings → Skills)" else "", p.skills?.contains(s.id) ?: true) { on ->
                        val cur = p.skills ?: skills.map { it.id }.toSet()
                        p = p.copy(skills = if (on) cur + s.id else cur - s.id)
                    }
                }
            }

            Section("Instructions")
            OutlinedTextField(p.instructions, { p = p.copy(instructions = it.take(Profiles.MAX_INSTRUCTIONS)) },
                placeholder = { Text("Context or persona for this profile, e.g. \"You help me with Android coding in Kotlin.\"") },
                minLines = 4, modifier = Modifier.fillMaxWidth())
            Hint("Added to the agent's instructions, after AGENTS.md.")

            Section("Memory")
            if (p.isDefault) Hint("Default always uses the shared long-term memory.")
            else {
                RadioRow("Shared with all profiles", !p.separateMemory) { p = p.copy(separateMemory = false) }
                RadioRow("Separate for this profile", p.separateMemory) { p = p.copy(separateMemory = true) }
                Hint("Separate: this profile's long-term memories are kept apart. Short-term (per chat) notes are always per chat.")
            }
            Spacer(Modifier.height(12.dp))
            Text(Profiles.SHARED_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable private fun Section(t: String) =
    Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))

@Composable private fun Hint(t: String) =
    Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))

@Composable private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange); Text(label)
    }

@Composable private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick); Text(label)
    }

/** Chat header quick picker. */
@Composable
fun ProfilePickerDialog(profiles: List<Profile>, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Profile") },
        text = {
            Column {
                profiles.forEach { p -> RadioRow(p.label, p.id == current) { onPick(p.id) } }
                Spacer(Modifier.height(8.dp))
                Text("Edit profiles in Settings → Profiles.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

/** Suggested profile icons (free entry stays available below the grid). */
internal val SUGGESTED_EMOJI = listOf("🤖", "🔎", "💻", "🧑‍💻", "📚", "✍️", "🎨", "📈", "🛠️", "🔐", "🧪", "🎮",
    "🏠", "💼", "📝", "🌐", "📱", "🎵", "🧠", "⚡", "🗂️", "💬", "🌍", "❤️")

@Composable
internal fun EmojiGrid(selected: String, onPick: (String) -> Unit) {
    Column {
        SUGGESTED_EMOJI.chunked(8).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { e ->
                    val on = e == selected
                    Surface(shape = MaterialTheme.shapes.small,
                        color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(40.dp).clickable { onPick(e) }
                            .semantics { contentDescription = "Icon $e" + if (on) ", selected" else "" }) {
                        Box(contentAlignment = Alignment.Center) { Text(e, style = MaterialTheme.typography.titleLarge, maxLines = 1, softWrap = false) }
                    }
                }
            }
        }
    }
}
