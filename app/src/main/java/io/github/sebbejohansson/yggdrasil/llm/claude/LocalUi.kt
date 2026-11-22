package io.github.sebbejohansson.yggdrasil.llm.claude

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import io.github.sebbejohansson.yggdrasil.ui.Markdown
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
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
    /** The open session's checkout: branch, changes, commits. */
    val repo: RepoState? = null,
    /** The file whose diff is open, and the diff. */
    val diffPath: String? = null,
    val diff: String = "",
    /** What a pull or push said. */
    val gitNote: String? = null,
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
        _state.update { it.copy(chat = s, error = null, repo = null, diffPath = null, diff = "", gitNote = null) }
        if (s != null) { refreshChat(); refreshRepo() }
    }

    fun refreshRepo() = viewModelScope.launch {
        val name = _state.value.chat?.name ?: return@launch
        runCatching { bridge.repoLocal(name) }.onSuccess { r -> _state.update { if (it.chat?.name == name) it.copy(repo = r) else it } }
    }

    fun openDiff(path: String?) = viewModelScope.launch {
        val name = _state.value.chat?.name ?: return@launch
        _state.update { it.copy(diffPath = path, diff = "") }
        if (path == null) return@launch
        runCatching { bridge.diffLocal(name, path) }
            .onSuccess { d -> _state.update { if (it.diffPath == path) it.copy(diff = d) else it } }
            .onFailure { e -> _state.update { it.copy(error = e.message) } }
    }

    /** pull or push, with the bridge's GitHub token. */
    fun git(action: String) = viewModelScope.launch {
        val name = _state.value.chat?.name ?: return@launch
        _state.update { it.copy(sending = true, gitNote = null, error = null) }
        runCatching { bridge.gitLocal(name, action) }
            .onSuccess { out -> _state.update { it.copy(gitNote = "${action.replaceFirstChar { c -> c.uppercase() }}: $out") } }
            .onFailure { e -> _state.update { it.copy(error = e.message) } }
        _state.update { it.copy(sending = false) }
        refreshRepo()
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
    fun resume(name: String) = act { bridge.resumeLocal(name) }

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
        refresh()
    }

    companion object {
        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory { initializer { LocalViewModel(c.bridge) } }
    }
}

/** A local session's phase in words, as the desktop's sessions say it. */
fun phaseText(c: LocalSession) = when (c.phase) {
    "busy" -> if (c.tool.isNotEmpty()) "running ${c.tool}…" else "working"
    "turn-ended" -> "your move"
    "blocked" -> "needs you"
    "ended" -> if (c.resumable) "ended · resumable" else "ended"
    else -> "starting"
}

fun phaseColor(phase: String) = when (phase) {
    "busy" -> Hud.green
    "turn-ended" -> Hud.cyan
    "blocked" -> Hud.yellow
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
    // The list every 5 s; the open chat every 2 s, and its checkout every 10 s. Read fresh each time round.
    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            val open = vm.state.value.chat != null
            if (open) {
                vm.refreshChat()
                if (n++ % 5 == 0) vm.refreshRepo()
            } else vm.refresh()
            delay(if (open) 2_000 else 5_000)
        }
    }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Hud.base).systemBarsPadding().imePadding().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (s.chat != null) s.chat.title.ifEmpty { s.chat.repo.substringAfter('/') } else "New session on this phone", style = HudText.title,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
                s.chat != null -> LocalSessionView(s.chat, s, vm, Modifier.weight(1f))
                else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LocalList(b, s, vm)
                }
            }
            s.error?.let { Text(it, style = HudText.meta.copy(color = Hud.amber), maxLines = 3, overflow = TextOverflow.Ellipsis) }
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
}

/**
 * The phone's own Claude Code sessions, in the Code tab's list beside the
 * desktop's and drawn like them: what it's doing, its last words, a question
 * it asks as buttons, and a message box. A message to an ended one resumes
 * it first. The chat and the checkout's changes open full screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneSessions(b: BridgeUiState, s: LocalUiState, vm: LocalViewModel, onOpen: () -> Unit, onNew: () -> Unit) {
    val now = java.time.Instant.now()
    Column(Modifier.fillMaxWidth().hudPanel(warn = s.sessions.any { it.phase == "blocked" }).padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (s.sessions.isEmpty()) Text("Nothing running here. Claude Code runs on the phone itself, in herdr, in the phone's own checkout of a repo.",
            style = HudText.small.copy(color = Hud.overlay0))
        val byAccount = s.sessions.groupBy { it.account }
        val order = b.accounts.map { it.id } + byAccount.keys.filter { k -> b.accounts.none { it.id == k } }
        order.filter { it in byAccount }.forEach { id ->
            val a = b.accounts.firstOrNull { it.id == id }
            if (b.accounts.size > 1 || a == null) HudLabel(listOfNotNull(a?.name ?: id, a?.email?.ifEmpty { null }).joinToString(" · "))
            byAccount.getValue(id).forEach { c -> PhoneSessionItem(c, s, vm, now) { vm.open(c); onOpen() } }
        }
        HudButton("New session on this phone", onNew, kind = HudButtonKind.SMALL)
        s.error?.let { Text(it, style = HudText.meta.copy(color = Hud.amber), maxLines = 3, overflow = TextOverflow.Ellipsis) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneSessionItem(c: LocalSession, s: LocalUiState, vm: LocalViewModel, now: java.time.Instant, open: () -> Unit) {
    var draft by remember(c.name) { mutableStateOf("") }
    val color = phaseColor(c.phase)
    Column(Modifier.fillMaxWidth().leftStripe(color).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.clickable(onClick = open), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(7.dp).background(color, CircleShape))
            Text(c.title.ifEmpty { c.task.ifEmpty { c.repo.substringAfter('/') } }, style = HudText.small.copy(color = Hud.text),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(listOfNotNull(c.repo.substringAfter('/'), c.lastActivity?.let { agoShort(it, now) }).joinToString(" · "), style = HudText.meta)
        }
        Text(phaseText(c), style = HudText.meta.copy(color = color))
        if (c.phase != "busy" && c.lastText.isNotBlank()) {
            Text(c.lastText, style = HudText.meta.copy(color = Hud.subtext0), maxLines = 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = open))
        }
        val dialog = c.dialog
        if (dialog != null) {
            Text(dialog.question, style = HudText.meta.copy(color = Hud.subtext0))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                dialog.choices.forEach { ch -> HudButton(ch.label, { vm.choose(c.name, dialog, ch) }, kind = HudButtonKind.SMALL, enabled = !s.sending) }
            }
        } else if (c.phase != "ended" || c.resumable) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HudTextField(draft, { draft = it }, placeholder = if (c.phase == "ended") "Type to resume it" else "Type into it on this phone",
                    maxLines = 3, modifier = Modifier.weight(1f))
                HudButton("Send", { vm.send(c.name, draft.trim()); draft = "" }, kind = HudButtonKind.SMALL, enabled = draft.isNotBlank() && !s.sending)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HudButton("Open", open, kind = HudButtonKind.SMALL)
            if (c.phase == "busy") HudButton("Esc", { vm.interrupt(c.name) }, kind = HudButtonKind.SMALL)
            if (c.phase == "ended" && c.resumable) HudButton("Resume", { vm.resume(c.name) }, kind = HudButtonKind.SMALL, enabled = !s.sending)
        }
    }
}

private fun agoShort(t: java.time.Instant, now: java.time.Instant): String {
    val d = java.time.Duration.between(t, now).coerceAtLeast(java.time.Duration.ZERO)
    return when {
        d.toMinutes() < 1 -> "now"
        d.toHours() < 1 -> "${d.toMinutes()}m"
        d.toDays() < 1 -> "${d.toHours()}h"
        else -> "${d.toDays()}d"
    }
}

/** One session: its branch and state on top, then the chat or its changes, and the message box. */
@Composable
private fun LocalSessionView(c: LocalSession, s: LocalUiState, vm: LocalViewModel, modifier: Modifier) {
    var tab by remember(c.name) { mutableStateOf("chat") }
    val r = s.repo
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Where things stand, at a glance.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(phaseText(c), style = HudText.meta.copy(color = phaseColor(c.phase)))
            if (r != null) {
                Text("⎇ ${r.branch.ifEmpty { "detached" }}" + (if (r.ahead > 0) " ↑${r.ahead}" else "") + (if (r.behind > 0) " ↓${r.behind}" else ""),
                    style = HudText.meta.copy(fontFamily = HudFonts.mono), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            } else Box(Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudButton("Chat", { tab = "chat" }, kind = if (tab == "chat") HudButtonKind.PRIMARY else HudButtonKind.SMALL)
            HudButton("Changes" + (r?.files?.size?.takeIf { it > 0 }?.let { " ($it)" } ?: ""),
                { tab = "changes"; vm.refreshRepo() }, kind = if (tab == "changes") HudButtonKind.PRIMARY else HudButtonKind.SMALL)
        }
        if (tab == "chat") LocalChat(c, s, vm, Modifier.weight(1f)) else LocalChanges(c, s, vm, Modifier.weight(1f))
    }
}

/** A run of tool lines, or one message. */
private sealed interface ChatItem {
    data class Message(val line: ChatLine) : ChatItem
    data class Tools(val lines: List<String>) : ChatItem
}

private fun chatItems(lines: List<ChatLine>): List<ChatItem> {
    val out = mutableListOf<ChatItem>()
    for (l in lines) {
        val last = out.lastOrNull()
        if (l.role == "tool") {
            if (last is ChatItem.Tools) out[out.lastIndex] = ChatItem.Tools(last.lines + l.text) else out += ChatItem.Tools(listOf(l.text))
        } else out += ChatItem.Message(l)
    }
    return out
}

@Composable
private fun LocalChat(c: LocalSession, s: LocalUiState, vm: LocalViewModel, modifier: Modifier) {
    var draft by remember(c.name) { mutableStateOf("") }
    val chat = remember(c.messages) { chatItems(c.messages.takeLast(120)) }
    val list = rememberLazyListState()
    // New messages scroll into view.
    LaunchedEffect(chat.size, c.status) { if (chat.isNotEmpty()) list.animateScrollToItem(chat.size) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (chat.isEmpty() && c.screen.isBlank()) item { Text("Nothing yet.", style = HudText.meta) }
            items(chat.size) { i ->
                when (val entry = chat[i]) {
                    is ChatItem.Tools -> ToolRun(entry.lines)
                    is ChatItem.Message -> if (entry.line.role == "user") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Text(entry.line.text, style = HudText.small.copy(color = Hud.bright),
                                modifier = Modifier.widthIn(max = 300.dp).background(Hud.surface0).padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    } else Markdown(entry.line.text, Modifier.fillMaxWidth())
                }
            }
            // Before there's a chat, or while Claude shows something only the screen has.
            if (c.screen.isNotBlank() && (chat.isEmpty() || (c.status == "blocked" && c.dialog == null))) item {
                Box(Modifier.fillMaxWidth().heightIn(max = 260.dp).background(Hud.crust).padding(8.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                        c.screen.lines().takeLast(30).forEach { l -> Text(l.ifEmpty { " " }, style = HudText.meta.copy(fontFamily = HudFonts.mono, color = Hud.text), softWrap = false) }
                    }
                }
            }
            if (c.phase == "busy") item { Text(phaseText(c).replaceFirstChar { it.uppercase() }, style = HudText.meta.copy(color = Hud.cyan)) }
            item { Box(Modifier.heightIn(min = 1.dp)) }
        }
        c.dialog?.let { d ->
            // A question Claude asks (a tool to allow, say): its choices, answered with its own keys.
            Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).hudPanel(warn = true).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (d.detail.isNotBlank()) Text(d.detail, style = HudText.meta.copy(fontFamily = HudFonts.mono), maxLines = 12, overflow = TextOverflow.Ellipsis)
                Text(d.question, style = HudText.small.copy(color = Hud.text))
                d.choices.forEach { ch ->
                    HudButton("${ch.key}. ${ch.label}", { vm.choose(c.name, d, ch) }, kind = if (ch.selected) HudButtonKind.PRIMARY else HudButtonKind.SMALL,
                        enabled = !s.sending, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (c.phase != "ended" || c.resumable) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HudTextField(draft, { draft = it }, placeholder = if (c.phase == "ended") "Message Claude (resumes it)" else "Message Claude", maxLines = 5, modifier = Modifier.weight(1f))
                HudButton(if (s.sending) "…" else "Send", { vm.send(c.name, draft.trim()); draft = "" }, kind = HudButtonKind.PRIMARY,
                    enabled = draft.isNotBlank() && !s.sending && c.phase != "blocked")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (c.phase == "busy") HudButton("Esc (interrupt)", { vm.interrupt(c.name) }, kind = HudButtonKind.SMALL)
            if (c.phase == "ended" && c.resumable) HudButton("Resume", { vm.resume(c.name) }, kind = HudButtonKind.SMALL, enabled = !s.sending)
            HudButton(if (c.phase == "ended") "Remove" else "Stop", { vm.stop(c.name) }, kind = HudButtonKind.SMALL)
        }
    }
}

/** Tools Claude used in a row: one line, open on a tap. */
@Composable
private fun ToolRun(lines: List<String>) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().leftStripe(Hud.overlay0).clickable { open = !open }.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val names = lines.map { it.substringBefore(':') }.distinct().joinToString(", ")
        Text((if (open) "▾ " else "▸ ") + if (lines.size == 1) lines[0] else "${lines.size} tools · $names",
            style = HudText.meta.copy(fontFamily = HudFonts.mono), maxLines = if (open) 3 else 1, overflow = TextOverflow.Ellipsis)
        if (open && lines.size > 1) lines.forEach { Text("· $it", style = HudText.meta.copy(fontFamily = HudFonts.mono), maxLines = 3, overflow = TextOverflow.Ellipsis) }
    }
}

/** The checkout: what changed (a file opens as its diff), the last commits, and pull and push. */
@Composable
private fun LocalChanges(c: LocalSession, s: LocalUiState, vm: LocalViewModel, modifier: Modifier) {
    val r = s.repo
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (r == null) { Text("Looking…", style = HudText.meta); return@Column }
        Text(
            "${r.branch.ifEmpty { "detached" }} → ${r.upstream.ifEmpty { "no upstream yet" }}" +
                when {
                    r.ahead > 0 && r.behind > 0 -> " · ${r.ahead} to push, ${r.behind} to pull"
                    r.ahead > 0 -> " · ${r.ahead} to push"
                    r.behind > 0 -> " · ${r.behind} to pull"
                    else -> " · up to date"
                },
            style = HudText.small.copy(fontFamily = HudFonts.mono),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudButton("Pull", { vm.git("pull") }, kind = HudButtonKind.SMALL, enabled = !s.sending)
            HudButton("Push", { vm.git("push") }, kind = if (r.ahead > 0 || r.upstream.isEmpty()) HudButtonKind.PRIMARY else HudButtonKind.SMALL, enabled = !s.sending)
            if (r.files.isNotEmpty()) HudButton("Ask Claude to commit", {
                vm.send(c.name, "Commit the current changes with a clear, conventional commit message. Don't push.")
            }, kind = HudButtonKind.SMALL, enabled = !s.sending && c.phase != "busy" && c.phase != "blocked")
        }
        s.gitNote?.let { Text(it, style = HudText.meta.copy(color = Hud.green, fontFamily = HudFonts.mono)) }
        HudLabel(if (r.files.isEmpty()) "no changes" else "${r.files.size} changed")
        r.files.forEach { f ->
            val openFile = s.diffPath == f.path
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().clickable { vm.openDiff(if (openFile) null else f.path) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(f.status, style = HudText.meta.copy(fontFamily = HudFonts.mono, color = when (f.status) {
                        "??", "A" -> Hud.green; "D" -> Hud.amber; else -> Hud.cyan }), modifier = Modifier.widthIn(min = 22.dp))
                    Text(f.path, style = HudText.small.copy(fontFamily = HudFonts.mono, color = Hud.text), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("+${f.added} −${f.removed}", style = HudText.meta.copy(fontFamily = HudFonts.mono))
                }
                if (openFile) DiffView(s.diff)
            }
        }
        if (r.log.isNotEmpty()) {
            HudLabel("last commits", modifier = Modifier.padding(top = 6.dp))
            r.log.forEach { cm ->
                Text("${cm.hash} ${cm.subject}", style = HudText.meta.copy(fontFamily = HudFonts.mono), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A diff in colour: added green, removed amber, hunks cyan. */
@Composable
private fun DiffView(diff: String) {
    if (diff.isBlank()) { Text("…", style = HudText.meta); return }
    Column(Modifier.fillMaxWidth().background(Hud.crust).horizontalScroll(rememberScrollState()).padding(8.dp)) {
        diff.lines().filterNot { it.startsWith("diff --git") || it.startsWith("index ") || it.startsWith("--- ") || it.startsWith("+++ ") }
            .take(600).forEach { l ->
                Text(l.ifEmpty { " " }, softWrap = false, style = HudText.meta.copy(fontFamily = HudFonts.mono, color = when {
                    l.startsWith("+") -> Hud.green
                    l.startsWith("-") -> Hud.amber
                    l.startsWith("@@") -> Hud.cyan
                    else -> Hud.subtext0
                }))
            }
    }
}
