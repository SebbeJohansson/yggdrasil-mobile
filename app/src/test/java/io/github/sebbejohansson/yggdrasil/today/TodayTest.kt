package io.github.sebbejohansson.yggdrasil.today

import io.github.sebbejohansson.yggdrasil.norn.CalendarEvent
import io.github.sebbejohansson.yggdrasil.norn.Promise
import io.github.sebbejohansson.yggdrasil.norn.PromiseState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class TodayTest {
    private val z = ZoneOffset.UTC

    // Friday 2026-11-20.
    private fun at(hm: String, day: Int = 20) = Instant.parse("2026-11-${day}T$hm:00Z")
    private fun ev(title: String, from: String, to: String, busy: Boolean = true) = CalendarEvent(title, at(from), at(to), false, busy, false)

    @Test fun freeUntilTheNextThingThatHoldsYouOrTheEndOfTheDay() {
        val events = listOf(ev("Standup", "10:00", "10:15"), ev("Lunch?", "12:00", "13:00", busy = false), ev("Focus", "14:00", "15:00"))
        assertEquals(at("10:00"), Today.freeUntil(events, at("09:20"), z))
        assertNull(Today.freeUntil(events, at("10:05"), z))
        // A free-looking lunch doesn't hold you; a focus block does.
        assertEquals(at("14:00"), Today.freeUntil(events, at("11:00"), z))
        assertEquals(at("17:00"), Today.freeUntil(events, at("15:30"), z))
        assertNull(Today.freeUntil(events, at("08:30"), z))
        assertNull(Today.freeUntil(events, at("10:00", 21), z))
    }

    private fun item(kind: Today.Kind, since: String, title: String = kind.name) = Today.Item(kind, title, "", at(since), title)

    @Test fun pickPutsAlertsFirstThenTheOldestThatFits() {
        val items = listOf(item(Today.Kind.PROMISE, "08:00"), item(Today.Kind.JOB, "07:00"), item(Today.Kind.ALERT, "09:00"))
        assertEquals(Today.Kind.ALERT, Today.pick(items, Duration.ofMinutes(30))!!.kind)
        assertEquals(Today.Kind.JOB, Today.pick(items.drop(0).filter { it.kind != Today.Kind.ALERT }, Duration.ofMinutes(30))!!.kind)
        // Twelve minutes: a promise (10 + 5) doesn't fit, a job (5 + 5) does.
        assertEquals(Today.Kind.JOB, Today.pick(listOf(item(Today.Kind.PROMISE, "06:00"), item(Today.Kind.JOB, "08:00")), Duration.ofMinutes(12))!!.kind)
        assertNull(Today.pick(items, Duration.ofMinutes(9)))
    }

    @Test fun promisesDueByTomorrowAreItems() {
        fun p(due: String?, state: PromiseState = PromiseState.OPEN) =
            Promise(1, "Send the deck", "Kim", due?.let(LocalDate::parse), state, "chat", null, at("08:00"), at("08:00"))
        assertEquals("promised to Kim", Today.promiseItem(p("2026-11-21"), z, at("09:00"))!!.text)
        assertEquals(Today.Kind.PROMISE, Today.promiseItem(p("2026-11-18"), z, at("09:00"))!!.kind)
        assertNull(Today.promiseItem(p("2026-11-22"), z, at("09:00")))
        assertNull(Today.promiseItem(p(null), z, at("09:00")))
        assertNull(Today.promiseItem(p("2026-11-20", PromiseState.DONE), z, at("09:00")))
    }

    @Test fun theDaysMeetings() {
        val events = listOf(ev("A", "09:00", "09:30"), ev("B", "10:00", "11:00"), ev("C", "14:00", "15:00"), ev("Focus", "12:00", "13:00"),
            CalendarEvent("Tomorrow", at("09:00", 21), at("10:00", 21), false, true, false))
        val d = Today.day(events, at("10:30"), z)
        assertEquals(listOf("A", "B", "C"), d.meetings.map { it.title })
        assertEquals(1, d.done)
        assertEquals("B", d.now!!.title)
        assertEquals("C", d.next!!.title)
    }
}
