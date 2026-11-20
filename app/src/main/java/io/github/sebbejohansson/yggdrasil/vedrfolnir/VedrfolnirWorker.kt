package io.github.sebbejohansson.yggdrasil.vedrfolnir

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
        (applicationContext as YggApp).container.vedrfolnir.check()
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
