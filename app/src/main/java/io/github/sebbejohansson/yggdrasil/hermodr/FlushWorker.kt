package io.github.sebbejohansson.yggdrasil.hermodr

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.sebbejohansson.yggdrasil.YggApp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Sends what Hermóðr held once quiet hours (or a meeting) end. */
class FlushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as YggApp).container.hermodr.flushHeld()
        return Result.success()
    }
}

/**
 * One pending wake-up at a time: the earliest one asked for wins.
 * WorkManager may run it a little late (Doze); that's fine for held messages.
 */
class WorkManagerFlushScheduler(
    private val context: Context,
    private val clock: Clock = Clock.systemDefaultZone(),
) : FlushScheduler {

    @Volatile private var pending: Instant? = null

    @Synchronized
    override fun flushAt(at: Instant) {
        val now = clock.instant()
        val current = pending
        if (current != null && current.isAfter(now) && !at.isBefore(current)) return
        pending = at
        val delay = Duration.between(now, at).coerceAtLeast(Duration.ZERO)
        WorkManager.getInstance(context).enqueueUniqueWork(
            "hermodr-flush",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<FlushWorker>()
                .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                .build(),
        )
    }
}
