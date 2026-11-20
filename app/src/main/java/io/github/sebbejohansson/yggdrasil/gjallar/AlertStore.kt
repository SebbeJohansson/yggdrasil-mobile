package io.github.sebbejohansson.yggdrasil.gjallar

import java.time.Instant

interface AlertStore {
    /** The newest alert for this source and key, in any state. */
    fun find(source: String, key: String): Alert?
    fun get(id: Long): Alert?

    /** Returns the new id. */
    fun insert(a: Alert): Long
    fun update(a: Alert)

    /** Most recently seen first. */
    fun list(states: Set<AlertState>, limit: Int): List<Alert>
    fun isMuted(source: String, key: String): Boolean
    fun setMuted(source: String, key: String, muted: Boolean)
    fun deleteResolvedBefore(t: Instant)
}

class InMemoryAlertStore : AlertStore {
    private val rows = linkedMapOf<Long, Alert>()
    private val mutes = mutableSetOf<Pair<String, String>>()
    private var nextId = 1L

    override fun find(source: String, key: String) =
        rows.values.filter { it.source == source && it.key == key }.maxByOrNull { it.id }
    override fun get(id: Long) = rows[id]
    override fun insert(a: Alert): Long = nextId++.also { rows[it] = a.copy(id = it) }
    override fun update(a: Alert) { rows[a.id] = a }
    override fun list(states: Set<AlertState>, limit: Int) =
        rows.values.filter { it.state in states }.sortedByDescending { it.lastSeenAt }.take(limit)
    override fun isMuted(source: String, key: String) = (source to key) in mutes
    override fun setMuted(source: String, key: String, muted: Boolean) {
        if (muted) mutes += source to key else mutes -= source to key
    }
    override fun deleteResolvedBefore(t: Instant) {
        rows.values.removeAll { it.state == AlertState.RESOLVED && it.resolvedAt?.isBefore(t) == true }
    }
}
