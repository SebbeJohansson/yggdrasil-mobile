package io.github.sebbejohansson.yggdrasil.norn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class BusyTest {
    private fun at(hm: String) = Instant.parse("2026-11-20T${hm}:00Z")
    private fun meeting(from: String, to: String, busy: Boolean = true, declined: Boolean = false, allDay: Boolean = false) =
        CalendarEvent("m $from", at(from), at(to), allDay, busy, declined)

    @Test fun freeOutsideMeetings() {
        assertNull(Busy.until(listOf(meeting("10:00", "11:00")), at("11:00")))
        assertNull(Busy.until(listOf(meeting("10:00", "11:00")), at("09:59")))
    }

    @Test fun busyUntilTheMeetingEnds() {
        assertEquals(at("11:00"), Busy.until(listOf(meeting("10:00", "11:00")), at("10:30")))
    }

    @Test fun backToBackMeetingsJoinUp() {
        val day = listOf(meeting("10:00", "11:00"), meeting("11:05", "12:00"), meeting("12:30", "13:00"))
        assertEquals(at("12:00"), Busy.until(day, at("10:15")))
    }

    @Test fun overlappingMeetingsTakeTheLaterEnd() {
        assertEquals(at("11:30"), Busy.until(listOf(meeting("10:00", "11:00"), meeting("10:30", "11:30")), at("10:45")))
    }

    @Test fun freeDeclinedAndAllDayEventsDontCount() {
        val now = at("10:30")
        assertNull(Busy.until(listOf(meeting("10:00", "11:00", busy = false)), now))
        assertNull(Busy.until(listOf(meeting("10:00", "11:00", declined = true)), now))
        assertNull(Busy.until(listOf(meeting("00:00", "23:59", allDay = true)), now))
    }

    @Test fun nextMeeting() {
        val day = listOf(meeting("09:00", "10:00"), meeting("13:00", "14:00", declined = true), meeting("15:00", "16:00"))
        assertEquals(at("15:00"), Busy.next(day, at("12:00"))!!.begin)
    }
}
