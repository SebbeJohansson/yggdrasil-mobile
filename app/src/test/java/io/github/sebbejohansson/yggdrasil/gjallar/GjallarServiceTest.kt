package io.github.sebbejohansson.yggdrasil.gjallar

import io.github.sebbejohansson.yggdrasil.hermodr.FakeNotifier
import io.github.sebbejohansson.yggdrasil.hermodr.FakeScheduler
import io.github.sebbejohansson.yggdrasil.hermodr.HermodrService
import io.github.sebbejohansson.yggdrasil.hermodr.InMemoryMessageStore
import io.github.sebbejohansson.yggdrasil.hermodr.Priority
import io.github.sebbejohansson.yggdrasil.hermodr.QuietHours
import io.github.sebbejohansson.yggdrasil.hermodr.TestClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class GjallarServiceTest {
    private val clock = TestClock(Instant.parse("2026-11-20T12:00:00Z"))
    private val notifier = FakeNotifier()
    private val hermodr = HermodrService(
        InMemoryMessageStore(), notifier, FakeScheduler(), { QuietHours(enabled = false) },
        clock = clock, io = Dispatchers.Unconfined,
    )
    private val store = InMemoryAlertStore()
    private val gjallar = GjallarService(store, hermodr, clock, Dispatchers.Unconfined)

    private suspend fun raise(severity: Severity, key: String = "disk") =
        gjallar.raise("vedrfolnir", key, severity, "Disk almost full")

    private fun alert(key: String = "disk") = store.find("vedrfolnir", key)!!

    @Test fun sameSourceAndKeyUpdatesTheOpenAlert() = runTest {
        assertEquals(RaiseResult.OPENED, raise(Severity.WARNING))
        assertEquals(RaiseResult.UPDATED, raise(Severity.WARNING))
        assertEquals(1, store.list(AlertState.entries.toSet(), 10).size)
    }

    @Test fun infoIsOnlyListed() = runTest {
        raise(Severity.INFO)
        assertTrue(notifier.shown.isEmpty())
    }

    @Test fun warningPushesQuietlyOnceWhenItOpens() = runTest {
        raise(Severity.WARNING)
        raise(Severity.WARNING)
        assertEquals(listOf(Priority.LOW), notifier.shown.map { it.priority })
        assertEquals("alert:${alert().id}", notifier.shown.single().tag)
    }

    @Test fun criticalRepeatsAtMostEvery15Minutes() = runTest {
        raise(Severity.CRITICAL)
        clock.advance(Duration.ofMinutes(10))
        raise(Severity.CRITICAL)
        assertEquals(1, notifier.shown.size)
        clock.advance(Duration.ofMinutes(5))
        raise(Severity.CRITICAL)
        assertEquals(listOf(Priority.URGENT, Priority.URGENT), notifier.shown.map { it.priority })
    }

    @Test fun warningThatTurnsCriticalPushesUrgentAtOnce() = runTest {
        raise(Severity.WARNING)
        raise(Severity.CRITICAL)
        assertEquals(listOf(Priority.LOW, Priority.URGENT), notifier.shown.map { it.priority })
    }

    @Test fun resolvedAlertOpensAgain() = runTest {
        raise(Severity.WARNING)
        assertTrue(gjallar.resolve("vedrfolnir", "disk"))
        assertFalse(gjallar.resolve("vedrfolnir", "disk"))
        assertEquals(RaiseResult.REOPENED, raise(Severity.WARNING))
        assertEquals(AlertState.OPEN, alert().state)
        assertEquals(2, notifier.shown.size)
    }

    @Test fun resolveTakesTheNotificationBack() = runTest {
        raise(Severity.CRITICAL)
        val tag = alert().notificationTag
        assertTrue(tag in notifier.onScreen)
        gjallar.resolve("vedrfolnir", "disk")
        assertFalse(tag in notifier.onScreen)
    }

    @Test fun acknowledgedStaysQuietUntilSeverityGoesUp() = runTest {
        raise(Severity.WARNING)
        gjallar.acknowledge(alert().id)
        assertFalse(alert().notificationTag in notifier.onScreen)

        assertEquals(RaiseResult.UPDATED, raise(Severity.WARNING))
        assertEquals(AlertState.ACKNOWLEDGED, alert().state)
        assertEquals(1, notifier.shown.size)

        assertEquals(RaiseResult.REOPENED, raise(Severity.CRITICAL))
        assertEquals(AlertState.OPEN, alert().state)
        assertEquals(Priority.URGENT, notifier.shown.last().priority)
    }

    @Test fun mutedIsListedNeverPushed() = runTest {
        gjallar.setMuted("vedrfolnir", "disk", true)
        raise(Severity.CRITICAL)
        assertTrue(notifier.shown.isEmpty())
        assertEquals(1, gjallar.active().size)
    }

    @Test fun snoozeHoldsPushesUntilItEnds() = runTest {
        raise(Severity.CRITICAL)
        gjallar.snooze(alert().id, clock.now.plus(Duration.ofHours(1)))
        clock.advance(Duration.ofMinutes(30))
        raise(Severity.CRITICAL)
        assertEquals(1, notifier.shown.size)
        clock.advance(Duration.ofMinutes(31))
        raise(Severity.CRITICAL)
        assertEquals(2, notifier.shown.size)
    }

    @Test fun snoozeIsCappedAt30Days() = runTest {
        raise(Severity.WARNING)
        gjallar.snooze(alert().id, clock.now.plus(Duration.ofDays(90)))
        assertEquals(clock.now.plus(Duration.ofDays(30)), alert().snoozedUntil)
    }
}
