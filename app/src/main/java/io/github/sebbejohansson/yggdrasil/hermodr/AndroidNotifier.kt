package io.github.sebbejohansson.yggdrasil.hermodr

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.sebbejohansson.yggdrasil.MainActivity
import io.github.sebbejohansson.yggdrasil.R

/**
 * Hermóðr's only channel on the phone: Android notifications. One
 * notification channel per priority, so you can tune each in Android's
 * settings (sound, vibration, Do Not Disturb).
 */
class AndroidNotifier(private val context: Context) : Notifier {

    private val nm = NotificationManagerCompat.from(context)

    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(channelId(Priority.LOW), "Low priority", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Quiet: in the list, no sound. Gjallar warnings." },
                NotificationChannel(channelId(Priority.NORMAL), "Messages", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(channelId(Priority.HIGH), "Important", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(channelId(Priority.URGENT), "Urgent", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Critical alerts. Sent even in quiet hours." },
            ),
        )
    }

    override fun blockedReason(priority: Priority): String? {
        // The runtime permission exists from Android 13; before that it's granted at install.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return "notifications aren't allowed"
        if (!nm.areNotificationsEnabled()) return "notifications are turned off for the app"
        val channel = nm.getNotificationChannel(channelId(priority))
        if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return "the \"${channel.name}\" channel is turned off"
        return null
    }

    override fun show(message: Message) {
        if (blockedReason(message.priority) != null) return
        val alertId = message.tag?.removePrefix(ALERT_TAG_PREFIX)?.takeIf { message.tag.startsWith(ALERT_TAG_PREFIX) }?.toLongOrNull()

        val open = PendingIntent.getActivity(
            context, message.notificationTag.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, if (alertId != null) MainActivity.TAB_ALERTS else MainActivity.TAB_MESSAGES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val b = NotificationCompat.Builder(context, channelId(message.priority))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(message.title)
            .setContentText(message.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.text))
            .setWhen(message.createdAt.toEpochMilli())
            .setShowWhen(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(
                when {
                    alertId != null && message.priority == Priority.URGENT -> NotificationCompat.CATEGORY_ALARM
                    alertId != null -> NotificationCompat.CATEGORY_STATUS
                    else -> NotificationCompat.CATEGORY_MESSAGE
                },
            )
            .setPriority(
                when (message.priority) {
                    Priority.LOW -> NotificationCompat.PRIORITY_LOW
                    Priority.NORMAL -> NotificationCompat.PRIORITY_DEFAULT
                    Priority.HIGH -> NotificationCompat.PRIORITY_HIGH
                    Priority.URGENT -> NotificationCompat.PRIORITY_MAX
                },
            )

        if (alertId != null) {
            b.addAction(0, "Acknowledge", NotificationActionReceiver.intent(context, NotificationActionReceiver.ACK_ALERT, alertId))
            b.addAction(0, "Snooze 1 h", NotificationActionReceiver.intent(context, NotificationActionReceiver.SNOOZE_ALERT, alertId))
        } else {
            b.addAction(0, "Seen", NotificationActionReceiver.intent(context, NotificationActionReceiver.SEEN_MESSAGE, message.id))
        }

        try {
            nm.notify(message.notificationTag, NOTIFICATION_ID, b.build())
        } catch (_: SecurityException) {
            // Permission taken away between the check and here; the message stays listed.
        }
    }

    override fun cancel(notificationTag: String) = nm.cancel(notificationTag, NOTIFICATION_ID)

    companion object {
        const val ALERT_TAG_PREFIX = "alert:"
        private const val NOTIFICATION_ID = 1
        fun channelId(p: Priority) = "hermodr_${p.name.lowercase()}"
    }
}
