package io.github.sebbejohansson.yggdrasil.norn

import io.github.sebbejohansson.yggdrasil.hermodr.FakeNotifier
import io.github.sebbejohansson.yggdrasil.hermodr.FakeScheduler
import io.github.sebbejohansson.yggdrasil.hermodr.HermodrService
import io.github.sebbejohansson.yggdrasil.hermodr.InMemoryMessageStore
import io.github.sebbejohansson.yggdrasil.hermodr.QuietHours
import io.github.sebbejohansson.yggdrasil.hermodr.TestClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class PromisesTest {
    private val thursday = LocalDate.of(2026, 11, 19)
    private val friday = LocalDate.of(2026, 11, 20)
    private val saturday = LocalDate.of(2026, 11, 21)

    // The desktop's cases (promises_test.go), one for one.
    @Test fun resolveDueTurnsWordsIntoADay() {
        val cases = listOf(
            Triple("Friday", thursday, "2026-11-20"), Triple("by Friday", thursday, "2026-11-20"), Triple("på fredag", thursday, "2026-11-20"),
            Triple("Friday", friday, "2026-11-27"), Triple("next Friday", thursday, "2026-11-27"), Triple("nästa måndag", thursday, "2026-11-23"),
            Triple("Monday", thursday, "2026-11-23"), Triple("tomorrow", thursday, "2026-11-20"), Triple("i morgon", thursday, "2026-11-20"),
            Triple("imorgon bitti", thursday, "2026-11-20"), Triple("day after tomorrow", thursday, "2026-11-21"),
            Triple("today", thursday, "2026-11-19"), Triple("end of day", thursday, "2026-11-19"), Triple("by end of week", thursday, "2026-11-20"),
            Triple("i veckan", saturday, "2026-11-27"), Triple("end of next week", thursday, "2026-11-27"), Triple("24/11", thursday, "2026-11-24"),
            Triple("Nov 24", thursday, "2026-11-24"), Triple("the 24th of November", thursday, "2026-11-24"),
            Triple("den 3 december", thursday, "2026-12-03"), Triple("2 jan", thursday, "2027-01-02"), Triple("2026-12-01", thursday, "2026-12-01"),
            Triple("soon", thursday, null), Triple("next week", thursday, null), Triple("", thursday, null), Triple("31/2", thursday, null),
        )
        for ((words, sent, want) in cases) {
            assertEquals("\"$words\" on ${sent.dayOfWeek}", want, PromiseText.resolveDue(words, sent)?.toString())
        }
    }

    @Test fun mayPromiseAndParse() {
        assertTrue(PromiseText.mayPromise("Sure, I'll send it Friday"))
        assertTrue(PromiseText.mayPromise("Jag kollar med juristen"))
        assertFalse(PromiseText.mayPromise("What's the weather like?"))
        val raw = """Here: ```json {"promises":[{"what":"Send the deck.","to":"Kim","when":"Friday"},{"what":"send the deck","to":"","when":""},{"what":" ","to":"","when":""}]} ```"""
        assertEquals(listOf(Extracted("Send the deck", "Kim", "Friday")), PromiseText.parse(raw))
        assertEquals(emptyList<Extracted>(), PromiseText.parse("no json"))
    }

    private val zone = ZoneId.of("Europe/Stockholm")
    private fun at(date: String, hm: String) = LocalDate.parse(date).atTime(LocalTime.parse(hm)).atZone(zone).toInstant()
    private fun promise(due: String?, made: Instant) =
        Promise(1, "Send the deck", "Kim", due?.let(LocalDate::parse), PromiseState.OPEN, "chat", null, made, made)

    @Test fun remindedOnTheWorkingDayBeforeFromEight() {
        // Due Monday, made Wednesday: Friday is the working day before.
        val p = promise("2026-11-23", at("2026-11-18", "10:00"))
        assertNull(PromiseText.reminderDue(p, at("2026-11-19", "12:00"), zone))
        assertNull(PromiseText.reminderDue(p, at("2026-11-20", "07:30"), zone))
        assertEquals("Monday", PromiseText.reminderDue(p, at("2026-11-20", "08:00"), zone))
        // Due tomorrow: "tomorrow".
        assertEquals("tomorrow", PromiseText.reminderDue(promise("2026-11-20", at("2026-11-17", "10:00")), at("2026-11-19", "09:00"), zone))
        // Made on the day before: reminded on the day, two hours after it was made at the earliest.
        val late = promise("2026-11-20", at("2026-11-19", "16:00"))
        assertNull(PromiseText.reminderDue(late, at("2026-11-19", "17:00"), zone))
        assertEquals("today", PromiseText.reminderDue(late, at("2026-11-20", "08:00"), zone))
        assertNull(PromiseText.reminderDue(late, at("2026-11-21", "09:00"), zone))
        assertNull(PromiseText.reminderDue(promise(null, at("2026-11-17", "10:00")), at("2026-11-19", "09:00"), zone))
    }

    private val clock = TestClock(at("2026-11-19", "09:00"))
    private val messages = InMemoryMessageStore()
    private val notifier = FakeNotifier()
    private val hermodr = HermodrService(messages, notifier, FakeScheduler(), { QuietHours(false) }, { null }, clock, Dispatchers.Unconfined)
    private var answer = """{"promises":[{"what":"Send the Q3 deck","to":"Kim","when":"tomorrow"}]}"""
    private var asked = 0
    private val service = PromiseService(InMemoryPromiseStore(), { _, _ -> asked++; answer }, hermodr, { zone }, { clock.now }, Dispatchers.Unconfined)

    @Test fun readsKeepsOnceAndReminds() = runTest {
        assertEquals(emptyList<Promise>(), service.read("What's on today?", chat = true))
        assertEquals(0, asked)
        val kept = service.read("I told Kim I'll send the Q3 deck tomorrow", chat = true)
        assertEquals(LocalDate.of(2026, 11, 20), kept.single().due)
        // The same words again: kept once.
        assertEquals(emptyList<Promise>(), service.read("I told Kim I'll send the Q3 deck tomorrow", chat = true))
        clock.now = at("2026-11-19", "10:00")
        // Made today for tomorrow: the day itself reminds, from 08:00.
        service.remind()
        assertTrue(messages.recent(10).isEmpty())
        clock.now = at("2026-11-20", "08:15")
        service.remind()
        service.remind()
        assertEquals(listOf("Promised for today: Send the Q3 deck"), messages.recent(10).map { it.title })
        service.done(kept.single().id)
        assertEquals(PromiseState.DONE, service.listed().single().state)
    }

    @Test fun addedByHandAndANewDayRemindsAgain() = runTest {
        val p = service.add("Book the offsite", "", "24/11")!!
        assertEquals(LocalDate.of(2026, 11, 24), p.due)
        // Made Tuesday, moved to Thursday: Wednesday is the working day before.
        clock.now = at("2026-11-17", "09:00")
        val q = service.add("Order the cake", "Ada", "Friday")!!
        service.setDue(q.id, LocalDate.of(2026, 11, 19))
        clock.now = at("2026-11-18", "09:00")
        service.remind()
        assertEquals("Promised for tomorrow: Order the cake", messages.recent(10).single().title)
        assertEquals(LocalDate.of(2026, 11, 24), service.listed().first { it.id == p.id }.due)
    }
}
