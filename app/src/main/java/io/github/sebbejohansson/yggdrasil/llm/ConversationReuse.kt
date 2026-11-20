package io.github.sebbejohansson.yggdrasil.llm

/**
 * What an on-device conversation has already been fed. Providers that keep
 * state (LiteRT-LM's KV cache) can skip feeding the history again when a new
 * request only adds one user message to it.
 */
data class ConversationSnapshot(val system: String?, val messages: List<LlmMessage>)

object ConversationReuse {
    /** True when [request] is [seen] plus exactly one new user message. */
    fun canReuse(seen: ConversationSnapshot?, request: LlmRequest): Boolean =
        seen != null &&
            seen.system == request.system &&
            request.messages.size == seen.messages.size + 1 &&
            request.messages.subList(0, seen.messages.size) == seen.messages

    /** The state after [request] was answered with [answer]. */
    fun after(request: LlmRequest, answer: String) = ConversationSnapshot(
        system = request.system,
        messages = request.messages + LlmMessage(LlmRole.ASSISTANT, answer),
    )
}
