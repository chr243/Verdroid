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
import androidx.compose.ui.res.stringResource
import com.verdroid.app.R
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val store: ProfileStore,
    private val skillStore: SkillStore,
    memoryRepo: com.verdroid.app.data.memory.MemoryRepository,
    settings: com.verdroid.app.domain.repository.SettingsRepository,
) : ViewModel() {
    val profiles = store.allProfiles
    val enabled = store.enabled
    fun setEnabled(on: Boolean) = store.setEnabled(on)
    val activeId = store.activeId
    val skills = skillStore.skills
    /** Long-term memories (pickable in a Selected-only profile). */
    val memories = memoryRepo.memories.map { l -> l.filter { it.chatId == null } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val models = settings.modelPriority.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    init { skillStore.refresh() }

    fun newId(): String = Profiles.newId(store.allProfiles.value.map { it.id })
    /** "New profile": a copy of the active one. */
    fun draftCopy(): Profile = Profiles.copyOf(store.active(), newId(), context.getString(R.string.new_profile))
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
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Profile?>(null) }
    var confirmDelete by remember { mutableStateOf<Profile?>(null) }
    var newMenu by remember { mutableStateOf(startNew) }

    if (!enabled) editing = null
    editing?.let { p ->
        // A profile created from the main screen's "+ Add new" becomes the active one.
        ProfileEditor(p, vm, onDone = { saved -> saved?.let { vm.save(it); if (startNew) vm.activate(it.id) }; editing = null })
        return
    }

    BackScaffold(stringResource(R.string.profiles), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item("toggle") {
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.profiles), style = MaterialTheme.typography.titleMedium)
                        Text(if (enabled) stringResource(R.string.profiles_on) else stringResource(R.string.profiles_off),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.Switch(checked = enabled, onCheckedChange = { vm.setEnabled(it) })
                }
            }
            if (enabled) {
            item {
                Text(stringResource(R.string.profiles_intro),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            items(profiles, key = { it.id }) { p ->
                ProfileRow(p, p.id == active, onUse = { vm.activate(p.id) }, onEdit = { editing = p }, onDelete = { confirmDelete = p })
            }
            item {
                Box(Modifier.padding(16.dp)) {
                    Button(onClick = { newMenu = true }) { Text(stringResource(R.string.new_profile)) }
                    DropdownMenu(expanded = newMenu, onDismissRequest = { newMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.copy_of_current)) }, onClick = { newMenu = false; editing = vm.draftCopy() })
                        vm.templates().forEach { t ->
                            DropdownMenuItem(text = { Text(stringResource(R.string.template_arg, t.label)) }, onClick = { newMenu = false; editing = t })
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
    }

    confirmDelete?.let { p ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text(stringResource(R.string.delete_profile, p.name)) },
            text = { Text(stringResource(R.string.delete_profile_desc)) },
            confirmButton = { TextButton(onClick = { vm.delete(p.id); confirmDelete = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun summary(p: Profile): String = listOfNotNull(
    if (p.model.isBlank()) "Model priority" else ModelIds.label(p.model),
    if (p.tools == null) stringResource(R.string.all_tools) else stringResource(R.string.n_tool_groups, p.tools.size),
    if (p.skills == null) stringResource(R.string.all_skills) else stringResource(R.string.n_skills, p.skills.size),
    if (p.memories == null) stringResource(R.string.all_memories) else stringResource(R.string.n_memories, p.memories.size),
    if (p.instructions.isNotBlank()) stringResource(R.string.instructions_word) else null,
).joinToString(" · ")

@Composable
private fun ProfileEditor(initial: Profile, vm: ProfilesViewModel, onDone: (Profile?) -> Unit) {
    var p by remember(initial.id) { mutableStateOf(initial) }
    val skills by vm.skills.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    BackHandler { onDone(null) }
    BackScaffold(if (initial.isDefault) stringResource(R.string.default_profile) else stringResource(R.string.edit_profile), { onDone(null) },
        actions = { TextButton(onClick = { onDone(p) }) { Text(stringResource(R.string.save)) } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            ProfileEditorContent(p, skills.map { PickItem(it.id, it.name + if (!it.enabled) " (off in Settings → Skills)" else "", it.description.takeIf { d -> d.isNotBlank() }) },
                memories.map { PickItem(it.id.toString(), it.text.take(120), it.tags.takeIf { t -> t.isNotEmpty() }?.joinToString(", ")) },
                models) { p = it }
        }
    }
}

/** Editor sections in order: Icon, Model, Tools, Skills, Memory, Instructions (last). */
@Composable
internal fun ProfileEditorContent(initialP: Profile, skills: List<PickItem>, memories: List<PickItem>, models: List<String>, onChange: (Profile) -> Unit) {
    val p = initialP
    fun set(n: Profile) = onChange(n)
    var modelMenu by remember { mutableStateOf(false) }
    run {
            Section(stringResource(R.string.icon))
            EmojiGrid(p.emoji) { set(p.copy(emoji = it)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(p.emoji, { set(p.copy(emoji = it.take(8))) }, label = { Text(stringResource(R.string.any_emoji)) }, singleLine = true, modifier = Modifier.width(88.dp))
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(p.name, { set(p.copy(name = it.take(40))) }, label = { Text(stringResource(R.string.name)) }, singleLine = true,
                    enabled = !p.isDefault, modifier = Modifier.weight(1f))
            }
            Section(stringResource(R.string.model))
            Box {
                OutlinedButton(onClick = { modelMenu = true }) { Text(if (p.model.isBlank()) stringResource(R.string.follow_model_priority) else ModelIds.label(p.model)) }
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.follow_model_priority)) }, onClick = { set(p.copy(model = "")); modelMenu = false })
                    (models + listOfNotNull(p.model.takeIf { it.isNotBlank() })).distinct().forEach { m ->
                        DropdownMenuItem(text = { Text(ModelIds.label(m)) }, onClick = { set(p.copy(model = m)); modelMenu = false })
                    }
                }
            }
            Hint(stringResource(R.string.profile_model_hint))

            Section(stringResource(R.string.tools))
            ToolsSection(p) { set(it) }
            Hint(stringResource(R.string.profile_tools_hint))

            Section(stringResource(R.string.skills))
            SkillsSection(p, skills) { set(it) }

            Section(stringResource(R.string.memory))
            if (p.isDefault) Hint(stringResource(R.string.default_all_memories))
            else {
                MemorySection(p, memories) { set(it) }
                Hint(stringResource(R.string.profile_memory_hint))
            }

            Section(stringResource(R.string.instructions))
            OutlinedTextField(p.instructions, { set(p.copy(instructions = it.take(Profiles.MAX_INSTRUCTIONS))) },
                placeholder = { Text(stringResource(R.string.profile_instructions_hint)) },
                minLines = 4, modifier = Modifier.fillMaxWidth())
            Hint(stringResource(R.string.profile_instructions_note))
            Spacer(Modifier.height(12.dp))
            Text(Profiles.SHARED_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
    }
}

@Composable private fun Section(t: String) =
    Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))

@Composable private fun Hint(t: String) =
    Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))

@Composable private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick); Text(label)
    }

/** Chat header quick picker. */
@Composable
fun ProfilePickerDialog(profiles: List<Profile>, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.profile)) },
        text = {
            Column {
                profiles.forEach { p -> RadioRow(p.label, p.id == current) { onPick(p.id) } }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.edit_profiles_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
}

/** Suggested profile icons (free entry stays available below the grid). */
internal val SUGGESTED_EMOJI = listOf("🤖", "🔎", "💻", "🧑‍💻", "📚", "✍️", "🎨", "📈", "🛠️", "🔐", "🧪", "🎮",
    "🏠", "💼", "📝", "🌐", "📱", "🎵", "🧠", "⚡", "🗂️", "💬", "🌍", "❤️")

@Composable
internal fun EmojiGrid(selected: String, onPick: (String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column {
        SUGGESTED_EMOJI.chunked(8).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { e ->
                    val on = e == selected
                    Surface(shape = MaterialTheme.shapes.small,
                        color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(40.dp).clickable { onPick(e) }
                            .semantics { contentDescription = if (on) ctx.getString(R.string.cd_icon_selected, e) else ctx.getString(R.string.cd_icon, e) }) {
                        Box(contentAlignment = Alignment.Center) { Text(e, style = MaterialTheme.typography.titleLarge, maxLines = 1, softWrap = false) }
                    }
                }
            }
        }
    }
}

/** One Settings → Profiles row: name + summary, actions on their own line (Use · Edit · Delete; Default has no Delete). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfileRow(p: Profile, isActive: Boolean, onUse: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 2.dp)) {
        Text(p.label + if (isActive) "  · " + stringResource(R.string.active_word) else "", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(summary(p), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onUse, enabled = !isActive,
                modifier = Modifier.semantics { contentDescription = if (isActive) context.getString(R.string.cd_profile_active, p.name) else context.getString(R.string.cd_use_profile, p.name) }) {
                Text(if (isActive) stringResource(R.string.in_use) else stringResource(R.string.use), maxLines = 1, softWrap = false)
            }
            TextButton(onClick = onEdit, modifier = Modifier.semantics { contentDescription = context.getString(R.string.cd_edit_profile, p.name) }) { Text(stringResource(R.string.edit), maxLines = 1, softWrap = false) }
            if (!p.isDefault) TextButton(onClick = onDelete, modifier = Modifier.semantics { contentDescription = context.getString(R.string.cd_delete_profile, p.name) },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.delete), maxLines = 1, softWrap = false) }
        }
    }
}

/** One pickable item of a profile section (tool group, skill or memory). */
internal data class PickItem(val id: String, val title: String, val subtitle: String? = null)

@Composable
internal fun ToolsSection(p: Profile, onChange: (Profile) -> Unit) =
    SelectionSection("tools", ToolPreset.entries.map { PickItem(it.groupId, it.label, it.description) }, p.tools,
        startWith = Profiles.ALL_TOOL_GROUPS.toSet()) { onChange(p.copy(tools = it, toolGroupsOff = emptySet())) }

@Composable
internal fun SkillsSection(p: Profile, items: List<PickItem>, onChange: (Profile) -> Unit) =
    SelectionSection("skills", items, p.skills, startWith = items.map { it.id }.toSet()) { onChange(p.copy(skills = it)) }

@Composable
internal fun MemorySection(p: Profile, items: List<PickItem>, onChange: (Profile) -> Unit) =
    SelectionSection("memories", items, p.memories?.map { it.toString() }?.toSet(),
        startWith = items.map { it.id }.toSet()) { sel -> onChange(p.copy(memories = sel?.mapNotNull { it.toLongOrNull() }?.toSet())) }

/**
 * Same pattern for Tools, Skills and Memory: a dropdown "All <noun> (incl. new ones)" / "Selected only";
 * Selected shows the chosen items (✕ removes) and "+ Add" opens a searchable multi-select picker of the rest.
 */
@Composable
internal fun SelectionSection(noun: String, items: List<PickItem>, selected: Set<String>?, startWith: Set<String>, onChange: (Set<String>?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "$noun mode" }) {
            Text(if (selected == null) stringResource(R.string.all_noun, noun) else stringResource(R.string.selected_only_n, selected.size), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("  ▾")
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.all_noun, noun)) }, onClick = { onChange(null); menu = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.selected_only)) }, onClick = { if (selected == null) onChange(startWith); menu = false })
        }
    }
    if (selected == null) return
    val byId = items.associateBy { it.id }
    if (selected.isEmpty()) Hint(stringResource(R.string.none_selected, noun))
    selected.sortedBy { id -> items.indexOfFirst { it.id == id }.let { if (it < 0) Int.MAX_VALUE else it } }.forEach { id ->
        val it = byId[id]
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(it?.title ?: "(deleted)", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                it?.subtitle?.let { s -> Text(s, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            IconButton(onClick = { onChange(selected - id) }, modifier = Modifier.semantics { contentDescription = "Remove ${it?.title ?: id}" }) { Text("✕") }
        }
    }
    val rest = items.filter { it.id !in selected }
    TextButton(onClick = { picking = true }, enabled = rest.isNotEmpty(), modifier = Modifier.semantics { contentDescription = "Add $noun" }) {
        Text(if (rest.isEmpty()) stringResource(R.string.all_noun_added, noun) else stringResource(R.string.plus_add))
    }
    if (picking) PickerDialog(noun, rest, onDismiss = { picking = false }) { onChange(selected + it); picking = false }
}

@Composable
internal fun PickerDialog(noun: String, items: List<PickItem>, onDismiss: () -> Unit, onAdd: (Set<String>) -> Unit) {
    var q by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    val shown = items.filter { q.isBlank() || it.title.contains(q, true) || it.subtitle?.contains(q, true) == true }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.add_noun, noun)) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, placeholder = { Text(stringResource(R.string.search_word)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.id }) { item ->
                        Row(Modifier.fillMaxWidth().clickable { chosen = if (item.id in chosen) chosen - item.id else chosen + item.id },
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(item.id in chosen, { on -> chosen = if (on) chosen + item.id else chosen - item.id })
                            Column {
                                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                    if (shown.isEmpty()) item { Text(stringResource(R.string.no_matches), modifier = Modifier.padding(8.dp)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(chosen) }, enabled = chosen.isNotEmpty()) { Text(if (chosen.isEmpty()) stringResource(R.string.add) else stringResource(R.string.add_n, chosen.size)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
