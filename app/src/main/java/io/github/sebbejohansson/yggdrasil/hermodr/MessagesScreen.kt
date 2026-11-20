package io.github.sebbejohansson.yggdrasil.hermodr

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudButton
import io.github.sebbejohansson.yggdrasil.ui.HudButtonKind
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudSection
import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.HudToggle
import io.github.sebbejohansson.yggdrasil.ui.hudPanel
import io.github.sebbejohansson.yggdrasil.ui.leftStripe
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.sebbejohansson.yggdrasil.AppContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class MessagesUiState(
    val messages: List<Message> = emptyList(),
    val quietHours: QuietHours = QuietHours(),
    /** Why notifications can't be shown right now, if they can't. */
    val blocked: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MessagesViewModel(
    private val hermodr: HermodrService,
    private val settings: HermodrSettings,
    private val notifier: Notifier,
) : ViewModel() {

    private val quiet = MutableStateFlow(settings.quietHours)
    private val recheck = MutableStateFlow(0)

    val state = combine(hermodr.changes.flow, quiet, recheck) { _, q, _ -> q }
        .mapLatest { q -> MessagesUiState(hermodr.list(), q, notifier.blockedReason(Priority.NORMAL)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MessagesUiState())

    fun markSeen(m: Message) = viewModelScope.launch { hermodr.markSeen(m.id) }

    fun setQuietHours(enabled: Boolean) {
        settings.quietHours = settings.quietHours.copy(enabled = enabled)
        quiet.value = settings.quietHours
        // Turning them off releases what they held.
        viewModelScope.launch { hermodr.flushHeld() }
    }

    /** After the permission dialog, or coming back from Android's settings. */
    fun recheckPermission() {
        recheck.value++
    }

    fun sendTest(priority: Priority) = viewModelScope.launch {
        hermodr.send(MessageRequest(sender = "test", title = "Test ${priority.name.lowercase()} message", text = "Sent from the Messages tab", priority = priority))
    }

    companion object {
        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { MessagesViewModel(c.hermodr, c.hermodrSettings, c.notifier) }
        }
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault())

private fun Priority.color(): Color = when (this) {
    Priority.LOW -> Hud.overlay0
    Priority.NORMAL -> Hud.cyan
    Priority.HIGH -> Hud.yellow
    Priority.URGENT -> Hud.red
}

/** Hermóðr: every message for you, newest first, with quiet hours on top. */
@Composable
fun MessagesScreen(vm: MessagesViewModel, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsStateWithLifecycle()
    val unseen = s.messages.count { it.seenAt == null }
    val held = s.messages.count { it.state == MessageState.HELD }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                Text("Hermóðr", style = HudText.title)
                HudLabel(
                    listOfNotNull(
                        if (unseen > 0) "$unseen unread" else "all read",
                        if (held > 0) "$held held" else null,
                        "last 7 days",
                    ).joinToString(" · "),
                    color = if (unseen > 0) Hud.amber else Hud.overlay2,
                )
            }
        }
        s.blocked?.let { why ->
            item {
                Column(Modifier.fillMaxWidth().hudPanel(warn = true).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HudLabel("Notifications off", color = Hud.amber)
                    Text("Messages stay in this list only: $why.", style = HudText.small)
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().hudPanel().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    HudLabel("Quiet hours", color = Hud.group)
                    Text(
                        "${s.quietHours.start}–${s.quietHours.end} · only urgent goes out",
                        style = HudText.meta,
                    )
                }
                HudToggle(s.quietHours.enabled, vm::setQuietHours)
            }
        }
        item { HudSection("Messages", Modifier.padding(top = 8.dp)) }
        if (s.messages.isEmpty()) {
            item {
                Text(
                    "No messages in the last 7 days.",
                    style = HudText.small.copy(color = Hud.overlay0, textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        }
        items(s.messages, key = { it.id }) { m -> MessageItem(m, onSeen = { vm.markSeen(m) }) }
        item { HudSection("Test", Modifier.padding(top = 12.dp)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HudButton("Normal", { vm.sendTest(Priority.NORMAL) }, kind = HudButtonKind.SMALL)
                HudButton("Urgent", { vm.sendTest(Priority.URGENT) }, kind = HudButtonKind.SMALL)
            }
        }
    }
}

@Composable
private fun MessageItem(m: Message, onSeen: () -> Unit) {
    val unseen = m.seenAt == null
    Column(
        Modifier.fillMaxWidth().alpha(if (unseen) 1f else 0.55f).leftStripe(m.priority.color()).padding(start = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.title, style = HudText.small, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            HudLabel(m.priority.name, color = m.priority.color(), style = HudText.label.copy(fontSize = 10.sp))
        }
        m.text?.let { Text(it, style = HudText.meta.copy(color = Hud.subtext0)) }
        val status = when (m.state) {
            MessageState.HELD -> "held"
            MessageState.SENT -> "sent"
            MessageState.LISTED -> "listed only"
            // The banner above says why; once is enough.
            MessageState.FAILED -> "not shown"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${m.sender} · $status · ${timeFmt.format(m.createdAt)}",
                style = HudText.meta.copy(color = if (m.state == MessageState.FAILED) Hud.red else Hud.overlay0),
                modifier = Modifier.weight(1f),
            )
            if (unseen) HudButton("Seen", onSeen, kind = HudButtonKind.SMALL)
        }
    }
}
