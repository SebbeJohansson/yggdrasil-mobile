package io.github.sebbejohansson.yggdrasil.llm

import io.github.sebbejohansson.yggdrasil.norn.Person
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class Remote(private val answer: (LlmRequest) -> String) : LlmProvider {
    var got: LlmRequest? = null
    override val id = "remote"
    override val displayName = "Remote"
    override val leavesDevice = true
    override val tiers = setOf(LlmTier.LARGE)
    override suspend fun status() = ProviderStatus.Ready
    override fun stream(request: LlmRequest): Flow<String> {
        got = request
        return flowOf(answer(request))
    }
}

class NameGuardTest {
    private val people = listOf(Person("Ada Lind", role = "your manager"))
    private fun ask(text: String) = LlmRequest(listOf(LlmMessage(LlmRole.USER, text)), system = "You are Norn.", privacy = Privacy.MAY_LEAVE_DEVICE)

    @Test fun namesNeverReachTheRemoteAndComeBackInTheAnswer() = runTest {
        // As on the desktop, addresses are numbered first: kalle@ is Person 1, Ada Person 2, Kalle Ek Person 3.
        val remote = Remote { "Ask Person 2 first, then Person 3." }
        val guard = NameGuard(remote, { listOf("Kalle Ek") }, { people }, { false })
        val answer = guard.complete(ask("Should I ask Ada Lind or Kalle Ek about leave? Mail kalle@firm.se."))
        val sent = remote.got!!
        val all = sent.messages.joinToString { it.text } + sent.system
        assertFalse(all, all.contains("Ada") || all.contains("Kalle") || all.contains("kalle@"))
        assertTrue(sent.system!!, sent.system!!.contains("- Person 2: your manager"))
        assertEquals("Ask Ada Lind first, then Kalle Ek.", answer)
    }

    @Test fun nothingIsSentWhenTheLocalModelCantLook() = runTest {
        val remote = Remote { "?" }
        val guard = NameGuard(remote, { throw IllegalStateException("No model is set up.") }, { people }, { false })
        try {
            guard.complete(ask("Hello"))
            fail("expected NamesNotSentException")
        } catch (e: NamesNotSentException) {
            assertTrue(e.message!!.startsWith("The local model couldn't look for names"))
        }
        assertEquals(null, remote.got)
    }

    @Test fun switchedOffSendsAsItIs() = runTest {
        val remote = Remote { "Hi Ada" }
        val guard = NameGuard(remote, { throw IllegalStateException("never asked") }, { people }, { true })
        assertEquals("Hi Ada", guard.complete(ask("Say hi to Ada Lind")))
        assertTrue(remote.got!!.messages.single().text.contains("Ada Lind"))
    }

    @Test fun readsTheLocalModelsNames() {
        val text = "Kalle Ek and Ada asked Claude in Teams."
        assertEquals(listOf("Kalle Ek", "Ada"), NameAnswers.read("""{"names": ["Kalle Ek", "Ada", "Claude", "Teams", "Ghost"]}""", text))
        try {
            NameAnswers.read("no idea", text)
            fail("not JSON gave names")
        } catch (_: IllegalStateException) {
        }
    }

    @Test fun chunksCutAtLineEnds() {
        val text = (1..300).joinToString("\n") { "line $it with some words" }
        val parts = NameAnswers.chunks(text, 1_000)
        assertTrue(parts.all { it.length <= 1_000 })
        assertEquals(text, parts.joinToString(""))
    }
}
