package io.github.sebbejohansson.yggdrasil.hermodr

import io.github.sebbejohansson.yggdrasil.norn.CalendarEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

class FocusPlanTest {
    private fun at(hm: String) = Instant.parse("2026-11-20T${hm}:00Z")
    private fun ev(title: String, from: String, to: String, busy: Boolean = true, declined: Boolean = false) =
        CalendarEvent(title, at(from), at(to), false, busy, declined)

    @Test fun focusBlocksAndBreaksAreNotMeetings() {
        assertTrue(FocusPlan.isFocusBlock(ev("Focus", "10:00", "11:00")))
        assertTrue(FocusPlan.isFocusBlock(ev("Focus time", "10:00", "11:00", busy = false)))
        assertTrue(FocusPlan.isBreak(ev("Break", "10:00", "10:15")))
        assertTrue(!FocusPlan.isMeeting(ev("Focus time", "10:00", "11:00")))
        assertTrue(FocusPlan.isMeeting(ev("Refocusing the roadmap", "10:00", "11:00")))
        assertTrue(!FocusPlan.isMeeting(ev("Standup", "10:00", "11:00", declined = true)))
    }

    @Test fun fitEndsBeforeTheNextMeetingUnlessThatIsTooSoon() {
        val standup = ev("Standup", "14:00", "14:15")
        val events = listOf(standup, ev("Focus", "13:00", "14:00"))
        assertEquals(FocusPlan.Fit(at("13:55"), standup), FocusPlan.fit(at("14:30"), events, at("13:00")))
        assertEquals(FocusPlan.Fit(at("13:30")), FocusPlan.fit(at("13:30"), events, at("13:00")).copy(meeting = null))
        // Five minutes before the meeting would leave under ten: as asked, the meeting named.
        assertEquals(FocusPlan.Fit(at("14:30"), standup), FocusPlan.fit(at("14:30"), events, at("13:50")))
    }

    @Test fun offersAFreeStretchWorthIt() {
        val z = ZoneOffset.UTC
        val review = ev("Sprint review", "14:00", "15:00")
        assertEquals(FocusPlan.Offer(at("13:55"), 115, review), FocusPlan.offer(listOf(review), at("12:00"), z))
        assertNull(FocusPlan.offer(listOf(review), at("13:30"), z))
        assertNull(FocusPlan.offer(listOf(review), at("14:10"), z))
        // Nothing left today: up to two hours, not past 18:00.
        assertEquals(at("17:00"), FocusPlan.offer(emptyList(), at("15:00"), z)!!.until)
        assertEquals(at("18:00"), FocusPlan.offer(emptyList(), at("16:50"), z)!!.until)
        assertNull(FocusPlan.offer(emptyList(), at("17:40"), z))
    }

    @Test fun focusBlockUntilJoinsBackToBackBlocks() {
        val events = listOf(ev("Focus", "09:00", "10:00"), ev("Focus time", "10:00", "11:30"), ev("Standup", "11:30", "12:00"))
        assertEquals(at("11:30"), FocusPlan.focusBlockUntil(events, at("09:30")))
        assertNull(FocusPlan.focusBlockUntil(events, at("11:40")))
    }

    @Test fun words() {
        assertEquals("25 min", FocusPlan.words(Duration.ofMinutes(25)))
        assertEquals("1 h", FocusPlan.words(Duration.ofMinutes(60)))
        assertEquals("1 h 40 min", FocusPlan.words(Duration.ofMinutes(100)))
        assertEquals("1 min", FocusPlan.words(Duration.ofSeconds(5)))
    }
}

class HermodrFocusTest {
    private val clock = TestClock(Instant.parse("2026-11-20T12:00:00Z"))
    private val store = InMemoryMessageStore()
    private val notifier = FakeNotifier()
    private val scheduler = FakeScheduler()
    private val focus = InMemoryFocusStore()
    private val hermodr = HermodrService(
        store, notifier, scheduler, { QuietHours(true, LocalTime.of(22, 0), LocalTime.of(7, 0)) }, { null }, clock, Dispatchers.Unconfined, focus,
    )

    private fun req(title: String, priority: Priority = Priority.NORMAL) = MessageRequest(sender = "test", title = title, priority = priority)
    private fun after(m: Int) = clock.now.plus(Duration.ofMinutes(m.toLong()))

    @Test fun holdsAllButUrgentUntilTheFocusEnds() = runTest {
        hermodr.startFocus(after(50))
        assertEquals(listOf(after(50)), scheduler.wakeUps)
        assertEquals(MessageState.HELD, hermodr.send(req("a")).state)
        assertEquals(MessageState.SENT, hermodr.send(req("fire", Priority.URGENT)).state)
        assertEquals(listOf("fire"), notifier.shown.map { it.title })
    }

    @Test fun twoOrMoreThatWaitedArriveAsOneRecap() = runTest {
        hermodr.startFocus(after(50))
        hermodr.send(req("a"))
        hermodr.send(req("b"))
        clock.advance(Duration.ofMinutes(20))
        hermodr.endFocus()
        assertEquals(listOf("Focus done"), notifier.shown.map { it.title })
        assertTrue(notifier.shown.single().text!!.contains("20 min of focus. 2 waited:\n· a\n· b"))
        assertTrue(store.recent(10).filter { it.title in setOf("a", "b") }.all { it.state == MessageState.SENT })
        assertNull(hermodr.focusNow())
        assertEquals(clock.now, focus.sessions(Instant.EPOCH).single().endedAt)
    }

    @Test fun oneThatWaitedIsJustDeliveredAndTheRecapIsOnlyListed() = runTest {
        hermodr.startFocus(after(25))
        hermodr.send(req("a"))
        clock.advance(Duration.ofMinutes(25))
        hermodr.flushHeld()
        assertEquals(listOf("a"), notifier.shown.map { it.title })
        val recap = store.recent(10).first { it.title == "Focus done" }
        assertEquals(MessageState.LISTED, recap.state)
        // Ran out on its own: it ended at its time.
        assertEquals(after(0), focus.sessions(Instant.EPOCH).single().endedAt)
    }

    @Test fun startingAgainKeepsWhenItStartedAndIsCappedAtTwelveHours() = runTest {
        val first = hermodr.startFocus(after(25))
        clock.advance(Duration.ofMinutes(10))
        val more = hermodr.startFocus(after(15 + 25))
        assertEquals(first.startedAt, more.startedAt)
        assertEquals(1, focus.sessions(Instant.EPOCH).size)
        assertEquals(after(40), focus.sessions(Instant.EPOCH).single().until)
        val capped = hermodr.startFocus(after(24 * 60))
        assertEquals(after(12 * 60), capped.until)
    }

    @Test fun aFocusEndingInQuietHoursRecapsInTheMorning() = runTest {
        clock.now = Instant.parse("2026-11-20T21:30:00Z")
        hermodr.startFocus(after(60))
        hermodr.send(req("a"))
        hermodr.send(req("b"))
        clock.advance(Duration.ofMinutes(60))
        hermodr.flushHeld()
        // 22:30: quiet hours. Nothing goes, not even the recap.
        assertTrue(notifier.shown.isEmpty())
        clock.now = Instant.parse("2026-11-21T07:00:00Z")
        hermodr.flushHeld()
        // One notification in the morning: the recap, with both in it.
        assertEquals(listOf("Focus done"), notifier.shown.map { it.title })
        assertTrue(notifier.shown.single().text!!.contains("· a\n· b"))
    }
}
