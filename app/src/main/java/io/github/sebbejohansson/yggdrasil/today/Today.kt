package io.github.sebbejohansson.yggdrasil.today

import io.github.sebbejohansson.yggdrasil.hermodr.FocusPlan
import io.github.sebbejohansson.yggdrasil.norn.CalendarEvent
import io.github.sebbejohansson.yggdrasil.norn.Promise
import io.github.sebbejohansson.yggdrasil.norn.PromiseState
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Today and the gap you're in, ported from Yggdrasil's `norn/gap.go` and the
 * dashboard's Today: the day's meetings, the one you're in or the next, and
 * when nothing holds you, the one waiting thing that fits before the next
 * meeting (or the end of the working day).
 *
 * The desktop's waiting things are mostly coding goals; the phone's are what
 * it has: promises due by tomorrow, an alert that wants you, a job that keeps
 * failing. Everything stays on the phone.
 */
object Today {
    /** The least free time worth offering anything in. */
    val SHORTEST: Duration = Duration.ofMinutes(10)

    /** The room an item must leave in the gap, so it isn't a race against the clock. */
    val SLACK: Duration = Duration.ofMinutes(5)

    val DAY_FROM: LocalTime = LocalTime.of(9, 0)
    val DAY_TO: LocalTime = LocalTime.of(17, 0)

    /** Whether an event holds you: a meeting, a break (not for work), a focus block (you're focusing). */
    fun holds(e: CalendarEvent) = FocusPlan.isMeeting(e) || FocusPlan.isBreak(e) || FocusPlan.isFocusBlock(e)

    /**
     * When the free time you're in ends: the next event that holds you, or
     * the end of the working day. Null when something holds you now, or it's
     * outside the working day.
     */
    fun freeUntil(events: List<CalendarEvent>, now: Instant, zone: ZoneId, from: LocalTime = DAY_FROM, to: LocalTime = DAY_TO): Instant? {
        val local = now.atZone(zone)
        if (local.dayOfWeek == DayOfWeek.SATURDAY || local.dayOfWeek == DayOfWeek.SUNDAY) return null
        val t = local.toLocalTime()
        if (t.isBefore(from) || !t.isBefore(to)) return null
        var end = local.toLocalDate().atTime(to).atZone(zone).toInstant()
        for (e in events) {
            if (!holds(e) || !e.end.isAfter(now)) continue
            if (!e.begin.isAfter(now)) return null
            if (e.begin.isBefore(end)) end = e.begin
        }
        return end
    }

    enum class Kind(val minutes: Long) {
        /** An open critical alert: comes first, as an agent waiting on you does on the desktop. */
        ALERT(3),
        PROMISE(10),
        JOB(5),
    }

    /** The one thing offered for a gap. [ref] is the promise's, alert's or job's id. */
    data class Item(val kind: Kind, val title: String, val text: String, val since: Instant, val ref: String)

    /** An open promise due by tomorrow (or already past); none without a day or due later. */
    fun promiseItem(p: Promise, zone: ZoneId, now: Instant): Item? {
        val due = p.due ?: return null
        if (p.state != PromiseState.OPEN || due.isAfter(now.atZone(zone).toLocalDate().plusDays(1))) return null
        return Item(Kind.PROMISE, p.what, if (p.to.isNotEmpty()) "promised to ${p.to}" else "a promise", p.createdAt, p.id.toString())
    }

    /**
     * The one item for a gap of [free]: an alert first, then the oldest
     * waiting, among those that fit with [SLACK] to spare. Null when the gap
     * is under [SHORTEST] or nothing fits.
     */
    fun pick(items: List<Item>, free: Duration): Item? {
        if (free < SHORTEST) return null
        return items.sortedWith(compareBy<Item> { it.kind != Kind.ALERT }.thenBy { it.since })
            .firstOrNull { Duration.ofMinutes(it.kind.minutes).plus(SLACK) <= free }
    }

    /** The day's meetings, and where you are in them. */
    data class Day(
        val meetings: List<CalendarEvent>,
        val done: Int,
        val now: CalendarEvent?,
        val next: CalendarEvent?,
    )

    fun day(events: List<CalendarEvent>, now: Instant, zone: ZoneId): Day {
        val date = now.atZone(zone).toLocalDate()
        val meetings = events.filter { FocusPlan.isMeeting(it) && it.begin.atZone(zone).toLocalDate() == date }.sortedBy { it.begin }
        return Day(
            meetings,
            meetings.count { !it.end.isAfter(now) },
            meetings.firstOrNull { !it.begin.isAfter(now) && it.end.isAfter(now) },
            meetings.firstOrNull { it.begin.isAfter(now) },
        )
    }

    /** "in 40 min", "in 2 h 10 min". */
    fun inWords(d: Duration) = "in " + FocusPlan.words(d)
}
