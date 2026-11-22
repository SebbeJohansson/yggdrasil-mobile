package io.github.sebbejohansson.yggdrasil.llm.claude

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import io.github.sebbejohansson.yggdrasil.ui.HudFonts
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.HudTextField
import io.github.sebbejohansson.yggdrasil.ui.hudPanel
import io.github.sebbejohansson.yggdrasil.ui.leftStripe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LocalUiState(
    val sessions: List<LocalSession> = emptyList(),
    /** The session open as a chat, with its messages. */
    val chat: LocalSession? = null,
    /** The repo a start is under way for. */
    val starting: String? = null,
    val sending: Boolean = false,
    val error: String? = null,
)

/** Claude Code on this phone, in herdr: the sessions, and the one open as a chat. */
class LocalViewModel(private val bridge: BridgeClient) : ViewModel() {
    private val _state = MutableStateFlow(LocalUiState())
    val state: StateFlow<LocalUiState> = _state

    fun refresh() = viewModelScope.launch {
        runCatching { bridge.localSessions() }
            .onSuccess { list -> _state.update { it.copy(sessions = list) } }
    }

    fun refreshChat() = viewModelScope.launch {
        val name = _state.value.chat?.name ?: return@launch
        runCatching { bridge.localChat(name) }
            .onSuccess { c -> _state.update { if (it.chat?.name == name) it.copy(chat = c) else it } }
            .onFailure { e -> if (e is BridgeException && e.status == 404) _state.update { it.copy(chat = null) } }
    }

    fun open(s: LocalSession?) {
        _state.update { it.copy(chat = s, error = null) }
        if (s != null) refreshChat()
    }

    fun start(repo: String, task: String, account: String) = viewModelScope.launch {
        _state.update { it.copy(starting = repo, error = null) }
        runCatching { bridge.startLocal(repo, task, account) }
            .onSuccess { s -> _state.update { it.copy(sessions = listOf(s) + it.sessions.filter { x -> x.name != s.name }, chat = s) } }
            .onFailure { e -> _state.update { it.copy(error = "Couldn't start: ${e.message}") } }
        _state.update { it.copy(starting = null) }
        refreshChat()
    }

    fun send(name: String, text: String) = act { bridge.promptLocal(name, text) }
    fun choose(name: String, d: LocalDialog, c: LocalChoice) = act { bridge.chooseLocal(name, d, c) }
    fun interrupt(name: String) = act { bridge.keysLocal(name, "esc") }

    fun stop(name: String) = viewModelScope.launch {
        runCatching { bridge.stopLocal(name) }.onFailure { e -> _state.update { it.copy(error = e.message) } }
        _state.update { it.copy(chat = null) }
        refresh()
    }

    private fun act(what: suspend () -> Unit) = viewModelScope.launch {
        _state.update { it.copy(sending = true, error = null) }
        runCatching { what() }.onFailure { e -> _state.update { it.copy(error = e.message) } }
        _state.update { it.copy(sending = false) }
        refreshChat()
    }

    companion object {
        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory { initializer { LocalViewModel(c.bridge) } }
    }
}

private fun statusText(s: String) = when (s) {
    "working" -> "working"
    "idle", "done" -> "your move"
    "blocked" -> "needs you"
    "gone" -> "ended"
    else -> "starting"
}

private fun statusColor(s: String) = when (s) {
    "working" -> Hud.cyan
    "idle", "done" -> Hud.green
    "blocked" -> Hud.amber
    else -> Hud.overlay0
}

/**
 * Claude Code on this phone, as on the desktop: in herdr, in the phone's own
 * checkout of a repo. A popup over the Code tab: the sessions by account and a
 * start, or one session as a chat.
 */
@Composable
fun PhoneLocalDialog(bridgeVm: BridgeViewModel, vm: LocalViewModel, onSetUp: () -> Unit, onDismiss: () -> Unit) {
    val b = bridgeVm.state.collectAsStateWithLifecycle().value
    val s = vm.state.collectAsStateWithLifecycle().value
    // The list every 5 s; the open chat every 2 s. Read fresh each time round.
    LaunchedEffect(Unit) {
        while (true) {
            val open = vm.state.value.chat != null
            if (open) vm.refreshChat() else vm.refresh()
            delay(if (open) 2_000 else 5_000)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 24.dp).background(Hud.base)
                .verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (s.chat != null) s.chat.repo.substringAfter('/') else "On this phone", style = HudText.title, modifier = Modifier.weight(1f))
                if (s.chat != null) HudButton("Back", { vm.open(null) }, kind = HudButtonKind.SMALL, modifier = Modifier.padding(end = 6.dp))
                HudButton("Close", onDismiss, kind = HudButtonKind.SMALL)
            }
            when {
                !b.ready -> {
                    Text("Claude Code runs here through Ygg's Claude bridge. Set it up and turn on Always on.", style = HudText.small)
                    HudButton("Set it up", { onDismiss(); onSetUp() }, kind = HudButtonKind.SMALL)
                }
                b.health?.herdr == false -> {
                    Text("herdr isn't in Ygg's Linux yet: Settings → Claude bridge → Update adds it.", style = HudText.small)
                    HudButton("Settings", { onDismiss(); onSetUp() }, kind = HudButtonKind.SMALL)
                }
                s.chat != null -> LocalChat(s.chat, s, vm)
                else -> LocalList(b, s, vm)
            }
            s.error?.let { Text(it, style = HudText.meta.copy(color = Hud.amber)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalList(b: BridgeUiState, s: LocalUiState, vm: LocalViewModel) {
    val usable = b.accounts.filter { it.signedIn }
    var account by remember { mutableStateOf("") }
    if (usable.none { it.id == account }) account = usable.firstOrNull()?.id ?: "default"
    var repo by remember { mutableStateOf("") }
    var task by remember { mutableStateOf("") }
    if (repo.isEmpty() && b.repos.isNotEmpty()) repo = b.repos.first()
    HudLabel("claude code in herdr · this phone's checkout")
    Column(Modifier.fillMaxWidth().hudPanel().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HudLabel("New session")
        if (b.accounts.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                b.accounts.forEach { a ->
                    HudButton(a.name, { account = a.id }, kind = if (a.id == account) HudButtonKind.PRIMARY else HudButtonKind.SMALL, enabled = a.signedIn)
                }
            }
        }
        if (b.repos.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                b.repos.forEach { r -> HudButton(r.substringAfter('/'), { repo = r }, kind = if (r == repo) HudButtonKind.GHOST else HudButtonKind.SMALL) }
            }
        }
        HudTextField(repo, { repo = it.trim() }, placeholder = "owner/repo", maxLines = 1, modifier = Modifier.fillMaxWidth())
        HudTextField(task, { task = it }, placeholder = "What Claude should do (or leave empty)", maxLines = 4, modifier = Modifier.fillMaxWidth())
        HudButton(
            if (s.starting != null) "Starting…" else "Start",
            { vm.start(repo, task.trim(), account); task = "" },
            kind = HudButtonKind.PRIMARY,
            enabled = s.starting == null && repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")),
        )
        if (s.starting != null) Text("Getting ${s.starting} onto the phone (the first time it's cloned) and Claude started.", style = HudText.meta)
    }
    val byAccount = s.sessions.groupBy { it.account }
    val order = b.accounts.map { it.id } + byAccount.keys.filter { k -> b.accounts.none { it.id == k } }
    order.filter { it in byAccount }.forEach { id ->
        val a = b.accounts.firstOrNull { it.id == id }
        if (b.accounts.size > 1 || a == null) {
            HudLabel(listOfNotNull(a?.name ?: id, a?.email?.ifEmpty { null }).joinToString(" · "), modifier = Modifier.padding(top = 4.dp))
        }
        byAccount.getValue(id).forEach { c ->
            Column(Modifier.fillMaxWidth().leftStripe(statusColor(c.status)).padding(start = 10.dp).clickable { vm.open(c) },
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(c.task.ifEmpty { c.repo }, style = HudText.small.copy(color = Hud.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${c.repo.substringAfter('/')} · ${statusText(c.status)}", style = HudText.meta.copy(color = statusColor(c.status)))
            }
        }
    }
}

@Composable
private fun LocalChat(c: LocalSession, s: LocalUiState, vm: LocalViewModel) {
    var draft by remember(c.name) { mutableStateOf("") }
    Text("${c.repo} · ${statusText(c.status)}", style = HudText.meta.copy(color = statusColor(c.status)))
    if (c.messages.isEmpty() && c.screen.isBlank()) Text("Nothing yet.", style = HudText.meta)
    c.messages.takeLast(60).forEach { m ->
        when (m.role) {
            "user" -> Text(m.text, style = HudText.small.copy(color = Hud.cyan), modifier = Modifier.fillMaxWidth().padding(start = 32.dp))
            "tool" -> Text("· ${m.text}", style = HudText.meta.copy(fontFamily = HudFonts.mono), maxLines = 2, overflow = TextOverflow.Ellipsis)
            else -> Text(m.text, style = HudText.small.copy(color = Hud.text))
        }
    }
    // Before there's a chat, or while Claude shows something only the screen has.
    if (c.screen.isNotBlank() && (c.messages.isEmpty() || (c.status == "blocked" && c.dialog == null))) {
        Box(Modifier.fillMaxWidth().heightIn(max = 260.dp).background(Hud.crust).padding(8.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                c.screen.lines().takeLast(30).forEach { Text(it.ifEmpty { " " }, style = HudText.meta.copy(fontFamily = HudFonts.mono, color = Hud.text), softWrap = false) }
            }
        }
    }
    if (c.status == "working") Text("Claude is working…", style = HudText.meta.copy(color = Hud.cyan))
    c.dialog?.let { d ->
        // A question Claude asks (a tool to allow, say): its choices, answered with its own keys.
        Column(Modifier.fillMaxWidth().hudPanel(warn = true).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (d.detail.isNotBlank()) Text(d.detail, style = HudText.meta.copy(fontFamily = HudFonts.mono), maxLines = 12, overflow = TextOverflow.Ellipsis)
            Text(d.question, style = HudText.small.copy(color = Hud.text))
            d.choices.forEach { ch ->
                HudButton("${ch.key}. ${ch.label}", { vm.choose(c.name, d, ch) }, kind = if (ch.selected) HudButtonKind.PRIMARY else HudButtonKind.SMALL,
                    enabled = !s.sending, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    if (c.status != "gone") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudTextField(draft, { draft = it }, placeholder = "Message Claude", maxLines = 5, modifier = Modifier.weight(1f))
            HudButton(if (s.sending) "…" else "Send", { vm.send(c.name, draft.trim()); draft = "" }, kind = HudButtonKind.PRIMARY,
                enabled = draft.isNotBlank() && !s.sending && c.status != "blocked")
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (c.status == "working") HudButton("Esc (interrupt)", { vm.interrupt(c.name) }, kind = HudButtonKind.SMALL)
        HudButton(if (c.status == "gone") "Remove" else "Stop", { vm.stop(c.name) }, kind = HudButtonKind.SMALL)
    }
    if (c.status != "gone") Text("Stop ends Claude here; the checkout and its changes stay on the phone.", style = HudText.meta.copy(color = Hud.overlay0))
}
