package io.github.sebbejohansson.yggdrasil.ratatoskr

import android.content.Context
import androidx.core.content.edit
import io.github.sebbejohansson.yggdrasil.hermodr.FocusPlan
import io.github.sebbejohansson.yggdrasil.hermodr.HermodrService
import io.github.sebbejohansson.yggdrasil.hermodr.MessageRequest
import io.github.sebbejohansson.yggdrasil.hermodr.Priority
import io.github.sebbejohansson.yggdrasil.norn.CalendarEvent
import io.github.sebbejohansson.yggdrasil.norn.CalendarReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.util.Locale

/**
 * Watching you, ported from Yggdrasil's `ratatoskr/you.go`: the parts'
 * watchdog pointed at your own rhythm. Three patterns, each a pure function:
 *
 * - **stretch:** meetings back to back, no gap of [Rhythm.gap] or more, for
 *   [Rhythm.stretch] or more.
 * - **late:** the phone in use past [Rhythm.late] (the desktop watches your
 *   coding sessions; the phone has the screen).
 * - **week:** most of the working hours left this week are meetings.
 *
 * None of these is a fault, so they never reach Gjallar. Each sends at most
 * one quiet, low, held message per day (the week: per week), never in quiet
 * hours, worded as an observation with something to do. Times only: meeting
 * titles and people never go into a message.
 */
object You {
    enum class Pattern(val title: String) {
        STRETCH("A long run of meetings"),
        LATE("It's late"),
        WEEK("A full week"),
    }

    data class Rhythm(
        val stretch: Duration = Duration.ofHours(4),
        /** A gap this long or longer ends a run of meetings. */
        val gap: Duration = Duration.ofMinutes(15),
        val late: LocalTime = LocalTime.of(23, 0),
        /** Percent of the week's working hours in meetings that makes it full. */
        val packed: Int = 60,
        val dayFrom: LocalTime = LocalTime.of(9, 0),
        val dayTo: LocalTime = LocalTime.of(17, 0),
    )

    /** The shortest gap inside a stretch worth naming as a pause. */
    val PAUSE: Duration = Duration.ofMinutes(5)

    /** The shortest free block in a full week worth suggesting. */
    val FREE_BLOCK: Duration = Duration.ofMinutes(30)

    /** When a late night ends. */
    val MORNING: LocalTime = LocalTime.of(5, 0)

    data class Span(val start: Instant, val end: Instant) {
        val length: Duration get() = Duration.between(start, end)
        fun overlaps(o: Span) = o.start.isBefore(end) && start.isBefore(o.end)
    }

    /** Sorts spans and joins any two whose gap is shorter than [gap] (zero joins only overlapping ones). */
    fun join(spans: List<Span>, gap: Duration = Duration.ZERO): List<Span> {
        val joined = mutableListOf<Span>()
        for (s in spans.sortedBy { it.start }) {
            if (!s.end.isAfter(s.start)) continue
            val last = joined.lastOrNull()
            if (last != null && s.start.isBefore(last.end.plus(gap))) {
                if (s.end.isAfter(last.end)) joined[joined.size - 1] = last.copy(end = s.end)
            } else {
                joined += s
            }
        }
        return joined
    }

    data class StretchSeen(
        val active: Boolean,
        val since: Instant? = null,
        /** The first gap of [Rhythm.gap] or more: when the run ends. */
        val nextGap: Instant? = null,
        /** The next short pause inside the run, if one is ahead. */
        val pauseAt: Instant? = null,
        /** A focus block is part of the run. */
        val focus: Boolean = false,
    )

    /**
     * Active while now is inside a run of meetings lasting [Rhythm.stretch]
     * or more with no gap of [Rhythm.gap]. A focus block carries a run on (it
     * isn't time away from the screen); a break you kept free ends it, however
     * short. A run of focus alone is no stretch.
     */
    fun stretch(meetings: List<Span>, focus: List<Span>, breaks: List<Span>, now: Instant, r: Rhythm): StretchSeen {
        val busy = join(meetings + focus)
        for (run in splitAt(join(busy, r.gap), busy, breaks)) {
            if (now.isBefore(run.start) || !now.isBefore(run.end) || run.length < r.stretch || meetings.none { it.overlaps(run) }) continue
            val pause = busy.zipWithNext { a, b -> Span(a.end, b.start) }
                .firstOrNull { g -> !g.start.isBefore(now) && !g.start.isBefore(run.start) && !g.end.isAfter(run.end) && g.length >= PAUSE }
            return StretchSeen(true, run.start, run.end, pause?.start, focus.any { it.overlaps(run) })
        }
        return StretchSeen(false)
    }

    private fun splitAt(runs: List<Span>, busy: List<Span>, breaks: List<Span>): List<Span> {
        if (breaks.isEmpty()) return runs
        return runs.flatMap { run ->
            val out = mutableListOf<Span>()
            var start = run.start
            for ((a, b) in busy.zipWithNext()) {
                val gap = Span(a.end, b.start)
                if (!gap.start.isAfter(start) || !gap.end.isBefore(run.end) || breaks.none { it.overlaps(gap) }) continue
                out += Span(start, gap.start)
                start = gap.end
            }
            out + Span(start, run.end)
        }
    }

    /** Between [Rhythm.late] and [MORNING]. */
    fun isLate(now: Instant, zone: ZoneId, r: Rhythm): Boolean {
        val t = now.atZone(zone).toLocalTime()
        return if (r.late.isAfter(MORNING)) !t.isBefore(r.late) || t.isBefore(MORNING) else !t.isBefore(r.late) && t.isBefore(MORNING)
    }

    /** Late: it's late and the phone is in use. */
    fun late(now: Instant, zone: ZoneId, r: Rhythm, inUse: Boolean) = inUse && isLate(now, zone, r)

    data class WeekSeen(val active: Boolean, val free: Span? = null)

    /**
     * Active when from the start of today to Friday's end, meetings fill
     * [Rhythm.packed] percent or more of the working hours. Weekends never.
     * Focus blocks aren't meetings, but [WeekSeen.free] never falls in one.
     */
    fun week(meetings: List<Span>, focus: List<Span>, now: Instant, zone: ZoneId, r: Rhythm): WeekSeen {
        val today = now.atZone(zone).toLocalDate()
        if (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY) return WeekSeen(false)
        val meetingsOnly = join(meetings)
        val busy = join(meetings + focus)
        var working = Duration.ZERO
        var booked = Duration.ZERO
        var free: Span? = null
        fun longer(s: Span) {
            if (s.length > Duration.ZERO && (free == null || s.length > free!!.length)) free = s
        }
        var day = today
        while (day.dayOfWeek != DayOfWeek.SATURDAY) {
            val hours = Span(day.atTime(r.dayFrom).atZone(zone).toInstant(), day.atTime(r.dayTo).atZone(zone).toInstant())
            working += hours.length
            for (m in meetingsOnly) {
                val s = maxOf(m.start, hours.start)
                val e = minOf(m.end, hours.end)
                if (e.isAfter(s)) booked += Duration.between(s, e)
            }
            var cursor = hours.start
            for (m in busy) {
                val s = maxOf(m.start, hours.start)
                val e = minOf(m.end, hours.end)
                if (!e.isAfter(s)) continue
                longer(Span(maxOf(cursor, now), s))
                cursor = maxOf(cursor, e)
            }
            longer(Span(maxOf(cursor, now), hours.end))
            day = day.plusDays(1)
        }
        if (working <= Duration.ZERO || booked.toMillis() * 100 < working.toMillis() * r.packed) return WeekSeen(false)
        return WeekSeen(true, free?.takeIf { it.length >= FREE_BLOCK })
    }

    /** The calendar as the patterns see it: meetings, focus blocks, breaks. */
    data class Calendar(val meetings: List<Span>, val focus: List<Span>, val breaks: List<Span>)

    fun calendar(events: List<CalendarEvent>) = Calendar(
        events.filter(FocusPlan::isMeeting).map { Span(it.begin, it.end) },
        events.filter(FocusPlan::isFocusBlock).map { Span(it.begin, it.end) },
        events.filter(FocusPlan::isBreak).map { Span(it.begin, it.end) },
    )

    /** One message per pattern per period: a day, a late evening, or a week. */
    fun period(p: Pattern, at: Instant, zone: ZoneId, r: Rhythm): String {
        val t = at.atZone(zone)
        return when (p) {
            Pattern.LATE -> {
                val start = t.toLocalDate().let { if (t.toLocalTime().isBefore(r.late) && isLate(at, zone, r)) it.minusDays(1) else it }
                start.toString()
            }
            Pattern.WEEK -> "%d-W%02d".format(t.get(IsoFields.WEEK_BASED_YEAR), t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
            Pattern.STRETCH -> t.toLocalDate().toString()
        }
    }

    private val clockFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val dayFmt = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)

    /** What you're told when a pattern starts. Times only, no titles. */
    fun message(p: Pattern, stretch: StretchSeen, week: WeekSeen, zone: ZoneId, r: Rhythm): Pair<String, String> {
        fun clock(t: Instant) = t.atZone(zone).format(clockFmt)
        val text = when (p) {
            Pattern.STRETCH -> {
                val gap = clock(stretch.nextGap!!)
                when {
                    stretch.focus -> "Meetings and focus run back to back until $gap. The gap at $gap is a good moment to step away from the screen."
                    stretch.pauseAt != null -> "Meetings run back to back until $gap. The pause at ${clock(stretch.pauseAt)} is enough to stand up and " +
                        "get some water, and the gap at $gap is a good moment to step away."
                    else -> "Meetings run back to back until $gap. The next gap is at $gap, a good moment to step away from the screen."
                }
            }
            Pattern.LATE -> "The phone is still in use past ${r.late.format(clockFmt)}. Writing down tomorrow's first step now makes the " +
                "start easy, and then it's a good moment to stop for tonight."
            Pattern.WEEK -> week.free?.let { f ->
                "Most of this week's working hours are meetings. ${f.start.atZone(zone).format(dayFmt)} ${clock(f.start)}–${clock(f.end)} " +
                    "is still open, a good block to keep for yourself."
            } ?: "Most of this week's working hours are meetings. A short walk between two of them is a good way to keep some air in the week."
        }
        return p.title to text
    }
}

class YouSettings(context: Context) {
    private val prefs = context.getSharedPreferences("you", Context.MODE_PRIVATE)

    var on: Boolean
        get() = prefs.getBoolean("on", true)
        set(v) = prefs.edit { putBoolean("on", v) }

    /** Also a notification (quiet, low priority); off lists them in Messages only. */
    var push: Boolean
        get() = prefs.getBoolean("push", true)
        set(v) = prefs.edit { putBoolean("push", v) }

    fun told(p: You.Pattern): Instant? = prefs.getLong("told_${p.name}", 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
    fun setTold(p: You.Pattern, at: Instant) = prefs.edit { putLong("told_${p.name}", at.toEpochMilli()) }
}

/** What a round saw, for the Settings page. */
data class YouStatus(val active: Map<You.Pattern, String?> = emptyMap(), val checkedAt: Instant? = null)

/** Runs the patterns with Veðrfölnir's 15-minute check. */
class YouService(
    private val calendar: CalendarReader,
    private val hermodr: HermodrService,
    val settings: YouSettings,
    /** Whether the phone is in use now (screen on). */
    private val inUse: () -> Boolean,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val now: () -> Instant = Instant::now,
    val rhythm: You.Rhythm = You.Rhythm(),
) {
    private val _status = MutableStateFlow(YouStatus())
    val status: StateFlow<YouStatus> = _status

    suspend fun round() {
        if (!settings.on) {
            _status.value = YouStatus()
            return
        }
        val at = now()
        val z = zone()
        val today = at.atZone(z).toLocalDate()
        val events = if (calendar.allowed()) calendar.between(today.atStartOfDay(z).toInstant(), today.plusDays(7).atStartOfDay(z).toInstant()) else emptyList()
        val cal = You.calendar(events)
        val stretch = You.stretch(cal.meetings, cal.focus, cal.breaks, at, rhythm)
        val week = You.week(cal.meetings, cal.focus, at, z, rhythm)
        val late = You.late(at, z, rhythm, inUse())
        val clock = DateTimeFormatter.ofPattern("HH:mm")
        val active = buildMap {
            if (stretch.active) put(You.Pattern.STRETCH, "until ${stretch.nextGap!!.atZone(z).format(clock)}")
            if (late) put(You.Pattern.LATE, null)
            if (week.active) put(You.Pattern.WEEK, null)
        }
        _status.value = YouStatus(active, at)
        // Not in quiet hours: an observation that waits till morning is stale.
        // Late is the exception, as late is in quiet hours: it's listed in
        // Messages then, never a notification.
        val quiet = hermodr.quietHoursNow().endAfter(at, z) != null
        for (p in active.keys) {
            if (quiet && p != You.Pattern.LATE) continue
            val told = settings.told(p)
            if (told != null && You.period(p, told, z, rhythm) == You.period(p, at, z, rhythm)) continue
            val (title, text) = You.message(p, stretch, week, z, rhythm)
            hermodr.send(
                MessageRequest(
                    "ratatoskr", title, text, priority = Priority.LOW, hold = true,
                    push = settings.push && !quiet, tag = "you-${p.name.lowercase()}",
                ),
            )
            settings.setTold(p, at)
        }
    }
}
