package io.github.sebbejohansson.yggdrasil.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationReuseTest {
    private fun u(t: String) = LlmMessage(LlmRole.USER, t)
    private fun a(t: String) = LlmMessage(LlmRole.ASSISTANT, t)

    private val first = LlmRequest(listOf(u("hi")), system = "sys")
    private val seen = ConversationReuse.after(first, "hello")

    @Test fun reusesWhenOneUserMessageIsAdded() {
        assertTrue(ConversationReuse.canReuse(seen, LlmRequest(listOf(u("hi"), a("hello"), u("how are you")), system = "sys")))
    }

    @Test fun notWithoutAnEarlierConversation() {
        assertFalse(ConversationReuse.canReuse(null, first))
    }

    @Test fun notWhenTheSystemPromptChanged() {
        assertFalse(ConversationReuse.canReuse(seen, LlmRequest(listOf(u("hi"), a("hello"), u("x")), system = "other")))
    }

    @Test fun notWhenTheHistoryWasEdited() {
        assertFalse(ConversationReuse.canReuse(seen, LlmRequest(listOf(u("hi"), a("HELLO"), u("x")), system = "sys")))
    }

    @Test fun notWhenTheChatStartedOver() {
        assertFalse(ConversationReuse.canReuse(seen, LlmRequest(listOf(u("new chat")), system = "sys")))
    }
}
