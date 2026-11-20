package io.github.sebbejohansson.yggdrasil.huginn

import io.github.sebbejohansson.yggdrasil.core.Changes
import io.github.sebbejohansson.yggdrasil.dellingr.Asker
import io.github.sebbejohansson.yggdrasil.hermodr.Hermodr
import io.github.sebbejohansson.yggdrasil.hermodr.MessageRequest
import io.github.sebbejohansson.yggdrasil.hermodr.Priority
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Huginn, the thought that flies out: reacts to what just happened. Ported
 * from the desktop part's rules (`huginn/huginn.go`): a rule is a trigger
 * (an event and filters) and an action (tell you, or ask Norn), delivered
 * through Hermóðr, with hold when it should wait out a meeting.
 *
 * Events on the phone:
 * - `notification.arrived`: a notification from another app (the phone's
 *   inbox: mail, chat, texts), read by [HuginnListener] once you allow it.
 * - `calendar.soon`, `calendar.ended`: the phone's calendars.
 * - `alert.opened`, `alert.escalated`, `alert.resolved`: Gjallar.
 */
object Events {
    const val NOTIFICATION = "notification.arrived"
    const val CALENDAR_SOON = "calendar.soon"
    const val CALENDAR_ENDED = "calendar.ended"
    const val ALERT_OPENED = "alert.opened"
    const val ALERT_ESCALATED = "alert.escalated"
    const val ALERT_RESOLVED = "alert.resolved"
}

/** Something that happened, with the fields a rule's text may use as `{name}`. */
data class Fired(
    val kind: String,
    /** The same event seen twice has the same key, so it's told once. */
    val key: String,
    val at: Instant,
    val title: String,
    val urgent: Boolean = false,
    val fields: Map<String, String> = emptyMap(),
) {
    operator fun get(field: String) = fields[field].orEmpty()
}

/** What a rule listens for. Empty filters take everything. */
data class Trigger(
    val event: String,
    /** Notifications: the app's name contains this. */
    val app: String = "",
    /** Notifications: the sender contains this. */
    val from: String = "",
    /** Notifications: the text contains this. */
    val text: String = "",
    /** Notifications: the sender is in People who matter. */
    val important: Boolean? = null,
    /** Notifications: the message says it's urgent. */
    val urgent: Boolean? = null,
    /** Notifications: a one-to-one conversation, not a group. */
    val direct: Boolean? = null,
    /** Alerts: these severities ("warning", "critical"). */
    val severity: Set<String> = emptySet(),
    /** Alerts: from this part. */
    val source: String = "",
) {
    fun matches(f: Fired): Boolean {
        if (event != f.kind) return false
        fun has(field: String, want: String) = want.isBlank() || f[field].contains(want.trim(), ignoreCase = true)
        if (!has("app", app) || !has("from", from) || !has("text", text) || !has("source", source)) return false
        if (important != null && (f["important"] == "yes") != important) return false
        if (urgent != null && f.urgent != urgent) return false
        if (direct != null && (f["direct"] == "yes") != direct) return false
        if (severity.isNotEmpty() && f["severity"] !in severity) return false
        return true
    }

    /** The trigger in words, for the list. */
    fun said(): String {
        val what = when (event) {
            Events.NOTIFICATION -> "A notification"
            Events.CALENDAR_SOON -> "A meeting in the next half hour"
            Events.CALENDAR_ENDED -> "A meeting ends"
            Events.ALERT_OPENED -> "An alert opens"
            Events.ALERT_ESCALATED -> "An alert gets worse"
            Events.ALERT_RESOLVED -> "An alert heals"
            else -> event
        }
        val filters = listOfNotNull(
            "from someone who matters".takeIf { important == true },
            "that's urgent".takeIf { urgent == true },
            "in a one-to-one chat".takeIf { direct == true },
            app.takeIf { it.isNotBlank() }?.let { "in $it" },
            from.takeIf { it.isNotBlank() }?.let { "from $it" },
            text.takeIf { it.isNotBlank() }?.let { "saying \"$it\"" },
            severity.takeIf { it.isNotEmpty() }?.joinToString(" or ")?.let { "that's $it" },
            source.takeIf { it.isNotBlank() }?.let { "from $it" },
        )
        return (listOf(what) + filters).joinToString(" ")
    }
}

enum class ActionKind { NOTIFY, ASK }

data class Rule(
    val id: String,
    val name: String,
    val on: Trigger,
    val kind: ActionKind,
    /** NOTIFY: the text to send; ASK: what to ask Norn. Both may use `{field}`. */
    val text: String,
    /** Also a notification; otherwise only listed in Messages. */
    val push: Boolean = true,
    /** Wait out quiet hours and meetings. */
    val hold: Boolean = false,
    val enabled: Boolean = true,
    /** Ships with the app: can be switched off, never changed or removed. */
    val builtIn: Boolean = false,
)

enum class ReactionState { DELIVERED, FAILED, SKIPPED }

data class Reaction(
    val id: String,
    val ruleId: String,
    val ruleName: String,
    val event: String,
    val eventAt: Instant,
    val title: String,
    val text: String?,
    val state: ReactionState,
    val reason: String?,
    val at: Instant,
)

interface HuginnStore {
    fun rules(): List<Rule>
    fun putRule(rule: Rule)
    fun deleteRule(id: String)
    fun putReaction(r: Reaction)
    /** Newest first. */
    fun reactions(limit: Int): List<Reaction>
    fun forgetReactionsBefore(t: Instant)
    /** Marks a key seen; false when it already was. For calendar events checked over and over. */
    fun firstSight(key: String, at: Instant): Boolean
    fun forgetSightsBefore(t: Instant)
}

object Fill {
    private val placeholder = Regex("""\{(\w+)\}""")

    /** Puts the event's fields into the text; an unknown one is left empty. */
    operator fun invoke(text: String, fields: Map<String, String>) = placeholder.replace(text) { fields[it.groupValues[1]].orEmpty() }
}

/** The desktop's built-in rules, as they apply on a phone. */
object BuiltIn {
    private const val BRIEF =
        "Brief me for my meeting \"{subject}\" at {start}{where}: what it's likely about and what I should bring.{notes}"

    val rules = listOf(
        Rule(
            "important-message", "Message from someone who matters",
            Trigger(Events.NOTIFICATION, important = true), ActionKind.NOTIFY, "{from} on {app}: {summary}", builtIn = true,
        ),
        Rule(
            "urgent-message", "Urgent message",
            Trigger(Events.NOTIFICATION, urgent = true, direct = true), ActionKind.NOTIFY, "{from} on {app}: {summary}", builtIn = true,
        ),
        Rule(
            "meeting-prep", "Meeting in the next half hour",
            Trigger(Events.CALENDAR_SOON), ActionKind.ASK, BRIEF, hold = true, builtIn = true,
        ),
    )
}

/**
 * Runs every enabled rule an event matches. As on the desktop: an event more
 * than an hour old is skipped, and when two rules would tell you the same
 * event the same way, only the first does. Reactions are kept 7 days.
 */
class HuginnService(
    private val store: HuginnStore,
    private val norn: Asker,
    private val hermodr: Hermodr,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    val changes = Changes()
    private val lock = Mutex()
    private val told = LinkedHashMap<String, String>()

    /** Built-in rules first, in their order, then yours by name. */
    suspend fun rules(): List<Rule> = withContext(io) {
        val stored = store.rules().associateBy { it.id }
        BuiltIn.rules.map { b -> stored[b.id]?.let { b.copy(enabled = it.enabled) } ?: b } +
            stored.values.filter { !it.builtIn }.sortedBy { it.name.lowercase() }
    }

    suspend fun reactions(limit: Int = 50) = withContext(io) { store.reactions(limit) }

    suspend fun create(name: String, on: Trigger, kind: ActionKind, text: String, push: Boolean, hold: Boolean): Rule {
        require(name.isNotBlank()) { "a rule needs a name" }
        require(text.isNotBlank()) { "a rule needs text" }
        val rule = Rule(UUID.randomUUID().toString().take(8), name.trim(), on, kind, text.trim(), push, hold)
        write { store.putRule(rule) }
        return rule
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = write {
        val rule = rules().firstOrNull { it.id == id } ?: return@write
        store.putRule(rule.copy(enabled = enabled))
    }

    /** Built-in rules can't be removed, only switched off. */
    suspend fun delete(id: String) = write {
        if (BuiltIn.rules.none { it.id == id }) store.deleteRule(id)
    }

    /** Whether a calendar event was already handled; marks it handled. */
    suspend fun firstSight(key: String) = withContext(io) { store.firstSight(key, clock.instant()) }

    suspend fun handle(f: Fired) {
        val matching = rules().filter { it.enabled && it.on.matches(f) }
        for (rule in matching) react(rule, f)
    }

    suspend fun prune() = write {
        store.forgetReactionsBefore(clock.instant().minus(KEEP_REACTIONS))
        store.forgetSightsBefore(clock.instant().minus(Duration.ofDays(2)))
    }

    private suspend fun react(rule: Rule, f: Fired) {
        val now = clock.instant()
        val first = claim(f, rule)
        val (state, text, reason) = when {
            Duration.between(f.at, now) > TOO_OLD -> Triple(ReactionState.SKIPPED, null, "the event is more than an hour old")
            first != null -> Triple(ReactionState.SKIPPED, null, "already told by $first")
            else -> try {
                val text = when (rule.kind) {
                    ActionKind.NOTIFY -> Fill(rule.text, f.fields)
                    ActionKind.ASK -> norn.ask(Fill(rule.text, f.fields)).trim()
                }
                hermodr.send(
                    MessageRequest(
                        sender = SOURCE, title = f.title, text = text, priority = priority(f, rule),
                        hold = rule.hold, push = rule.push,
                    ),
                )
                Triple(ReactionState.DELIVERED, text, null)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Triple(ReactionState.FAILED, null, e.message ?: e.javaClass.simpleName)
            }
        }
        write { store.putReaction(Reaction(newId(), rule.id, rule.name, f.kind, f.at, f.title, text, state, reason, now)) }
    }

    /** Null when this rule is the first to tell the event this way; else the rule that did. */
    private suspend fun claim(f: Fired, rule: Rule): String? = lock.withLock {
        val key = f.key + "|" + rule.kind
        told[key]?.let { return@withLock it }
        if (told.size > 5_000) told.clear()
        told[key] = rule.name
        null
    }

    private suspend fun write(block: suspend () -> Unit) {
        withContext(io) { block() }
        changes.bump()
    }

    private fun newId() = UUID.randomUUID().toString().take(12)

    companion object {
        const val SOURCE = "huginn"
        val TOO_OLD: Duration = Duration.ofHours(1)
        val KEEP_REACTIONS: Duration = Duration.ofDays(7)

        /** Urgent for an urgent message or a critical alert, high for someone who matters, normal otherwise. */
        fun priority(f: Fired, rule: Rule): Priority = when {
            f.urgent -> Priority.URGENT
            f["important"] == "yes" -> Priority.HIGH
            rule.on.event == Events.CALENDAR_SOON -> Priority.HIGH
            else -> Priority.NORMAL
        }
    }
}

class InMemoryHuginnStore : HuginnStore {
    private val rules = linkedMapOf<String, Rule>()
    private val reactions = mutableListOf<Reaction>()
    private val seen = linkedMapOf<String, Instant>()
    override fun rules() = rules.values.toList()
    override fun putRule(rule: Rule) { rules[rule.id] = rule }
    override fun deleteRule(id: String) { rules.remove(id) }
    override fun putReaction(r: Reaction) { reactions += r }
    override fun reactions(limit: Int) = reactions.sortedByDescending { it.at }.take(limit)
    override fun forgetReactionsBefore(t: Instant) { reactions.removeAll { it.at.isBefore(t) } }
    override fun firstSight(key: String, at: Instant) = seen.putIfAbsent(key, at) == null
    override fun forgetSightsBefore(t: Instant) { seen.values.removeAll { it.isBefore(t) } }
}
