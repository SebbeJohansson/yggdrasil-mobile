package io.github.sebbejohansson.yggdrasil.model

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.sebbejohansson.yggdrasil.R
import io.github.sebbejohansson.yggdrasil.YggApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Downloads a model as a foreground job, so it carries on when you leave the
 * app, with its progress in a notification. WorkManager starts it again
 * after a dropped connection or a restart, and [ModelDownloader] continues
 * from the bytes it already has.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val model = ModelCatalog.byId(inputData.getString(KEY_MODEL) ?: return Result.failure()) ?: return Result.failure()
        val c = (applicationContext as YggApp).container
        setForeground(foreground(model, 0f, indeterminate = true))
        return try {
            var lastPercent = -1
            c.modelDownloader.download(model).collect { s ->
                when (s) {
                    is DownloadState.Downloading -> {
                        val percent = (s.fraction * 100).toInt()
                        setProgress(workDataOf(KEY_PHASE to PHASE_DOWNLOADING, KEY_BYTES to s.bytes, KEY_TOTAL to s.total))
                        if (percent != lastPercent) {
                            lastPercent = percent
                            setForeground(foreground(model, s.fraction, indeterminate = false))
                        }
                    }
                    DownloadState.Verifying -> {
                        setProgress(workDataOf(KEY_PHASE to PHASE_VERIFYING))
                        setForeground(foreground(model, 1f, indeterminate = true, verifying = true))
                    }
                    DownloadState.Done -> Unit
                }
            }
            Result.success()
        } catch (e: java.io.IOException) {
            // A checksum mismatch removed the file; anything else (the network) is worth another go.
            if (e.message?.startsWith("checksum") == true) Result.failure(workDataOf(KEY_ERROR to e.message)) else Result.retry()
        }
    }

    private fun foreground(model: LocalModel, fraction: Float, indeterminate: Boolean, verifying: Boolean = false): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF4FD8F0.toInt())
            .setContentTitle("Downloading ${model.displayName}")
            .setContentText(
                if (verifying) "Checking the file…"
                else "%.0f%% of %.1f GB".format(fraction * 100, model.sizeBytes / 1e9),
            )
            .setProgress(100, (fraction * 100).toInt(), indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(model.id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val CHANNEL = "model_downloads"
        private const val KEY_MODEL = "model"
        private const val KEY_PHASE = "phase"
        private const val KEY_BYTES = "bytes"
        private const val KEY_TOTAL = "total"
        private const val KEY_ERROR = "error"
        private const val PHASE_DOWNLOADING = "downloading"
        private const val PHASE_VERIFYING = "verifying"

        private fun name(model: LocalModel) = "download:${model.id}"

        /** Starts the download, or leaves the one already going. */
        fun start(context: Context, model: LocalModel) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                name(model),
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                    .setInputData(Data.Builder().putString(KEY_MODEL, model.id).build())
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }

        fun cancel(context: Context, model: LocalModel) {
            WorkManager.getInstance(context).cancelUniqueWork(name(model))
        }

        /** The download as the screens show it: null when none is going. Failed ones say why. */
        fun state(context: Context, model: LocalModel): Flow<ModelDownload?> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(name(model)).map { infos ->
                val info = infos.firstOrNull() ?: return@map null
                when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ModelDownload(DownloadState.Downloading(0, model.sizeBytes), waiting = true)
                    WorkInfo.State.RUNNING -> {
                        val p = info.progress
                        ModelDownload(
                            if (p.getString(KEY_PHASE) == PHASE_VERIFYING) DownloadState.Verifying
                            else DownloadState.Downloading(p.getLong(KEY_BYTES, 0), p.getLong(KEY_TOTAL, model.sizeBytes)),
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> ModelDownload(DownloadState.Done)
                    WorkInfo.State.FAILED -> ModelDownload(null, error = info.outputData.getString(KEY_ERROR) ?: "the download failed")
                    WorkInfo.State.CANCELLED -> null
                }
            }
    }
}

/** A download as shown: its state, whether it waits for a network, or why it failed. */
data class ModelDownload(val state: DownloadState?, val waiting: Boolean = false, val error: String? = null)

/** Starts and follows model downloads, for screens that have no Context of their own. */
class ModelDownloads(private val context: Context) {
    fun start(model: LocalModel) = ModelDownloadWorker.start(context, model)
    fun cancel(model: LocalModel) = ModelDownloadWorker.cancel(context, model)
    fun state(model: LocalModel) = ModelDownloadWorker.state(context, model)
}
