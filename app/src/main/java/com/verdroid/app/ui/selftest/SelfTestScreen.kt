package com.verdroid.app.ui.selftest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.verdroid.app.data.selftest.SelfTestCoverage
import com.verdroid.app.data.selftest.SelfTestResult
import com.verdroid.app.data.selftest.SelfTestRunner
import com.verdroid.app.data.selftest.SelfTestStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SelfTestViewModel @Inject constructor(private val runner: SelfTestRunner) : ViewModel() {
    private val _results = MutableStateFlow<List<SelfTestResult>>(emptyList())
    val results = _results.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()
    private val _report = MutableStateFlow<String?>(null)
    val report = _report.asStateFlow()
    private val _savedTo = MutableStateFlow<String?>(null)
    val savedTo = _savedTo.asStateFlow()

    fun run() {
        if (_running.value) return
        _running.value = true; _results.value = emptyList(); _report.value = null; _savedTo.value = null
        viewModelScope.launch {
            try {
                val all = runner.runAll { r -> _results.value = _results.value + r }
                val md = runner.report(all)
                _report.value = md
                _savedTo.value = runner.save(md)?.absolutePath
            } finally { _running.value = false }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelfTestScreen(onBack: () -> Unit, vm: SelfTestViewModel = hiltViewModel()) {
    val results by vm.results.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val savedTo by vm.savedTo.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Scaffold(topBar = {
        TopAppBar(title = { Text("Self-test") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Text("Safe, non-destructive checks of every feature (no installs, no trades, no model calls). " +
                "Missing apps/permissions are skipped.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !running, onClick = vm::run) { Text(if (results.isEmpty()) "Run self-test" else "Run again") }
                val md = report
                OutlinedButton(enabled = md != null, onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Verdroid self-test", md))
                    Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
                OutlinedButton(enabled = md != null, onClick = {
                    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, md)
                    runCatching { context.startActivity(Intent.createChooser(i, "Share self-test")) }
                }) { Text("Share") }
            }
            if (running) LinearProgressIndicator(
                progress = { results.size / SelfTestCoverage.CHECKS.size.toFloat() }, modifier = Modifier.fillMaxWidth())
            savedTo?.let { Text("Saved: $it", style = MaterialTheme.typography.labelSmall) }
            if (report != null) {
                val f = results.count { it.status == SelfTestStatus.FAIL }
                Text("${results.count { it.status == SelfTestStatus.PASS }} pass · $f fail · " +
                    "${results.count { it.status == SelfTestStatus.SKIP }} skip", style = MaterialTheme.typography.titleSmall,
                    color = if (f > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp))
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(results, key = { it.id }) { r ->
                    val color = when (r.status) {
                        SelfTestStatus.PASS -> MaterialTheme.colorScheme.primary
                        SelfTestStatus.FAIL -> MaterialTheme.colorScheme.error
                        SelfTestStatus.SKIP -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    ListItem(
                        headlineContent = { Text(r.id) },
                        supportingContent = { Text(r.detail, style = MaterialTheme.typography.bodySmall) },
                        trailingContent = { Text("${r.status} · ${r.ms}ms", color = color, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }
    }
}
