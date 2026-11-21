package io.github.sebbejohansson.yggdrasil.huginn

import io.github.sebbejohansson.yggdrasil.gjallar.GjallarService
import io.github.sebbejohansson.yggdrasil.norn.CalendarReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Wires Huginn to what it follows: Gjallar's alerts as they change, and the
 * calendar, looked at by [checkCalendar] (on start and with Veðrfölnir's
 * 15-minute check). Notifications come in through [HuginnListener].
 */
class HuginnRuntime(
    private val huginn: HuginnService,
    private val calendar: CalendarReader,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    fun follow(gjallar: GjallarService, scope: CoroutineScope) {
        scope.launch {
            gjallar.events.collect { (change, alert) -> huginn.handle(Sources.alert(change, alert)) }
        }
    }

    /** Meetings starting within 30 minutes or just ended, each told once. */
    suspend fun checkCalendar(now: Instant = Instant.now()) {
        if (!calendar.allowed()) return
        val events = withContext(Dispatchers.IO) { calendar.between(now.minus(Duration.ofHours(2)), now.plus(Duration.ofHours(1))) }
        for ((kind, event) in Sources.calendarDue(events, now)) {
            val fired = Sources.calendar(kind, event, zone())
            if (huginn.firstSight(fired.key)) huginn.handle(fired)
        }
    }
}
