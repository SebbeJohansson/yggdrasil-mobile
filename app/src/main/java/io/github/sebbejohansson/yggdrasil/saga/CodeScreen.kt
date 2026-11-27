package io.github.sebbejohansson.yggdrasil.saga

import io.github.sebbejohansson.yggdrasil.llm.claude.PhoneCloudDialog
import io.github.sebbejohansson.yggdrasil.llm.claude.PhoneLocalDialog
import io.github.sebbejohansson.yggdrasil.llm.claude.LocalViewModel
import io.github.sebbejohansson.yggdrasil.llm.claude.PhoneSessions
import io.github.sebbejohansson.yggdrasil.llm.claude.FollowUp
import io.github.sebbejohansson.yggdrasil.llm.claude.FollowBridge
import io.github.sebbejohansson.yggdrasil.llm.claude.BridgeUiState
import io.github.sebbejohansson.yggdrasil.llm.claude.BridgeViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.sebbejohansson.yggdrasil.AppContainer
import io.github.sebbejohansson.yggdrasil.nodes.Machine
import io.github.sebbejohansson.yggdrasil.nodes.MachineClient
import io.github.sebbejohansson.yggdrasil.nodes.MachineGoal
import io.github.sebbejohansson.yggdrasil.nodes.MachineGoals
import io.github.sebbejohansson.yggdrasil.nodes.ReadTarget
import io.github.sebbejohansson.yggdrasil.nodes.TranscriptDialog
import io.github.sebbejohansson.yggdrasil.nodes.MachineService
import io.github.sebbejohansson.yggdrasil.nodes.MachineStatus
import io.github.sebbejohansson.yggdrasil.nodes.Machines
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudButton
import io.github.sebbejohansson.yggdrasil.ui.HudButtonKind
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudSection
import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.HudTextField
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
import java.util.UUID

data class CodeUiState(
    val me: String = "",
    val devices: List<DeviceSessions> = emptyList(),
    val asked: List<Asked> = emptyList(),
    val sync: SyncStatus = SyncStatus(),
    /** The machines reached directly, and how each answered last. */
    val machines: List<MachineStatus> = emptyList(),
    /** The newest Yggdrasil release, to say which machines are behind it. */
    val latest: String? = null,
    /** Each machine's goals in play, by machine id: only for machines reached directly. */
    val goals: Map<String, List<MachineGoal>> = emptyMap(),
)

/**
 * Your machines' coding sessions, and what you can ask of them from the
 * phone. A machine reached directly over the tailnet (nodes/) is read now
 * and does what you ask now; any other machine is shown as it last sent its
 * sessions through Mímir, and what you ask goes with Sága's next sync, carried
 * out when that machine is on and trusts this phone. A machine reached both
 * ways shows once, directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CodeViewModel(
    private val saga: SagaService,
    private val settings: SagaSettings,
    private val machines: MachineService,
) : ViewModel() {
    val state = combine(
        combine(saga.sessionChanges.flow, saga.status) { _, status -> status },
        machines.sessions,
        machines.status,
        combine(machines.asked, machines.latest, machines.goals) { a, l, g -> Triple(a, l, g) },
    ) { status, direct, machineStatus, more -> Triple(status, direct, machineStatus to more) }
        .mapLatest { (status, direct, rest) ->
            val (machineStatus, more) = rest
            val (directAsked, latest, goals) = more
            val reached = direct.map { it.device }.toSet()
            CodeUiState(
                me = settings.device,
                devices = direct + saga.devices().filter { it.device !in reached },
                asked = (directAsked + saga.asked()).sortedByDescending { it.at },
                sync = status,
                machines = machineStatus,
                latest = latest,
                goals = goals,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CodeUiState())

    fun syncNow() = viewModelScope.launch {
        machines.refresh()
        saga.sync()
    }

    /** The machines reached directly, read again: the Code tab does this every few seconds while it shows. */
    suspend fun refreshMachines() = machines.refresh()

    private fun ask(device: String, kind: String, build: (SessionAsk) -> SessionAsk) = viewModelScope.launch {
        val ask = build(SessionAsk(id = UUID.randomUUID().toString().replace("-", ""), kind = kind, origin = device))
        if (machines.isDirect(device)) {
            machines.ask(ask)
            return@launch
        }
        saga.askSession(ask)
        saga.sync()
    }

    /** A goal action on a machine reached directly; null, or why it failed. */
    suspend fun onGoal(device: String, act: (MachineClient) -> Unit): String? = machines.onGoal(device, act)

    /** The end of a session's transcript on a machine reached directly. */
    suspend fun transcript(device: String, session: String, directory: String?, tail: Int, since: String?) =
        machines.transcript(device, session, directory, tail, since)

    /** Settings → Connection → Machines. */
    fun machineList(): List<Machine> = machines.machines()

    /** Saves a machine and reads it at once; the error says what is wrong with what was typed. */
    fun saveMachine(label: String, host: String, token: String, keepToken: Boolean): String? {
        val checked = Machines.check(label, host, if (keepToken && token.isBlank()) "kept" else token)
        val machine = checked.getOrElse { return it.message }
        machines.save(machine, token)
        viewModelScope.launch { machines.refresh() }
        return null
    }

    fun removeMachine(id: String) = viewModelScope.launch {
        machines.remove(id)
    }

    fun prompt(device: String, s: RemoteSession, text: String) = ask(device, SessionKinds.PROMPT) { it.copy(session = s.id, text = text) }
    fun choose(device: String, s: RemoteSession, c: RemoteChoice) =
        ask(device, SessionKinds.CHOOSE) { it.copy(session = s.id, question = s.dialog?.question, key = c.key, label = c.label) }
    fun resume(device: String, s: RemoteSession) = ask(device, SessionKinds.RESUME) { it.copy(session = s.id, cwd = s.directory) }
    fun startThere(device: String, cwd: String, task: String) = ask(device, SessionKinds.START) { it.copy(cwd = cwd, task = task) }
    fun startCloud(device: String, cwd: String, task: String) = ask(device, SessionKinds.CLOUD_START) { it.copy(cwd = cwd, task = task) }
    fun follow(device: String, c: RemoteCloud, text: String) = ask(device, SessionKinds.CLOUD_MESSAGE) { it.copy(session = c.id, text = text) }
    fun teleport(device: String, c: RemoteCloud) = ask(device, SessionKinds.CLOUD_TELEPORT) { it.copy(session = c.id) }

    companion object {
        fun factory(c: AppContainer): ViewModelProvider.Factory =
            viewModelFactory { initializer { CodeViewModel(c.saga, c.sagaSettings, c.machines) } }
    }
}

/** "4m", "2h", "3d". */
internal fun ago(t: Instant?, now: Instant): String {
    if (t == null) return ""
    val d = Duration.between(t, now).coerceAtLeast(Duration.ZERO)
    return when {
        d.toMinutes() < 1 -> "${d.seconds}s"
        d.toHours() < 1 -> "${d.toMinutes()}m"
        d.toDays() < 1 -> "${d.toHours()}h"
        else -> "${d.toDays()}d"
    }
}

@Composable
fun CodeScreen(
    vm: CodeViewModel,
    modifier: Modifier = Modifier,
    /** Ygg's Claude bridge on this phone: cloud sessions with no machine on. */
    phone: BridgeViewModel? = null,
    /** Claude Code on this phone, in herdr. */
    local: LocalViewModel? = null,
    onSetUpPhone: () -> Unit = {},
) {
    val s by vm.state.collectAsStateWithLifecycle()
    phone?.let { FollowBridge(it) }
    val phoneState = phone?.state?.collectAsStateWithLifecycle()?.value
    val now = Instant.now()
    var cloudOpen by remember { mutableStateOf(false) }
    if (phone != null && cloudOpen) PhoneCloudDialog(phone, onSetUpPhone) { cloudOpen = false }
    var localOpen by remember { mutableStateOf(false) }
    // A session on a machine reached directly, open in the reader.
    var reading by remember { mutableStateOf<ReadTarget?>(null) }
    reading?.let { target -> TranscriptDialog(target, vm) { reading = null } }
    if (phone != null && local != null && localOpen) PhoneLocalDialog(phone, local, onSetUpPhone) { localOpen = false; local.open(null) }
    val localState = local?.state?.collectAsStateWithLifecycle()?.value
    // The machines reached directly, read again every few seconds while the Code tab shows.
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { vm.refreshMachines() }
            kotlinx.coroutines.delay(5_000)
        }
    }
    // The phone's own sessions, kept fresh while the Code tab shows.
    if (local != null && phoneState?.ready == true) {
        LaunchedEffect(Unit) {
            while (true) {
                local.refresh()
                kotlinx.coroutines.delay(5_000)
            }
        }
    }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sindri", style = HudText.title)
                    HudLabel(
                        when {
                            !s.sync.configured && s.machines.isEmpty() -> "no machines set up (Settings → Connection)"
                            s.devices.isEmpty() -> "no machine has answered or sent its sessions"
                            else -> "${s.devices.sumOf { it.sessions.size }} sessions on ${s.devices.size} " +
                                if (s.devices.size == 1) "machine" else "machines"
                        },
                    )
                }
                if (phone != null) {
                    // Cloud sessions from this phone, in a popup: a start, and where to find them.
                    HudButton(if (phoneState?.starting != null) "Cloud · starting…" else "Cloud", { cloudOpen = true },
                        kind = HudButtonKind.SMALL, modifier = Modifier.padding(end = 6.dp))
                }
                HudButton("Sync now", { vm.syncNow() }, kind = HudButtonKind.SMALL)
            }
        }
        if (phone != null && local != null && localState != null && phoneState?.ready == true && phoneState?.health?.herdr == true) {
            item(key = "phone-local-h") {
                HudSection("This phone", Modifier.padding(top = 8.dp)) {
                    HudLabel("${localState.sessions.size} ${if (localState.sessions.size == 1) "session" else "sessions"} · herdr")
                }
            }
            item(key = "phone-local") {
                PhoneSessions(phoneState, localState, local, onOpen = { localOpen = true }, onNew = { local.open(null); localOpen = true })
            }
        }
        // A machine reached directly that did not answer, or was left out.
        s.machines.filter { it.error != null || it.refused }.forEach { m ->
            item(key = "m-${m.machine.id}") {
                HudLabel(
                    if (m.refused) "${m.machine.label}: left out, it is a work machine"
                    else "${m.machine.label}: ${m.error}",
                    color = Hud.overlay2,
                )
            }
        }
        if (s.devices.isEmpty()) {
            item {
                Text(
                    "Add your machines under Settings → Connection → Machines to see their sessions live over the tailnet. " +
                        "A machine that runs Sindri and Sága also sends its sessions here through Mímir.",
                    style = HudText.small.copy(color = Hud.overlay0, textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        }
        s.devices.forEach { d ->
            item(key = "h-${d.device}") {
                val machine = s.machines.firstOrNull { it.machine.id == d.device }
                HudSection(d.title, Modifier.padding(top = 8.dp)) {
                    HudLabel(
                        when {
                            d.direct && machine != null -> "live · ${Machines.summary(machine, s.latest)}"
                            d.direct -> "live · over the tailnet"
                            d.quiet(now) -> "quiet ${ago(d.heardAt, now)}"
                            else -> "heard ${ago(d.heardAt, now)} ago · through Mímir"
                        },
                    )
                }
            }
            item(key = "d-${d.device}") { DevicePanel(d, s, vm, now, phone?.takeIf { phoneState?.ready == true }, phoneState) { reading = it } }
        }
    }
}

@Composable
private fun DevicePanel(
    d: DeviceSessions,
    s: CodeUiState,
    vm: CodeViewModel,
    now: Instant,
    phone: BridgeViewModel?,
    phoneState: BridgeUiState?,
    onRead: (ReadTarget) -> Unit,
) {
    val drive = d.drivableBy(s.me) && !d.quiet(now)
    Column(
        Modifier.fillMaxWidth().alpha(if (d.quiet(now)) 0.65f else 1f).hudPanel(warn = d.sessions.any { it.waiting }).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when {
            !d.drivableBy(s.me) -> Text("Look only: ${d.device} does not take requests from ${s.me} (SAGA_DRIVE_FROM there).", style = HudText.meta)
            d.quiet(now) -> Text("Not heard from in a while. What you ask waits until it is back, for up to 30 minutes.", style = HudText.meta)
        }
        d.errors.forEach { (source, text) -> Text("$source: $text", style = HudText.meta) }
        if (d.sessions.isEmpty()) Text("Nothing running there.", style = HudText.small.copy(color = Hud.overlay0))
        d.sessions.forEach { session -> SessionItem(d, session, drive, s.asked, vm, now, onRead) }
        if (drive && d.projects.isNotEmpty()) StartSession(d, s.asked, vm)
        if (d.cloud.isNotEmpty()) {
            HudLabel("Cloud sessions started there")
            d.cloud.forEach { c -> CloudItem(d, c, drive, s.asked, vm, now, phone, phoneState) }
        }
        // A machine reached directly: its goals too.
        if (d.direct) MachineGoals(d, s.goals[d.device].orEmpty(), vm, onRead)
    }
}

private fun RemoteSession.color(): Color = when {
    waiting -> Hud.yellow
    working -> Hud.green
    else -> Hud.overlay0
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionItem(
    d: DeviceSessions,
    s: RemoteSession,
    drive: Boolean,
    asked: List<Asked>,
    vm: CodeViewModel,
    now: Instant,
    onRead: (ReadTarget) -> Unit = {},
) {
    val uri = LocalUriHandler.current
    var draft by remember(s.id) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().leftStripe(s.color()).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(7.dp).background(s.color(), CircleShape))
            Text(s.name, style = HudText.small.copy(color = Hud.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(listOfNotNull(s.folder.ifEmpty { null }, s.kind.takeIf { it != "claude" }, s.lastActivity?.let { ago(it, now) }).joinToString(" · "), style = HudText.meta)
        }
        when {
            s.permission != null -> Text("Waiting on a permission prompt for ${s.permission}.", style = HudText.meta.copy(color = Hud.yellow))
            s.working && s.tool != null -> Text("Running ${s.tool}…", style = HudText.meta)
        }
        if (!s.working) s.lastText?.let { Text(it, style = HudText.meta.copy(color = Hud.subtext0), maxLines = 4, overflow = TextOverflow.Ellipsis) }
        val dialog = s.dialog
        if (dialog != null && drive && s.pane != null) {
            Text(dialog.question, style = HudText.meta.copy(color = Hud.subtext0))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                dialog.choices.forEach { c -> HudButton(c.label, { vm.choose(d.device, s, c) }, kind = HudButtonKind.SMALL) }
            }
        }
        if (drive && s.pane != null && dialog == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HudTextField(draft, { draft = it }, placeholder = "Type into it on ${d.device}", maxLines = 3, modifier = Modifier.weight(1f))
                HudButton("Send", { vm.prompt(d.device, s, draft.trim()); draft = "" }, kind = HudButtonKind.SMALL, enabled = draft.isNotBlank())
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // A Claude Code session on a machine reached directly: its transcript, read from its Sindri.
            if (d.direct && s.kind == "claude" && !s.id.startsWith("herdr:")) {
                HudButton("Read", { onRead(ReadTarget(d.device, d.title, s.id, s.directory, s.name)) }, kind = HudButtonKind.SMALL)
            }
            s.claudeLink?.let { link -> HudButton("Open in Claude", { uri.openUri(link) }, kind = HudButtonKind.SMALL) }
            if (drive && d.herdr && s.pane == null && s.kind == "claude" && s.directory != null) {
                HudButton("Resume there", { vm.resume(d.device, s) }, kind = HudButtonKind.SMALL)
            }
        }
        if (s.pane == null && s.claudeLink == null) {
            Text(
                if (d.direct) "Outside herdr, and Remote Control is off: read it here, or resume it there to type into it."
                else "Outside herdr, and Remote Control is off: it can only be read from here.",
                style = HudText.meta,
            )
        }
        AskedLines(asked.filter { it.ask.origin == d.device && it.ask.session == s.id })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CloudItem(
    d: DeviceSessions,
    c: RemoteCloud,
    drive: Boolean,
    asked: List<Asked>,
    vm: CodeViewModel,
    now: Instant,
    phone: BridgeViewModel?,
    phoneState: BridgeUiState?,
) {
    val uri = LocalUriHandler.current
    var draft by remember(c.id) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().leftStripe(Hud.cyan).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(c.task, style = HudText.small.copy(color = Hud.text), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${c.cwd.trimEnd('/').substringAfterLast('/')} · ${ago(c.at, now)}", style = HudText.meta)
        }
        c.lastError?.let { Text("Last message did not go: $it", style = HudText.meta.copy(color = Hud.yellow)) }
        when {
            drive -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HudTextField(draft, { draft = it }, placeholder = "Follow up", maxLines = 3, modifier = Modifier.weight(1f))
                HudButton("Send", { vm.follow(d.device, c, draft.trim()); draft = "" }, kind = HudButtonKind.SMALL, enabled = draft.isNotBlank())
            }
            // A cloud session takes a follow-up from any machine of yours: with this one away, the phone's bridge sends it.
            phone != null && phoneState != null -> FollowUp(c.id, phoneState, phone, draft) { draft = it }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HudButton("Open in Claude", { uri.openUri(c.url) }, kind = HudButtonKind.SMALL)
            if (drive && d.herdr) HudButton(if (c.teleportedAt != null) "Bring home again" else "Bring home there", { vm.teleport(d.device, c) }, kind = HudButtonKind.SMALL)
        }
        AskedLines(asked.filter { it.ask.origin == d.device && it.ask.session == c.id })
    }
}

/**
 * A new Claude session on that machine. Start there runs Claude on the machine
 * itself, in a herdr pane in the project (as the desktop's own start does), so
 * it works in the machine's checkout; Cloud runs `claude --cloud`, which works
 * in Anthropic's cloud on a copy of the repo instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartSession(d: DeviceSessions, asked: List<Asked>, vm: CodeViewModel) {
    val uri = LocalUriHandler.current
    var cwd by remember(d.device) { mutableStateOf(d.projects.first().path) }
    var task by remember(d.device) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HudLabel("New session")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            d.projects.forEach { p ->
                HudButton(p.name, { cwd = p.path }, kind = if (p.path == cwd) HudButtonKind.GHOST else HudButtonKind.SMALL)
            }
        }
        HudTextField(task, { task = it }, placeholder = "What Claude should do", maxLines = 3)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HudButton("Start on ${d.title}", { vm.startThere(d.device, cwd, task.trim()); task = "" },
                kind = HudButtonKind.SMALL, enabled = task.isNotBlank() && d.herdr)
            HudButton("Start in the cloud", { vm.startCloud(d.device, cwd, task.trim()); task = "" },
                kind = HudButtonKind.SMALL, enabled = task.isNotBlank())
        }
        if (!d.herdr) Text("herdr is off on ${d.title}, so Claude can only start in the cloud from here.", style = HudText.meta)
        asked.filter { it.ask.origin == d.device && (it.ask.kind == SessionKinds.START || it.ask.kind == SessionKinds.CLOUD_START) }.take(3).forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(a.line, style = HudText.meta.copy(color = a.color()), modifier = Modifier.weight(1f))
                a.url?.let { link -> HudButton("Open", { uri.openUri(link) }, kind = HudButtonKind.SMALL) }
            }
        }
    }
}

private fun Asked.color(): Color = when (state) {
    AskState.WAITING -> Hud.overlay0
    AskState.DONE -> Hud.green
    AskState.FAILED -> Hud.amber
}

@Composable
private fun AskedLines(asked: List<Asked>) {
    asked.take(2).forEach { a -> Text(a.line, style = HudText.meta.copy(color = a.color())) }
}
