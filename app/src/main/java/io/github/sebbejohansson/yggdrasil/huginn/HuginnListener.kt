package io.github.sebbejohansson.yggdrasil.huginn

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import io.github.sebbejohansson.yggdrasil.YggApp
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Reads the notifications other apps post: on the phone, that's the inbox
 * (mail, chat, texts). Android lets it run only once you allow it in
 * Settings → Notification access. Everything stays on the phone: the text is
 * matched against your rules here, and only what a rule sends is kept.
 */
class HuginnListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = read(sbn) ?: return
        val c = (application as YggApp).container
        c.appScope.launch { c.huginn.handle(Sources.notification(n, c.people.names)) }
    }

    private fun read(sbn: StatusBarNotification): PhoneNotification? {
        if (sbn.packageName == packageName) return null // Hermóðr's own
        val notification = sbn.notification
        if (sbn.isOngoing || notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val category = notification.category
        if (category in SKIPPED_CATEGORIES) return null

        val extras = notification.extras
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val last = style?.messages?.lastOrNull()
        val from = last?.person?.name?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = last?.text?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: return null
        if (text.isBlank()) return null

        return PhoneNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = label(sbn.packageName),
            from = from,
            text = text,
            conversation = style?.conversationTitle?.toString(),
            group = style?.isGroupConversation ?: false,
            postedAt = Instant.ofEpochMilli(last?.timestamp?.takeIf { it > 0 } ?: sbn.postTime),
        )
    }

    private fun label(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    companion object {
        /** Not messages: media players, progress, the phone's own status. */
        private val SKIPPED_CATEGORIES = setOf(
            Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_SYSTEM, Notification.CATEGORY_STATUS, Notification.CATEGORY_NAVIGATION,
        )

        /** Whether you allowed Huginn to read notifications. */
        fun allowed(context: Context): Boolean {
            val me = ComponentName(context, HuginnListener::class.java).flattenToString()
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            return enabled.split(':').any { it == me }
        }
    }
}

/** People who matter: a message from one of them is told to you, higher. Kept on the phone. */
class PeopleSettings(context: Context) {
    private val prefs = context.getSharedPreferences("people", Context.MODE_PRIVATE)

    var names: Set<String>
        get() = prefs.getStringSet("names", emptySet()).orEmpty()
        set(v) = prefs.edit { putStringSet("names", v) }
}
