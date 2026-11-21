package io.github.sebbejohansson.yggdrasil.norn

import io.github.sebbejohansson.yggdrasil.core.Changes
import io.github.sebbejohansson.yggdrasil.llm.LlmMessage
import io.github.sebbejohansson.yggdrasil.llm.LlmRole
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.math.sqrt

/**
 * Norn's memory, ported from Yggdrasil's `norn/memory.go`: recall,
 * remember, and learn from what you say.
 *
 * Learning reads only your own words, never the answer. A small model states
 * things confidently that are not true, and a fact taken from its own answer
 * would be recalled into the next prompt and repeated back as something you
 * said. Recall is plain cosine similarity over every stored vector: a
 * personal assistant holds hundreds of facts, not millions.
 */
data class Memory(
    val id: Long,
    val text: String,
    /** "manual" (you said remember this) or "extracted" (learned from a message). */
    val source: String,
    val createdAt: Instant,
    val embedding: FloatArray? = null,
    /** The embedding model the vector is from; recall compares only vectors of one model. */
    val model: String? = null,
) {
    override fun equals(other: Any?) = other is Memory && other.id == id && other.text == text
    override fun hashCode() = id.hashCode()
}

interface MemoryStore {
    /** Newest first. */
    fun memories(): List<Memory>
    fun add(text: String, source: String, at: Instant, embedding: FloatArray?, model: String?): Memory
    fun setEmbedding(id: Long, embedding: FloatArray, model: String)
    fun delete(id: Long)
}

/** Turns text into vectors. [LiteRtEmbedder] on the phone; null vectors when it can't. */
interface Embedder {
    /** Which model the vectors are from. */
    val model: String
    suspend fun embed(texts: List<String>): List<FloatArray>?
}

/** Runs a short, few-shot prompt and returns the model's raw answer. */
fun interface FactModel {
    suspend fun complete(messages: List<LlmMessage>): String
}

object MemoryMath {
    /** How many memories a prompt gets at most. */
    const val RECALL_TOP = 5

    /**
     * The similarity below which a memory isn't about the question. The desktop
     * tuned 0.5 for nomic-embed-text; EmbeddingGemma's scale differs, so this
     * starts lower and may want tuning on the phone.
     */
    const val RECALL_MIN = 0.4

    /** The similarity at which a new fact says what an old one does. */
    const val DUPLICATE_MIN = 0.9

    /** The most facts kept from one message. */
    const val FACTS_PER_MESSAGE = 3

    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / sqrt(na * nb)
    }

    /**
     * The memories most like the question, best first. Without a query vector
     * (no embedding model yet, or it failed) the newest few stand in.
     */
    fun recall(memories: List<Memory>, query: FloatArray?, model: String?): List<Memory> {
        if (query == null) return memories.take(RECALL_TOP)
        return memories
            .filter { it.embedding != null && it.model == model }
            .map { it to cosine(query, it.embedding!!) }
            .filter { it.second >= RECALL_MIN }
            .sortedByDescending { it.second }
            .take(RECALL_TOP)
            .map { it.first }
    }

    private val notWordOrNumber = Regex("[^\\p{L}\\p{N}]+")

    private fun normalise(text: String) = notWordOrNumber.replace(text.lowercase(), " ").trim()

    /** Whether text is already remembered: by meaning when there are vectors, by wording when not. */
    fun isKnown(memories: List<Memory>, text: String, vector: FloatArray?, model: String?): Boolean {
        val wording = normalise(text)
        return memories.any { m ->
            normalise(m.text) == wording ||
                (vector != null && m.embedding != null && m.model == model && cosine(vector, m.embedding) >= DUPLICATE_MIN)
        }
    }
}

object Facts {
    private val SYSTEM = listOf(
        "You pick out lasting facts about the user from one message they wrote to their assistant.",
        "A lasting fact is still true next week: who they are, their role, the people they work with",
        "and how, their projects, their preferences, their recurring commitments.",
        "Not questions, not requests, not anything only about today.",
        "Write each fact as one short sentence about the user. Use only what the message says.",
        "If there are none, return an empty list.",
        "Answer with JSON only: {\"facts\": [\"...\"]}.",
    ).joinToString(" ")

    /** The system prompt for [messages]. */
    val system: String get() = SYSTEM

    /**
     * The few-shot prompt that picks facts out of a message. Two examples, one
     * each way: without the empty one a small model finds a "fact" in every message.
     */
    fun messages(message: String): List<LlmMessage> = listOf(
        LlmMessage(LlmRole.USER, "What did Mira want? She is my team lead, and we ship every Friday."),
        LlmMessage(LlmRole.ASSISTANT, """{"facts":["Mira is the user's team lead.","The user's team ships every Friday."]}"""),
        LlmMessage(LlmRole.USER, "Can you rewrite this paragraph to sound less formal?"),
        LlmMessage(LlmRole.ASSISTANT, """{"facts":[]}"""),
        LlmMessage(LlmRole.USER, message),
    )

    private val word = Regex("[\\p{L}’'-]+")

    /** Capitalised in a fact because they open it or the fact is about you. */
    private val factWords = setOf("the", "a", "an", "user", "user's", "they", "their", "he", "she", "his", "her", "it", "its")

    /**
     * Whether every name in a fact is in the message it came from. A small
     * model copies the worked example: asked "who is my team lead?" it answers
     * with the example's fact and puts Mira in charge.
     */
    fun namesAreFromMessage(fact: String, message: String): Boolean {
        val said = word.findAll(message).map { it.value.lowercase() }.toSet()
        return word.findAll(fact).all { m ->
            val w = m.value
            val lower = w.lowercase()
            !w[0].isUpperCase() || lower in factWords || lower in said
        }
    }

    private val jsonObject = Regex("""\{[\s\S]*\}""")
    private val quoted = Regex(""""((?:[^"\\]|\\.)*)"""")

    /**
     * The facts in a reply; none when the reply isn't what was asked for. A
     * small model may wrap the JSON in words or a code fence, so the first
     * {...} is read.
     */
    fun read(raw: String): List<String> {
        val obj = jsonObject.find(raw)?.value ?: return emptyList()
        val key = obj.indexOf("\"facts\"").takeIf { it >= 0 } ?: return emptyList()
        val open = obj.indexOf('[', key).takeIf { it >= 0 } ?: return emptyList()
        val close = obj.indexOf(']', open).takeIf { it >= 0 } ?: return emptyList()
        val list = obj.substring(open + 1, close)
        // Only the strings: numbers and the like are left out, as the desktop's type check does.
        return quoted.findAll(list)
            .map { it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\").trim() }
            .filter { it.codePointCount(0, it.length) in 8..200 }
            .take(MemoryMath.FACTS_PER_MESSAGE)
            .toList()
    }
}

/** What memory needs from the models, and where it keeps things. */
class Brain(
    private val store: MemoryStore,
    private val embedder: Embedder?,
    private val facts: FactModel,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Instant = Instant::now,
) {
    val changes = Changes()

    private val model: String? get() = embedder?.model

    private suspend fun vectorFor(text: String): FloatArray? = try {
        embedder?.embed(listOf(text))?.firstOrNull()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null
    }

    suspend fun memories(): List<Memory> = withContext(io) { store.memories() }

    /** The memories worth putting in front of the model for a question. */
    suspend fun recallFor(question: String): List<Memory> {
        val all = memories()
        if (all.isEmpty()) return emptyList()
        return MemoryMath.recall(all, vectorFor(question), model)
    }

    /** Stores a fact; null when it's already known. */
    suspend fun remember(text: String, source: String = "manual"): Memory? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        val vector = vectorFor(clean)
        val all = memories()
        if (MemoryMath.isKnown(all, clean, vector, model)) return null
        return withContext(io) { store.add(clean, source, now(), vector, if (vector != null) model else null) }.also { changes.bump() }
    }

    suspend fun forget(id: Long) {
        withContext(io) { store.delete(id) }
        changes.bump()
    }

    /** Extracts and stores what's new in one of your messages. Never fails: what it can't learn it leaves. */
    suspend fun learnFrom(message: String): List<Memory> {
        val raw = try {
            facts.complete(Facts.messages(message))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return emptyList()
        }
        return Facts.read(raw)
            .filter { Facts.namesAreFromMessage(it, message) }
            .mapNotNull { remember(it, "extracted") }
    }

    /** Gives every memory without a vector from the current model one, e.g. after the embedding model arrived. */
    suspend fun reembed() {
        val e = embedder ?: return
        val stale = memories().filter { it.embedding == null || it.model != e.model }
        for (batch in stale.chunked(16)) {
            val vectors = e.embed(batch.map { it.text }) ?: return
            withContext(io) { batch.zip(vectors).forEach { (m, v) -> store.setEmbedding(m.id, v, e.model) } }
        }
        if (stale.isNotEmpty()) changes.bump()
    }

    companion object {
        /** How recalled memories go in front of a question. */
        fun context(memories: List<Memory>): String =
            if (memories.isEmpty()) "" else "What you know about the user:\n" + memories.joinToString("\n") { "- ${it.text}" }
    }
}

class InMemoryMemoryStore : MemoryStore {
    private val rows = mutableListOf<Memory>()
    private var nextId = 1L
    override fun memories() = rows.sortedByDescending { it.id }
    override fun add(text: String, source: String, at: Instant, embedding: FloatArray?, model: String?) =
        Memory(nextId++, text, source, at, embedding, model).also { rows += it }
    override fun setEmbedding(id: Long, embedding: FloatArray, model: String) {
        val i = rows.indexOfFirst { it.id == id }
        if (i >= 0) rows[i] = rows[i].copy(embedding = embedding, model = model)
    }
    override fun delete(id: Long) { rows.removeAll { it.id == id } }
}
