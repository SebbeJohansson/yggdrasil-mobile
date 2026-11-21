package io.github.sebbejohansson.yggdrasil.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sebbejohansson.yggdrasil.AppContainer
import io.github.sebbejohansson.yggdrasil.dellingr.DellingrService
import io.github.sebbejohansson.yggdrasil.dellingr.Job
import io.github.sebbejohansson.yggdrasil.gjallar.AlertRow
import io.github.sebbejohansson.yggdrasil.gjallar.AlertState
import io.github.sebbejohansson.yggdrasil.gjallar.Severity
import io.github.sebbejohansson.yggdrasil.hermodr.FOCUS
import io.github.sebbejohansson.yggdrasil.hermodr.FocusPlan
import io.github.sebbejohansson.yggdrasil.hermodr.rememberFocus
import io.github.sebbejohansson.yggdrasil.norn.CalendarEvent
import io.github.sebbejohansson.yggdrasil.norn.Promise
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudButton
import io.github.sebbejohansson.yggdrasil.ui.HudButtonKind
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.HudFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val clockFmt = DateTimeFormatter.ofPattern("HH:mm")
private val dayFmt = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.ENGLISH)
private fun clock(t: Instant) = t.atZone(ZoneId.systemDefault()).format(clockFmt)

/** Today's calendar, read off the main thread, every 2 minutes. */
@Composable
private fun rememberToday(c: AppContainer): List<CalendarEvent> {
    val events by produceState(emptyList<CalendarEvent>(), c) {
        while (true) {
            val zone = ZoneId.systemDefault()
            val day = Instant.now().atZone(zone).toLocalDate()
            value = withContext(Dispatchers.IO) {
                if (c.calendar.allowed()) c.calendar.between(day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant()) else emptyList()
            }
            delay(120_000)
        }
    }
    return events
}

/** Where the card's buttons go. */
enum class TodayTarget { PROMISES, ALERTS, JOBS }

/**
 * Today, as the dashboard's overview: a day ring, the meeting you're in or
 * the next, and when nothing holds you, how long you're free and the one
 * thing that fits, with Focus for the rest. Lives on the Ask page and at the
 * top of the home screen's apps.
 */
@Composable
fun TodayCard(
    c: AppContainer,
    promises: List<Promise>,
    alerts: List<AlertRow>,
    jobs: List<Job>,
    unread: Int,
    onOpen: (TodayTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val events = rememberToday(c)
    val (focus, now) = rememberFocus(c.hermodr)
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    val day = Today.day(events, now, zone)
    val today = now.atZone(zone).toLocalDate()

    val dueSoon = promises.mapNotNull { Today.promiseItem(it, zone, now) }
    val critical = alerts.filter { it.alert.state == AlertState.OPEN && it.alert.severity == Severity.CRITICAL && !it.muted }
    val items = dueSoon + critical.map { Today.Item(Today.Kind.ALERT, it.alert.title, "an alert", it.alert.openedAt, it.alert.id.toString()) } +
        jobs.filter { it.failures >= DellingrService.FAILING_AFTER }
            .map { Today.Item(Today.Kind.JOB, "Look at ${it.name}", "a job that keeps failing", it.lastRunAt ?: now, it.id) }
    val freeUntil = if (focus == null) Today.freeUntil(events, now, zone) else null
    val pick = freeUntil?.let { Today.pick(items, Duration.between(now, it)) }
    val needs = alerts.count { it.alert.state == AlertState.OPEN && !it.muted } + unread + dueSoon.size
    val offer = if (focus == null && c.calendar.allowed()) FocusPlan.offer(events, now, zone) else null

    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .border(1.dp, Hud.surface1).background(Hud.base.copy(alpha = 0.6f)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        DayRing(day.meetings.size, day.done, dueSoon.size, needs, Modifier.size(84.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HudLabel("Today · ${today.format(dayFmt)}", color = Hud.cyan)
            val meetingLine = when {
                !c.calendar.allowed() -> "Allow the calendar to see your day."
                day.now != null -> "In ${day.now.title} until ${clock(day.now.end)}"
                day.next != null -> "Next: ${day.next.title} at ${clock(day.next.begin)}, ${Today.inWords(Duration.between(now, day.next.begin))}"
                day.meetings.isEmpty() -> "No meetings today."
                else -> "No more meetings today."
            }
            Text(meetingLine, style = HudText.small, maxLines = 2)
            if (day.meetings.isNotEmpty()) Text("${day.done} of ${day.meetings.size} meetings done", style = HudText.meta)
            when {
                focus != null -> Text("Focusing until ${clock(focus.until)}.", style = HudText.meta.copy(color = FOCUS))
                freeUntil != null -> Text("${FocusPlan.words(Duration.between(now, freeUntil))} free until ${clock(freeUntil)}", style = HudText.meta.copy(color = Hud.green))
            }
            pick?.let { item ->
                Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    HudLabel("Fits now · ~${item.kind.minutes} min", color = Hud.amber)
                    Text(item.title, style = HudText.small.copy(color = Hud.bright), maxLines = 2)
                    Text(item.text, style = HudText.meta)
                }
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pick?.let { item ->
                    when (item.kind) {
                        Today.Kind.PROMISE -> {
                            HudButton("Done", { scope.launch { c.promises.done(item.ref.toLong()) } }, kind = HudButtonKind.SMALL)
                            HudButton("Promises", { onOpen(TodayTarget.PROMISES) }, kind = HudButtonKind.SMALL)
                        }
                        Today.Kind.ALERT -> HudButton("Alerts", { onOpen(TodayTarget.ALERTS) }, kind = HudButtonKind.SMALL)
                        Today.Kind.JOB -> HudButton("Jobs", { onOpen(TodayTarget.JOBS) }, kind = HudButtonKind.SMALL)
                    }
                }
                offer?.let { o ->
                    HudButton("Focus until ${clock(o.until)}", { scope.launch { runCatching { c.hermodr.startFocus(o.until) } } }, kind = HudButtonKind.SMALL, accent = FOCUS)
                }
            }
        }
    }
}

/**
 * The day in three rings, as the dashboard's: outside, today's meetings, one
 * segment each, lit once over. Then the promises due by tomorrow, amber.
 * Inside, what needs you (open alerts, unread, due promises), amber while
 * anything does. The count of what needs you sits in the middle.
 */
@Composable
fun DayRing(meetings: Int, done: Int, promises: Int, needs: Int, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(84.dp)) {
            val w = 5.dp.toPx()
            ring(size.minDimension / 2 - w, meetings, done, Hud.cyan, w)
            ring(size.minDimension / 2 - w * 2.6f, promises, promises, Hud.amber.copy(alpha = 0.75f), w)
            ring(size.minDimension / 2 - w * 4.2f, needs.coerceAtMost(16), needs.coerceAtMost(16), Hud.amber, w)
        }
        Text(
            if (needs == 0) "✓" else "$needs",
            style = HudText.title.copy(fontFamily = HudFonts.mono, fontSize = 18.sp, color = if (needs == 0) Hud.green else Hud.amber),
        )
    }
}

private fun DrawScope.ring(radius: Float, total: Int, lit: Int, color: Color, width: Float) {
    val topLeft = Offset(center.x - radius, center.y - radius)
    val box = Size(radius * 2, radius * 2)
    val track = Hud.surface0
    if (total <= 0) {
        drawArc(track, 0f, 360f, false, topLeft, box, style = Stroke(width))
        return
    }
    val gap = if (total == 1) 0f else 6f
    val sweep = 360f / total - gap
    for (i in 0 until total) {
        val start = -90f + i * (360f / total) + gap / 2
        drawArc(if (i < lit) color else track, start, sweep, false, topLeft, box, style = Stroke(width, cap = StrokeCap.Butt))
    }
}
