package io.github.sebbejohansson.yggdrasil.hermodr

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class TestClock(var now: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId) = TestClock(now, zone)
    override fun instant(): Instant = now
    fun advance(d: Duration) { now = now.plus(d) }
}

class FakeNotifier : Notifier {
    var blocked: String? = null
    val shown = mutableListOf<Message>()
    val cancelled = mutableListOf<String>()

    /** Tags with a notification on screen right now. */
    val onScreen = linkedMapOf<String, Message>()

    override fun blockedReason(priority: Priority) = blocked
    override fun show(message: Message) {
        shown += message
        onScreen[message.notificationTag] = message
    }
    override fun cancel(notificationTag: String) {
        cancelled += notificationTag
        onScreen.remove(notificationTag)
    }
}

class FakeScheduler : FlushScheduler {
    val wakeUps = mutableListOf<Instant>()
    override fun flushAt(at: Instant) { wakeUps += at }
}
