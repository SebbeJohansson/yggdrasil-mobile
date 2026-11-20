package io.github.sebbejohansson.yggdrasil.dellingr

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.sebbejohansson.yggdrasil.YggApp
import java.time.Instant

/**
 * Wakes the phone for Dellingr's next run. With exact alarms allowed
 * (Android asks the user from Android 14) it fires on the minute, even in
 * Doze; without, Android may move it by some minutes.
 */
class AndroidJobAlarm(private val context: Context) : JobAlarm {
    private val am = context.getSystemService(AlarmManager::class.java)

    fun exactAllowed(): Boolean = am.canScheduleExactAlarms()

    override fun wakeAt(at: Instant?) {
        val pending = PendingIntent.getBroadcast(
            context, 0, Intent(context, DellingrAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (at == null) {
            am.cancel(pending)
            return
        }
        val ms = at.toEpochMilli()
        try {
            if (exactAllowed()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pending)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pending)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pending)
        }
    }
}

/** The alarm went off: hand the work to WorkManager, which may take longer than a receiver. */
class DellingrAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "dellingr-tick",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DellingrWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        )
    }
}

class DellingrWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as YggApp).container.dellingr.tick()
        return Result.success()
    }
}

/**
 * After a restart alarms are gone. Starting the app process is enough:
 * YggApp sets the next one when it starts.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // YggApp.onCreate has already run and set the alarm; nothing else to do.
    }
}
