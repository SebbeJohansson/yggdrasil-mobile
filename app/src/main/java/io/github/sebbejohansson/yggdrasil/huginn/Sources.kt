package io.github.sebbejohansson.yggdrasil.huginn

import io.github.sebbejohansson.yggdrasil.gjallar.Alert
import io.github.sebbejohansson.yggdrasil.gjallar.AlertChange
import io.github.sebbejohansson.yggdrasil.gjallar.Severity
import io.github.sebbejohansson.yggdrasil.norn.Busy
import io.github.sebbejohansson.yggdrasil.norn.CalendarEvent
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A notification from another app, as [HuginnListener] reads it. */
data class PhoneNotification(
    /** Android's key for it; an update to the same notification has the same key. */
    val key: String,
    val packageName: String,
    val appLabel: String,
    val from: String,
    val text: String,
    val conversation: String?,
    val group: Boolean,
    val postedAt: Instant,
)

object Sources {
    /** Words that make a message urgent, in English and Swedish. */
    private val URGENT = Regex(
        """\b(urgent|asap|a\.s\.a\.p|emergency|right away|immediately|akut|brådskande|omedelbart|så fort som möjligt)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun isUrgent(text: String) = URGENT.containsMatchIn(text)

    /** The sender is one of [people] (by name, either way round, ignoring case). */
    fun isImportant(from: String, people: Set<String>): Boolean {
        val f = from.trim().lowercase()
        if (f.isEmpty()) return false
        return people.any { p -> val n = p.trim().lowercase(); n.isNotEmpty() && (f.contains(n) || n.contains(f)) }
    }

    private fun summary(text: String) = text.replace(Regex("\\s+"), " ").trim().let { if (it.length > 160) it.take(157) + "…" else it }

    fun notification(n: PhoneNotification, people: Set<String>): Fired {
        val important = isImportant(n.from, people)
        return Fired(
            kind = Events.NOTIFICATION,
            // The text is in the key: a chat that adds a message is a new event, the same one posted again is not.
            key = "n:${n.key}:${n.text.hashCode()}",
            at = n.postedAt,
            title = if (n.from.isNotBlank()) "${n.from} · ${n.appLabel}" else n.appLabel,
            urgent = isUrgent(n.text),
            fields = mapOf(
                "app" to n.appLabel,
                "from" to n.from.ifBlank { n.appLabel },
                "text" to n.text,
                "summary" to summary(n.text),
                "conversation" to n.conversation.orEmpty(),
                "direct" to if (n.group) "no" else "yes",
                "important" to if (important) "yes" else "no",
            ),
        )
    }

    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM")

    fun calendar(kind: String, e: CalendarEvent, zone: ZoneId): Fired {
        val at = if (kind == Events.CALENDAR_ENDED) e.end else e.begin
        return Fired(
            kind = kind,
            key = "$kind:${e.id}:${at.toEpochMilli()}",
            // For "soon" the event is now: when it was seen coming, not when it starts.
            at = if (kind == Events.CALENDAR_SOON) e.begin.minus(Duration.ofMinutes(30)) else at,
            title = if (kind == Events.CALENDAR_ENDED) "${e.title} ended" else "${e.title} at ${TIME.format(e.begin.atZone(zone))}",
            fields = mapOf(
                "subject" to e.title,
                "start" to TIME.format(e.begin.atZone(zone)),
                "end" to TIME.format(e.end.atZone(zone)),
                "day" to DAY.format(e.begin.atZone(zone)),
                "location" to e.location.orEmpty(),
                "where" to (e.location?.takeIf { it.isNotBlank() }?.let { " in $it" } ?: ""),
                "notes" to (e.description?.trim()?.takeIf { it.isNotBlank() }?.let { "\nThe invite says: " + it.take(600) } ?: ""),
            ),
        )
    }

    /**
     * The calendar events to tell about at [now]: meetings starting within
     * 30 minutes, and meetings that ended in the last 20. Huginn remembers
     * which it told, so checking often is fine.
     */
    fun calendarDue(events: List<CalendarEvent>, now: Instant): List<Pair<String, CalendarEvent>> {
        val meetings = events.filter { !it.allDay && !it.declined }
        val soon = meetings.filter { it.begin.isAfter(now) && !it.begin.isAfter(now.plus(Duration.ofMinutes(30))) }
            .map { Events.CALENDAR_SOON to it }
        val ended = meetings.filter { !it.end.isAfter(now) && it.end.isAfter(now.minus(Duration.ofMinutes(20))) && Busy.counts(it) }
            .map { Events.CALENDAR_ENDED to it }
        return soon + ended
    }

    fun alert(change: AlertChange, a: Alert): Fired {
        val kind = when (change) {
            AlertChange.OPENED -> Events.ALERT_OPENED
            AlertChange.ESCALATED -> Events.ALERT_ESCALATED
            AlertChange.RESOLVED -> Events.ALERT_RESOLVED
        }
        return Fired(
            kind = kind,
            key = "$kind:${a.id}:${a.lastSeenAt.toEpochMilli()}",
            at = a.lastSeenAt,
            title = a.title,
            urgent = a.severity == Severity.CRITICAL && change != AlertChange.RESOLVED,
            fields = mapOf(
                "title" to a.title, "detail" to a.detail.orEmpty(), "severity" to a.severity.name.lowercase(),
                "source" to a.source, "key" to a.key,
            ),
        )
    }
}
