package io.github.sebbejohansson.yggdrasil.saga

import io.github.sebbejohansson.yggdrasil.core.Changes
import io.github.sebbejohansson.yggdrasil.gjallar.AlertState
import io.github.sebbejohansson.yggdrasil.gjallar.GjallarService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** How syncing goes, for the Alerts tab. */
data class SyncStatus(
    val configured: Boolean = false,
    val lastSyncAt: Instant? = null,
    val lastError: String? = null,
    val waiting: Int = 0,
)

/**
 * Sága: keeps the phone's alerts and the desktop's in step through Mímir,
 * the event log on the VPS, as the desktop's `ygg saga` does on its side.
 *
 * - The phone's alerts go to Mímir as `gjallar.alert` each time something
 *   about one changes (not each time it is seen again).
 * - The desktop's alerts come back as [Mirror]s, listed in the Alerts tab
 *   and never pushed: the desktop already told you.
 * - Seen, Snooze and Mute on a mirror ask the desktop to do the same; the
 *   mirror shows it at once, and the desktop's answer settles it.
 * - What the desktop asks of the phone's alerts, Sága does in Gjallar.
 *
 * Every event waits in the outbox until Mímir has it, so either side can be off.
 */
class SagaService(
    private val store: SagaStore,
    private val gjallar: GjallarService,
    /** Mímir, or null while it isn't set up. */
    private val mimir: () -> MimirClient?,
    /** This phone's name at Mímir. */
    private val device: () -> String,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Bumped when the mirrors change, so the Alerts tab reloads. */
    val changes = Changes()

    /** Bumped when another machine's sessions, or an answer to what you asked, come in. */
    val sessionChanges = Changes()
    private val lock = Mutex()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** The other devices' alerts that aren't resolved, newest first. */
    suspend fun mirrors(): List<Mirror> = withContext(io) {
        store.mirrors().filter { it.alert.state != AlertState.RESOLVED }.sortedByDescending { it.alert.lastSeenAt }
    }

    /**
     * One round: read what the other devices sent and act on it, send what
     * changed here, and hand it all to Mímir. False when it couldn't finish;
     * the reason is in [status].
     */
    suspend fun sync(): Boolean = withContext(io) {
        lock.withLock {
            val client = mimir()
            if (client == null) {
                _status.value = SyncStatus(configured = false, waiting = store.outboxSize())
                return@withLock false
            }
            val error = runCatching { round(client) }.exceptionOrNull()
            _status.value = SyncStatus(
                configured = true,
                lastSyncAt = clock.instant(),
                lastError = error?.let { it.message ?: it.javaClass.simpleName },
                waiting = store.outboxSize(),
            )
            error == null
        }
    }

    private suspend fun round(client: MimirClient) {
        // Pick up our numbering the first time, so a fresh install never sends a seq Mímir holds.
        if (store.mark("seq") == null) store.setMark("seq", client.lastSeq())
        // Read first, so the mirrors are current; a failure still lets ours go out.
        val readError = runCatching { pull(client) }.exceptionOrNull()
        publish()
        push(client)
        readError?.let { throw it }
    }

    private suspend fun pull(client: MimirClient) {
        while (true) {
            val mark = store.mark("read") ?: 0
            when (val page = client.read(mark, device())) {
                Page.Gone -> {
                    // Start again where the snapshot leaves off, or from the start: each
                    // device sends an alert's whole state whenever it changes.
                    store.setMark("read", client.snapshotPos())
                }
                is Page.Events -> {
                    apply(page.events)
                    store.setMark("read", page.last)
                    if (!page.more) return
                }
            }
        }
    }

    /** Acts on a page of events. Only each alert's newest state in it counts. */
    private suspend fun apply(events: List<MimirEvent>) {
        val me = device()
        val newest = linkedMapOf<Triple<String, String, String>, Mirror>()
        val now = clock.instant()
        var sessionsChanged = false
        for (e in events) {
            when (e.kind) {
                Kinds.ALERT -> {
                    if (e.device == me) continue
                    val alert = AlertSnapshot.parse(e.body) ?: continue
                    newest[Triple(e.device, alert.source, alert.key)] = Mirror(e.device, alert, now)
                }
                Kinds.ACK, Kinds.SNOOZE, Kinds.MUTE -> {
                    val r = AlertRequest.parse(e.kind, e.body) ?: continue
                    if (r.origin == me) handle(r)
                }
                SessionKinds.SESSIONS -> {
                    if (e.device == me) continue
                    val d = DeviceSessions.parse(e.device, e.body, e.at) ?: continue
                    store.putDeviceSessions(d, e.body)
                    sessionsChanged = true
                }
                SessionKinds.DONE -> {
                    val done = RequestDone.parse(e.body) ?: continue
                    if (done.to != me) continue
                    store.answerAsked(done.id, if (done.ok) AskState.DONE else AskState.FAILED, done.error, done.url)
                    sessionsChanged = true
                }
            }
        }
        if (sessionsChanged) sessionChanges.bump()
        if (newest.isNotEmpty()) {
            store.transaction { newest.values.forEach(store::putMirror) }
            changes.bump()
        }
    }

    /** Does what another device asked of one of this phone's alerts. */
    private suspend fun handle(r: AlertRequest) {
        if (r.kind == Kinds.MUTE) {
            // A mute is kept by source and key, even with no alert open.
            r.muted?.let { gjallar.setMuted(r.source, r.key, it) }
            return
        }
        val a = gjallar.find(r.source, r.key) ?: return
        when (r.kind) {
            Kinds.ACK -> if (a.state == AlertState.OPEN) gjallar.acknowledge(a.id)
            Kinds.SNOOZE -> {
                val until = r.until ?: return
                if (a.state != AlertState.RESOLVED && until.isAfter(clock.instant())) gjallar.snooze(a.id, until)
            }
        }
    }

    /** Queues each of the phone's alerts whose state changed since it was last sent. */
    private suspend fun publish() {
        val alerts = gjallar.active(1000) + gjallar.resolved(1000)
        val snapshots = alerts.map { AlertSnapshot.of(it, gjallar.isMuted(it.source, it.key)) }
        val at = clock.instant()
        store.transaction {
            for (s in snapshots) {
                val fingerprint = s.fingerprint
                if (store.published(s.source, s.key) == fingerprint) continue
                store.enqueue(at, Kinds.ALERT, s.toJson().toString())
                store.setPublished(s.source, s.key, fingerprint)
            }
        }
    }

    /** Hands the outbox to Mímir, 500 at a time. */
    private fun push(client: MimirClient) {
        while (true) {
            val waiting = store.outbox(500)
            if (waiting.isEmpty()) return
            when (val answer = client.append(waiting)) {
                is Appended.Ok -> {
                    store.sent(answer.lastSeq)
                    if (waiting.size < 500) return
                }
                is Appended.Gap ->
                    // It holds some already: drop those. Or it lost our newest: follow on from what it has.
                    if (answer.lastSeq >= waiting.first().seq) store.sent(answer.lastSeq) else store.renumber(answer.lastSeq)
            }
        }
    }

    /** Seen, on another device's alert: asks it, and shows it here at once. */
    suspend fun acknowledge(m: Mirror) = ask(m, AlertRequest(Kinds.ACK, m.device, m.alert.source, m.alert.key)) {
        if (it.state == AlertState.OPEN) it.copy(state = AlertState.ACKNOWLEDGED) else it
    }

    suspend fun snooze(m: Mirror, until: Instant) {
        val capped = minOf(until, clock.instant().plus(Duration.ofDays(30)))
        ask(m, AlertRequest(Kinds.SNOOZE, m.device, m.alert.source, m.alert.key, until = capped)) { it.copy(snoozedUntil = capped) }
    }

    suspend fun setMuted(m: Mirror, muted: Boolean) =
        ask(m, AlertRequest(Kinds.MUTE, m.device, m.alert.source, m.alert.key, muted = muted)) { it.copy(muted = muted) }

    private suspend fun ask(m: Mirror, r: AlertRequest, shown: (AlertSnapshot) -> AlertSnapshot) = withContext(io) {
        lock.withLock {
            store.transaction {
                store.enqueue(clock.instant(), r.kind, r.toJson().toString())
                store.putMirror(m.copy(alert = shown(m.alert)))
            }
        }
        changes.bump()
    }

    /** Every other machine's sessions as each last sent them. */
    suspend fun devices(): List<DeviceSessions> = withContext(io) { store.deviceSessions() }

    /** What you asked from this phone in the last day, newest first. */
    suspend fun asked(): List<Asked> = withContext(io) { store.asked(clock.instant().minus(Duration.ofDays(1))) }

    /**
     * Asks a thing of another machine's session: it goes with the next sync,
     * and [ask]'s origin carries it out when it is on and trusts this phone.
     */
    suspend fun askSession(ask: SessionAsk) = withContext(io) {
        lock.withLock {
            store.transaction {
                store.enqueue(clock.instant(), ask.kind, ask.toJson().toString())
                store.putAsked(Asked(ask, clock.instant()))
            }
        }
        sessionChanges.bump()
    }

    /** Forgets resolved mirrors after [keep]. */
    suspend fun prune(keep: Duration = Duration.ofDays(30)) = withContext(io) {
        lock.withLock { store.deleteMirrorsResolvedBefore(clock.instant().minus(keep)) }
    }
}
