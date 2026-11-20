package io.github.sebbejohansson.yggdrasil.norn

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import io.github.sebbejohansson.yggdrasil.hermodr.BusySignal
import java.time.Duration
import java.time.Instant

data class CalendarEvent(
    val title: String,
    val begin: Instant,
    val end: Instant,
    val allDay: Boolean,
    /** Shown as busy (not free or tentative). */
    val busy: Boolean,
    /** You said no to it. */
    val declined: Boolean,
)

/** The phone's calendars. [AndroidCalendarReader] in the app, a fake in tests. */
interface CalendarReader {
    fun allowed(): Boolean
    fun between(from: Instant, to: Instant): List<CalendarEvent>
}

object Busy {
    /** Meetings this close together count as one stretch. */
    val JOIN: Duration = Duration.ofMinutes(5)

    /** What counts as a meeting: timed, busy, and not declined. */
    fun counts(e: CalendarEvent) = !e.allDay && e.busy && !e.declined

    /**
     * When the meeting you're in ends, following straight on into the next
     * one if it starts within [JOIN]. Null when you're free.
     */
    fun until(events: List<CalendarEvent>, now: Instant): Instant? {
        val meetings = events.filter(::counts).sortedBy { it.begin }
        var end: Instant? = meetings.filter { !it.begin.isAfter(now) && it.end.isAfter(now) }.maxOfOrNull { it.end }
            ?: return null
        var grew = true
        while (grew) {
            grew = false
            for (m in meetings) {
                if (m.end.isAfter(end) && !m.begin.isAfter(end!!.plus(JOIN))) {
                    end = m.end
                    grew = true
                }
            }
        }
        return end
    }

    /** The next meeting that starts after [now]. */
    fun next(events: List<CalendarEvent>, now: Instant): CalendarEvent? =
        events.filter { counts(it) && it.begin.isAfter(now) }.minByOrNull { it.begin }
}

/** Hermóðr's busy signal from the phone's calendars. Free when calendar access isn't given. */
class CalendarBusySignal(private val reader: CalendarReader) : BusySignal {
    override suspend fun busyUntil(now: Instant): Instant? {
        if (!reader.allowed()) return null
        return Busy.until(reader.between(now.minus(Duration.ofHours(12)), now.plus(Duration.ofHours(12))), now)
    }
}

class AndroidCalendarReader(private val context: Context) : CalendarReader {

    override fun allowed() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override fun between(from: Instant, to: Instant): List<CalendarEvent> {
        if (!allowed()) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(from.toEpochMilli().toString())
            .appendPath(to.toEpochMilli().toString())
            .build()
        val cols = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.AVAILABILITY,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        return try {
            context.contentResolver.query(uri, cols, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            CalendarEvent(
                                title = c.getString(0) ?: "(no title)",
                                begin = Instant.ofEpochMilli(c.getLong(1)),
                                end = Instant.ofEpochMilli(c.getLong(2)),
                                allDay = c.getInt(3) != 0,
                                busy = c.getInt(4) == CalendarContract.Events.AVAILABILITY_BUSY,
                                declined = c.getInt(5) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED,
                            ),
                        )
                    }
                }
            } ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }
}
