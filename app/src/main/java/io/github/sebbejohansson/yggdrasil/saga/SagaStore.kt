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

    override fun <T> transaction(block: () -> T): T = block()
}
