package com.verdroid.app.ui.theme

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.verdroid.app.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Settings > Theme: mode (System/Light/Dark) + palette. Text rows; the choice is saved and applies instantly. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeScreen(onBack: () -> Unit) {
    val store = ThemeStore.get(LocalContext.current)
    val mode by store.mode.collectAsState()
    val palette by store.palette.collectAsState()
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.theme)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Header(stringResource(R.string.mode))
            ThemeMode.entries.forEach { m ->
                Row(stringResource(m.labelRes()), when (m) { ThemeMode.SYSTEM -> stringResource(R.string.follow_phone_dark); ThemeMode.LIGHT -> stringResource(R.string.always_light); ThemeMode.DARK -> stringResource(R.string.always_dark) },
                    selected = mode == m) { store.setMode(m) }
            }
            Header(stringResource(R.string.colors))
            Palette.entries.forEach { p ->
                val unavailable = p == Palette.DYNAMIC && Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                val palDesc = stringResource(p.descRes())
                Row(stringResource(p.labelRes()), if (unavailable) stringResource(R.string.pal_unavailable, palDesc) else palDesc,
                    selected = palette == p) { store.setPalette(p) }
            }
            Header(stringResource(R.string.font_size))
            val scale by store.fontScale.collectAsState()
            var pending by remember(scale) { mutableFloatStateOf(scale) }
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("A", style = MaterialTheme.typography.bodySmall)
                    Slider(value = pending, onValueChange = { pending = FontScale.clamp(it) },
                        onValueChangeFinished = { store.setFontScale(pending) },
                        valueRange = FontScale.MIN..FontScale.MAX, steps = FontScale.STEPS,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            .semantics { contentDescription = ctx.getString(R.string.font_size_label, FontScale.label(pending)) })
                    Text("A", style = MaterialTheme.typography.titleLarge)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(FontScale.label(pending) + if (pending == FontScale.DEFAULT) " (default)" else "",
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    TextButton(enabled = scale != FontScale.DEFAULT || pending != FontScale.DEFAULT,
                        onClick = { pending = FontScale.DEFAULT; store.setFontScale(FontScale.DEFAULT) }) { Text(stringResource(R.string.reset)) }
                }
                // Live preview at the pending size (applied app-wide when you let go of the slider).
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(d.density, d.fontScale / scale * pending)) {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.preview), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.preview_msg), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.theme_applies), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Text(stringResource(R.string.theme_bubbles_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun Header(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))

@Composable
private fun Row(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { RadioButton(selected = selected, onClick = onClick) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
