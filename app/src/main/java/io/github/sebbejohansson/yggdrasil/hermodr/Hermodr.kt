package io.github.sebbejohansson.yggdrasil.hermodr

import java.time.Instant

/** How hard a message tries to reach you. Maps to a notification channel. */
enum class Priority { LOW, NORMAL, HIGH, URGENT }

enum class MessageState {
    /** Waiting for quiet hours (or a meeting) to end. */
    HELD,

    /** Shown as a notification. */
    SENT,

    /** Only listed in the app (`push = false`). */
    LISTED,

    /** Couldn't be shown, e.g. notifications are turned off. */
    FAILED,
}

/**
 * What a part hands to Hermóðr. Same fields as Yggdrasil's
 * `POST /v1/messages`, plus [tag].
 */
data class MessageRequest(
    /** The part that sends it, e.g. "gjallar". */
    val sender: String,
    val title: String,
    val text: String? = null,
    val link: String? = null,
    val priority: Priority = Priority.NORMAL,
    /** Also wait while you're busy (meeting, focus block), not only through quiet hours. */
    val hold: Boolean = false,
    /** False: only list it in the app, never notify. */
    val push: Boolean = true,
    /**
     * Messages with the same tag share one notification: a newer one replaces
     * it, and [Hermodr.withdraw] removes it. Gjallar uses `alert:<id>`.
     */
    val tag: String? = null,
)

data class Message(
    val id: Long,
    val sender: String,
    val title: String,
    val text: String?,
    val link: String?,
    val priority: Priority,
    val hold: Boolean,
    val push: Boolean,
    val tag: String?,
    val state: MessageState,
    val createdAt: Instant,
    val sentAt: Instant? = null,
    val seenAt: Instant? = null,
    val failure: String? = null,
) {
    /** The notification this message is shown as. */
    val notificationTag: String get() = tag ?: "message:$id"
}

/** What the other parts may call. */
interface Hermodr {
    suspend fun send(request: MessageRequest): Message

    /** Takes back the notification for [tag] and marks its messages seen. */
    suspend fun withdraw(tag: String)
}
