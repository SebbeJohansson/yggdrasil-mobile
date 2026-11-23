package io.github.sebbejohansson.yggdrasil.gjallar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.sebbejohansson.yggdrasil.AppContainer
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudButton
import io.github.sebbejohansson.yggdrasil.ui.HudButtonKind
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudSection
import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.HudTextField
import io.github.sebbejohansson.yggdrasil.saga.Mirror
import io.github.sebbejohansson.yggdrasil.saga.SagaService
import io.github.sebbejohansson.yggdrasil.saga.SagaSettings
import io.github.sebbejohansson.yggdrasil.saga.SyncStatus
import io.github.sebbejohansson.yggdrasil.ui.hudPanel
import io.github.sebbejohansson.yggdrasil.ui.leftStripe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class AlertRow(val alert: Alert, val muted: Boolean)

data class AlertsUiState(
    val active: List<AlertRow> = emptyList(),
    val resolved: List<Alert> = emptyList(),
    /** The other devices' alerts (the desktop's), through Mímir. */
    val mirrors: List<Mirror> = emptyList(),
    val sync: SyncStatus = SyncStatus(),
)

/** What is set up for Mímir, for the form. */
data class MimirForm(val url: String, val token: String, val device: String, val driveFrom: String = "")

@OptIn(ExperimentalCoroutinesApi::class)
class AlertsViewModel(
    private val gjallar: GjallarService,
    private val saga: SagaService,
    private val settings: SagaSettings,
) : ViewModel() {

    val state = combine(gjallar.changes.flow, saga.changes.flow, saga.status) { _, _, status -> status }.mapLatest { status ->
        AlertsUiState(
            active = gjallar.active().map { AlertRow(it, gjallar.isMuted(it.source, it.key)) },
            resolved = gjallar.resolved(20),
            mirrors = saga.mirrors(),
            sync = status,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertsUiState())

    fun form() = MimirForm(settings.url, settings.token, settings.device, settings.driveFrom)

    fun saveMimir(form: MimirForm) {
        settings.url = form.url
        settings.token = form.token
        settings.device = form.device
        settings.driveFrom = form.driveFrom
        syncNow()
    }

    fun syncNow() = viewModelScope.launch { saga.sync() }

    fun acknowledge(m: Mirror) = viewModelScope.launch { saga.acknowledge(m); saga.sync() }
    fun snooze(m: Mirror, hours: Long) = viewModelScope.launch { saga.snooze(m, Instant.now().plus(Duration.ofHours(hours))); saga.sync() }
    fun setMuted(m: Mirror, muted: Boolean) = viewModelScope.launch { saga.setMuted(m, muted); saga.sync() }

    fun acknowledge(a: Alert) = viewModelScope.launch { gjallar.acknowledge(a.id) }
    fun snooze(a: Alert, hours: Long) = viewModelScope.launch { gjallar.snooze(a.id, Instant.now().plus(Duration.ofHours(hours))) }
    fun setMuted(a: Alert, muted: Boolean) = viewModelScope.launch { gjallar.setMuted(a.source, a.key, muted) }

    // To see the whole path on the phone: Gjallar → Hermóðr → notification.
    fun raiseTest(severity: Severity) = viewModelScope.launch {
        gjallar.raise("test", "test-${severity.name.lowercase()}", severity, "Test ${severity.name.lowercase()} alert", "Raised from Settings")
    }
    fun resolveTests() = viewModelScope.launch { Severity.entries.forEach { gjallar.resolve("test", "test-${it.name.lowercase()}") } }

    companion object {
        fun factory(c: AppContainer): ViewModelProvider.Factory =
            viewModelFactory { initializer { AlertsViewModel(c.gjallar, c.saga, c.sagaSettings) } }
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault())

private fun Severity.color(): Color = when (this) {
    Severity.INFO -> Hud.cyan
    Severity.WARNING -> Hud.yellow
    Severity.CRITICAL -> Hud.red
}

/** Gjallar, as the dashboard's alert list: a severity line down the left of each. */
@Composable
fun AlertsScreen(vm: AlertsViewModel, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsStateWithLifecycle()
    val open = s.active.count { it.alert.state == AlertState.OPEN }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                Text("Gjallar", style = HudText.title)
                HudLabel(
                    when {
                        open > 0 -> "$open open · ${s.active.size - open} seen"
                        s.active.isNotEmpty() -> "${s.active.size} seen"
                        else -> "all quiet"
                    },
                    color = if (open > 0) Hud.amber else Hud.overlay2,
                )
            }
        }
        item { HudSection("Active", Modifier.padding(top = 8.dp)) }
        if (s.active.isEmpty()) {
            item {
                Text(
                    "Nothing wants you.",
                    style = HudText.small.copy(color = Hud.overlay0, textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        } else {
            item {
                Column(
                    Modifier.fillMaxWidth().hudPanel(warn = open > 0).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    s.active.forEach { row -> AlertItem(row, vm) }
                }
            }
        }
        if (s.resolved.isNotEmpty()) {
            item { HudSection("Healed", Modifier.padding(top = 12.dp)) }
            items(s.resolved, key = { "r${it.id}" }) { a ->
                Row(
                    Modifier.fillMaxWidth().alpha(0.55f).leftStripe(Hud.green).padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(a.title, style = HudText.small, modifier = Modifier.weight(1f))
                    Text("${a.source} · ${a.resolvedAt?.let(timeFmt::format) ?: ""}", style = HudText.meta)
                }
            }
        }
        if (s.mirrors.isNotEmpty()) {
            s.mirrors.groupBy { it.device }.forEach { (device, mirrors) ->
                item { HudSection(device.replaceFirstChar { it.uppercase() }, Modifier.padding(top = 12.dp)) }
                item {
                    Column(
                        Modifier.fillMaxWidth().hudPanel(warn = false).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        mirrors.forEach { m -> MirrorItem(m, vm) }
                    }
                }
            }
        }
        if (s.sync.lastError != null) {
            item {
                Text(
                    "Mímir sync failing: ${s.sync.lastError}. Fix it in Settings → Connection.",
                    style = HudText.meta.copy(color = Hud.amber),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** Settings → Developer: raise and clear test alerts. */
@Composable
fun AlertTests(vm: AlertsViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HudButton("Warning", { vm.raiseTest(Severity.WARNING) }, kind = HudButtonKind.SMALL)
        HudButton("Critical", { vm.raiseTest(Severity.CRITICAL) }, kind = HudButtonKind.SMALL)
        HudButton("Resolve", vm::resolveTests, kind = HudButtonKind.SMALL)
    }
}

@Composable
internal fun AlertItem(row: AlertRow, vm: AlertsViewModel) {
    val a = row.alert
    val quiet = a.state == AlertState.ACKNOWLEDGED || row.muted || a.snoozedUntil?.isAfter(Instant.now()) == true
    Column(
        Modifier.fillMaxWidth().alpha(if (quiet) 0.55f else 1f).leftStripe(a.severity.color()).padding(start = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.title, style = HudText.small.copy(color = Hud.text), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            HudLabel(a.severity.name, color = a.severity.color(), style = HudText.label.copy(fontSize = 10.sp))
        }
        val meta = buildList {
            add(a.source)
            add("since ${timeFmt.format(a.openedAt)}")
            if (a.state == AlertState.ACKNOWLEDGED) add("seen")
            a.snoozedUntil?.takeIf { it.isAfter(Instant.now()) }?.let { add("snoozed to ${timeFmt.format(it)}") }
            if (row.muted) add("muted")
        }
        Text(meta.joinToString(" · "), style = HudText.meta)
        a.detail?.let { Text(it, style = HudText.meta) }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (a.state == AlertState.OPEN) HudButton("Seen", { vm.acknowledge(a) }, kind = HudButtonKind.SMALL)
            HudButton("Snooze 1h", { vm.snooze(a, 1) }, kind = HudButtonKind.SMALL)
            HudButton("1d", { vm.snooze(a, 24) }, kind = HudButtonKind.SMALL)
            HudButton(if (row.muted) "Unmute" else "Mute", { vm.setMuted(a, !row.muted) }, kind = HudButtonKind.SMALL)
        }
    }
}

/** Another device's alert: listed, never pushed. Your buttons ask that device. */
@Composable
private fun MirrorItem(m: Mirror, vm: AlertsViewModel) {
    val a = m.alert
    val quiet = a.state == AlertState.ACKNOWLEDGED || a.muted || a.snoozedUntil?.isAfter(Instant.now()) == true
    Column(
        Modifier.fillMaxWidth().alpha(if (quiet) 0.55f else 1f).leftStripe(a.severity.color()).padding(start = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.title, style = HudText.small.copy(color = Hud.text), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            HudLabel(a.severity.name, color = a.severity.color(), style = HudText.label.copy(fontSize = 10.sp))
        }
        val meta = buildList {
            add(a.source)
            add("since ${timeFmt.format(a.openedAt)}")
            if (a.state == AlertState.ACKNOWLEDGED) add("seen")
            a.snoozedUntil?.takeIf { it.isAfter(Instant.now()) }?.let { add("snoozed to ${timeFmt.format(it)}") }
            if (a.muted) add("muted")
        }
        Text(meta.joinToString(" · "), style = HudText.meta)
        a.detail?.let { Text(it, style = HudText.meta) }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (a.state == AlertState.OPEN) HudButton("Seen", { vm.acknowledge(m) }, kind = HudButtonKind.SMALL)
            HudButton("Snooze 1h", { vm.snooze(m, 1) }, kind = HudButtonKind.SMALL)
            HudButton("1d", { vm.snooze(m, 24) }, kind = HudButtonKind.SMALL)
            HudButton(if (a.muted) "Unmute" else "Mute", { vm.setMuted(m, !a.muted) }, kind = HudButtonKind.SMALL)
        }
    }
}

/** Settings → Connection: where Mímir is, and how syncing goes. */
@Composable
fun MimirSettings(vm: AlertsViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val sync = s.sync
    val saved = remember { vm.form() }
    var url by remember { mutableStateOf(saved.url) }
    var token by remember { mutableStateOf(saved.token) }
    var device by remember { mutableStateOf(saved.device) }
    var driveFrom by remember { mutableStateOf(saved.driveFrom) }
    Column(
        Modifier.fillMaxWidth().hudPanel(warn = sync.lastError != null).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val line = when {
            !sync.configured && sync.lastSyncAt == null && saved.url.isBlank() -> "Not set up: the alerts stay on this phone."
            !sync.configured -> "Needs an https:// URL and a token."
            sync.lastError != null -> "Last try failed: ${sync.lastError}"
            sync.lastSyncAt != null -> "Synced ${timeFmt.format(sync.lastSyncAt)}"
            else -> "Not synced yet"
        }
        Text(line, style = HudText.meta.copy(color = if (sync.lastError != null) Hud.amber else Hud.overlay2))
        if (sync.waiting > 0) Text("${sync.waiting} waiting to go", style = HudText.meta)
        HudTextField(url, { url = it }, placeholder = "https://vps.tailnet.ts.net", maxLines = 1)
        HudTextField(token, { token = it }, placeholder = "This phone's token", maxLines = 1)
        HudTextField(device, { device = it }, placeholder = "Name at Mímir (phone)", maxLines = 1)
        HudTextField(driveFrom, { driveFrom = it }, placeholder = "Take requests from (e.g. desktop)", maxLines = 1)
        Text("The devices that may type into this phone's Claude sessions, answer their questions and resume them. Empty: they can only look.",
            style = HudText.meta)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudButton("Save", { vm.saveMimir(MimirForm(url, token, device, driveFrom)) }, kind = HudButtonKind.SMALL)
            HudButton("Sync now", vm::syncNow, kind = HudButtonKind.SMALL)
        }
    }
}
