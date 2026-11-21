package io.github.sebbejohansson.yggdrasil.norn

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sebbejohansson.yggdrasil.llm.LlmRole
import io.github.sebbejohansson.yggdrasil.llm.ProviderStatus
import io.github.sebbejohansson.yggdrasil.model.DownloadState
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudButton
import io.github.sebbejohansson.yggdrasil.ui.HudButtonKind
import io.github.sebbejohansson.yggdrasil.ui.HudFonts
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.HudTextField
import io.github.sebbejohansson.yggdrasil.ui.hudPanel
import io.github.sebbejohansson.yggdrasil.ui.leftStripe

/**
 * The chat, written like my-widget's ask panel: your lines in cyan behind a
 * `›`, Yggdrasil's under its name in sand with a line down the left.
 */
@Composable
fun ChatScreen(vm: ChatViewModel, onSettings: () -> Unit = {}) {
    val s by vm.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(NornPage.CHAT) }
    when (page) {
        NornPage.CHATS -> ChatsPage(s, onOpen = { vm.openChat(it); page = NornPage.CHAT }, onDelete = vm::deleteChat,
            onNew = { vm.newChat(); page = NornPage.CHAT }, onBack = { page = NornPage.CHAT })
        NornPage.MEMORY -> MemoryPage(s, vm, onBack = { page = NornPage.CHAT })
        NornPage.CHAT -> Column(Modifier.fillMaxSize()) {
            Header(s, onChats = { vm.refreshChats(); page = NornPage.CHATS }, onMemory = { page = NornPage.MEMORY }, onNew = vm::newChat)
            SetupNotice(s, onSettings)
            Transcript(s, Modifier.weight(1f))
            s.error?.let {
                Text(
                    it,
                    style = HudText.small.copy(color = Hud.red),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).leftStripe(Hud.red).padding(start = 8.dp),
                )
            }
            Composer(busy = s.busy, onSend = vm::send, onStop = vm::stop)
        }
    }
}

enum class NornPage { CHAT, CHATS, MEMORY }

@Composable
private fun Header(s: ChatUiState, onChats: () -> Unit, onMemory: () -> Unit, onNew: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Yggdrasil", style = HudText.title)
            HudLabel(statusLine(s), color = if (s.providers.any { it.status == ProviderStatus.Ready }) Hud.overlay2 else Hud.amber)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudButton("Chats", onChats, kind = HudButtonKind.SMALL)
            HudButton("Memory", onMemory, kind = HudButtonKind.SMALL)
            if (s.messages.isNotEmpty() && !s.busy) HudButton("New", onNew, kind = HudButtonKind.SMALL)
        }
    }
}

private fun statusLine(s: ChatUiState): String {
    val ready = s.providers.filter { it.status == ProviderStatus.Ready }
    if (ready.isEmpty()) return "no model ready"
    val local = ready.firstOrNull { !it.leavesDevice }?.name?.substringBefore(" (")
    return listOfNotNull(local, s.backend?.lowercase()?.let { "on $it" }, if (s.allowClaude) "claude allowed" else "on-device only")
        .joinToString(" · ")
}

/**
 * Only when the chat can't answer yet, or the model is still coming: one line,
 * and the way to Settings → Assistant, where the switches live.
 */
@Composable
private fun SetupNotice(s: ChatUiState, onSettings: () -> Unit) {
    val local = s.providers.firstOrNull { !it.leavesDevice }
    val localReady = local?.status == ProviderStatus.Ready
    val line = when (val d = s.download) {
        is DownloadState.Downloading -> if (s.downloadWaiting) "Model download waiting for a network" else "Model downloading · %.0f%%".format(d.fraction * 100)
        DownloadState.Verifying -> "Checking the model file…"
        DownloadState.Done, null -> if (localReady || local == null) return else "No model on this phone yet"
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).hudPanel(warn = !localReady).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(line, style = HudText.small, modifier = Modifier.weight(1f).padding(end = 8.dp))
        HudButton("Settings", onSettings, kind = HudButtonKind.SMALL)
    }
}

@Composable
private fun Transcript(s: ChatUiState, modifier: Modifier) {
    val list = rememberLazyListState()
    val count = s.messages.size + if (s.pending != null) 1 else 0
    LaunchedEffect(count, s.pending?.length) {
        if (count > 0) list.animateScrollToItem(count - 1)
    }
    if (count == 0) {
        Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            HudLabel("Yggdrasil is listening", color = Hud.overlay0)
            Spacer(Modifier.padding(4.dp))
            Text("Ask about anything. It runs on this phone.", style = HudText.small.copy(color = Hud.overlay0))
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = list,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        items(s.messages) { m ->
            if (m.role == LlmRole.USER) YouLine(m.text) else AnswerLine(m.text, writing = false)
        }
        s.pending?.let { item { AnswerLine(it, writing = true) } }
        if (s.learned.isNotEmpty()) {
            item {
                HudLabel(
                    "Remembered: " + s.learned.joinToString(" · "),
                    color = Hud.green, style = HudText.label.copy(fontSize = 10.sp, letterSpacing = 0.06.em),
                )
            }
        }
    }
}

@Composable
private fun YouLine(text: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Hud.overlay0, fontFamily = HudFonts.mono)) { append("› ") }
            append(text)
        },
        style = HudText.body.copy(color = Hud.cyan),
    )
}

@Composable
private fun AnswerLine(text: String, writing: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        HudLabel("Yggdrasil", color = Hud.yellow, style = HudText.label.copy(fontSize = 10.sp))
        Spacer(Modifier.padding(top = 3.dp))
        Row(Modifier.leftStripe(Hud.surface1).padding(start = 10.dp)) {
            if (text.isEmpty() && writing) {
                Cursor()
            } else {
                Text(
                    text,
                    style = HudText.body,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (writing) Cursor()
            }
        }
    }
}

@Composable
private fun Cursor() {
    val blink = rememberInfiniteTransition(label = "cursor")
    val a by blink.animateFloat(1f, 0f, infiniteRepeatable(tween(530), RepeatMode.Reverse), label = "blink")
    Text("▍", style = HudText.body.copy(color = Hud.cyan), modifier = Modifier.alpha(a))
}

@Composable
private fun Composer(busy: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HudTextField(text, { text = it }, placeholder = "Ask Yggdrasil", modifier = Modifier.weight(1f), leading = "›")
        if (busy) {
            HudButton("Stop", onStop, kind = HudButtonKind.GHOST, accent = Hud.amber)
        } else {
            HudButton("Send", { onSend(text); text = "" }, kind = HudButtonKind.PRIMARY, enabled = text.isNotBlank())
        }
    }
}
