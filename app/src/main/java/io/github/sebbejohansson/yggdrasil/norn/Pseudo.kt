package io.github.sebbejohansson.yggdrasil.norn

/**
 * Swaps people's names and email addresses for placeholders (Person 1,
 * Person 2…) before text leaves the phone, and swaps them back in what comes
 * back. Ported from Yggdrasil's `internal/pseudo/pseudo.go`, with its tests.
 * The placeholders stay the same for a person across calls that share a
 * [PseudoMap], so a conversation can go on over several turns.
 *
 * Names are found three ways: people already known (People who matter),
 * names found some other way (the local model), and email addresses by
 * their shape. [PseudoMap.leaks] is the last check before sending: anything
 * known that is still in the text.
 */
data class Person(
    val name: String,
    /** The other names they go by ("Anna B."): one placeholder covers them all. */
    val aliases: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    /** What they are to you, told to the model beside their placeholder: "your manager". */
    val role: String = "",
    /** Where they're known from: "people who matter", "local model", "email address". */
    val source: String = "",
) {
    /** Every name the person goes by, their own first. */
    fun names(): List<String> = (listOf(name) + aliases).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

/** One person's placeholder. */
data class PseudoEntry(
    val token: String,
    /** What the placeholder is turned back into. */
    val name: String,
    /** The names and addresses that become the placeholder. */
    val variants: MutableList<String> = mutableListOf(),
    var role: String = "",
    var found: String = "",
)

object Pseudo {
    const val TOKEN_PREFIX = "Person "

    val emailShape = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)*\.[A-Za-z]{2,}""")
    val tokenShape = Regex("""\bperson (\d+)""", RegexOption.IGNORE_CASE)

    /**
     * Words that are also first names: alone they're left alone, since
     * swapping every "May" or "Per" would garble the text.
     */
    private val ordinary = setOf(
        "per", "max", "may", "will", "mark", "bill", "rose", "june", "april", "august", "sol", "dag", "von",
        "van", "de", "del", "la", "le", "the", "and",
    )

    /**
     * The ways a name is written: the whole name in any case, and each part
     * of it alone, as written, when it's long enough and not a common word.
     */
    fun variants(name0: String): Pair<String, List<String>> {
        val name = name0.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        if (name.isEmpty() || '@' in name) return name to emptyList()
        val parts = mutableListOf<String>()
        for (raw in name.split(" ")) {
            val part = raw.trim('.', ',', ';', ':', '(', ')', '"', '\'')
            if (part.codePointCount(0, part.length) < 3 || part.lowercase() in ordinary || !part[0].isUpperCase()) continue
            if (part !in parts) parts += part
        }
        if (parts.size == 1 && parts[0].equals(name, ignoreCase = true)) return name to emptyList()
        return name to parts
    }

    private fun isWord(c: Char) = c.isLetterOrDigit()

    /**
     * Every place [variant] is written in [text], standing alone (a
     * possessive s after it is fine: "Annas", "Anna's").
     */
    fun find(text: String, variant: String, foldCase: Boolean): List<IntRange> {
        if (variant.isEmpty()) return emptyList()
        val options = if (foldCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val re = Regex(Regex.escape(variant), options)
        val found = mutableListOf<IntRange>()
        var offset = 0
        while (offset < text.length) {
            val m = re.find(text, offset) ?: break
            val start = m.range.first
            val end = m.range.last + 1
            val before = if (start > 0) text[start - 1] else null
            var rest = end
            for (suffix in listOf("'s", "’s", "s")) {
                if (text.startsWith(suffix, rest)) {
                    val after = text.getOrNull(rest + suffix.length)
                    if (after == null || !isWord(after)) {
                        rest += suffix.length
                        break
                    }
                }
            }
            val after = text.getOrNull(rest)
            if ((before == null || !isWord(before)) && (after == null || !isWord(after)) && before != '@' && after != '@') {
                found += start until end
                offset = end
                continue
            }
            offset = start + 1
        }
        return found
    }

    /** Whether [token] is in [text], not as the start of a longer one (Person 1 in Person 12). */
    fun tokenIn(text: String, token: String): Boolean {
        var offset = 0
        val lower = text.lowercase()
        val t = token.lowercase()
        while (true) {
            val at = lower.indexOf(t, offset)
            if (at < 0) return false
            val end = at + t.length
            if (end == text.length || !text[end].isDigit()) return true
            offset = end
        }
    }
}

/** The placeholders given out so far. */
class PseudoMap {
    val entries = mutableListOf<PseudoEntry>()
    private var next = 0

    private fun entryFor(variant: String) = entries.indexOfFirst { e -> e.variants.any { it.equals(variant, ignoreCase = true) } }

    /** Gives a person a placeholder, or the one they have, and records every way they're written. */
    private fun add(p: Person): Int {
        val variants = mutableListOf<String>()
        val wholes = mutableListOf<String>()
        for (written in p.names()) {
            val (whole, parts) = Pseudo.variants(written)
            if (whole.isNotEmpty()) {
                variants += whole
                wholes += whole
            }
            variants += parts
        }
        p.emails.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.forEach { variants += it }
        var at = -1
        for (v in variants) {
            // A first name alone doesn't tie two people together.
            val i = entryFor(v)
            if (i >= 0 && (v in wholes || '@' in v)) {
                at = i
                break
            }
        }
        if (at < 0) {
            next++
            val name = wholes.firstOrNull() ?: p.emails.firstOrNull().orEmpty()
            entries += PseudoEntry(Pseudo.TOKEN_PREFIX + next, name, role = p.role, found = p.source)
            at = entries.size - 1
        }
        val e = entries[at]
        if (e.role.isEmpty()) e.role = p.role
        if (e.found.isEmpty()) e.found = p.source
        for (v in variants) if (e.variants.none { it.equals(v, ignoreCase = true) }) e.variants += v
        return at
    }

    /** Swaps every known person, every name in [found], and every email address in [text] for a placeholder. */
    fun pseudonymize(text: String, known: List<Person>, found: List<String> = emptyList()): String {
        for (m in Pseudo.emailShape.findAll(text)) {
            val email = m.value.lowercase()
            if (entryFor(email) >= 0) continue
            add(known.firstOrNull { k -> k.emails.any { it.equals(email, ignoreCase = true) } } ?: Person("", emails = listOf(email), source = "email address"))
        }
        for (k in known) {
            for (name in k.names()) {
                val (whole, parts) = Pseudo.variants(name)
                if (Pseudo.find(text, whole, true).isNotEmpty() || parts.any { Pseudo.find(text, it, false).isNotEmpty() }) {
                    add(k)
                    break
                }
            }
        }
        for (name0 in found) {
            val name = name0.trim()
            if (name.isNotEmpty() && !Pseudo.tokenShape.containsMatchIn(name) && Pseudo.find(text, name, true).isNotEmpty()) {
                add(Person(name, source = "local model"))
            }
        }
        return swap(text)
    }

    /** Puts the placeholders in, longest variant first so a full name wins over the first name inside it. */
    private fun swap(text: String): String {
        data class V(val text: String, val token: String, val fold: Boolean)
        val all = entries.flatMap { e ->
            val (whole, _) = Pseudo.variants(e.name)
            e.variants.map { v -> V(v, e.token, v.equals(whole, ignoreCase = true) || '@' in v || ' ' in v) }
        }.sortedByDescending { it.text.length }
        val spans = mutableListOf<Pair<IntRange, String>>()
        for (v in all) {
            for (r in Pseudo.find(text, v.text, v.fold)) {
                if (spans.none { (s, _) -> r.first <= s.last && s.first <= r.last }) spans += r to v.token
            }
        }
        val out = StringBuilder()
        var last = 0
        for ((r, token) in spans.sortedBy { it.first.first }) {
            out.append(text, last, r.first).append(token)
            last = r.last + 1
        }
        return out.append(text.substring(last)).toString()
    }

    /** Turns the placeholders in [text] back into the names they stand for. One never given out is left as it is. */
    fun restore(text: String): String {
        if (entries.isEmpty()) return text
        val byToken = entries.associate { it.token.lowercase() to it.name }
        return Pseudo.tokenShape.replace(text) { m -> byToken[m.value.lowercase()] ?: m.value }
    }

    /**
     * What in [text] shouldn't be there: an email address, or the whole name
     * or an address of anyone known or given a placeholder, in any case.
     */
    fun leaks(text: String, known: List<Person>): List<String> {
        val leaks = linkedSetOf<String>()
        Pseudo.emailShape.findAll(text).forEach { leaks += it.value }
        val lower = text.lowercase()
        fun check(name: String, emails: List<String>) {
            val (whole, _) = Pseudo.variants(name)
            if (' ' in whole && Pseudo.find(text, whole, true).isNotEmpty()) leaks += whole
            emails.filter { it.isNotEmpty() && lower.contains(it.lowercase()) }.forEach { leaks += it }
        }
        for (k in known) {
            check(k.name, k.emails)
            k.aliases.forEach { check(it, emptyList()) }
        }
        entries.forEach { check(it.name, emptyList()) }
        return leaks.toList()
    }

    /** Tells the model what the placeholders in [text] are, and how to treat them; empty when there are none. */
    fun legend(text: String): String {
        val lines = entries.filter { Pseudo.tokenIn(text, it.token) }.map { e -> "- " + e.token + if (e.role.isNotEmpty()) ": " + e.role else "" }
        if (lines.isEmpty()) return ""
        return "Names and email addresses in this message are replaced with placeholders (Person 1, Person 2…) to keep them private. " +
            "Write the placeholders exactly as they are when you mean those people; do not guess who they are or ask.\n" + lines.joinToString("\n")
    }

    /** How many people, never who: for logs. */
    override fun toString() = "${entries.size} placeholders"
}
