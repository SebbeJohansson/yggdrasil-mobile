package io.github.sebbejohansson.yggdrasil.hermodr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sebbejohansson.yggdrasil.norn.CalendarEvent
import io.github.sebbejohansson.yggdrasil.norn.CalendarReader
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudButton
import io.github.sebbejohansson.yggdrasil.ui.HudButtonKind
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Focus's colour, as the dashboard's: a soft violet, never an alarm. */
val FOCUS = Color(0xFFC7A6FF)

private val clockFmt = DateTimeFormatter.ofPattern("HH:mm")
private fun clock(t: Instant) = t.atZone(ZoneId.systemDefault()).format(clockFmt)

/** The focus as it stands, kept fresh: on every Hermóðr write, and each 15 s so it runs out on screen. */
@Composable
fun rememberFocus(hermodr: HermodrService): Pair<Focus?, Instant> {
    val version by hermodr.changes.flow.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }
    @Suppress("UNUSED_EXPRESSION") version
    val at = Instant.ofEpochMilli(now)
    return hermodr.focusNow()?.takeIf { it.until.isAfter(at) } to at
}

/** The next few hours of calendar, read off the main thread, refreshed every 2 minutes. */
@Composable
fun rememberCalendar(calendar: CalendarReader): List<CalendarEvent> {
    val events by produceState(emptyList<CalendarEvent>(), calendar) {
        while (true) {
            val now = Instant.now()
            value = withContext(Dispatchers.IO) {
                if (calendar.allowed()) calendar.between(now.minus(Duration.ofHours(2)), now.plus(Duration.ofHours(14))) else emptyList()
            }
            delay(120_000)
        }
    }
    return events
}

/**
 * The top bar's focus ring: tap for the lengths (each ends before your next
 * meeting if that comes first), or, while focusing, more time or an end.
 */
@Composable
fun FocusButton(hermodr: HermodrService, calendar: CalendarReader) {
    val (focus, now) = rememberFocus(hermodr)
    val events = rememberCalendar(calendar)
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.size(40.dp).clickable { open = true }, contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(18.dp)
                    .border(2.dp, if (focus != null) FOCUS else Hud.subtext0, CircleShape)
                    .padding(4.dp)
                    .background(if (focus != null) FOCUS else Color.Transparent, CircleShape),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Hud.base2) {
            @Composable fun item(text: String, action: suspend () -> Unit) =
                DropdownMenuItem(text = { Text(text, style = HudText.body) }, onClick = { open = false; scope.launch { runCatching { action() } } })
            if (focus != null) {
                HudLabel("Focusing until ${clock(focus.until)}", color = FOCUS, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                item("25 min more") { hermodr.startFocus(focus.until.plus(Duration.ofMinutes(25))) }
                item("End focus") { hermodr.endFocus() }
            } else {
                HudLabel("Focus: only urgent gets through", color = FOCUS, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                FocusPlan.LENGTHS.forEach { m ->
                    val fit = FocusPlan.fit(now.plus(Duration.ofMinutes(m)), events, now)
                    val label = FocusPlan.words(Duration.ofMinutes(m)) +
                        if (fit.until.isBefore(now.plus(Duration.ofMinutes(m)))) " · ends ${clock(fit.until)}, before ${fit.meeting!!.title}" else ""
                    item(label) { hermodr.startFocus(fit.until) }
                }
                FocusPlan.nextMeeting(events, now)?.let { next ->
                    val until = next.begin.minus(FocusPlan.BEFORE_MEETING)
                    if (Duration.between(now, until) >= Duration.ofMinutes(10)) {
                        item("Until ${clock(until)}, before ${next.title}") { hermodr.startFocus(until) }
                    }
                }
            }
        }
    }
}

/** Under the top bar while focusing: until when, how long is left, and End. */
@Composable
fun FocusStrip(hermodr: HermodrService) {
    val (focus, now) = rememberFocus(hermodr)
    val scope = rememberCoroutineScope()
    focus ?: return
    Row(
        Modifier.fillMaxWidth().background(FOCUS.copy(alpha = 0.10f)).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HudLabel("Focus", color = FOCUS)
        Text(
            "until ${clock(focus.until)} · ${FocusPlan.words(Duration.between(now, focus.until))} left · only urgent gets through",
            style = HudText.meta.copy(color = Hud.subtext0),
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        HudButton("End", { scope.launch { hermodr.endFocus() } }, kind = HudButtonKind.SMALL, accent = FOCUS)
    }
}

/** Settings: what focus does, and this week's focus. */
@Composable
fun FocusPanel(hermodr: HermodrService) {
    val version by hermodr.changes.flow.collectAsStateWithLifecycle()
    val week by produceState(emptyList<FocusSession>(), version) {
        value = hermodr.focusSessions(Instant.now().minus(Duration.ofDays(7)))
    }
    Text(
        "Start a focus from the ring in the top bar, the Ask page or the Quick Settings tile. While it runs only urgent messages " +
            "reach you; what waited comes as one recap when it ends. Calendar events called \"Focus\" or \"Focus time\" hold " +
            "messages like a meeting does.",
        style = HudText.meta,
    )
    val now = Instant.now()
    val total = week.fold(Duration.ZERO) { sum, s -> sum + s.length(now) }
    HudLabel(
        if (week.isEmpty()) "No focus in the last 7 days" else "Last 7 days: ${week.size} · ${FocusPlan.words(total)}",
        color = if (week.isEmpty()) Hud.overlay2 else FOCUS,
    )
}
