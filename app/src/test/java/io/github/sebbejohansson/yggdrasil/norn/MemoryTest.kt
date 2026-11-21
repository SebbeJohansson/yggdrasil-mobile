package io.github.sebbejohansson.yggdrasil.norn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The desktop's norn_test.go memory cases, ported, and a few more. */
class MemoryTest {
    private val t0 = Instant.parse("2026-11-21T09:00:00Z")
    private fun m(id: Long, text: String, v: FloatArray?, model: String?) = Memory(id, text, "manual", t0, v, model)

    @Test fun recall() {
        val memories = listOf(
            m(3, "c", floatArrayOf(0f, 1f), "m"),
            m(2, "b", floatArrayOf(1f, 0.1f), "m"),
            m(1, "a", floatArrayOf(1f, 0f), "other"),
        )
        assertEquals("only b is close and from the same model", listOf(2L), MemoryMath.recall(memories, floatArrayOf(1f, 0f), "m").map { it.id })
        assertEquals("without a vector the newest stand in", listOf(3L, 2L, 1L), MemoryMath.recall(memories, null, "m").map { it.id })
    }

    @Test fun namesMustComeFromTheMessage() {
        assertFalse("a copied name passed", Facts.namesAreFromMessage("Mira is the user's team lead.", "who is my team lead?"))
        assertTrue("a name from the message failed", Facts.namesAreFromMessage("The user's manager is Ada.", "my manager is ada"))
    }

    @Test fun readFacts() {
        val got = Facts.read("""{"facts":["The user ships on Fridays.","short",42,"The user lives in Borås.","The user has two cats.","The user likes tea."]}""")
        assertEquals(listOf("The user ships on Fridays.", "The user lives in Borås.", "The user has two cats."), got)
        assertTrue("not json gave facts", Facts.read("not json").isEmpty())
    }

    @Test fun readFactsInsideWordsOrAFence() {
        assertEquals(listOf("The user has a dog."), Facts.read("Sure!\n```json\n{\"facts\": [\"The user has a dog.\"]}\n```"))
        assertTrue(Facts.read("""{"facts": []}""").isEmpty())
    }

    @Test fun learnKeepsOnlyNewFactsFromTheMessage() = runTest {
        val brain = Brain(InMemoryMemoryStore(), null, { """{"facts":["The user's manager is Ada.","Mira is the user's team lead."]}""" }, Dispatchers.Unconfined)
        val learned = brain.learnFrom("my manager is Ada")
        assertEquals(listOf("The user's manager is Ada."), learned.map { it.text })
        assertEquals("extracted", learned.single().source)
        assertTrue("learned it twice", brain.learnFrom("my manager is Ada").isEmpty())
    }

    @Test fun rememberingTheSameThingTwiceIsNotAnError() = runTest {
        val brain = Brain(InMemoryMemoryStore(), null, { "" }, Dispatchers.Unconfined)
        brain.remember("The user takes the 8:12 train.")
        assertNull(brain.remember("the user takes the 8:12 train"))
        assertEquals(1, brain.memories().size)
    }

    @Test fun aBrokenModelLearnsNothing() = runTest {
        val brain = Brain(InMemoryMemoryStore(), null, { throw IllegalStateException("No model is set up.") }, Dispatchers.Unconfined)
        assertTrue(brain.learnFrom("my manager is Ada").isEmpty())
    }

    @Test fun recallByMeaningWithAnEmbedder() = runTest {
        // A toy embedder: one dimension per topic word.
        val topics = listOf("train", "cat", "tea")
        val embedder = object : Embedder {
            override val model = "toy"
            override suspend fun embed(texts: List<String>) =
                texts.map { t -> FloatArray(topics.size) { i -> if (topics[i] in t.lowercase()) 1f else 0f } }
        }
        val brain = Brain(InMemoryMemoryStore(), embedder, { "" }, Dispatchers.Unconfined)
        brain.remember("The user takes the train to work.")
        brain.remember("The user has a cat called Sigge.")
        assertEquals(listOf("The user has a cat called Sigge."), brain.recallFor("What's my cat's name?").map { it.text })
        assertTrue(brain.recallFor("Do I like tea?").isEmpty())
    }

    @Test fun reembedGivesOldMemoriesVectors() = runTest {
        val store = InMemoryMemoryStore()
        Brain(store, null, { "" }, Dispatchers.Unconfined).remember("The user likes tea.")
        val embedder = object : Embedder {
            override val model = "toy"
            override suspend fun embed(texts: List<String>) = texts.map { floatArrayOf(1f) }
        }
        Brain(store, embedder, { "" }, Dispatchers.Unconfined).reembed()
        assertEquals("toy", store.memories().single().model)
    }
}
