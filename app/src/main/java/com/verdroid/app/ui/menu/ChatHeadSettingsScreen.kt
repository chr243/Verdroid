package com.verdroid.app.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.verdroid.app.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdroid.app.domain.model.ChatHeadMode
import com.verdroid.app.ui.theme.labelRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHeadSettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val mode by vm.chatHeadMode.collectAsStateWithLifecycle()
    val xiaomi = remember { vm.chatHeads.isXiaomiFamily() }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.chat_heads)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.chat_heads_intro),
                style = MaterialTheme.typography.bodyMedium)
            ModeRow(ChatHeadMode.AUTO, mode, stringResource(R.string.mode_auto),
                stringResource(R.string.mode_auto_desc) +
                    if (xiaomi) " (this device: overlay)" else "") { vm.setChatHeadMode(it) }
            ModeRow(ChatHeadMode.BUBBLES, mode, stringResource(R.string.mode_bubbles), stringResource(R.string.mode_bubbles_desc)) { vm.setChatHeadMode(it) }
            ModeRow(ChatHeadMode.OVERLAY, mode, stringResource(R.string.mode_overlay), stringResource(R.string.mode_overlay_desc)) { vm.setChatHeadMode(it) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            val autoHead by vm.appPrefs.autoChatHeadOnHome.collectAsStateWithLifecycle()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.auto_chat_head_home), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.auto_chat_head_desc),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.Switch(checked = autoHead, onCheckedChange = vm.appPrefs::setAutoChatHeadOnHome)
            }
            // Custom chat head GIFs, per theme (without one, Miku uses its bundled GIFs and other themes the normal bubble)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            val gifCtx = androidx.compose.ui.platform.LocalContext.current
            val theme by remember { com.verdroid.app.ui.theme.ThemeStore.get(gifCtx).palette }.collectAsStateWithLifecycle()
            Text(stringResource(R.string.chat_head_gifs, stringResource(theme.labelRes())), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.chat_head_gifs_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CustomGifRow(stringResource(R.string.on_task_gif), theme, com.verdroid.app.chathead.ChatHeadCustomGif.Slot.RUNNING)
            CustomGifRow(stringResource(R.string.idle_gif), theme, com.verdroid.app.chathead.ChatHeadCustomGif.Slot.IDLE)
        }
    }
}

@Composable
private fun ModeRow(value: ChatHeadMode, selected: ChatHeadMode, title: String, subtitle: String, onSelect: (ChatHeadMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) })
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = value == selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CustomGifRow(label: String, theme: com.verdroid.app.ui.theme.Palette, slot: com.verdroid.app.chathead.ChatHeadCustomGif.Slot) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val ver by com.verdroid.app.chathead.ChatHeadCustomGif.version.collectAsStateWithLifecycle()
    val hasCustom = remember(ver, theme) { com.verdroid.app.chathead.ChatHeadCustomGif.custom(ctx, theme, slot) != null }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) com.verdroid.app.chathead.ChatHeadCustomGif.set(ctx, theme, slot, uri) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text((if (hasCustom) stringResource(R.string.custom_file) else stringResource(R.string.default_word)) + " " + stringResource(R.string.for_theme, stringResource(theme.labelRes())), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.TextButton(onClick = { picker.launch(arrayOf("image/gif", "image/webp")) }) { Text(stringResource(R.string.pick)) }
        androidx.compose.material3.TextButton(onClick = { com.verdroid.app.chathead.ChatHeadCustomGif.reset(ctx, theme, slot) }, enabled = hasCustom) { Text(stringResource(R.string.reset)) }
    }
}
