package io.github.sebbejohansson.yggdrasil.gjallar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.sebbejohansson.yggdrasil.AppContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class AlertRow(val alert: Alert, val muted: Boolean)

data class AlertsUiState(val active: List<AlertRow> = emptyList(), val resolved: List<Alert> = emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
class AlertsViewModel(private val gjallar: GjallarService) : ViewModel() {

    val state = gjallar.changes.flow.mapLatest {
        AlertsUiState(
            active = gjallar.active().map { AlertRow(it, gjallar.isMuted(it.source, it.key)) },
            resolved = gjallar.resolved(20),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertsUiState())

    fun acknowledge(a: Alert) = viewModelScope.launch { gjallar.acknowledge(a.id) }
    fun snoozeHour(a: Alert) = viewModelScope.launch { gjallar.snooze(a.id, Instant.now().plus(Duration.ofHours(1))) }
    fun setMuted(a: Alert, muted: Boolean) = viewModelScope.launch { gjallar.setMuted(a.source, a.key, muted) }

    // To see the whole path on the phone: Gjallar → Hermóðr → notification.
    fun raiseTest(severity: Severity) = viewModelScope.launch {
        gjallar.raise("test", "test-${severity.name.lowercase()}", severity, "Test ${severity.name.lowercase()} alert", "Raised from the Alerts tab")
    }
    fun resolveTests() = viewModelScope.launch { Severity.entries.forEach { gjallar.resolve("test", "test-${it.name.lowercase()}") } }

    companion object {
        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory { initializer { AlertsViewModel(c.gjallar) } }
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault())

@Composable
fun AlertsScreen(vm: AlertsViewModel, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsStateWithLifecycle()
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Test:", style = MaterialTheme.typography.labelMedium)
                AssistChip(onClick = { vm.raiseTest(Severity.WARNING) }, label = { Text("warning") })
                AssistChip(onClick = { vm.raiseTest(Severity.CRITICAL) }, label = { Text("critical") })
                AssistChip(onClick = vm::resolveTests, label = { Text("resolve") })
            }
        }
        if (s.active.isEmpty()) {
            item { Text("No open alerts.", style = MaterialTheme.typography.bodyMedium) }
        }
        items(s.active, key = { it.alert.id }) { row -> ActiveAlert(row, vm) }
        if (s.resolved.isNotEmpty()) {
            item { Text("Resolved", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
            items(s.resolved, key = { "r${it.id}" }) { a ->
                Text(
                    "${a.title} · ${a.source} · ${a.resolvedAt?.let(timeFmt::format) ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ActiveAlert(row: AlertRow, vm: AlertsViewModel) {
    val a = row.alert
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(a.severity.name.lowercase(), color = severityColor(a.severity), style = MaterialTheme.typography.labelMedium)
                Text(a.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            }
            a.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val extras = buildList {
                add("from ${a.source}")
                add("since ${timeFmt.format(a.openedAt)}")
                if (a.state == AlertState.ACKNOWLEDGED) add("acknowledged")
                if (a.snoozedUntil?.isAfter(Instant.now()) == true) add("snoozed until ${timeFmt.format(a.snoozedUntil)}")
                if (row.muted) add("muted")
            }
            Text(extras.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (a.state == AlertState.OPEN) OutlinedButton(onClick = { vm.acknowledge(a) }) { Text("Acknowledge") }
                TextButton(onClick = { vm.snoozeHour(a) }) { Text("Snooze 1 h") }
                TextButton(onClick = { vm.setMuted(a, !row.muted) }) { Text(if (row.muted) "Unmute" else "Mute") }
            }
        }
    }
}

@Composable
private fun severityColor(s: Severity): Color = when (s) {
    Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    Severity.WARNING -> MaterialTheme.colorScheme.tertiary
    Severity.CRITICAL -> MaterialTheme.colorScheme.error
}
