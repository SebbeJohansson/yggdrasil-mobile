package io.github.sebbejohansson.yggdrasil.norn

import io.github.sebbejohansson.yggdrasil.llm.LlmRequest
import io.github.sebbejohansson.yggdrasil.llm.LlmRole
import io.github.sebbejohansson.yggdrasil.llm.LlmTier
import io.github.sebbejohansson.yggdrasil.llm.Privacy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * A chat with Norn, kept in [store]: what you said, what it answered, and
 * the memories recalled for each question. Memories go in front of your
 * message rather than in the system prompt, so the system prompt never
 * changes mid-chat and the model can keep its conversation open.
 */
class NornChat(
    private val store: ChatStore,
    private val brain: Brain,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Promises you mention to Yggdrasil go to the ledger. */
    private val promises: PromiseService? = null,
) {
    var conversationId: Long? = null
        private set
    var lines: List<ChatLine> = emptyList()
        private set

    suspend fun conversations(limit: Int = 30) = withContext(io) { store.conversations(limit) }

    /** Opens the newest chat, if there is one. */
    suspend fun openLatest() {
        conversations(1).firstOrNull()?.let { open(it.id) }
    }

    suspend fun open(id: Long) {
        lines = withContext(io) { store.lines(id) }
        conversationId = id
    }

    fun startNew() {
        conversationId = null
        lines = emptyList()
    }

    suspend fun delete(id: Long) {
        withContext(io) { store.delete(id) }
        if (conversationId == id) startNew()
    }

    /** Keeps your question (with its recalled memories) and returns the request for the model. */
    suspend fun ask(question: String, privacy: Privacy): LlmRequest {
        val recalled = brain.recallFor(question)
        val sent = if (recalled.isEmpty()) question else "${memoryNote(recalled)}\n\n$question"
        val line = ChatLine(LlmRole.USER, question, sent)
        val id = conversationId ?: withContext(io) { store.create(ChatTitle.of(question), now()) }.also { conversationId = it }
        withContext(io) { store.append(id, line, now()) }
        lines = lines + line
        return LlmRequest(messages = lines.map { it.toLlm() }, system = SYSTEM, tier = LlmTier.LARGE, privacy = privacy)
    }

    /** Keeps Norn's answer, whole or as far as it got. */
    suspend fun answered(text: String) {
        if (text.isEmpty()) return
        val id = conversationId ?: return
        val line = ChatLine(LlmRole.ASSISTANT, text, text)
        withContext(io) { store.append(id, line, now()) }
        lines = lines + line
    }

    /** Learns from your question: your words only, never the answer. */
    suspend fun learnFrom(question: String) = brain.learnFrom(question).also { promises?.read(question, chat = true) }

    companion object {
        const val SYSTEM = """You are Yggdrasil, a personal assistant that runs on the user's phone.
Answer briefly and plainly. Say so when you don't know.
A message may start with notes from your memory of the user. Use them when they help; never repeat them back unasked."""

        fun memoryNote(memories: List<Memory>) =
            "(From your memory of the user, use only if it helps:\n" + memories.joinToString("\n") { "- ${it.text}" } + ")"
    }
}
