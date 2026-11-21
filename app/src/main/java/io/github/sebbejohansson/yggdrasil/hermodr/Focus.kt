package io.github.sebbejohansson.yggdrasil.hermodr

import io.github.sebbejohansson.yggdrasil.norn.Busy
import io.github.sebbejohansson.yggdrasil.norn.CalendarEvent
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * A focus you started: for a while nothing that can wait reaches you. Every
 * message but an urgent one is held until it ends, then what waited arrives
 * as one recap. Ported from Yggdrasil's `hermodr/focus.go`, without the
 * desktop's kinds (dev, goal): on the phone a focus is just a focus.
 */
data class Focus(val startedAt: Instant, val until: Instant, val intent: String? = null)

/** One focus in the log, start to end. [endedAt] is null only on the one going on now. */
data class FocusSession(val startedAt: Instant, val endedAt: Instant?, val until: Instant, val intent: String? = null) {
    fun length(now: Instant): Duration = Duration.between(startedAt, endedAt ?: minOf(now, until))
}

/** The focus going on, and the log of every focus. Kept in `ygg.db`, so a restart doesn't end one early. */
interface FocusStore {
    fun current(): Focus?
    fun set(focus: Focus?)

    /** A new session in the log. */
    fun opened(focus: Focus)

    /** The open session's end time or intent changed. */
    fun extended(focus: Focus)

    /** The open session ended at [at]. */
    fun closed(at: Instant)

    /** Every focus that started since [since], oldest first. */
    fun sessions(since: Instant): List<FocusSession>
}

class InMemoryFocusStore : FocusStore {
    private var focus: Focus? = null
    private val log = mutableListOf<FocusSession>()
    override fun current() = focus
    override fun set(focus: Focus?) { this.focus = focus }
    override fun opened(focus: Focus) { log += FocusSession(focus.startedAt, null, focus.until, focus.intent) }
    override fun extended(focus: Focus) {
        val i = log.indexOfLast { it.endedAt == null }
        if (i >= 0) log[i] = log[i].copy(until = focus.until, intent = focus.intent)
    }
    override fun closed(at: Instant) {
        val i = log.indexOfLast { it.endedAt == null }
        if (i >= 0) log[i] = log[i].copy(endedAt = at)
    }
    override fun sessions(since: Instant) = log.filter { !it.startedAt.isBefore(since) }
}

/**
 * Focus that fits the calendar, as the dashboard's `focusPlan.ts`: a focus
 * ends a few minutes before your next meeting rather than through it, and the
 * Ask page offers one when a free stretch is worth it.
 */
object FocusPlan {
    /** The longest a focus may be. */
    val MAX: Duration = Duration.ofHours(12)

    /** A focus ends this long before a meeting. */
    val BEFORE_MEETING: Duration = Duration.ofMinutes(5)

    /** A free stretch shorter than this isn't worth a focus. */
    val WORTH: Duration = Duration.ofMinutes(30)

    /** Fitting to a meeting never leaves a focus shorter than this. */
    private val SHORTEST: Duration = Duration.ofMinutes(10)

    /** With no meeting left, a free afternoon is offered up to this hour. */
    private val DAY_ENDS: LocalTime = LocalTime.of(18, 0)

    /** The lengths the focus button offers, in minutes. */
    val LENGTHS = listOf(25L, 50L, 90L)

    private val focusTitle = Regex("""^\s*focus(\s+time)?\b""", RegexOption.IGNORE_CASE)
    private val breakTitle = Regex("""^\s*break\b""", RegexOption.IGNORE_CASE)

    /**
     * A focus block: time you booked for focus, titled "Focus" (Yggdrasil's
     * week plan) or "Focus time" (Outlook's). Not a meeting, but `hold`
     * messages wait through it as through one.
     */
    fun isFocusBlock(e: CalendarEvent) = !e.allDay && !e.declined && focusTitle.containsMatchIn(e.title)

    /** A break you kept free in the calendar, titled "Break". */
    fun isBreak(e: CalendarEvent) = !e.allDay && !e.declined && breakTitle.containsMatchIn(e.title)

    /** A meeting that pulls you out: busy, timed, not declined, not a focus block or a break. */
    fun isMeeting(e: CalendarEvent) = Busy.counts(e) && !isFocusBlock(e) && !isBreak(e)

    fun nextMeeting(events: List<CalendarEvent>, now: Instant): CalendarEvent? =
        events.filter { isMeeting(it) && it.begin.isAfter(now) }.minByOrNull { it.begin }

    fun inMeeting(events: List<CalendarEvent>, now: Instant): CalendarEvent? =
        events.firstOrNull { isMeeting(it) && !it.begin.isAfter(now) && it.end.isAfter(now) }

    /** When a focus is in a focus block now, and until when (joined with blocks right after). */
    fun focusBlockUntil(events: List<CalendarEvent>, now: Instant): Instant? {
        var end = events.filter { isFocusBlock(it) && !it.begin.isAfter(now) && it.end.isAfter(now) }.maxOfOrNull { it.end } ?: return null
        events.filter(::isFocusBlock).sortedBy { it.begin }.forEach { if (it.end.isAfter(end) && !it.begin.isAfter(end)) end = it.end }
        return end
    }

    data class Fit(val until: Instant, val meeting: CalendarEvent? = null)

    /**
     * When a focus asked to end at [wanted] should end: before the next
     * meeting if that comes first, unless that leaves under ten minutes (then
     * as asked, with the meeting named).
     */
    fun fit(wanted: Instant, events: List<CalendarEvent>, now: Instant): Fit {
        val meeting = nextMeeting(events, now) ?: return Fit(wanted)
        val stop = meeting.begin.minus(BEFORE_MEETING)
        return if (stop.isBefore(wanted) && Duration.between(now, stop) >= SHORTEST) Fit(stop, meeting)
        else Fit(wanted, meeting.takeIf { it.begin.isBefore(wanted) })
    }

    data class Offer(val until: Instant, val minutes: Long, val meeting: CalendarEvent? = null)

    /** A free stretch from now worth a focus, if there is one and you're not in a meeting. */
    fun offer(events: List<CalendarEvent>, now: Instant, zone: ZoneId): Offer? {
        if (inMeeting(events, now) != null) return null
        val meeting = nextMeeting(events, now)
        val local = now.atZone(zone)
        val midnight = local.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        val sameDay = meeting != null && meeting.begin.isBefore(midnight)
        val endOfDay = local.toLocalDate().atTime(DAY_ENDS).atZone(zone).toInstant()
        val until = if (sameDay) meeting!!.begin.minus(BEFORE_MEETING) else minOf(endOfDay, now.plus(Duration.ofHours(2)))
        if (Duration.between(now, until) < WORTH) return null
        return Offer(until, Duration.between(now, until).plusSeconds(30).toMinutes(), meeting.takeIf { sameDay })
    }

    /** "25 min", "1 h", "1 h 40 min". */
    fun words(d: Duration): String {
        val minutes = (d.toMillis() + 59_999) / 60_000
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h" + (if (minutes % 60 != 0L) " ${minutes % 60} min" else "")
    }

    /** What waited, as the recap's text: the first few titles, and how many more. */
    fun recapText(length: Duration, waited: List<Message>, shown: Int = 5): String {
        val head = "${words(length)} of focus."
        if (waited.isEmpty()) return "$head Nothing waited."
        val titles = waited.take(shown).joinToString("\n") { "· ${it.title}" }
        val more = waited.size - shown
        return "$head ${waited.size} waited:\n$titles" + if (more > 0) "\n· and $more more" else ""
    }
}
