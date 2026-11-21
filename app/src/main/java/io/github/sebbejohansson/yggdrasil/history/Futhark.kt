package io.github.sebbejohansson.yggdrasil.history

import java.text.Normalizer

/**
 * English to the Elder Futhark, the 24 runes of the oldest runestones, ported
 * from my-widget's `utils/futhark.ts`. Written the way the carvers wrote: by
 * sound, one rune a sound, so a few letter pairs become one rune (th ᚦ, ng ᛜ,
 * ch and ck ᚲ, sh ᛊ, ph ᚠ, wh ᚹ), soft c is s, x is ks and a gh inside a word
 * is silent. A sound written twice in a row is carved once. Words are parted
 * by ᛫ and sentences by ᛬. Numbers are spelled out, marks dropped, ð and þ are ᚦ.
 */
object Futhark {
    private const val F = "ᚠ"; private const val U = "ᚢ"; private const val TH = "ᚦ"; private const val A = "ᚨ"
    private const val R = "ᚱ"; private const val K = "ᚲ"; private const val G = "ᚷ"; private const val W = "ᚹ"
    private const val H = "ᚺ"; private const val N = "ᚾ"; private const val I = "ᛁ"; private const val J = "ᛃ"
    private const val EI = "ᛇ"; private const val P = "ᛈ"; private const val Z = "ᛉ"; private const val S = "ᛊ"
    private const val T = "ᛏ"; private const val B = "ᛒ"; private const val E = "ᛖ"; private const val M = "ᛗ"
    private const val L = "ᛚ"; private const val NG = "ᛜ"; private const val O = "ᛟ"; private const val D = "ᛞ"

    val RUNES = listOf(F, U, TH, A, R, K, G, W, H, N, I, J, EI, P, Z, S, T, B, E, M, L, NG, O, D)

    /** Between words. */
    const val WORD_MARK = "᛫"

    /** Between sentences, and at a comma. */
    const val SENTENCE_MARK = "᛬"

    private val PAIRS = mapOf("th" to TH, "ng" to NG, "ch" to K, "ck" to K, "sh" to S, "ph" to F, "wh" to W, "qu" to K + W)
    private val LETTERS = mapOf(
        'a' to A, 'b' to B, 'd' to D, 'e' to E, 'f' to F, 'g' to G, 'h' to H, 'i' to I, 'j' to J,
        'k' to K, 'l' to L, 'm' to M, 'n' to N, 'o' to O, 'p' to P, 'q' to K, 'r' to R, 's' to S,
        't' to T, 'u' to U, 'v' to F, 'w' to W, 'x' to K + S, 'z' to Z,
    )
    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u', 'y')
    private val ONES = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    /** A whole number in English words: 105 is "one hundred five". Past a million, digit by digit. */
    fun numberWords(n: Long): String = when {
        n < 0 -> ""
        n >= 1_000_000 -> n.toString().map { ONES[it - '0'] }.joinToString(" ")
        n < 20 -> ONES[n.toInt()]
        n < 100 -> TENS[(n / 10).toInt()] + if (n % 10 != 0L) " " + ONES[(n % 10).toInt()] else ""
        n < 1000 -> ONES[(n / 100).toInt()] + " hundred" + if (n % 100 != 0L) " " + numberWords(n % 100) else ""
        else -> numberWords(n / 1000) + " thousand" + if (n % 1000 != 0L) " " + numberWords(n % 1000) else ""
    }

    /** Plain lower-case letters: marks dropped, ð and þ as th, æ as ae, ø as o, ß as ss. */
    private fun plain(text: String): String {
        val replaced = text.lowercase().replace(Regex("[ðþ]"), "th").replace("æ", "ae").replace("ø", "o").replace("ß", "ss")
        return Normalizer.normalize(replaced, Normalizer.Form.NFD).replace(Regex("[̀-ͯ]"), "")
    }

    /** One word (lower-case a to z only) in runes. */
    fun word(word: String): String {
        val runes = mutableListOf<String>()
        var i = 0
        while (i < word.length) {
            val letter = word[i]
            val next = word.getOrNull(i + 1)
            val pair = if (next != null) PAIRS["$letter$next"] else null
            when {
                letter == 'g' && next == 'h' -> {
                    // gh is a g at the start of a word (ghost) and silent after it (caught, night).
                    if (i == 0) runes += G
                    i++
                }
                pair != null -> {
                    pair.codePoints().forEach { runes += String(Character.toChars(it)) }
                    i++
                }
                letter == 'c' -> runes += if (next == 'e' || next == 'i' || next == 'y') S else K
                // A y before a vowel is a j (yes, year); anywhere else it is an i (my).
                letter == 'y' -> runes += if (next != null && next in VOWELS) J else I
                else -> LETTERS[letter]?.codePoints()?.forEach { runes += String(Character.toChars(it)) }
            }
            i++
        }
        // A sound twice in a row is carved once.
        return runes.filterIndexed { index, r -> index == 0 || r != runes[index - 1] }.joinToString("")
    }

    /** Any text in Elder Futhark: words parted by ᛫, sentences by ᛬. */
    fun of(text: String): String {
        val spelled = Regex("\\d+").replace(plain(text)) { " " + numberWords(it.value.toLong()) + " " }
        val parts = mutableListOf<String>()
        var pendingStop = false
        for (m in Regex("[a-z]+|[.,;:!?]").findAll(spelled)) {
            val token = m.value
            if (token.length == 1 && token[0] in ".,;:!?") {
                if (parts.isNotEmpty()) pendingStop = true
                continue
            }
            val w = word(token)
            if (w.isEmpty()) continue
            if (parts.isNotEmpty()) parts += if (pendingStop) SENTENCE_MARK else WORD_MARK
            parts += w
            pendingStop = false
        }
        return parts.joinToString("")
    }

    /** The runes of a carving, one string each. */
    fun split(runes: String): List<String> = runes.codePoints().toArray().map { String(Character.toChars(it)) }

    /**
     * Each rune as strokes, so it's carved the same on every phone whatever its
     * fonts: lines in a box 6 wide and 10 high, y down, drawn with a round pen.
     * The two marks are dots.
     */
    val STROKES = mapOf(
        F to "M1 0V10M1 3L5 0M1 6L5 3", U to "M1 10V0L5 4V10", TH to "M1 0V10M1 3L4 5L1 7", A to "M1 0V10M1 0L5 3M1 4L5 7",
        R to "M1 10V0L4.5 2.5L1 5L4.5 10", K to "M4.5 2L1.5 5L4.5 8", G to "M0.5 1L5.5 9M5.5 1L0.5 9", W to "M1 10V0L4.5 2.5L1 5",
        H to "M1 0V10M5 0V10M1 3L5 6", N to "M3 0V10M1 3.5L5 6", I to "M3 0V10", J to "M2.5 1.5L0.5 4L2.5 6.5M3.5 3.5L5.5 6L3.5 8.5",
        EI to "M3 0V10M3 0L5 1.5M3 10L1 8.5", P to "M1 0V10M1 0L4.5 2.5V4M1 10L4.5 7.5V6", Z to "M3 0V10M3 4L0.5 0.5M3 4L5.5 0.5",
        S to "M4.5 0L1 4L4.5 6L1 10", T to "M3 0V10M3 0L0.5 3M3 0L5.5 3", B to "M1 0V10M1 0L4.5 2.5L1 5L4.5 7.5L1 10",
        E to "M1 10V0L3 3L5 0V10", M to "M1 10V0M5 10V0M1 0L5 4M5 0L1 4", L to "M1.5 10V0L5 3", NG to "M3 2L5 5L3 8L1 5Z",
        O to "M3 0L5.5 4L0.5 10M3 0L0.5 4L5.5 10", D to "M1 0V10M5 0V10M1 0L5 10M5 0L1 10",
        WORD_MARK to "M1.5 5h0.01", SENTENCE_MARK to "M1.5 3h0.01M1.5 7h0.01",
    )

    /** How far a rune takes the carver along, in the strokes' units: a mark less. */
    fun advance(rune: String) = if (rune == WORD_MARK || rune == SENTENCE_MARK) 3.5 else 7.5
}
