package io.github.sebbejohansson.yggdrasil.norn

import io.github.sebbejohansson.yggdrasil.llm.LlmMessage
import io.github.sebbejohansson.yggdrasil.llm.LlmRole
import java.time.Instant

/** One chat with Norn, kept so it survives closing the app. */
data class Conversation(val id: Long, val title: String, val updatedAt: Instant, val pinned: Boolean = false)

/**
 * A message in a chat. [shown] is what you see; [sent] is what the model got,
 * which for your messages may carry recalled memories in front. The model
 * is always given [sent], so a reopened chat continues exactly where it was.
 */
data class ChatLine(val role: LlmRole, val shown: String, val sent: String) {
    fun toLlm() = LlmMessage(role, sent)
}

interface ChatStore {
    /** Pinned chats first, then the rest, each newest first. */
    fun conversations(limit: Int): List<Conversation>
    fun lines(conversationId: Long): List<ChatLine>
    fun create(title: String, at: Instant): Long
    fun append(conversationId: Long, line: ChatLine, at: Instant)
    fun delete(conversationId: Long)
    fun setPinned(conversationId: Long, pinned: Boolean)
}

object ChatTitle {
    /** A chat's title: your first message, on one line, cut short. */
    fun of(firstMessage: String): String =
        firstMessage.replace(Regex("\\s+"), " ").trim().let { if (it.length > 60) it.take(57) + "…" else it }.ifEmpty { "Chat" }
}

class InMemoryChatStore : ChatStore {
    private val convs = linkedMapOf<Long, Conversation>()
    private val lines = mutableMapOf<Long, MutableList<ChatLine>>()
    private var next = 1L
    override fun conversations(limit: Int) = convs.values.sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.updatedAt }).take(limit)
    override fun lines(conversationId: Long) = lines[conversationId].orEmpty().toList()
    override fun create(title: String, at: Instant) = next++.also { convs[it] = Conversation(it, title, at); lines[it] = mutableListOf() }
    override fun append(conversationId: Long, line: ChatLine, at: Instant) {
        lines.getOrPut(conversationId) { mutableListOf() } += line
        convs[conversationId]?.let { convs[conversationId] = it.copy(updatedAt = at) }
    }
    override fun setPinned(conversationId: Long, pinned: Boolean) {
        convs[conversationId]?.let { convs[conversationId] = it.copy(pinned = pinned) }
    }
    override fun delete(conversationId: Long) {
        convs.remove(conversationId)
        lines.remove(conversationId)
    }
}
