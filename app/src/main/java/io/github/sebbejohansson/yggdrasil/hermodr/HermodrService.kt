package io.github.sebbejohansson.yggdrasil.hermodr

import io.github.sebbejohansson.yggdrasil.core.Changes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Shows a message on the phone. [AndroidNotifier] in the app, a fake in tests. */
interface Notifier {
    /** Null when it can be shown; otherwise why not. */
    fun blockedReason(priority: Priority): String?
    fun show(message: Message)
    fun cancel(notificationTag: String)

    /** How Android's Do Not Disturb treats the urgent channel; null where there's no such thing (tests). */
    fun dnd(): DndStatus? = null
}

/** Whether Do Not Disturb is on, and whether urgent messages get through it anyway. */
data class DndStatus(val on: Boolean, val urgentGetsThrough: Boolean)

/** Wakes Hermóðr at [at] to send what it held. */
interface FlushScheduler {
    fun flushAt(at: Instant)
}

/** Whether you're busy (meeting, focus block). Norn's calendars will feed it. */
fun interface BusySignal {
    /** When the current busy stretch ends, or null when you're free. */
    suspend fun busyUntil(now: Instant): Instant?
}

val NOT_BUSY = BusySignal { null }

/**
 * Hermóðr: carries every message for you.
 *
 * - `push = false`: listed in the app only.
 * - Urgent: shown at once, quiet hours or not.
 * - Others wait through quiet hours; with `hold` also while you're busy.
 * - Held messages go out when the wait ends ([flushHeld]).
 * - Kept for 7 days ([prune]).
 */
class HermodrService(
    private val store: MessageStore,
    private val notifier: Notifier,
    private val scheduler: FlushScheduler,
    private val quietHours: () -> QuietHours,
    private val busy: BusySignal = NOT_BUSY,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : Hermodr {

    val changes = Changes()
    private val lock = Mutex()

    override suspend fun send(request: MessageRequest): Message = locked {
        val now = clock.instant()
        val draft = Message(
            id = 0, sender = request.sender, title = request.title, text = request.text,
            link = request.link, priority = request.priority, hold = request.hold,
            push = request.push, tag = request.tag,
            state = if (request.push) MessageState.HELD else MessageState.LISTED,
            createdAt = now,
        )
        val stored = draft.copy(id = store.insert(draft))
        if (!request.push) return@locked stored
        deliverOrHold(stored, now)
    }

    override suspend fun withdraw(tag: String) = locked {
        notifier.cancel(tag)
        val now = clock.instant()
        for (m in store.unseenWithTag(tag)) store.update(m.copy(seenAt = now))
    }

    suspend fun markSeen(id: Long) = locked {
        val m = store.get(id) ?: return@locked
        notifier.cancel(m.notificationTag)
        if (m.seenAt == null) store.update(m.copy(seenAt = clock.instant()))
    }

    /** Sends every held message whose wait is over; reschedules the rest. */
    suspend fun flushHeld() = locked {
        val now = clock.instant()
        for (m in store.held()) deliverOrHold(m, now)
    }

    suspend fun list(limit: Int = 200): List<Message> = withContext(io) { store.recent(limit) }

    suspend fun prune(keep: Duration = Duration.ofDays(7)) = locked {
        store.deleteCreatedBefore(clock.instant().minus(keep))
    }

    fun quietHoursNow(): QuietHours = quietHours()

    /** Callers hold [lock]. */
    private suspend fun deliverOrHold(m: Message, now: Instant): Message {
        val waitUntil = waitUntil(m, now)
        if (waitUntil != null) {
            scheduler.flushAt(waitUntil)
            return m
        }
        val blocked = notifier.blockedReason(m.priority)
        val done = if (blocked == null) {
            notifier.show(m)
            m.copy(state = MessageState.SENT, sentAt = now, failure = null)
        } else {
            m.copy(state = MessageState.FAILED, failure = blocked)
        }
        store.update(done)
        return done
    }

    private suspend fun waitUntil(m: Message, now: Instant): Instant? {
        if (m.priority == Priority.URGENT) return null
        quietHours().endAfter(now, clock.zone)?.let { return it }
        if (m.hold) busy.busyUntil(now)?.takeIf { it.isAfter(now) }?.let { return it }
        return null
    }

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(io) {
        lock.withLock { block() }.also { changes.bump() }
    }
}
