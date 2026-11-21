package io.github.sebbejohansson.yggdrasil.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.fold

/**
 * How much model a job needs. Parts ask for a tier, never for a provider:
 * the router decides who answers.
 */
enum class LlmTier {
    /** Short, frequent jobs: classify, find names, one-line summaries. */
    SMALL,

    /** Chat, briefs, anything that has to reason over a lot of text. */
    LARGE,
}

/**
 * Where the text may go. Yggdrasil's rule carries over: anything with
 * people's names in it stays on the phone unless it has been swapped first.
 */
enum class Privacy {
    /** Only providers that run on this phone. */
    ON_DEVICE_ONLY,

    /** Providers that send the text off the phone (the Claude bridge) are fine too. */
    MAY_LEAVE_DEVICE,
}

enum class LlmRole { USER, ASSISTANT }

data class LlmMessage(val role: LlmRole, val text: String)

data class LlmRequest(
    val messages: List<LlmMessage>,
    val system: String? = null,
    val tier: LlmTier = LlmTier.LARGE,
    val privacy: Privacy = Privacy.ON_DEVICE_ONLY,
    val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
    /**
     * A short job on the side (picking facts out of a message): it shouldn't
     * disturb the conversation a provider keeps open for the chat.
     */
    val side: Boolean = false,
) {
    init {
        require(messages.isNotEmpty()) { "a request needs at least one message" }
        require(messages.last().role == LlmRole.USER) { "the last message must be the user's" }
    }
}

/** Whether a provider can answer right now, and if not, what it needs. */
sealed interface ProviderStatus {
    data object Ready : ProviderStatus

    /** It could work on this phone, after something is done (download, start the bridge). */
    data class NeedsSetup(val reason: String) : ProviderStatus

    /** It can't work on this phone (too little memory, not supported). */
    data class Unavailable(val reason: String) : ProviderStatus
}

interface LlmProvider {
    /** Stable id for settings and logs, e.g. `litert:gemma-4-e2b`. */
    val id: String
    val displayName: String

    /** True when the text is sent off the phone. */
    val leavesDevice: Boolean

    /** The tiers this provider is good enough for. */
    val tiers: Set<LlmTier>

    suspend fun status(): ProviderStatus

    /** Streams the answer as text deltas. Cancel the collector to stop generating. */
    fun stream(request: LlmRequest): Flow<String>
}

/** Collects a whole answer. */
suspend fun LlmProvider.complete(request: LlmRequest): String =
    stream(request).fold(StringBuilder()) { acc, delta -> acc.append(delta) }.toString()

class NoProviderException(val reasons: Map<String, String>) : IllegalStateException(
    if (reasons.isEmpty()) "No model is set up."
    else "No model can answer: " + reasons.entries.joinToString("; ") { "${it.key}: ${it.value}" },
)
