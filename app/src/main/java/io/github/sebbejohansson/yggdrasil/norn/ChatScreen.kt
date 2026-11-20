package io.github.sebbejohansson.yggdrasil.norn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sebbejohansson.yggdrasil.llm.LlmRole
import io.github.sebbejohansson.yggdrasil.llm.ProviderStatus
import io.github.sebbejohansson.yggdrasil.model.DownloadState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Norn")
                        Text(
                            statusLine(s),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = { TextButton(onClick = vm::clear) { Text("Clear") } },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .imePadding(),
        ) {
            SetupCard(s, onDownload = vm::downloadModel, onRefresh = { vm.refreshProviders() }, onAllowClaude = vm::setAllowClaude)
            Messages(s, Modifier.weight(1f))
            s.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Composer(busy = s.busy, onSend = vm::send, onStop = vm::stop)
        }
    }
}

private fun statusLine(s: ChatUiState): String {
    val ready = s.providers.filter { it.status == ProviderStatus.Ready }
    if (ready.isEmpty()) return "No model ready"
    return ready.joinToString(" · ") { it.name } + (s.backend?.let { " · $it" } ?: "")
}

@Composable
private fun SetupCard(
    s: ChatUiState,
    onDownload: () -> Unit,
    onRefresh: () -> Unit,
    onAllowClaude: (Boolean) -> Unit,
) {
    val notReady = s.providers.filter { it.status != ProviderStatus.Ready }
    val claudeRow = s.providers.firstOrNull { it.leavesDevice }
    if (notReady.isEmpty() && s.download == null && claudeRow == null) return

    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in notReady) {
                val why = when (val st = row.status) {
                    is ProviderStatus.NeedsSetup -> st.reason
                    is ProviderStatus.Unavailable -> st.reason
                    ProviderStatus.Ready -> ""
                }
                Text("${row.name}: $why", style = MaterialTheme.typography.bodySmall)
            }
            when (val d = s.download) {
                is DownloadState.Downloading -> {
                    LinearProgressIndicator(progress = { d.fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "%.0f of %.0f MB".format(d.bytes / 1e6, d.total / 1e6),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                DownloadState.Verifying -> Text("Checking the file…", style = MaterialTheme.typography.labelSmall)
                DownloadState.Done, null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val local = notReady.firstOrNull { !it.leavesDevice && it.status is ProviderStatus.NeedsSetup }
                    if (local != null) Button(onClick = onDownload) { Text("Download model") }
                    TextButton(onClick = onRefresh) { Text("Check again") }
                }
            }
            if (claudeRow != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Allow Claude (text leaves the phone)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = s.allowClaude, onCheckedChange = onAllowClaude)
                }
            }
        }
    }
}

@Composable
private fun Messages(s: ChatUiState, modifier: Modifier) {
    val list = rememberLazyListState()
    val count = s.messages.size + if (s.pending != null) 1 else 0
    LaunchedEffect(count, s.pending?.length) {
        if (count > 0) list.animateScrollToItem(count - 1)
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = list,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        items(s.messages) { Bubble(it.text, mine = it.role == LlmRole.USER) }
        s.pending?.let { item { Bubble(it.ifEmpty { "…" }, mine = false) } }
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Composer(busy: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Ask Norn") },
            maxLines = 5,
        )
        Spacer(Modifier.width(8.dp))
        if (busy) {
            Button(onClick = onStop) { Text("Stop") }
        } else {
            Button(onClick = { onSend(text); text = "" }, enabled = text.isNotBlank()) { Text("Send") }
        }
    }
}
