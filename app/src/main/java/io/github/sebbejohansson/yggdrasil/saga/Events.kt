package io.github.sebbejohansson.yggdrasil.saga

import io.github.sebbejohansson.yggdrasil.gjallar.Alert
import io.github.sebbejohansson.yggdrasil.gjallar.AlertState
import io.github.sebbejohansson.yggdrasil.gjallar.Severity
import org.json.JSONObject
import java.time.Instant

/**
 * The events the devices agree on (Mímir's docs/events.md). Times are epoch
 * millis. Mímir never reads a body; these are for the devices.
 */
object Kinds {
    /** An alert as it is now, sent only by the device that owns it. */
    const val ALERT = "gjallar.alert"
    const val ACK = "gjallar.alert.ack"
    const val SNOOZE = "gjallar.alert.snooze"
    const val MUTE = "gjallar.alert.mute"
}

/** An alert as it travels between devices. */
data class AlertSnapshot(
    val source: String,
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
    val muted: Boolean = false,
) {
    /** What matters about it: everything but when it was last seen, which moves each time a part raises it again. */
    val fingerprint: String get() = toJson().apply { remove("lastSeenAt") }.toString()

    fun toJson(): JSONObject = JSONObject().apply {
        put("source", source)
        put("key", key)
        put("severity", severity.name.lowercase())
        put("title", title)
        detail?.let { put("detail", it) }
        link?.let { put("link", it) }
        put("state", state.name.lowercase())
        put("openedAt", openedAt.toEpochMilli())
        put("lastSeenAt", lastSeenAt.toEpochMilli())
        resolvedAt?.let { put("resolvedAt", it.toEpochMilli()) }
        snoozedUntil?.let { put("snoozedUntil", it.toEpochMilli()) }
        put("muted", muted)
    }

    companion object {
        fun of(a: Alert, muted: Boolean) = AlertSnapshot(
            source = a.source, key = a.key, severity = a.severity, title = a.title, detail = a.detail,
            link = a.link, state = a.state, openedAt = a.openedAt, lastSeenAt = a.lastSeenAt,
            resolvedAt = a.resolvedAt, snoozedUntil = a.snoozedUntil, muted = muted,
        )

        /** Null when it isn't an alert this app understands. */
        fun parse(json: String): AlertSnapshot? = runCatching {
            val o = JSONObject(json)
            AlertSnapshot(
                source = o.getString("source"),
                key = o.getString("key"),
                severity = enumOr(o.optString("severity"), Severity.WARNING),
                title = o.optString("title").ifBlank { o.getString("key") },
                detail = o.text("detail"),
                link = o.text("link"),
                state = enumOr(o.optString("state"), AlertState.OPEN),
                openedAt = Instant.ofEpochMilli(o.optLong("openedAt")),
                lastSeenAt = Instant.ofEpochMilli(o.optLong("lastSeenAt")),
                resolvedAt = o.millis("resolvedAt"),
                snoozedUntil = o.millis("snoozedUntil"),
                muted = o.optBoolean("muted"),
            )
        }.getOrNull()?.takeIf { it.source.isNotBlank() && it.key.isNotBlank() }
    }
}

/** What one device asks of another's alert. [origin] owns the alert. */
data class AlertRequest(
    val kind: String,
    val origin: String,
    val source: String,
    val key: String,
    val until: Instant? = null,
    val muted: Boolean? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("origin", origin)
        put("source", source)
        put("key", key)
        until?.let { put("until", it.toEpochMilli()) }
        muted?.let { put("muted", it) }
    }

    companion object {
        fun parse(kind: String, json: String): AlertRequest? = runCatching {
            val o = JSONObject(json)
            AlertRequest(
                kind = kind,
                origin = o.getString("origin"),
                source = o.getString("source"),
                key = o.getString("key"),
                until = o.millis("until"),
                muted = if (o.has("muted") && !o.isNull("muted")) o.getBoolean("muted") else null,
            )
        }.getOrNull()
    }
}

private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
    enumValues<E>().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: fallback

private fun JSONObject.text(k: String): String? = if (has(k) && !isNull(k)) getString(k).ifEmpty { null } else null
private fun JSONObject.millis(k: String): Instant? = if (has(k) && !isNull(k)) Instant.ofEpochMilli(getLong(k)) else null
