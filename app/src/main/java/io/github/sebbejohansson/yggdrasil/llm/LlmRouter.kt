package io.github.sebbejohansson.yggdrasil.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Picks who answers a request. Providers are tried in the order given
 * (the user's preference); one is skipped when:
 *  - the request is [Privacy.ON_DEVICE_ONLY] and the provider leaves the phone,
 *  - it isn't good enough for the request's tier,
 *  - it isn't [ProviderStatus.Ready].
 */
/** Told of every call the router makes: who answered, how long it took, how much it wrote, and how it failed. */
fun interface LlmMeter {
    fun call(request: LlmRequest, provider: LlmProvider?, ms: Long, chars: Long, failure: Throwable?)
}

class LlmRouter(private val providers: List<LlmProvider>, private val meter: LlmMeter? = null) {

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
        val start = System.nanoTime()
        var provider: LlmProvider? = null
        var chars = 0L
        try {
            provider = pick(request)
            provider.stream(request).collect { chars += it.length; emit(it) }
            meter?.call(request, provider, ms(start), chars, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            meter?.call(request, provider, ms(start), chars, e)
            throw e
        }
    }

    suspend fun complete(request: LlmRequest): String {
        val start = System.nanoTime()
        var provider: LlmProvider? = null
        try {
            provider = pick(request)
            return provider.complete(request).also { meter?.call(request, provider, ms(start), it.length.toLong(), null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            meter?.call(request, provider, ms(start), 0, e)
            throw e
        }
    }

    private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000

    /** Every provider with its status, for the settings screen. */
    suspend fun statuses(): List<Pair<LlmProvider, ProviderStatus>> =
        providers.map { it to it.status() }
}

