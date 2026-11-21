package io.github.sebbejohansson.yggdrasil.ratatoskr

import io.github.sebbejohansson.yggdrasil.hermodr.FakeNotifier
import io.github.sebbejohansson.yggdrasil.hermodr.FakeScheduler
import io.github.sebbejohansson.yggdrasil.hermodr.HermodrService
import io.github.sebbejohansson.yggdrasil.hermodr.InMemoryMessageStore
import io.github.sebbejohansson.yggdrasil.hermodr.QuietHours
import io.github.sebbejohansson.yggdrasil.hermodr.TestClock
import io.github.sebbejohansson.yggdrasil.norn.CalendarEvent
import io.github.sebbejohansson.yggdrasil.norn.CalendarReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

class YouPatternsTest {
    private val z = ZoneOffset.UTC
    private val r = You.Rhythm()

    // Friday 2026-11-20.
    private fun at(hm: String, day: Int = 20) = Instant.parse("2026-11-${day}T$hm:00Z")
    private fun s(from: String, to: String, day: Int = 20) = You.Span(at(from, day), at(to, day))

    @Test fun joinMergesOverlapsAndShortGaps() {
        val spans = listOf(s("10:00", "11:00"), s("09:00", "10:30"), s("11:10", "12:00"), s("13:00", "14:00"))
        assertEquals(listOf(s("09:00", "11:00"), s("11:10", "12:00"), s("13:00", "14:00")), You.join(spans))
        assertEquals(listOf(s("09:00", "12:00"), s("13:00", "14:00")), You.join(spans, Duration.ofMinutes(15)))
    }

    @Test fun aStretchIsFourHoursWithoutAFifteenMinuteGap() {
        val meetings = listOf(s("09:00", "10:00"), s("10:00", "11:00"), s("11:05", "12:00"), s("12:10", "13:00"), s("14:00", "15:00"))
        val seen = You.stretch(meetings, emptyList(), emptyList(), at("10:30"), r)
        assertTrue(seen.active)
        assertEquals(at("09:00"), seen.since)
        assertEquals(at("13:00"), seen.nextGap)
        assertEquals(at("11:00"), seen.pauseAt)
        assertFalse(You.stretch(meetings, emptyList(), emptyList(), at("13:30"), r).active)
        // Three and a half hours isn't a stretch.
        assertFalse(You.stretch(meetings.drop(1), emptyList(), emptyList(), at("10:30"), r).active)
    }

    @Test fun focusCarriesARunOnAndABreakEndsIt() {
        val meetings = listOf(s("09:00", "11:00"), s("12:00", "13:00"))
        val focus = listOf(s("11:00", "12:00"))
        val seen = You.stretch(meetings, focus, emptyList(), at("09:30"), r)
        assertTrue(seen.active && seen.focus)
        // Focus alone is no stretch.
        assertFalse(You.stretch(emptyList(), listOf(s("09:00", "14:00")), emptyList(), at("10:00"), r).active)
        // A ten-minute break kept free ends the run.
        val withGap = listOf(s("09:00", "11:00"), s("11:10", "13:30"))
        assertTrue(You.stretch(withGap, emptyList(), emptyList(), at("09:30"), r).active)
        assertFalse(You.stretch(withGap, emptyList(), listOf(s("11:00", "11:10")), at("09:30"), r).active)
    }

    @Test fun lateIsBetweenElevenAndFiveWithThePhoneInUse() {
        assertTrue(You.late(at("23:30"), z, r, inUse = true))
        assertTrue(You.late(at("02:00", 21), z, r, inUse = true))
        assertFalse(You.late(at("23:30"), z, r, inUse = false))
        assertFalse(You.late(at("05:30", 21), z, r, inUse = true))
        // After midnight belongs to the evening before: one message for the night.
        assertEquals(You.period(You.Pattern.LATE, at("23:30"), z, r), You.period(You.Pattern.LATE, at("02:00", 21), z, r))
    }

    @Test fun aFullWeekNamesTheLongestFreeBlock() {
        // Monday 16th: 09-17 every day, all meetings but Thursday 13-16.
        val days = 16..20
        val meetings = days.flatMap { d -> if (d == 19) listOf(s("09:00", "13:00", d), s("16:00", "17:00", d)) else listOf(s("09:00", "17:00", d)) }
        val seen = You.week(meetings, emptyList(), at("08:00", 16), z, r)
        assertTrue(seen.active)
        assertEquals(s("13:00", "16:00", 19), seen.free)
        // Focus there isn't free.
        assertNull(You.week(meetings, listOf(s("13:00", "16:00", 19)), at("08:00", 16), z, r).free)
        assertFalse(You.week(meetings.take(1), emptyList(), at("08:00", 16), z, r).active)
        assertFalse(You.week(meetings, emptyList(), at("10:00", 21), z, r).active)
    }

    @Test fun messagesHaveTimesNotTitles() {
        val (title, text) = You.message(
            You.Pattern.STRETCH, You.StretchSeen(true, at("09:00"), at("13:00"), at("11:00")), You.WeekSeen(false), z, r,
        )
        assertEquals("A long run of meetings", title)
        assertTrue(text.contains("until 13:00") && text.contains("pause at 11:00"))
        val week = You.message(You.Pattern.WEEK, You.StretchSeen(false), You.WeekSeen(true, s("13:00", "16:00", 19)), z, r).second
        assertTrue(week.contains("Thursday 13:00–16:00"))
    }
}

@RunWith(RobolectricTestRunner::class)
class YouServiceTest {
    private val clock = TestClock(Instant.parse("2026-11-20T10:30:00Z"))
    private val store = InMemoryMessageStore()
    private val notifier = FakeNotifier()
    private val hermodr = HermodrService(
        store, notifier, FakeScheduler(), { QuietHours(true, LocalTime.of(22, 0), LocalTime.of(7, 0)) }, { null }, clock, Dispatchers.Unconfined,
    )
    private var events = listOf<CalendarEvent>()
    private var screenOn = false
    private val reader = object : CalendarReader {
        override fun allowed() = true
        override fun between(from: Instant, to: Instant) = events
    }
    private val settings = YouSettings(RuntimeEnvironment.getApplication())
    private val you = YouService(reader, hermodr, settings, { screenOn }, { ZoneOffset.UTC }, { clock.now })

    private fun ev(title: String, from: String, to: String) =
        CalendarEvent(title, Instant.parse("2026-11-20T$from:00Z"), Instant.parse("2026-11-20T$to:00Z"), false, true, false)

    @Test fun tellsOncePerDayWithoutMeetingTitles() = runTest {
        events = listOf(ev("1:1 with Kim", "09:00", "11:00"), ev("Planning", "11:00", "13:30"))
        you.round()
        you.round()
        val told = store.recent(10).filter { it.sender == "ratatoskr" }
        assertEquals(listOf("A long run of meetings"), told.map { it.title })
        assertFalse(told.single().text!!.contains("Kim") || told.single().text!!.contains("Planning"))
        assertEquals("until 13:30", you.status.value.active[You.Pattern.STRETCH])
    }

    @Test fun lateInQuietHoursIsListedNotPushed() = runTest {
        clock.now = Instant.parse("2026-11-20T23:30:00Z")
        screenOn = true
        you.round()
        val m = store.recent(10).single { it.sender == "ratatoskr" }
        assertEquals("It's late", m.title)
        assertFalse(m.push)
        assertTrue(notifier.shown.isEmpty())
    }

    @Test fun offTellsNothing() = runTest {
        settings.on = false
        events = listOf(ev("a", "09:00", "13:30"))
        you.round()
        assertTrue(store.recent(10).isEmpty())
    }
}
