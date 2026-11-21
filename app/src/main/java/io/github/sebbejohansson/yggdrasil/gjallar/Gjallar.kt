package io.github.sebbejohansson.yggdrasil.gjallar

import java.time.Instant

enum class Severity { INFO, WARNING, CRITICAL }

enum class AlertState { OPEN, ACKNOWLEDGED, RESOLVED }

data class Alert(
    val id: Long,
    /** The part that raised it. */
    val source: String,
    /** The part's own name for the problem, e.g. "battery-low". */
    val key: String,
    val severity: Severity,
    val title: String,
    val detail: String?,
    val link: String?,
    val state: AlertState,
    val openedAt: Instant,
    val lastSeenAt: Instant,
    val resolvedAt: Instant? = null,
    val snoozedUntil: Instant? = null,
    /** Last time a critical push went out, for the 15-minute limit. */
    val lastCriticalPushAt: Instant? = null,
) {
    val notificationTag: String get() = "alert:$id"
}

enum class RaiseResult { OPENED, REOPENED, UPDATED }

/** What an alert just did, for whoever follows Gjallar (Huginn), as the desktop's alert stream. */
enum class AlertChange { OPENED, ESCALATED, RESOLVED }

/**
 * What the other parts may call. Same rules as Yggdrasil's Gjallar:
 * the same source and key updates the open alert; a resolved one opens
 * again; an acknowledged one opens again when its severity goes up.
 */
interface Gjallar {
    suspend fun raise(
        source: String,
        key: String,
        severity: Severity,
        title: String,
        detail: String? = null,
        link: String? = null,
    ): RaiseResult

    /** Only the part's own alert. True when one was open. */
    suspend fun resolve(source: String, key: String): Boolean
}
