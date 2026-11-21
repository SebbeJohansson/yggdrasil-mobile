package io.github.sebbejohansson.yggdrasil.history

import io.github.sebbejohansson.yggdrasil.core.Changes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The world tree's history: what really happened, kept for good, so nothing
 * ever leaves the tree (memories can be forgotten and reactions expire, but
 * what they grew stays). On the desktop the tree reads the parts' own
 * permanent logs; on the phone this append-only log is that.
 *
 * - a branch for every fact Norn came to remember (the desktop's merged goal),
 * - a scar for every time an alert healed,
 * - a raven for every time Huginn told you something (on the desktop Loki's
 *   strikes; Huginn is Odin's raven).
 */
enum class HistoryKind { BRANCH, SCAR, RAVEN }

data class HistoryEvent(
    val kind: HistoryKind,
    /** The id in its part with the kind in front (memory:12, healed:3:1732…): it seeds where it grows. */
    val id: String,
    /** Unix ms. */
    val at: Long,
    /** What it was, in words, for tapping. */
    val words: String,
    /** For a scar: when the alert opened (unix ms), for how long it lasted. */
    val startedAt: Long? = null,
)

interface HistoryStore {
    /** Keeps the event; an id already kept is left as it was. */
    fun record(e: HistoryEvent)
    fun all(): List<HistoryEvent>
}

class HistoryLog(private val store: HistoryStore, private val io: CoroutineDispatcher = Dispatchers.IO) {
    val changes = Changes()

    suspend fun record(e: HistoryEvent) {
        withContext(io) { store.record(e) }
        changes.bump()
    }

    suspend fun all(): List<HistoryEvent> = withContext(io) { store.all() }
}

class InMemoryHistoryStore : HistoryStore {
    private val rows = linkedMapOf<String, HistoryEvent>()
    override fun record(e: HistoryEvent) { rows.putIfAbsent(e.id, e) }
    override fun all() = rows.values.toList()
}
