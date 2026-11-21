package io.github.sebbejohansson.yggdrasil.llm

import io.github.sebbejohansson.yggdrasil.norn.Person
import io.github.sebbejohansson.yggdrasil.norn.PseudoMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.fold

/** Finds people's names in a text; throws when it can't look (no local model). */
fun interface NameFinder {
    suspend fun names(text: String): List<String>
}

class NamesNotSentException(message: String) : IllegalStateException(message)

/**
 * Wraps a provider that sends text off the phone (Claude) with Yggdrasil's
 * rule: names stay on the machine. Before anything goes, people's names and
 * email addresses are swapped for placeholders (Person 1, Person 2…), from
 * the people you know, what the local model finds, and the shape of an
 * address; the answer gets the names back. When the local model can't look
 * for names, or a known name is still there after the swap, nothing is sent.
 * [namesOff] is the desktop's send-names switch: for a while, sent as they are.
 */
class NameGuard(
    private val inner: LlmProvider,
    private val finder: NameFinder,
    private val known: () -> List<Person>,
    private val namesOff: () -> Boolean,
) : LlmProvider by inner {

    override fun stream(request: LlmRequest): Flow<String> = flow {
        if (namesOff()) {
            emitAll(inner.stream(request))
            return@flow
        }
        val people = known()
        val map = PseudoMap()
        fun swap(text: String, found: List<String>): String {
            val out = map.pseudonymize(text, people, found)
            if (map.leaks(out, people).isNotEmpty()) throw NamesNotSentException("Names were still there after the swap, so nothing was sent.")
            return out
        }
        suspend fun find(text: String): List<String> = try {
            finder.names(text)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw NamesNotSentException("The local model couldn't look for names, so nothing was sent. (${e.message})")
        }

        val messages = request.messages.map { m -> m.copy(text = swap(m.text, find(m.text))) }
        val system = request.system?.let { swap(it, find(it)) }
        val legend = map.legend(messages.joinToString("\n") { it.text } + "\n" + system.orEmpty())
        val swapped = request.copy(
            messages = messages,
            system = listOfNotNull(system, legend.takeIf { it.isNotEmpty() }).joinToString("\n\n").ifEmpty { null },
        )
        // Placeholders can be cut between two pieces of a stream, so the answer is put back together first.
        val answer = inner.stream(swapped).fold(StringBuilder()) { acc, d -> acc.append(d) }.toString()
        emit(map.restore(answer))
    }

    private suspend fun <T> kotlinx.coroutines.flow.FlowCollector<T>.emitAll(f: Flow<T>) = f.collect { emit(it) }
}

object NameAnswers {
    /** Names the local model tends to take for people's. */
    private val notPeople = setOf(
        "claude", "norn", "huginn", "gjallar", "dellingr", "hermóðr", "hermodr", "veðrfölnir", "vedrfolnir", "yggdrasil",
        "gemma", "teams", "outlook", "slack", "gmail", "whatsapp", "messenger", "signal", "android", "pixel", "google",
    )

    const val SYSTEM = "List every person's name in the text, exactly as it is written there: full names, first names alone, " +
        "surnames alone, nicknames, in any language. Leave out companies, products, apps, places, teams and placeholders like \"Person 3\". " +
        "Answer with JSON: {\"names\": [...]}, an empty list when there are none."

    private val quoted = Regex(""""((?:[^"\\]|\\.)*)"""")

    /** The names in the local model's answer that are really in [text]. Throws when the answer isn't the asked-for JSON. */
    fun read(raw: String, text: String): List<String> {
        val obj = Regex("""\{[\s\S]*\}""").find(raw)?.value ?: throw IllegalStateException("the list of names wasn't JSON")
        val open = obj.indexOf('[', obj.indexOf("\"names\"").takeIf { it >= 0 } ?: throw IllegalStateException("no names list"))
        val close = obj.indexOf(']', open)
        if (open < 0 || close < 0) throw IllegalStateException("no names list")
        return quoted.findAll(obj.substring(open + 1, close)).map { it.groupValues[1].trim() }
            .filter { it.codePointCount(0, it.length) >= 2 && it.lowercase() !in notPeople && text.contains(it) }
            .distinct().toList()
    }

    /** Cuts text into pieces of at most [size] characters, at line ends where it can. */
    fun chunks(text: String, size: Int = 4_000): List<String> {
        if (text.length <= size) return listOf(text)
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > size) {
            val cut = rest.lastIndexOf('\n', size).takeIf { it > size / 2 } ?: size
            out += rest.substring(0, cut)
            rest = rest.substring(cut)
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}
