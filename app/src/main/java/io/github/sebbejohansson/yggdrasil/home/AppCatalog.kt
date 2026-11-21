package io.github.sebbejohansson.yggdrasil.home

import java.text.Normalizer
import java.util.Locale

/**
 * One app you can open from the home screen. [key] names it across restarts:
 * the activity, and the profile it's in (work apps have their own).
 */
data class AppEntry(
    val key: String,
    val label: String,
    val packageName: String,
    val activity: String,
    /** The profile's serial number; 0 for this one. */
    val profile: Long = 0,
) {
    val work: Boolean get() = profile != 0L

    companion object {
        fun keyOf(packageName: String, activity: String, profile: Long) = "$packageName/$activity#$profile"
    }
}

/** The pure part of the app list: order, search, letters and pins. */
object AppCatalog {
    private val marks = Regex("\\p{Mn}+")
    private val splitter = Regex("[^\\p{L}\\p{N}]+|(?<=\\p{Ll})(?=\\p{Lu})")

    /** Lower case, without accents: "Söderberg" finds "soderberg" and the other way. */
    fun fold(text: String): String =
        marks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    private fun words(label: String) = splitter.split(label).filter { it.isNotEmpty() }.map(::fold)

    /** A-Z by name, ties by profile so a work copy follows the personal one. */
    fun sorted(apps: List<AppEntry>): List<AppEntry> =
        apps.sortedWith(compareBy<AppEntry> { fold(it.label) }.thenBy { it.profile }.thenBy { it.key })

    /**
     * The apps that match, best first: the name starts with it, then a word
     * starts with it, then the initials ("gm" for Google Maps), then anywhere
     * in the name.
     */
    fun search(apps: List<AppEntry>, query: String): List<AppEntry> {
        val q = fold(query.trim())
        if (q.isEmpty()) return sorted(apps)
        val bare = q.replace(" ", "")
        return sorted(apps).mapNotNull { app ->
            val name = fold(app.label)
            val ws = words(app.label)
            val rank = when {
                name.startsWith(q) -> 0
                ws.any { it.startsWith(q) } -> 1
                ws.size > 1 && ws.joinToString("") { it.take(1) }.startsWith(bare) -> 2
                name.contains(q) || name.replace(" ", "").contains(bare) -> 3
                else -> null
            }
            rank?.let { it to app }
        }.sortedBy { it.first }.map { it.second }
    }

    /** The letter an app is listed under; digits and symbols under "#". */
    fun letter(app: AppEntry): String {
        val c = fold(app.label).firstOrNull() ?: return "#"
        return if (c.isLetter()) c.uppercase() else "#"
    }

    /** The apps under their first letters, in order. */
    fun sections(apps: List<AppEntry>): List<Pair<String, List<AppEntry>>> =
        sorted(apps).groupBy(::letter).toList().sortedBy { (l, _) -> if (l == "#") "￿" else l }

    /** The pinned apps that are still installed, in the order you pinned them. */
    fun pinned(apps: List<AppEntry>, pins: List<String>): List<AppEntry> {
        val byKey = apps.associateBy { it.key }
        return pins.mapNotNull { byKey[it] }
    }

    fun pin(pins: List<String>, key: String): List<String> = if (key in pins) pins else pins + key

    fun unpin(pins: List<String>, key: String): List<String> = pins - key

    /** Moves a pin one place earlier (-1) or later (+1). */
    fun move(pins: List<String>, key: String, by: Int): List<String> {
        val i = pins.indexOf(key)
        val j = i + by
        if (i < 0 || j !in pins.indices) return pins
        return pins.toMutableList().apply { add(j, removeAt(i)) }
    }
}
