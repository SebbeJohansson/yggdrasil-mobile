package io.github.sebbejohansson.yggdrasil.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class FakeProvider(
    override val id: String,
    override val leavesDevice: Boolean = false,
    override val tiers: Set<LlmTier> = setOf(LlmTier.SMALL, LlmTier.LARGE),
    var currentStatus: ProviderStatus = ProviderStatus.Ready,
    private val answer: List<String> = listOf("hi"),
) : LlmProvider {
    override val displayName = id
    override suspend fun status() = currentStatus
    override fun stream(request: LlmRequest): Flow<String> = flowOf(*answer.toTypedArray())
}

private fun ask(privacy: Privacy = Privacy.ON_DEVICE_ONLY, tier: LlmTier = LlmTier.LARGE) =
    LlmRequest(listOf(LlmMessage(LlmRole.USER, "hello")), tier = tier, privacy = privacy)

class LlmRouterTest {

    @Test fun picksFirstReadyProvider() = runTest {
        val a = FakeProvider("a", currentStatus = ProviderStatus.NeedsSetup("download"))
        val b = FakeProvider("b")
        assertSame(b, LlmRouter(listOf(a, b)).pick(ask()))
    }

    @Test fun onDeviceOnlyNeverPicksAProviderThatLeavesThePhone() = runTest {
        val cloud = FakeProvider("cloud", leavesDevice = true)
        try {
            LlmRouter(listOf(cloud)).pick(ask(Privacy.ON_DEVICE_ONLY))
            fail("expected NoProviderException")
        } catch (e: NoProviderException) {
            assertEquals("sends text off the phone", e.reasons["cloud"])
        }
    }

    @Test fun mayLeaveDeviceStillPrefersTheListOrder() = runTest {
        val local = FakeProvider("local")
        val cloud = FakeProvider("cloud", leavesDevice = true)
        assertSame(local, LlmRouter(listOf(local, cloud)).pick(ask(Privacy.MAY_LEAVE_DEVICE)))
    }

    @Test fun fallsBackToCloudWhenLocalIsUnavailableAndAllowed() = runTest {
        val local = FakeProvider("local", currentStatus = ProviderStatus.Unavailable("too little RAM"))
        val cloud = FakeProvider("cloud", leavesDevice = true)
        assertSame(cloud, LlmRouter(listOf(local, cloud)).pick(ask(Privacy.MAY_LEAVE_DEVICE)))
    }

    @Test fun skipsProvidersNotMeantForTheTier() = runTest {
        val big = FakeProvider("big", tiers = setOf(LlmTier.LARGE))
        val small = FakeProvider("small", tiers = setOf(LlmTier.SMALL))
        assertSame(small, LlmRouter(listOf(big, small)).pick(ask(tier = LlmTier.SMALL)))
    }

    @Test fun reportsEveryReasonWhenNothingCanAnswer() = runTest {
        val local = FakeProvider("local", currentStatus = ProviderStatus.NeedsSetup("download Gemma"))
        val cloud = FakeProvider("cloud", leavesDevice = true)
        try {
            LlmRouter(listOf(local, cloud)).pick(ask())
            fail("expected NoProviderException")
        } catch (e: NoProviderException) {
            assertEquals(mapOf("local" to "download Gemma", "cloud" to "sends text off the phone"), e.reasons)
            assertTrue(e.message!!.contains("download Gemma"))
        }
    }

    @Test fun streamsFromThePickedProvider() = runTest {
        val p = FakeProvider("p", answer = listOf("Hel", "lo"))
        val router = LlmRouter(listOf(p))
        assertEquals(listOf("Hel", "lo"), router.stream(ask()).toList())
        assertEquals("Hello", router.complete(ask()))
    }
}
