package io.github.sebbejohansson.yggdrasil.hermodr

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.sebbejohansson.yggdrasil.YggApp
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/** The buttons on a notification: Seen, Acknowledge, Snooze 1 h. */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        if (id < 0) return
        val c = (context.applicationContext as YggApp).container
        val pending = goAsync()
        c.appScope.launch {
            try {
                when (intent.action) {
                    SEEN_MESSAGE -> c.hermodr.markSeen(id)
                    ACK_ALERT -> c.gjallar.acknowledge(id)
                    SNOOZE_ALERT -> c.gjallar.snooze(id, Instant.now().plus(Duration.ofHours(1)))
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val SEEN_MESSAGE = "io.github.sebbejohansson.yggdrasil.SEEN_MESSAGE"
        const val ACK_ALERT = "io.github.sebbejohansson.yggdrasil.ACK_ALERT"
        const val SNOOZE_ALERT = "io.github.sebbejohansson.yggdrasil.SNOOZE_ALERT"
        private const val EXTRA_ID = "id"

        fun intent(context: Context, action: String, id: Long): PendingIntent = PendingIntent.getBroadcast(
            context,
            (action + id).hashCode(),
            Intent(context, NotificationActionReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
