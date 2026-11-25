package io.github.sebbejohansson.yggdrasil.norn

import io.github.sebbejohansson.yggdrasil.llm.ConversationReuse
import io.github.sebbejohansson.yggdrasil.llm.LlmRole
import io.github.sebbejohansson.yggdrasil.llm.Privacy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NornChatTest {
    private val store = InMemoryChatStore()
    private val memories = InMemoryMemoryStore()
    private val brain = Brain(memories, null, { "" }, Dispatchers.Unconfined)
    private fun chat() = NornChat(store, brain, io = Dispatchers.Unconfined)

    @Test fun aChatIsKeptAndOpensAgain() = runTest {
        val a = chat()
        a.ask("What's a good name for a cat?", Privacy.ON_DEVICE_ONLY)
        a.answered("Sigge.")
        val b = chat()
        b.openLatest()
        assertEquals(listOf("What's a good name for a cat?", "Sigge."), b.lines.map { it.shown })
        assertEquals("What's a good name for a cat?", store.conversations(1).single().title)
    }

    @Test fun recalledMemoriesGoInFrontOfTheQuestionNotTheSystemPrompt() = runTest {
        brain.remember("The user's manager is Ada.")
        val c = chat()
        val r = c.ask("Who should I ask about leave?", Privacy.ON_DEVICE_ONLY)
        assertEquals(NornChat.SYSTEM, r.system)
        val sent = r.messages.last().text
        assertTrue(sent.contains("- The user's manager is Ada."))
        assertTrue(sent.endsWith("Who should I ask about leave?"))
        // What you see is only what you typed.
        assertEquals("Who should I ask about leave?", c.lines.last().shown)
    }

    @Test fun aFollowUpContinuesTheOpenModelConversation() = runTest {
        brain.remember("The user's manager is Ada.")
        val c = chat()
        val first = c.ask("Who is my manager?", Privacy.ON_DEVICE_ONLY)
        c.answered("Ada.")
        val second = c.ask("And her role?", Privacy.ON_DEVICE_ONLY)
        // The same system prompt and history as sent: the provider can reuse its conversation.
        assertTrue(ConversationReuse.canReuse(ConversationReuse.after(first, "Ada."), second))
    }

    @Test fun startingNewLeavesTheOldChatKept() = runTest {
        val c = chat()
        c.ask("First", Privacy.ON_DEVICE_ONLY)
        c.startNew()
        assertNull(c.conversationId)
        c.ask("Second", Privacy.ON_DEVICE_ONLY)
        assertEquals(listOf("Second", "First"), store.conversations(10).map { it.title })
        assertEquals(LlmRole.USER, c.lines.single().role)
    }

    @Test fun deletingTheOpenChatStartsANewOne() = runTest {
        val c = chat()
        c.ask("Gone soon", Privacy.ON_DEVICE_ONLY)
        c.delete(c.conversationId!!)
        assertTrue(c.lines.isEmpty())
        assertTrue(store.conversations(10).isEmpty())
    }

    @Test fun pinnedChatsComeFirstEvenWhenOlder() = runTest {
        val c = chat()
        c.ask("Old one", Privacy.ON_DEVICE_ONLY)
        val old = c.conversationId!!
        c.startNew()
        c.ask("New one", Privacy.ON_DEVICE_ONLY)
        c.setPinned(old, true)
        assertEquals(listOf("Old one", "New one"), store.conversations(10).map { it.title })
        c.setPinned(old, false)
        assertEquals(listOf("New one", "Old one"), store.conversations(10).map { it.title })
    }
}
