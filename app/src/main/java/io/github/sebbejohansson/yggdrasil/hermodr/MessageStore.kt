package io.github.sebbejohansson.yggdrasil.hermodr

import java.time.Instant

interface MessageStore {
    /** Returns the new id. */
    fun insert(m: Message): Long
    fun update(m: Message)
    fun get(id: Long): Message?
    fun held(): List<Message>
    fun unseenWithTag(tag: String): List<Message>

    /** Newest first. */
    fun recent(limit: Int): List<Message>
    fun deleteCreatedBefore(t: Instant)
}

class InMemoryMessageStore : MessageStore {
    private val rows = linkedMapOf<Long, Message>()
    private var nextId = 1L

    override fun insert(m: Message): Long = nextId++.also { rows[it] = m.copy(id = it) }
    override fun update(m: Message) { rows[m.id] = m }
    override fun get(id: Long) = rows[id]
    override fun held() = rows.values.filter { it.state == MessageState.HELD }.sortedBy { it.id }
    override fun unseenWithTag(tag: String) = rows.values.filter { it.tag == tag && it.seenAt == null }
    override fun recent(limit: Int) = rows.values.sortedByDescending { it.id }.take(limit)
    override fun deleteCreatedBefore(t: Instant) { rows.values.removeAll { it.createdAt.isBefore(t) } }
}
