package io.github.sebbejohansson.yggdrasil.saga

import android.app.Application
import io.github.sebbejohansson.yggdrasil.gjallar.AlertState
import io.github.sebbejohansson.yggdrasil.gjallar.GjallarService
import io.github.sebbejohansson.yggdrasil.gjallar.InMemoryAlertStore
import io.github.sebbejohansson.yggdrasil.gjallar.Severity
import io.github.sebbejohansson.yggdrasil.hermodr.FakeNotifier
import io.github.sebbejohansson.yggdrasil.hermodr.FakeScheduler
import io.github.sebbejohansson.yggdrasil.hermodr.HermodrService
import io.github.sebbejohansson.yggdrasil.hermodr.InMemoryMessageStore
import io.github.sebbejohansson.yggdrasil.hermodr.QuietHours
import io.github.sebbejohansson.yggdrasil.hermodr.TestClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Duration
import java.time.Instant

/** Mímir in memory, with its rules for seq and pos. */
class FakeMimir(private val me: String = "phone") : MimirClient {
    val events = mutableListOf<MimirEvent>()
    val lastSeq = mutableMapOf<String, Long>()
    var down = false

    private fun up() { if (down) throw IOException("Mímir is down") }

    override fun lastSeq(): Long { up(); return lastSeq[me] ?: 0 }

    override fun append(events: List<Outgoing>): Appended {
        up()
        var last = lastSeq[me] ?: 0
        for (e in events) {
            if (e.seq <= last) continue
            if (e.seq != last + 1) return Appended.Gap(lastSeq[me] ?: 0)
            last = e.seq
        }
        for (e in events) {
            if (e.seq > (lastSeq[me] ?: 0)) {
                this.events += MimirEvent(this.events.size + 1L, me, e.seq, e.kind, e.body)
                lastSeq[me] = e.seq
            }
        }
        return Appended.Ok(lastSeq[me] ?: 0)
    }

    override fun read(since: Long, from: String): Page {
        up()
        if (since > events.size) return Page.Gone
        return Page.Events(events.drop(since.toInt()).filter { it.device != from }, events.size.toLong(), false)
    }

    override fun snapshotPos() = 0L

    fun fromDesktop(kind: String, body: JSONObject) {
        val seq = (lastSeq["desktop"] ?: 0) + 1
        lastSeq["desktop"] = seq
        events += MimirEvent(events.size + 1L, "desktop", seq, kind, body.toString())
    }

    fun sent(kind: String) = events.filter { it.device == me && it.kind == kind }.map { JSONObject(it.body) }
}

// A plain Application: these tests need Android's org.json, not the app's own start-up work.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SagaServiceTest {
    private val clock = TestClock(Instant.parse("2026-11-21T12:00:00Z"))
    private val notifier = FakeNotifier()
    private val hermodr = HermodrService(
        InMemoryMessageStore(), notifier, FakeScheduler(), { QuietHours(enabled = false) },
        clock = clock, io = Dispatchers.Unconfined,
    )
    private val alerts = InMemoryAlertStore()
    private val gjallar = GjallarService(alerts, hermodr, clock, Dispatchers.Unconfined)
    private val mimir = FakeMimir()
    private var configured = true
    private val store = InMemorySagaStore()
    private val saga = SagaService(store, gjallar, { mimir.takeIf { configured } }, { "phone" }, clock, Dispatchers.Unconfined)

    private fun desktopAlert(state: String = "open", openedAt: Long = 1000) = JSONObject()
        .put("source", "munin").put("key", "down:laya").put("severity", "critical").put("title", "Laya is down")
        .put("state", state).put("openedAt", openedAt).put("lastSeenAt", openedAt).put("muted", false)

    private suspend fun syncOk() = assertTrue(saga.status.value.lastError ?: "", saga.sync())

    @Test fun sendsThePhonesAlertsWhenTheyChangeOnly() = runTest {
        gjallar.raise("vedrfolnir", "battery-low", Severity.WARNING, "Battery at 12%", "not charging")
        syncOk()
        clock.advance(Duration.ofMinutes(15))
        gjallar.raise("vedrfolnir", "battery-low", Severity.WARNING, "Battery at 12%", "not charging") // only seen again
        syncOk()
        val sent = mimir.sent(Kinds.ALERT)
        assertEquals(1, sent.size)
        assertEquals("vedrfolnir", sent[0].getString("source"))
        assertEquals("open", sent[0].getString("state"))
        assertEquals("warning", sent[0].getString("severity"))

        gjallar.acknowledge(alerts.find("vedrfolnir", "battery-low")!!.id)
        syncOk()
        assertEquals(listOf("open", "acknowledged"), mimir.sent(Kinds.ALERT).map { it.getString("state") })
    }

    @Test fun listsTheDesktopsAlertsWithoutPushingThem() = runTest {
        mimir.fromDesktop(Kinds.ALERT, desktopAlert())
        syncOk()
        val m = saga.mirrors().single()
        assertEquals("desktop", m.device)
        assertEquals("Laya is down", m.alert.title)
        assertEquals(Severity.CRITICAL, m.alert.severity)
        assertTrue("never pushed", notifier.shown.isEmpty())
        assertTrue("not in the phone's own Gjallar", gjallar.active().isEmpty())
        assertTrue("not sent back", mimir.sent(Kinds.ALERT).isEmpty())

        mimir.fromDesktop(Kinds.ALERT, desktopAlert("resolved"))
        syncOk()
        assertTrue("resolved mirrors are not listed", saga.mirrors().isEmpty())
    }

    @Test fun yourButtonsOnADesktopAlertAskTheDesktop() = runTest {
        mimir.fromDesktop(Kinds.ALERT, desktopAlert())
        syncOk()
        val m = saga.mirrors().single()
        saga.acknowledge(m)
        assertEquals("shown at once", AlertState.ACKNOWLEDGED, saga.mirrors().single().alert.state)
        val until = clock.instant().plus(Duration.ofHours(1))
        saga.snooze(saga.mirrors().single(), until)
        saga.setMuted(saga.mirrors().single(), true)
        syncOk()

        val ack = mimir.sent(Kinds.ACK).single()
        assertEquals("desktop", ack.getString("origin"))
        assertEquals("munin", ack.getString("source"))
        assertEquals("down:laya", ack.getString("key"))
        assertEquals(until.toEpochMilli(), mimir.sent(Kinds.SNOOZE).single().getLong("until"))
        assertTrue(mimir.sent(Kinds.MUTE).single().getBoolean("muted"))

        // The desktop answers; its state settles the mirror.
        mimir.fromDesktop(
            Kinds.ALERT,
            desktopAlert("acknowledged").put("snoozedUntil", until.toEpochMilli()).put("muted", true),
        )
        syncOk()
        val settled = saga.mirrors().single().alert
        assertEquals(AlertState.ACKNOWLEDGED, settled.state)
        assertEquals(until, settled.snoozedUntil)
        assertTrue(settled.muted)
    }

    @Test fun doesWhatTheDesktopAsksOfThePhonesAlerts() = runTest {
        gjallar.raise("vedrfolnir", "heat", Severity.CRITICAL, "Phone is hot")
        syncOk()
        val until = clock.instant().plus(Duration.ofHours(2))
        mimir.fromDesktop(Kinds.ACK, JSONObject().put("origin", "phone").put("source", "vedrfolnir").put("key", "heat"))
        mimir.fromDesktop(Kinds.SNOOZE, JSONObject().put("origin", "phone").put("source", "vedrfolnir").put("key", "heat").put("until", until.toEpochMilli()))
        mimir.fromDesktop(Kinds.MUTE, JSONObject().put("origin", "phone").put("source", "vedrfolnir").put("key", "heat").put("muted", true))
        // Asked of another device: not ours to do.
        mimir.fromDesktop(Kinds.MUTE, JSONObject().put("origin", "tablet").put("source", "vedrfolnir").put("key", "storage").put("muted", true))
        syncOk()

        val a = alerts.find("vedrfolnir", "heat")!!
        assertEquals(AlertState.ACKNOWLEDGED, a.state)
        assertEquals(until, a.snoozedUntil)
        assertTrue(gjallar.isMuted("vedrfolnir", "heat"))
        assertFalse(gjallar.isMuted("vedrfolnir", "storage"))
        assertTrue("its notification is taken back", notifier.cancelled.contains(a.notificationTag))
        // The new state goes back in the same round.
        val last = mimir.sent(Kinds.ALERT).last()
        assertEquals("acknowledged", last.getString("state"))
        assertTrue(last.getBoolean("muted"))
        assertEquals(until.toEpochMilli(), last.getLong("snoozedUntil"))
    }

    @Test fun keepsEventsWhileMimirIsDown() = runTest {
        syncOk() // learns its count
        mimir.down = true
        gjallar.raise("vedrfolnir", "storage", Severity.WARNING, "Storage 91% full")
        assertFalse(saga.sync())
        assertEquals(1, saga.status.value.waiting)
        assertTrue(saga.status.value.lastError!!.contains("down"))
        mimir.down = false
        syncOk()
        assertEquals(0, saga.status.value.waiting)
        assertEquals(1, mimir.sent(Kinds.ALERT).size)
    }

    @Test fun followsOnWhenMimirLostOurEvents() = runTest {
        gjallar.raise("vedrfolnir", "storage", Severity.WARNING, "Storage 91% full")
        syncOk()
        // Mímir was restored from an old backup: our first event is gone.
        mimir.events.clear()
        mimir.lastSeq.clear()
        gjallar.raise("vedrfolnir", "heat", Severity.WARNING, "Phone is warm")
        syncOk()
        assertEquals(listOf("heat"), mimir.sent(Kinds.ALERT).map { it.getString("key") })
        assertEquals(0, saga.status.value.waiting)
    }

    @Test fun startsAfterTheSeqMimirHolds() = runTest {
        mimir.lastSeq["phone"] = 41 // the app was reinstalled
        gjallar.raise("vedrfolnir", "storage", Severity.WARNING, "Storage 91% full")
        syncOk()
        assertEquals(42L, mimir.events.single().seq)
    }

    @Test fun waitsWhileNotSetUp() = runTest {
        configured = false
        assertFalse(saga.sync())
        assertFalse(saga.status.value.configured)
        assertNull(saga.status.value.lastError)
    }

    @Test fun alertSnapshotsRoundTrip() {
        val a = AlertSnapshot(
            "vedrfolnir", "heat", Severity.CRITICAL, "Phone is hot", null, "https://x", AlertState.ACKNOWLEDGED,
            Instant.ofEpochMilli(1000), Instant.ofEpochMilli(2000), snoozedUntil = Instant.ofEpochMilli(3000), muted = true,
        )
        assertEquals(a, AlertSnapshot.parse(a.toJson().toString()))
        assertNull(AlertSnapshot.parse("""{"key":"no source"}"""))
        assertEquals("seen again only", a.fingerprint, a.copy(lastSeenAt = Instant.ofEpochMilli(9000)).fingerprint)
    }
}
