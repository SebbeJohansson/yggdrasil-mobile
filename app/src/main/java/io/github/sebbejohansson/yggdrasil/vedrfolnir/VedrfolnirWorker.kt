package io.github.sebbejohansson.yggdrasil.vedrfolnir

import io.github.sebbejohansson.yggdrasil.parts.Parts
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.sebbejohansson.yggdrasil.YggApp
import java.util.concurrent.TimeUnit

/** Veðrfölnir's loop: a check every 15 minutes, Android's shortest period for background work. */
class VedrfolnirWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as YggApp).container
        c.loops.run(Parts.VEDRFOLNIR_CHECK.first, Parts.VEDRFOLNIR_CHECK.second) { c.vedrfolnir.check() }
        // A safety net for Dellingr: anything an alarm missed runs within 15 minutes.
        c.loops.run(Parts.DELLINGR_TICK.first, Parts.DELLINGR_TICK.second) { c.dellingr.tick() }
        // Huginn's look at the calendar: meetings starting soon, meetings just ended.
        c.loops.run(Parts.HUGINN_CALENDAR.first, Parts.HUGINN_CALENDAR.second) { c.huginnRuntime.checkCalendar() }
        // Promises due tomorrow (or today): reminded once.
        c.loops.run(Parts.NORN_PROMISES.first, Parts.NORN_PROMISES.second) { c.promises.remind() }
        // Ratatoskr's look at your rhythm.
        c.loops.run(Parts.RATATOSKR_YOU.first, Parts.RATATOSKR_YOU.second) { c.you.round() }
        // Sága catches up with Mímir at least this often while the app is closed.
        c.loops.run(Parts.SAGA_SYNC.first, Parts.SAGA_SYNC.second) { if (!c.saga.sync() && c.saga.status.value.configured) error(c.saga.status.value.lastError ?: "sync failed") }
        // The watchers: Munin, Heimdall, Loki's plan, and Ratatoskr's watchdog last.
        c.watchRounds()
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "vedrfolnir-check",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<VedrfolnirWorker>(15, TimeUnit.MINUTES).build(),
            )
        }
    }
}
