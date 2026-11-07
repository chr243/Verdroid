package com.verdroid.app.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.verdroid.app.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdroid.app.data.mcp.McpManager
import com.verdroid.app.data.mcp.McpNaming
import com.verdroid.app.data.mcp.McpServerConfig
import com.verdroid.app.data.mcp.McpState

/** Settings > MCP servers: remote servers (Streamable HTTP / legacy SSE), on/off, status, per-tool switches. */
@Composable
fun McpServersSection(mcp: McpManager, disabled: Set<String>, setToolEnabled: (String, Boolean) -> Unit) {
    val servers by mcp.servers.collectAsStateWithLifecycle()
    val status by mcp.status.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<McpServerConfig?>(null) }
    var adding by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(setOf<String>()) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.servers), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(top = 20.dp))
            TextButton(onClick = { adding = true }, modifier = Modifier.padding(top = 20.dp)) { Text(stringResource(R.string.add_server)) }
        }
        Text(stringResource(R.string.mcp_intro),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        servers.forEachIndexed { idx, s ->
            val st = status[s.id]
            Surface(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                color = com.verdroid.app.ui.components.Zebra.color(idx)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleSmall)
                            Text(s.url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 2)
                        }
                        Switch(checked = s.enabled, onCheckedChange = { mcp.setEnabled(s.id, it) })
                    }
                    val tools = st?.tools.orEmpty()
                    val line = when (st?.state ?: McpState.OFF) {
                        McpState.OFF -> "Off" + if (tools.isNotEmpty()) " (${tools.size} tools last time)" else ""
                        McpState.CONNECTING -> stringResource(R.string.connecting_)
                        McpState.CONNECTED -> stringResource(R.string.connected_n_tools, tools.size) + (st?.transport?.let { " ($it)" } ?: "")
                        McpState.ERROR -> stringResource(R.string.error_word)
                    }
                    Text(line, style = MaterialTheme.typography.labelMedium,
                        color = when (st?.state) { McpState.CONNECTED -> MaterialTheme.colorScheme.primary; McpState.ERROR -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant })
                    st?.error?.takeIf { st.state == McpState.ERROR }?.let { err ->
                        Text(err, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 6)
                        com.verdroid.app.ui.components.CopyButton(err, label = stringResource(R.string.copy_error))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (tools.isNotEmpty()) TextButton(onClick = { expanded = if (s.id in expanded) expanded - s.id else expanded + s.id }) {
                            Text(if (s.id in expanded) stringResource(R.string.hide_tools) else stringResource(R.string.tools_n, tools.size))
                        }
                        if (s.enabled) TextButton(onClick = { mcp.refresh(s.id) }) { Text(stringResource(R.string.reconnect)) }
                        TextButton(onClick = { editing = s }) { Text(stringResource(R.string.edit)) }
                    }
                    if (s.id in expanded) tools.forEach { t ->
                        val exposed = McpNaming.toolName(s.name, t.name)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                                Text(t.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            }
                            Switch(checked = exposed !in disabled, onCheckedChange = { setToolEnabled(exposed, it) })
                        }
                    }
                }
            }
        }
    }

    if (adding || editing != null) {
        McpServerDialog(initial = editing, hasSecret = editing?.let { mcp.hasSecret(it.id) } == true,
            onDismiss = { adding = false; editing = null },
            onDelete = editing?.let { e -> { mcp.delete(e.id); editing = null } },
            onSave = { name, url, header, secret ->
                mcp.upsert(editing?.id, name, url, header, secret, editing?.enabled ?: true)
                adding = false; editing = null
            })
    }
}

@Composable
private fun McpServerDialog(initial: McpServerConfig?, hasSecret: Boolean, onDismiss: () -> Unit, onDelete: (() -> Unit)?,
                            onSave: (name: String, url: String, header: String, secret: String?) -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember { mutableStateOf(initial?.url ?: "https://") }
    var header by remember { mutableStateOf(initial?.authHeaderName ?: "Authorization") }
    var secret by remember { mutableStateOf("") }
    var clearSecret by remember { mutableStateOf(false) }
    val urlOk = url.trim().let { it.startsWith("https://") || it.startsWith("http://") } && url.trim().length > 10
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (initial == null) stringResource(R.string.add_mcp_server) else stringResource(R.string.edit_arg, initial.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.mcp_url_label)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), isError = !urlOk)
                OutlinedTextField(header, { header = it }, label = { Text(stringResource(R.string.auth_header_optional)) }, singleLine = true)
                OutlinedTextField(secret, { secret = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    label = { Text(if (hasSecret) stringResource(R.string.token_saved) else stringResource(R.string.token_optional)) },
                    supportingText = { Text(stringResource(R.string.token_storage_note)) })
                if (hasSecret) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { clearSecret = !clearSecret }) {
                    Checkbox(clearSecret, { clearSecret = it }); Text(stringResource(R.string.remove_saved_token))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && urlOk, onClick = {
                onSave(name, url, header, when { clearSecret -> ""; secret.isNotBlank() -> secret; else -> null })
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        })
}
