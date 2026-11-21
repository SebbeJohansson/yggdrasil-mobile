package io.github.sebbejohansson.yggdrasil.hermodr

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.github.sebbejohansson.yggdrasil.norn.Busy
import io.github.sebbejohansson.yggdrasil.norn.CalendarEvent
import io.github.sebbejohansson.yggdrasil.norn.CalendarReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class MessagesUiState(
    val messages: List<Message> = emptyList(),
    val quietHours: QuietHours = QuietHours(),
    /** Why notifications can't be shown right now, if they can't. */
    val blocked: String? = null,
    val calendarAllowed: Boolean = false,
    /** When the meeting you're in ends; null when free. */
    val busyUntil: Instant? = null,
    val nextMeeting: CalendarEvent? = null,
    val dnd: DndStatus? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MessagesViewModel(
    private val hermodr: HermodrService,
    private val settings: HermodrSettings,
    private val notifier: Notifier,
    private val calendar: CalendarReader,
) : ViewModel() {

    private val quiet = MutableStateFlow(settings.quietHours)
    private val recheck = MutableStateFlow(0)

    /** Once a minute, so "in a meeting" turns to "free" by itself. */
    private val minute = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    val state = combine(hermodr.changes.flow, quiet, recheck, minute) { _, q, _, _ -> q }
        .mapLatest { q ->
            val now = Instant.now()
            val allowed = calendar.allowed()
            val events = if (allowed) {
                withContext(Dispatchers.IO) { calendar.between(now.minus(Duration.ofHours(12)), now.plus(Duration.ofHours(24))) }
            } else {
                emptyList()
            }
            MessagesUiState(
                messages = hermodr.list(),
                quietHours = q,
                blocked = notifier.blockedReason(Priority.NORMAL),
                dnd = notifier.dnd(),
                calendarAllowed = allowed,
                busyUntil = Busy.until(events, now),
                nextMeeting = Busy.next(events, now),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MessagesUiState())

    fun markSeen(m: Message) = viewModelScope.launch { hermodr.markSeen(m.id) }

    fun setQuietHours(enabled: Boolean) {
        settings.quietHours = settings.quietHours.copy(enabled = enabled)
        quiet.value = settings.quietHours
        // Turning them off releases what they held.
        viewModelScope.launch { hermodr.flushHeld() }
    }

    /** After a permission dialog, or coming back from Android's settings. */
    fun recheckPermission() {
        recheck.value++
        // Calendar access may have just been given: what was held for a meeting may go now.
        viewModelScope.launch { hermodr.flushHeld() }
    }

    fun sendTest(priority: Priority, hold: Boolean = false) = viewModelScope.launch {
        hermodr.send(
            MessageRequest(
                sender = "test",
                title = "Test ${priority.name.lowercase()} message" + if (hold) " (hold)" else "",
                text = if (hold) "Waits while you're in a meeting" else "Sent from Settings",
                priority = priority,
                hold = hold,
            ),
        )
    }

    companion object {
        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { MessagesViewModel(c.hermodr, c.hermodrSettings, c.notifier, c.calendar) }
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

/** Hermóðr: every message for you, newest first. Its switches are in Settings → Notifications. */
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
                        s.busyUntil?.let { "in a meeting until ${clockFmt.format(it)}" },
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
        s.dnd?.let { d -> item { DndPanel(d) } }
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
    }
}

/** Settings → Notifications: quiet hours, and holding messages through meetings. */
@Composable
fun MessageSettings(vm: MessagesViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
        MeetingsPanel(s, onAllowed = vm::recheckPermission)
    }
}

/** Settings → Developer: send test messages. */
@Composable
fun MessageTests(vm: MessagesViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HudButton("Normal", { vm.sendTest(Priority.NORMAL) }, kind = HudButtonKind.SMALL)
        HudButton("Urgent", { vm.sendTest(Priority.URGENT) }, kind = HudButtonKind.SMALL)
        HudButton("Hold", { vm.sendTest(Priority.NORMAL, hold = true) }, kind = HudButtonKind.SMALL)
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
            MessageState.HELD -> if (m.hold) "held until you're free" else "held through quiet hours"
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

private val clockFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** Hermóðr's hold: messages that ask to wait, wait while a meeting in your calendar runs. */
@Composable
private fun MeetingsPanel(s: MessagesUiState, onAllowed: () -> Unit) {
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onAllowed() }
    Column(
        Modifier.fillMaxWidth().hudPanel().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HudLabel("Meetings", color = Hud.group, modifier = Modifier.weight(1f))
            when {
                !s.calendarAllowed -> HudButton("Allow calendar", { ask.launch(Manifest.permission.READ_CALENDAR) }, kind = HudButtonKind.SMALL)
                s.busyUntil != null -> HudLabel("in a meeting", color = Hud.amber)
                else -> HudLabel("free", color = Hud.green)
            }
        }
        Text(
            when {
                !s.calendarAllowed -> "With your calendar, messages that ask to wait are held while you're in a meeting. It's read on this phone only."
                s.busyUntil != null -> "Until ${clockFmt.format(s.busyUntil)}. Messages that ask to wait are held till then."
                s.nextMeeting != null -> "Next: ${s.nextMeeting.title} at ${clockFmt.format(s.nextMeeting.begin)}."
                else -> "Nothing more in your calendar today."
            },
            style = HudText.meta,
        )
    }
}

/**
 * Android's Do Not Disturb silences Yggdrasil too. Letting only the urgent
 * channel through keeps critical alerts and urgent messages loud, the rest
 * quiet. Only you can switch that on, in Android's settings for the channel.
 */
@Composable
private fun DndPanel(d: DndStatus) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier.fillMaxWidth().hudPanel(warn = d.on && !d.urgentGetsThrough).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HudLabel("Do Not Disturb", color = Hud.group, modifier = Modifier.weight(1f))
            HudLabel(
                if (d.urgentGetsThrough) "urgent gets through" else "urgent silenced",
                color = if (d.urgentGetsThrough) Hud.green else if (d.on) Hud.amber else Hud.overlay0,
            )
        }
        Text(
            when {
                d.urgentGetsThrough && d.on -> "On now. Urgent messages and critical alerts still reach you; the rest wait quietly."
                d.urgentGetsThrough -> "When it's on, urgent messages and critical alerts still reach you; the rest stay quiet."
                d.on -> "On now: even urgent messages and critical alerts are silent. Let the Urgent channel through to change that."
                else -> "When it's on, everything from Yggdrasil is silent. Let only the Urgent channel through, and critical alerts still reach you."
            },
            style = HudText.meta,
        )
        if (!d.urgentGetsThrough) {
            HudButton(
                "Let urgent through",
                { context.startActivity(AndroidNotifier.urgentChannelSettings(context)) },
                kind = HudButtonKind.SMALL,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
