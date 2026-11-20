package io.github.sebbejohansson.yggdrasil.hermodr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class HermodrServiceTest {
    private val clock = TestClock(Instant.parse("2026-11-20T12:00:00Z"))
    private val store = InMemoryMessageStore()
    private val notifier = FakeNotifier()
    private val scheduler = FakeScheduler()
    private var quiet = QuietHours(true, java.time.LocalTime.of(22, 0), java.time.LocalTime.of(7, 0))
    private var busyUntil: Instant? = null

    private val hermodr = HermodrService(
        store, notifier, scheduler, { quiet }, { busyUntil }, clock, Dispatchers.Unconfined,
    )

    private fun req(priority: Priority = Priority.NORMAL, hold: Boolean = false, push: Boolean = true, tag: String? = null) =
        MessageRequest(sender = "test", title = "hello", priority = priority, hold = hold, push = push, tag = tag)

    @Test fun sendsAtOnceOutsideQuietHours() = runTest {
        val m = hermodr.send(req())
        assertEquals(MessageState.SENT, m.state)
        assertEquals(listOf(m.id), notifier.shown.map { it.id })
    }

    @Test fun listOnlyNeverNotifies() = runTest {
        val m = hermodr.send(req(push = false))
        assertEquals(MessageState.LISTED, m.state)
        assertTrue(notifier.shown.isEmpty())
    }

    @Test fun quietHoursHoldUntilTheyEndThenFlush() = runTest {
        clock.now = Instant.parse("2026-11-20T23:00:00Z")
        val m = hermodr.send(req())
        assertEquals(MessageState.HELD, m.state)
        assertEquals(listOf(Instant.parse("2026-11-21T07:00:00Z")), scheduler.wakeUps)
        assertTrue(notifier.shown.isEmpty())

        clock.now = Instant.parse("2026-11-21T07:00:00Z")
        hermodr.flushHeld()
        assertEquals(MessageState.SENT, store.get(m.id)!!.state)
        assertEquals(1, notifier.shown.size)
    }

    @Test fun urgentGoesThroughQuietHours() = runTest {
        clock.now = Instant.parse("2026-11-20T23:00:00Z")
        assertEquals(MessageState.SENT, hermodr.send(req(Priority.URGENT)).state)
    }

    @Test fun flushTooEarlyKeepsHolding() = runTest {
        clock.now = Instant.parse("2026-11-20T23:00:00Z")
        val m = hermodr.send(req())
        clock.advance(Duration.ofHours(1))
        hermodr.flushHeld()
        assertEquals(MessageState.HELD, store.get(m.id)!!.state)
        assertEquals(2, scheduler.wakeUps.size)
    }

    @Test fun holdWaitsWhileBusyOnlyWhenAsked() = runTest {
        busyUntil = clock.now.plus(Duration.ofMinutes(30))
        assertEquals(MessageState.SENT, hermodr.send(req(hold = false)).state)
        val held = hermodr.send(req(hold = true))
        assertEquals(MessageState.HELD, held.state)
        assertEquals(busyUntil, scheduler.wakeUps.last())
    }

    @Test fun blockedNotificationsFailWithAReason() = runTest {
        notifier.blocked = "notifications are turned off for the app"
        val m = hermodr.send(req())
        assertEquals(MessageState.FAILED, m.state)
        assertEquals("notifications are turned off for the app", m.failure)
    }

    @Test fun withdrawCancelsAndMarksSeen() = runTest {
        val m = hermodr.send(req(tag = "alert:1"))
        hermodr.withdraw("alert:1")
        assertTrue("alert:1" !in notifier.onScreen)
        assertNotNull(store.get(m.id)!!.seenAt)
    }

    @Test fun pruneDropsMessagesOlderThanAWeek() = runTest {
        val old = hermodr.send(req())
        clock.advance(Duration.ofDays(8))
        val fresh = hermodr.send(req())
        hermodr.prune()
        assertEquals(listOf(fresh.id), store.recent(10).map { it.id })
        assertTrue(store.get(old.id) == null)
    }
}
