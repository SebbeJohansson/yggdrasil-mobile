package io.github.sebbejohansson.yggdrasil.dellingr

import io.github.sebbejohansson.yggdrasil.gjallar.AlertState
import io.github.sebbejohansson.yggdrasil.gjallar.GjallarService
import io.github.sebbejohansson.yggdrasil.gjallar.InMemoryAlertStore
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
import java.time.Duration
import java.time.Instant

class DellingrServiceTest {
    private val clock = TestClock(Instant.parse("2026-11-20T07:00:00Z")) // a Friday
    private val messages = InMemoryMessageStore()
    private val hermodr = HermodrService(messages, FakeNotifier(), FakeScheduler(), { QuietHours(enabled = false) }, clock = clock, io = Dispatchers.Unconfined)
    private val alerts = InMemoryAlertStore()
    private val gjallar = GjallarService(alerts, hermodr, clock, Dispatchers.Unconfined)
    private val store = InMemoryJobStore()
    private var answer: () -> String = { "Three meetings today." }
    private val asked = mutableListOf<String>()
    private val wakes = mutableListOf<Instant?>()
    private val dellingr = DellingrService(
        store, { q -> asked += q; answer() }, hermodr, gjallar, { wakes += it }, clock, Dispatchers.Unconfined,
    )

    @Test fun aJobRunsWhenDueAndTellsYouWithHold() = runTest {
        val job = dellingr.create("Morning brief", "every weekday at 8", ActionKind.ASK, "What's on today?", push = true)
        assertEquals(Instant.parse("2026-11-20T08:00:00Z"), job.nextRunAt)

        dellingr.tick() // 07:00: not yet
        assertTrue(asked.isEmpty())
        assertEquals(Instant.parse("2026-11-20T08:00:00Z"), wakes.last())

        clock.now = Instant.parse("2026-11-20T08:00:30Z")
        dellingr.tick()
        assertEquals(listOf("What's on today?"), asked)
        val sent = messages.recent(1).single()
        assertEquals("Morning brief", sent.title)
        assertEquals("Three meetings today.", sent.text)
        assertTrue(sent.hold)
        // Next is Monday: it's Monday to Friday.
        assertEquals(Instant.parse("2026-11-23T08:00:00Z"), store.job(job.id)!!.nextRunAt)
        assertEquals(RunState.DONE, dellingr.runs().single().state)
        assertFalse(dellingr.runs().single().missed)
    }

    @Test fun aSleptThroughRunHappensOnceAndIsMarkedMissed() = runTest {
        dellingr.create("Hourly", "every hour", ActionKind.NOTIFY, "Stretch", push = false)
        clock.now = Instant.parse("2026-11-20T12:30:00Z") // five ticks slept through
        dellingr.tick()
        assertEquals(1, dellingr.runs().size)
        assertTrue(dellingr.runs().single().missed)
        dellingr.tick()
        assertEquals(1, dellingr.runs().size)
    }

    @Test fun threeFailuresInARowRaiseAnAlertThatHealsOnSuccess() = runTest {
        answer = { throw IllegalStateException("No model is set up.") }
        val job = dellingr.create("Brief", "every hour", ActionKind.ASK, "Anything?", push = true)
        repeat(3) { dellingr.runNow(job.id) }
        val alert = alerts.find("dellingr", "failing:${job.id}")!!
        assertEquals(AlertState.OPEN, alert.state)
        assertEquals("No model is set up.", alert.detail)

        answer = { "Fine now." }
        dellingr.runNow(job.id)
        assertEquals(AlertState.RESOLVED, alerts.find("dellingr", "failing:${job.id}")!!.state)
        assertEquals(0, store.job(job.id)!!.failures)
    }

    @Test fun runningByHandKeepsTheSchedule() = runTest {
        val job = dellingr.create("Note", "every day at 18", ActionKind.NOTIFY, "Wind down", push = true)
        dellingr.runNow(job.id)
        assertEquals(job.nextRunAt, store.job(job.id)!!.nextRunAt)
        assertTrue(dellingr.runs().single().byHand)
    }

    @Test fun aDisabledJobDoesntRunAndEnablingStartsFromNow() = runTest {
        val job = dellingr.create("Note", "every hour", ActionKind.NOTIFY, "Hi", push = true)
        dellingr.setEnabled(job.id, false)
        clock.advance(Duration.ofHours(3))
        dellingr.tick()
        assertTrue(dellingr.runs().isEmpty())
        assertNull(wakes.last())
        dellingr.setEnabled(job.id, true)
        assertEquals(Instant.parse("2026-11-20T11:00:00Z"), store.job(job.id)!!.nextRunAt)
    }

    @Test fun deletingResolvesItsAlert() = runTest {
        answer = { throw IllegalStateException("down") }
        val job = dellingr.create("Brief", "every hour", ActionKind.ASK, "?", push = true)
        repeat(3) { dellingr.runNow(job.id) }
        dellingr.delete(job.id)
        assertEquals(AlertState.RESOLVED, alerts.find("dellingr", "failing:${job.id}")!!.state)
        assertNull(store.job(job.id))
    }
}
