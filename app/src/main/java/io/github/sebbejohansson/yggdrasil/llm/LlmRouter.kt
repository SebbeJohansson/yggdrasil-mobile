package io.github.sebbejohansson.yggdrasil.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Picks who answers a request. Providers are tried in the order given
 * (the user's preference); one is skipped when:
 *  - the request is [Privacy.ON_DEVICE_ONLY] and the provider leaves the phone,
 *  - it isn't good enough for the request's tier,
 *  - it isn't [ProviderStatus.Ready].
 */
class LlmRouter(private val providers: List<LlmProvider>) {

    suspend fun pick(request: LlmRequest): LlmProvider {
        val skipped = linkedMapOf<String, String>()
        for (p in providers) {
            if (request.privacy == Privacy.ON_DEVICE_ONLY && p.leavesDevice) {
                skipped[p.displayName] = "sends text off the phone"
                continue
            }
            if (request.tier !in p.tiers) {
                skipped[p.displayName] = "not meant for ${request.tier.name.lowercase()} jobs"
                continue
            }
            when (val s = p.status()) {
                ProviderStatus.Ready -> return p
                is ProviderStatus.NeedsSetup -> skipped[p.displayName] = s.reason
                is ProviderStatus.Unavailable -> skipped[p.displayName] = s.reason
            }
        }
        throw NoProviderException(skipped)
    }

    fun stream(request: LlmRequest): Flow<String> = flow {
        emitAll(pick(request).stream(request))
    }

    suspend fun complete(request: LlmRequest): String = pick(request).complete(request)

    /** Every provider with its status, for the settings screen. */
    suspend fun statuses(): List<Pair<LlmProvider, ProviderStatus>> =
        providers.map { it to it.status() }
}

