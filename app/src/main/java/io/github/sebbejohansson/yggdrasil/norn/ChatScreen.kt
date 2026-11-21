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
import io.github.sebbejohansson.yggdrasil.ui.HudProgress
import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.HudTextField
import io.github.sebbejohansson.yggdrasil.ui.HudToggle
import io.github.sebbejohansson.yggdrasil.ui.hudPanel
import io.github.sebbejohansson.yggdrasil.ui.leftStripe

/**
 * Norn, written like my-widget's ask panel: your lines in cyan behind a
 * `›`, Norn's under its name in sand with a line down the left.
 */
@Composable
fun ChatScreen(vm: ChatViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(NornPage.CHAT) }
    when (page) {
        NornPage.CHATS -> ChatsPage(s, onOpen = { vm.openChat(it); page = NornPage.CHAT }, onDelete = vm::deleteChat,
            onNew = { vm.newChat(); page = NornPage.CHAT }, onBack = { page = NornPage.CHAT })
        NornPage.MEMORY -> MemoryPage(s, vm, onBack = { page = NornPage.CHAT })
        NornPage.CHAT -> Column(Modifier.fillMaxSize()) {
            Header(s, onChats = { vm.refreshChats(); page = NornPage.CHATS }, onMemory = { page = NornPage.MEMORY }, onNew = vm::newChat)
            SetupPanel(s, onDownload = vm::downloadModel, onRefresh = { vm.refreshProviders() }, onAllowClaude = vm::setAllowClaude)
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
            Text("Norn", style = HudText.title)
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

@Composable
private fun SetupPanel(
    s: ChatUiState,
    onDownload: () -> Unit,
    onRefresh: () -> Unit,
    onAllowClaude: (Boolean) -> Unit,
) {
    val local = s.providers.firstOrNull { !it.leavesDevice }
    val claude = s.providers.firstOrNull { it.leavesDevice }
    val localReady = local?.status == ProviderStatus.Ready
    // Once the model is in, the panel only holds the Claude switch, so it stays out of the way.
    if (localReady && s.download == null && claude == null) return

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).hudPanel(warn = !localReady).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!localReady && local != null) {
            HudLabel("Model", color = Hud.amber)
            val why = when (val st = local.status) {
                is ProviderStatus.NeedsSetup -> st.reason
                is ProviderStatus.Unavailable -> st.reason
                ProviderStatus.Ready -> ""
            }
            Text(why.replaceFirstChar { it.uppercase() }, style = HudText.small)
        }
        when (val d = s.download) {
            is DownloadState.Downloading -> {
                HudProgress(d.fraction, Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("%.0f%%".format(d.fraction * 100), style = HudText.number.copy(color = Hud.cyan))
                    Spacer(Modifier.width(8.dp))
                    HudLabel("%.2f / %.2f GB".format(d.bytes / 1e9, d.total / 1e9))
                }
            }
            DownloadState.Verifying -> HudLabel("Checking the file…", color = Hud.cyan)
            DownloadState.Done, null -> if (!localReady && local?.status is ProviderStatus.NeedsSetup) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton("Download model", onDownload, kind = HudButtonKind.PRIMARY)
                    HudButton("Check again", onRefresh, kind = HudButtonKind.GHOST)
                }
            }
        }
        if (claude != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    HudLabel("Claude", color = if (claude.status == ProviderStatus.Ready) Hud.green else Hud.overlay0)
                    Text(
                        if (claude.status == ProviderStatus.Ready) "Allowed to answer. Text leaves the phone."
                        else "Bridge not running. Text would leave the phone.",
                        style = HudText.meta,
                    )
                }
                HudToggle(s.allowClaude, onAllowClaude)
            }
        }
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
            HudLabel("Norn is listening", color = Hud.overlay0)
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
            if (m.role == LlmRole.USER) YouLine(m.text) else NornLine(m.text, writing = false)
        }
        s.pending?.let { item { NornLine(it, writing = true) } }
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
private fun NornLine(text: String, writing: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        HudLabel("Norn", color = Hud.yellow, style = HudText.label.copy(fontSize = 10.sp))
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
        HudTextField(text, { text = it }, placeholder = "Ask Norn", modifier = Modifier.weight(1f), leading = "›")
        if (busy) {
            HudButton("Stop", onStop, kind = HudButtonKind.GHOST, accent = Hud.amber)
        } else {
            HudButton("Send", { onSend(text); text = "" }, kind = HudButtonKind.PRIMARY, enabled = text.isNotBlank())
        }
    }
}
