package io.github.sebbejohansson.yggdrasil.saga

import io.github.sebbejohansson.yggdrasil.gjallar.AlertState
import java.time.Instant

/** Another device's alert, as that device last sent it (or as you just changed it here, until it answers). */
data class Mirror(val device: String, val alert: AlertSnapshot, val receivedAt: Instant)

/**
 * Sága's own state, in `ygg.db`: what waits to go to Mímir, how far it has
 * read, what it last sent of each of the phone's alerts, and the other
 * devices' alerts.
 */
interface SagaStore {
    /** "read" (the last Mímir pos applied) or "seq" (the last seq given out). Null when unset. */
    fun mark(name: String): Long?
    fun setMark(name: String, value: Long)

    /** Numbers an event and keeps it until Mímir has it. Returns its seq. */
    fun enqueue(at: Instant, kind: String, body: String): Long
    fun outbox(limit: Int): List<Outgoing>
    fun outboxSize(): Int

    /** Mímir holds everything up to [lastSeq]. */
    fun sent(lastSeq: Long)

    /** Mímir lost our newest events (a restore): move what waits to follow [lastSeq]. */
    fun renumber(lastSeq: Long)

    fun published(source: String, key: String): String?
    fun setPublished(source: String, key: String, fingerprint: String)

    fun mirrors(): List<Mirror>
    fun putMirror(m: Mirror)
    fun deleteMirrorsResolvedBefore(t: Instant)

    /** Keeps a machine's sessions, unless what is kept is newer. [body] is the event as it came. */
    fun putDeviceSessions(d: DeviceSessions, body: String)
    fun deviceSessions(): List<DeviceSessions>

    /** What you asked of other machines' sessions, newest first, and how each went. */
    fun putAsked(a: Asked)
    fun asked(since: Instant): List<Asked>
    fun answerAsked(id: String, state: AskState, error: String?, url: String?)

    /** Runs [block] all or nothing. */
    fun <T> transaction(block: () -> T): T
}

class InMemorySagaStore : SagaStore {
    private val marks = mutableMapOf<String, Long>()
    private val queue = sortedMapOf<Long, Outgoing>()
    private val fingerprints = mutableMapOf<Pair<String, String>, String>()
    private val mirrorRows = linkedMapOf<Triple<String, String, String>, Mirror>()

    override fun mark(name: String) = marks[name]
    override fun setMark(name: String, value: Long) { marks[name] = value }

    override fun enqueue(at: Instant, kind: String, body: String): Long {
        val seq = (marks["seq"] ?: 0) + 1
        queue[seq] = Outgoing(seq, at.toEpochMilli(), kind, body)
        marks["seq"] = seq
        return seq
    }
    override fun outbox(limit: Int) = queue.values.take(limit)
    override fun outboxSize() = queue.size
    override fun sent(lastSeq: Long) { queue.headMap(lastSeq + 1).clear() }
    override fun renumber(lastSeq: Long) {
        val waiting = queue.values.toList()
        queue.clear()
        waiting.forEachIndexed { i, o -> queue[lastSeq + 1 + i] = o.copy(seq = lastSeq + 1 + i) }
        marks["seq"] = lastSeq + waiting.size
    }

    override fun published(source: String, key: String) = fingerprints[source to key]
    override fun setPublished(source: String, key: String, fingerprint: String) { fingerprints[source to key] = fingerprint }

    override fun mirrors() = mirrorRows.values.toList()
    override fun putMirror(m: Mirror) { mirrorRows[Triple(m.device, m.alert.source, m.alert.key)] = m }
    override fun deleteMirrorsResolvedBefore(t: Instant) {
        mirrorRows.values.removeAll { it.alert.state == AlertState.RESOLVED && it.receivedAt.isBefore(t) }
    }

    private val devices = linkedMapOf<String, DeviceSessions>()
    override fun putDeviceSessions(d: DeviceSessions, body: String) {
        val kept = devices[d.device]
        if (kept == null || !kept.heardAt.isAfter(d.heardAt)) devices[d.device] = d
    }
    override fun deviceSessions() = devices.values.sortedBy { it.device }

    private val askedRows = linkedMapOf<String, Asked>()
    override fun putAsked(a: Asked) { askedRows[a.ask.id] = a }
    override fun asked(since: Instant) = askedRows.values.filter { !it.at.isBefore(since) }.sortedByDescending { it.at }
    override fun answerAsked(id: String, state: AskState, error: String?, url: String?) {
        askedRows[id]?.let { askedRows[id] = it.copy(state = state, error = error, url = url) }
    }

    override fun <T> transaction(block: () -> T): T = block()
}
