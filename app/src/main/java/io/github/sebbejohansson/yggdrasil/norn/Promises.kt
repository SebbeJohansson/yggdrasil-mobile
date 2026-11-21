package io.github.sebbejohansson.yggdrasil.norn

import io.github.sebbejohansson.yggdrasil.core.Changes
import io.github.sebbejohansson.yggdrasil.hermodr.Hermodr
import io.github.sebbejohansson.yggdrasil.hermodr.MessageRequest
import io.github.sebbejohansson.yggdrasil.hermodr.Priority
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The promise ledger, ported from Yggdrasil's `norn/promises.go`: what you
 * said you'd do for someone ("I'll send it Friday", "jag kollar med
 * juristen"), so none is forgotten.
 *
 * On the phone, promises come from what you write to Yggdrasil, from text you
 * share to Ygg (a mail or chat message you sent), or by hand. The local model
 * reads them: your words never leave the phone. It returns the words for
 * when; [PromiseText.resolveDue] turns those into a day. You're reminded once,
 * on the working day before (or on the day, if made too late for that).
 */
enum class PromiseState { OPEN, DONE, DROPPED }

data class Promise(
    val id: Long,
    val what: String,
    /** Who it was promised to; empty when unclear. */
    val to: String,
    /** The day; null when no day was said. */
    val due: LocalDate?,
    val state: PromiseState,
    /** Where it was found: chat, shared or you (added by hand). */
    val source: String,
    /** The words it was found in, cut short, to see what was meant. */
    val said: String?,
    val createdAt: Instant,
    val changedAt: Instant,
    val remindedAt: Instant? = null,
)

/** A promise as the model reads it: the words for when, not yet a day. */
data class Extracted(val what: String, val to: String, val `when`: String)

interface PromiseStore {
    /** Newest first. */
    fun all(): List<Promise>
    fun get(id: Long): Promise?

    /** The new id, or null when the same promise from the same words is already kept. */
    fun add(p: Promise, sourceKey: String, norm: String): Long?
    fun update(p: Promise)
    fun deleteClosedBefore(t: Instant)
}

class InMemoryPromiseStore : PromiseStore {
    private val rows = linkedMapOf<Long, Promise>()
    private val keys = mutableSetOf<String>()
    private var next = 1L
    override fun all() = rows.values.sortedByDescending { it.id }
    override fun get(id: Long) = rows[id]
    override fun add(p: Promise, sourceKey: String, norm: String): Long? {
        if (!keys.add("$sourceKey|$norm")) return null
        val id = next++
        rows[id] = p.copy(id = id)
        return id
    }
    override fun update(p: Promise) { rows[p.id] = p }
    override fun deleteClosedBefore(t: Instant) {
        rows.values.removeAll { it.state != PromiseState.OPEN && it.changedAt.isBefore(t) }
    }
}

/** The pure part: the prompt, the answer, and the days. */
object PromiseText {
    /** The most promises taken from one message. */
    const val MAX_PER_MESSAGE = 5

    /** What a message that promises something nearly always holds, so most never reach the model. */
    private val MARKERS = listOf(
        "i'll", "i’ll", "i will", "i'm going to", "i am going to", "i can ", "let me", "i promise", "will send", "will check", "will get back",
        "will follow", "will share", "will look", "will update", "will fix", "will do", "will have", "will write", "by tomorrow", "by friday",
        "by monday", "i promised", "i told", "promised", "jag ska", "ska jag", "jag kan", "jag kollar", "kollar ", "fixar", "skickar",
        "återkommer", "återkopplar", "hör av mig", "jag tar", "tar jag", "jag lovar", "jag lovade", "lovade", "jag ordnar", "ordnar", "jag hör",
        "jag fixar", "jag skickar",
    )

    fun mayPromise(text: String): Boolean {
        val lower = " " + text.lowercase() + " "
        return MARKERS.any { lower.contains(it) }
    }

    fun system(chat: Boolean): String = buildString {
        if (chat) {
            append("You read what the owner wrote to their assistant and list the promises the owner says they made to someone: ")
            append("things the owner will do for someone (\"I told Kim I'll send the deck Friday\", \"jag lovade Anna att kolla med juristen\").\n")
            append("Only the owner's own promises to a person. Questions to the assistant, requests to the assistant, and what others will do are not promises.\n")
        } else {
            append("You read a message the owner sent and list the promises in it: things the owner says they will do for someone ")
            append("(\"I'll send it Friday\", \"I'll check with legal\", \"jag återkommer i morgon\").\n")
            append("Only the owner's own words in this message. Quoted earlier messages, what others will do, and requests to others are not promises.\n")
        }
        append("Not a promise: thanks, questions, opinions, something already done, or a vague pleasantry (\"let's talk sometime\").\n")
        append("what: what the owner will do, as a short imperative in the message's language, at most 100 characters (\"Send the Q3 deck\", \"Kolla med juristen\").\n")
        append("to: who it is promised to, as the text names them; empty when unclear.\n")
        append("when: the exact words the text uses for when it will be done (\"Friday\", \"tomorrow\", \"by end of week\", \"på fredag\", \"24/11\"); ")
        append("empty when no time is given.\n")
        append("No promises: an empty list. Answer with JSON only: {\"promises\":[{\"what\":\"…\",\"to\":\"…\",\"when\":\"…\"}]}")
    }

    fun user(text: String, sent: Instant, zone: ZoneId): String =
        "Sent: " + sent.atZone(zone).format(DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd", Locale.ENGLISH)) + "\n\nText:\n" + text.take(4000)

    fun norm(what: String) = what.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** The model's answer as promises: blanks and repeats dropped, at most five. Empty when it isn't what was asked for. */
    fun parse(raw: String): List<Extracted> {
        val from = raw.indexOf('{')
        val to = raw.lastIndexOf('}')
        if (from < 0 || to <= from) return emptyList()
        val list = runCatching { JSONObject(raw.substring(from, to + 1)).optJSONArray("promises") }.getOrNull() ?: return emptyList()
        val seen = mutableSetOf<String>()
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val what = o.optString("what").trim().trimEnd('.').take(160)
            val key = norm(what)
            if (key.isEmpty() || !seen.add(key)) null else Extracted(what, o.optString("to").trim().take(120), o.optString("when").trim().take(60))
        }.take(MAX_PER_MESSAGE)
    }

    private val weekdays = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY, "måndag" to DayOfWeek.MONDAY, "måndagen" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY, "tisdag" to DayOfWeek.TUESDAY, "tisdagen" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY, "onsdag" to DayOfWeek.WEDNESDAY, "onsdagen" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY, "torsdag" to DayOfWeek.THURSDAY,
        "torsdagen" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY, "fredag" to DayOfWeek.FRIDAY,
        "fredagen" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY, "lördag" to DayOfWeek.SATURDAY,
        "lördagen" to DayOfWeek.SATURDAY, "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY, "söndag" to DayOfWeek.SUNDAY,
        "söndagen" to DayOfWeek.SUNDAY,
    )
    private val months = mapOf(
        "jan" to 1, "january" to 1, "januari" to 1, "feb" to 2, "february" to 2, "februari" to 2, "mar" to 3, "march" to 3, "mars" to 3,
        "apr" to 4, "april" to 4, "may" to 5, "maj" to 5, "jun" to 6, "june" to 6, "juni" to 6, "jul" to 7, "july" to 7, "juli" to 7,
        "aug" to 8, "august" to 8, "augusti" to 8, "sep" to 9, "sept" to 9, "september" to 9, "oct" to 10, "october" to 10, "okt" to 10,
        "oktober" to 10, "nov" to 11, "november" to 11, "dec" to 12, "december" to 12,
    )
    private val isoDay = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")
    private val slashDay = Regex("""\b(\d{1,2})/(\d{1,2})\b""")
    private val ordinal = Regex("""^(\d{1,2})(st|nd|rd|th|e|:e|:a)?$""")
    private val notWord = Regex("""[^\p{L}\p{N}/:-]+""")

    private fun has(words: String, vararg phrases: String) = phrases.any { words.contains(" $it ") }

    /** The Friday of the day's working week; on a weekend, the next one. */
    private fun fridayOf(d: LocalDate): LocalDate = when (d.dayOfWeek) {
        DayOfWeek.SATURDAY -> d.plusDays(6)
        DayOfWeek.SUNDAY -> d.plusDays(5)
        else -> d.plusDays((DayOfWeek.FRIDAY.value - d.dayOfWeek.value).toLong())
    }

    private fun mondayOf(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value - 1).toLong())

    /**
     * The day [words] name, said on [sent]; null when they name none ("soon",
     * "next week"). A weekday is the next one after the day it was said
     * ("Friday" said on a Friday is a week later); "next Friday" is the
     * Friday of the next week.
     */
    fun resolveDue(words: String, sent: LocalDate): LocalDate? {
        val text = words.lowercase().trim()
        if (text.isEmpty()) return null
        isoDay.find(text)?.let { m -> runCatching { return LocalDate.parse(m.value) } }
        val w = " " + notWord.replace(text, " ").trim() + " "
        when {
            has(w, "day after tomorrow", "övermorgon", "i övermorgon") -> return sent.plusDays(2)
            has(w, "tomorrow", "tmrw", "imorgon", "i morgon", "imorn", "i morn") -> return sent.plusDays(1)
            has(w, "today", "tonight", "eod", "end of day", "end of the day", "idag", "i dag", "ikväll", "i kväll", "innan dagens slut",
                "senare idag", "later today") -> return sent
            has(w, "end of next week", "slutet av nästa vecka") -> return fridayOf(sent).plusDays(7)
            has(w, "end of week", "end of the week", "this week", "before the weekend", "i veckan", "slutet av veckan", "innan helgen",
                "under veckan") -> return fridayOf(sent)
        }
        val tokens = w.trim().split(" ").filter { it.isNotEmpty() }
        tokens.forEachIndexed { i, t ->
            val day = weekdays[t] ?: return@forEachIndexed
            if (i > 0 && (tokens[i - 1] == "next" || tokens[i - 1] == "nästa")) {
                return mondayOf(sent).plusDays(7L + day.value - 1)
            }
            var ahead = (day.value - sent.dayOfWeek.value + 7) % 7
            if (ahead == 0) ahead = 7
            return sent.plusDays(ahead.toLong())
        }
        tokens.forEachIndexed { i, t ->
            val month = months[t] ?: return@forEachIndexed
            val number = when {
                i > 0 && ordinal.matches(tokens[i - 1]) -> tokens[i - 1]
                i > 1 && tokens[i - 1] == "of" && ordinal.matches(tokens[i - 2]) -> tokens[i - 2]
                i + 1 < tokens.size && ordinal.matches(tokens[i + 1]) -> tokens[i + 1]
                else -> return@forEachIndexed
            }
            dateFrom(sent, month, ordinal.find(number)!!.groupValues[1])?.let { return it }
        }
        slashDay.find(text)?.let { m ->
            val first = m.groupValues[1].toInt()
            val second = m.groupValues[2].toInt()
            if (second in 1..12) dateFrom(sent, second, m.groupValues[1])?.let { return it }
            if (first in 1..12) return dateFrom(sent, first, m.groupValues[2])
        }
        return null
    }

    /** Day [number] of [month] on or after [from]: this year, or next when it has passed. */
    private fun dateFrom(from: LocalDate, month: Int, number: String): LocalDate? {
        val n = number.toIntOrNull() ?: return null
        if (n !in 1..31 || n > Month.of(month).length(from.isLeapYear)) return null
        val date = LocalDate.of(from.year, month, n)
        return if (date.isBefore(from)) date.plusYears(1) else date
    }

    fun workingDayBefore(day: LocalDate): LocalDate {
        var d = day.minusDays(1)
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.minusDays(1)
        return d
    }

    /** Reminders start this hour. */
    const val REMIND_FROM = 8

    /** A promise made on its own day waits this long before it's reminded. */
    val SAME_DAY_GRACE: Duration = Duration.ofHours(2)

    /**
     * Whether a promise is reminded now, and how its day reads ("tomorrow",
     * "today", "Monday"). Once: on the working day before its day, or on the
     * day when made too late for that. Never before 08:00, after its day, or
     * without a day.
     */
    fun reminderDue(p: Promise, now: Instant, zone: ZoneId): String? {
        val due = p.due ?: return null
        if (p.state != PromiseState.OPEN || p.remindedAt != null) return null
        val local = now.atZone(zone)
        if (local.hour < REMIND_FROM) return null
        val today = local.toLocalDate()
        val before = workingDayBefore(due)
        val made = p.createdAt.atZone(zone).toLocalDate()
        return when {
            today.isAfter(due) || today.isBefore(before) -> null
            today == due -> if (Duration.between(p.createdAt, now) < SAME_DAY_GRACE) null else "today"
            !made.isBefore(before) -> null
            today.plusDays(1) == due -> "tomorrow"
            else -> due.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        }
    }

    /** "today", "tomorrow", "Friday", "24 Nov", "2 days ago": a promise's day as it reads now. */
    fun dayWords(due: LocalDate, today: LocalDate): String {
        val days = Duration.between(today.atStartOfDay(), due.atStartOfDay()).toDays()
        return when {
            days == 0L -> "today"
            days == 1L -> "tomorrow"
            days == -1L -> "yesterday"
            days < 0 -> "${-days} days ago"
            days < 7 -> due.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            else -> due.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
        }
    }

    fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.trim().toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }
}

/** Runs one prompt on the local model. */
fun interface PromiseModel {
    suspend fun complete(system: String, user: String): String
}

/** The ledger: reads, keeps, changes and reminds. */
class PromiseService(
    private val store: PromiseStore,
    private val model: PromiseModel,
    private val hermodr: Hermodr,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    val changes = Changes()

    suspend fun all(): List<Promise> = withContext(io) { store.all() }

    /** Open first (soonest day first, then without a day), then the closed ones. */
    suspend fun listed(): List<Promise> = all().sortedWith(
        compareBy<Promise> { it.state != PromiseState.OPEN }.thenBy { it.due ?: LocalDate.MAX }.thenByDescending { it.changedAt },
    )

    /**
     * The promises in [text], kept: from your chat with Yggdrasil ([chat]) or
     * a message you shared. Never fails: what it can't read it leaves.
     */
    suspend fun read(text: String, chat: Boolean): List<Promise> {
        if (!PromiseText.mayPromise(text)) return emptyList()
        val at = now()
        val raw = try {
            model.complete(PromiseText.system(chat), PromiseText.user(text, at, zone()))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return emptyList()
        }
        val today = at.atZone(zone()).toLocalDate()
        val key = (if (chat) "chat:" else "shared:") + PromiseText.hash(text)
        val said = text.trim().replace(Regex("\\s+"), " ").take(200)
        val kept = withContext(io) {
            PromiseText.parse(raw).mapNotNull { e ->
                val p = Promise(0, e.what, e.to, PromiseText.resolveDue(e.`when`, today), PromiseState.OPEN, if (chat) "chat" else "shared", said, at, at)
                store.add(p, key, PromiseText.norm(e.what))?.let { p.copy(id = it) }
            }
        }
        if (kept.isNotEmpty()) changes.bump()
        return kept
    }

    /** A promise you add by hand; [whenWords] read as the model's are ("Friday", "24/11"). */
    suspend fun add(what: String, to: String, whenWords: String): Promise? {
        val clean = what.trim().trimEnd('.')
        if (clean.isEmpty()) return null
        val at = now()
        val p = Promise(0, clean.take(160), to.trim().take(120), PromiseText.resolveDue(whenWords, at.atZone(zone()).toLocalDate()),
            PromiseState.OPEN, "you", null, at, at)
        val id = withContext(io) { store.add(p, "you:${at.toEpochMilli()}", PromiseText.norm(clean)) } ?: return null
        changes.bump()
        return p.copy(id = id)
    }

    private suspend fun change(id: Long, f: (Promise) -> Promise) {
        withContext(io) { store.get(id)?.let { store.update(f(it).copy(changedAt = now())) } }
        changes.bump()
    }

    suspend fun done(id: Long) = change(id) { it.copy(state = PromiseState.DONE) }
    suspend fun drop(id: Long) = change(id) { it.copy(state = PromiseState.DROPPED) }
    suspend fun reopen(id: Long) = change(id) { it.copy(state = PromiseState.OPEN) }

    /** A new day; it's reminded again for it. */
    suspend fun setDue(id: Long, due: LocalDate?) = change(id) { it.copy(due = due, remindedAt = null) }

    /** Reminds you of each promise due, once; and forgets closed ones after 60 days. */
    suspend fun remind() {
        val at = now()
        val z = zone()
        for (p in all()) {
            val day = PromiseText.reminderDue(p, at, z) ?: continue
            hermodr.send(
                MessageRequest(
                    sender = "norn",
                    title = "Promised for $day: ${p.what}",
                    text = (if (p.to.isNotEmpty()) "To ${p.to}. " else "") + "Done, dropped or a new day under Inbox → Promises.",
                    priority = Priority.NORMAL,
                    hold = true,
                    tag = "promise-${p.id}",
                ),
            )
            withContext(io) { store.update(p.copy(remindedAt = at)) }
        }
        withContext(io) { store.deleteClosedBefore(at.minus(Duration.ofDays(60))) }
        changes.bump()
    }
}
