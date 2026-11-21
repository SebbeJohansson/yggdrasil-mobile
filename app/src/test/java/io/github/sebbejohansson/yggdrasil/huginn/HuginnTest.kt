package io.github.sebbejohansson.yggdrasil.huginn

import io.github.sebbejohansson.yggdrasil.gjallar.AlertChange
import io.github.sebbejohansson.yggdrasil.gjallar.GjallarService
import io.github.sebbejohansson.yggdrasil.gjallar.InMemoryAlertStore
import io.github.sebbejohansson.yggdrasil.gjallar.Severity
import io.github.sebbejohansson.yggdrasil.hermodr.FakeNotifier
import io.github.sebbejohansson.yggdrasil.hermodr.FakeScheduler
import io.github.sebbejohansson.yggdrasil.hermodr.HermodrService
import io.github.sebbejohansson.yggdrasil.hermodr.InMemoryMessageStore
import io.github.sebbejohansson.yggdrasil.hermodr.Priority
import io.github.sebbejohansson.yggdrasil.hermodr.QuietHours
import io.github.sebbejohansson.yggdrasil.hermodr.TestClock
import io.github.sebbejohansson.yggdrasil.norn.CalendarEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class HuginnTest {
    private val now = Instant.parse("2026-11-20T09:00:00Z")
    private val clock = TestClock(now)
    private val messages = InMemoryMessageStore()
    private val hermodr = HermodrService(messages, FakeNotifier(), FakeScheduler(), { QuietHours(enabled = false) }, clock = clock, io = Dispatchers.Unconfined)
    private val asked = mutableListOf<String>()
    private val huginn = HuginnService(InMemoryHuginnStore(), { q -> asked += q; "Bring the Q4 numbers." }, hermodr, clock, Dispatchers.Unconfined)

    private fun note(from: String, text: String, group: Boolean = false, at: Instant = now, key: String = "k1") =
        PhoneNotification(key, "com.slack", "Slack", from, text, null, group, at)

    @Test fun anUrgentDirectMessageIsToldUrgently() = runTest {
        huginn.handle(Sources.notification(note("Anna Berg", "Can you look at this ASAP?"), emptySet()))
        val m = messages.recent(1).single()
        assertEquals("Anna Berg · Slack", m.title)
        assertEquals("Anna Berg on Slack: Can you look at this ASAP?", m.text)
        assertEquals(Priority.URGENT, m.priority)
    }

    @Test fun urgentInAGroupChatIsNotTheBuiltIn() = runTest {
        huginn.handle(Sources.notification(note("Anna", "urgent: lunch?", group = true), emptySet()))
        assertTrue(messages.recent(10).isEmpty())
    }

    @Test fun someoneWhoMattersIsToldHigh() = runTest {
        huginn.handle(Sources.notification(note("Kim Svensson", "Got a minute later?"), setOf("kim svensson")))
        assertEquals(Priority.HIGH, messages.recent(1).single().priority)
    }

    @Test fun anOrdinaryMessageIsLeftAlone() = runTest {
        huginn.handle(Sources.notification(note("Bob", "See you tomorrow"), setOf("Kim")))
        assertTrue(messages.recent(10).isEmpty())
    }

    @Test fun theSameMessageIsToldOnceEvenByTwoRules() = runTest {
        huginn.create("Anything from Kim", Trigger(Events.NOTIFICATION, from = "kim"), ActionKind.NOTIFY, "{from}: {summary}", push = true, hold = false)
        val f = Sources.notification(note("Kim", "Urgent, call me"), setOf("Kim"))
        huginn.handle(f)
        huginn.handle(f)
        assertEquals(1, messages.recent(10).size)
        val skipped = huginn.reactions().filter { it.state == ReactionState.SKIPPED }
        assertTrue(skipped.isNotEmpty())
        assertTrue(skipped.all { it.reason!!.startsWith("already told by") })
    }

    @Test fun anEventMoreThanAnHourOldIsSkipped() = runTest {
        huginn.handle(Sources.notification(note("Kim", "urgent", at = now.minus(Duration.ofMinutes(61))), setOf("Kim")))
        assertTrue(messages.recent(10).isEmpty())
        assertEquals("the event is more than an hour old", huginn.reactions().first().reason)
    }

    @Test fun aMeetingSoonAsksNornForABriefAndWaitsOutAMeeting() = runTest {
        val meeting = CalendarEvent("Q4 review", now.plus(Duration.ofMinutes(20)), now.plus(Duration.ofMinutes(80)), false, true, false, id = 7, location = "Room 3")
        val due = Sources.calendarDue(listOf(meeting), now)
        assertEquals(listOf(Events.CALENDAR_SOON), due.map { it.first })
        huginn.handle(Sources.calendar(due.single().first, meeting, ZoneOffset.UTC))
        assertEquals("Brief me for my meeting \"Q4 review\" at 09:20 in Room 3: what it's likely about and what I should bring.", asked.single())
        val m = messages.recent(1).single()
        assertEquals("Bring the Q4 numbers.", m.text)
        assertTrue(m.hold)
    }

    @Test fun calendarDueFindsSoonAndJustEnded() {
        val later = CalendarEvent("Later", now.plus(Duration.ofMinutes(45)), now.plus(Duration.ofMinutes(60)), false, true, false)
        val ended = CalendarEvent("Standup", now.minus(Duration.ofMinutes(25)), now.minus(Duration.ofMinutes(10)), false, true, false)
        val declined = CalendarEvent("Nope", now.plus(Duration.ofMinutes(10)), now.plus(Duration.ofMinutes(20)), false, true, true)
        assertEquals(listOf(Events.CALENDAR_ENDED to ended), Sources.calendarDue(listOf(later, ended, declined), now))
    }

    @Test fun builtInsCanBeSwitchedOffButNotRemoved() = runTest {
        huginn.setEnabled("urgent-message", false)
        huginn.delete("urgent-message")
        val rule = huginn.rules().first { it.id == "urgent-message" }
        assertFalse(rule.enabled)
        huginn.handle(Sources.notification(note("Anna", "ASAP please"), emptySet()))
        assertTrue(messages.recent(10).isEmpty())
    }

    @Test fun fillLeavesUnknownFieldsEmpty() {
        assertEquals("Kim says hi ()", Fill("{from} says {text} ({nope})", mapOf("from" to "Kim", "text" to "hi")))
    }

    @Test fun triggerFiltersAndWords() {
        val f = Sources.notification(note("Kim Svensson", "The build is red"), emptySet())
        assertTrue(Trigger(Events.NOTIFICATION, app = "slack", text = "build").matches(f))
        assertFalse(Trigger(Events.NOTIFICATION, app = "teams").matches(f))
        assertFalse(Trigger(Events.CALENDAR_SOON).matches(f))
        assertEquals("A notification in Slack saying \"build\"", Trigger(Events.NOTIFICATION, app = "Slack", text = "build").said())
    }

    @Test fun urgentWordsInSwedishToo() {
        assertTrue(Sources.isUrgent("Det är akut, ring mig"))
        assertTrue(Sources.isUrgent("Brådskande: avtal"))
        assertFalse(Sources.isUrgent("Turgent"))
    }

    @Test fun gjallarTellsHuginnWhatAlertsDo() = runTest {
        val gjallar = GjallarService(InMemoryAlertStore(), hermodr, clock, Dispatchers.Unconfined)
        val seen = mutableListOf<AlertChange>()
        val job = launch(Dispatchers.Unconfined) { gjallar.events.collect { seen += it.first } }
        yield()
        gjallar.raise("vedrfolnir", "heat", Severity.WARNING, "Hot")
        gjallar.raise("vedrfolnir", "heat", Severity.WARNING, "Hot")
        gjallar.raise("vedrfolnir", "heat", Severity.CRITICAL, "Very hot")
        gjallar.resolve("vedrfolnir", "heat")
        gjallar.setMuted("vedrfolnir", "quiet", true)
        gjallar.raise("vedrfolnir", "quiet", Severity.CRITICAL, "Muted")
        job.cancel()
        assertEquals(listOf(AlertChange.OPENED, AlertChange.ESCALATED, AlertChange.RESOLVED), seen)
    }
}
