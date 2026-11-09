package com.verdroid.app.ui.skills

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.verdroid.app.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdroid.app.data.skills.Skill
import com.verdroid.app.data.skills.SkillStore
import com.verdroid.app.ui.components.BackScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SkillsViewModel @Inject constructor(private val store: SkillStore) : ViewModel() {
    val skills = store.skills
    init { store.refresh() }
    fun setEnabled(id: String, on: Boolean) { store.setEnabled(id, on) }
    fun delete(id: String) { store.delete(id) }
}

/** Settings > Skills: procedures the agent saved (files/skills/<id>/SKILL.md). Only enabled skills go into the prompt. */
@Composable
fun SkillsScreen(onBack: () -> Unit, vm: SkillsViewModel = hiltViewModel()) {
    val skills by vm.skills.collectAsStateWithLifecycle()
    var viewing by remember { mutableStateOf<Skill?>(null) }
    var confirmDelete by remember { mutableStateOf<Skill?>(null) }

    BackScaffold(stringResource(R.string.skills), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(stringResource(R.string.skills_intro),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (skills.isEmpty()) item {
                Text(stringResource(R.string.no_skills_hint),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            }
            items(skills, key = { it.id }) { s ->
                ListItem(
                    modifier = Modifier.clickable { viewing = s },
                    headlineContent = { Text(s.name) },
                    supportingContent = {
                        Column {
                            if (s.description.isNotBlank()) Text(s.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(s.id + " · " + (if (s.enabled) stringResource(R.string.enabled_word) else stringResource(R.string.off_word)), style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                color = if (s.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    trailingContent = {
                        Row {
                            TextButton(onClick = { confirmDelete = s }) { Text(stringResource(R.string.delete)) }
                            Switch(checked = s.enabled, onCheckedChange = { vm.setEnabled(s.id, it) })
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    viewing?.let { s ->
        AlertDialog(onDismissRequest = { viewing = null }, title = { Text(s.name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (s.description.isNotBlank()) Text(s.description, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(s.body, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = { viewing = null }) { Text(stringResource(R.string.close)) } })
    }
    confirmDelete?.let { s ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text(stringResource(R.string.delete_skill, s.name)) },
            text = { Text(stringResource(R.string.delete_skill_desc)) },
            confirmButton = { TextButton(onClick = { vm.delete(s.id); confirmDelete = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
