package io.github.sebbejohansson.yggdrasil.gjallar

import io.github.sebbejohansson.yggdrasil.core.Changes
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

/**
 * Gjallar: alerts from every part, in one place.
 *
 * How loud, as in Yggdrasil:
 * - info: listed in the app only.
 * - warning: a quiet notification when it opens (the widget badge, on a phone).
 * - critical: an urgent notification, again at most every 15 minutes while
 *   it stays open and keeps being raised.
 * Muted alerts are listed, never pushed. Snoozed ones aren't pushed until
 * the snooze ends. Acknowledging, snoozing or resolving takes the
 * notification back.
 */
class GjallarService(
    private val store: AlertStore,
    private val hermodr: Hermodr,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : Gjallar {

    val changes = Changes()
    private val lock = Mutex()

    override suspend fun raise(
        source: String,
        key: String,
        severity: Severity,
        title: String,
        detail: String?,
        link: String?,
    ): RaiseResult = locked {
        val now = clock.instant()
        val old = store.find(source, key)
        val (alert, result) = when {
            old == null -> {
                val a = Alert(0, source, key, severity, title, detail, link, AlertState.OPEN, now, now)
                a.copy(id = store.insert(a)) to RaiseResult.OPENED
            }
            old.state == AlertState.RESOLVED -> old.copy(
                severity = severity, title = title, detail = detail, link = link,
                state = AlertState.OPEN, openedAt = now, lastSeenAt = now,
                resolvedAt = null, snoozedUntil = null, lastCriticalPushAt = null,
            ) to RaiseResult.REOPENED
            old.state == AlertState.ACKNOWLEDGED && severity > old.severity -> old.copy(
                severity = severity, title = title, detail = detail, link = link,
                state = AlertState.OPEN, lastSeenAt = now,
            ) to RaiseResult.REOPENED
            else -> old.copy(
                severity = severity, title = title, detail = detail, link = link, lastSeenAt = now,
            ) to RaiseResult.UPDATED
        }
        store.update(push(alert, result, now))
        result
    }

    override suspend fun resolve(source: String, key: String): Boolean = locked {
        val a = store.find(source, key)?.takeIf { it.state != AlertState.RESOLVED } ?: return@locked false
        store.update(a.copy(state = AlertState.RESOLVED, resolvedAt = clock.instant()))
        hermodr.withdraw(a.notificationTag)
        true
    }

    suspend fun acknowledge(id: Long) = locked {
        val a = store.get(id)?.takeIf { it.state == AlertState.OPEN } ?: return@locked
        store.update(a.copy(state = AlertState.ACKNOWLEDGED))
        hermodr.withdraw(a.notificationTag)
    }

    /** No push until [until], at most 30 days ahead. */
    suspend fun snooze(id: Long, until: Instant) = locked {
        val max = clock.instant().plus(Duration.ofDays(30))
        val a = store.get(id) ?: return@locked
        store.update(a.copy(snoozedUntil = minOf(until, max)))
        hermodr.withdraw(a.notificationTag)
    }

    suspend fun setMuted(source: String, key: String, muted: Boolean) = locked {
        store.setMuted(source, key, muted)
        if (muted) store.find(source, key)?.let { hermodr.withdraw(it.notificationTag) }
    }

    suspend fun isMuted(source: String, key: String) = withContext(io) { store.isMuted(source, key) }

    /** The newest alert for this source and key, in any state. */
    suspend fun find(source: String, key: String) = withContext(io) { store.find(source, key) }

    /** Open and acknowledged, like Yggdrasil's `?state=active`. */
    suspend fun active(limit: Int = 200) =
        withContext(io) { store.list(setOf(AlertState.OPEN, AlertState.ACKNOWLEDGED), limit) }

    suspend fun resolved(limit: Int = 50) = withContext(io) { store.list(setOf(AlertState.RESOLVED), limit) }

    suspend fun prune(keep: Duration = Duration.ofDays(30)) = locked {
        store.deleteResolvedBefore(clock.instant().minus(keep))
    }

    /** Sends the push this raise calls for, if any. Returns the alert to store. */
    private suspend fun push(a: Alert, result: RaiseResult, now: Instant): Alert {
        if (a.state != AlertState.OPEN) return a
        if (store.isMuted(a.source, a.key)) return a
        if (a.snoozedUntil?.isAfter(now) == true) return a
        return when (a.severity) {
            Severity.INFO -> a
            Severity.WARNING -> {
                if (result != RaiseResult.UPDATED) hermodr.send(message(a, Priority.LOW))
                a
            }
            Severity.CRITICAL -> {
                val last = a.lastCriticalPushAt
                if (last == null || Duration.between(last, now) >= CRITICAL_REPEAT) {
                    hermodr.send(message(a, Priority.URGENT))
                    a.copy(lastCriticalPushAt = now)
                } else {
                    a
                }
            }
        }
    }

    private fun message(a: Alert, priority: Priority) = MessageRequest(
        sender = "gjallar",
        title = a.title,
        text = listOfNotNull(a.detail, "from ${a.source}").joinToString("\n"),
        link = a.link,
        priority = priority,
        tag = a.notificationTag,
    )

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(io) {
        lock.withLock { block() }.also { changes.bump() }
    }

    companion object {
        val CRITICAL_REPEAT: Duration = Duration.ofMinutes(15)
    }
}
