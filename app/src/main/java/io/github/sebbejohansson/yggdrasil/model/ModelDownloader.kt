package io.github.sebbejohansson.yggdrasil.model

import io.github.sebbejohansson.yggdrasil.core.NetMeter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed interface DownloadState {
    data class Downloading(val bytes: Long, val total: Long) : DownloadState {
        val fraction: Float get() = if (total > 0) bytes.toFloat() / total else 0f
    }
    data object Verifying : DownloadState
    data object Done : DownloadState
}

/**
 * Downloads a model into [ModelStore]. A stopped download continues where it
 * left off (HTTP Range on the `.part` file). The file is checked against its
 * SHA-256 before it's renamed into place.
 * [ModelDownloadWorker] runs it as a foreground job.
 */
class ModelDownloader(private val store: ModelStore) {

    fun download(model: LocalModel): Flow<DownloadState> = flow {
        if (store.isDownloaded(model)) {
            emit(DownloadState.Done)
            return@flow
        }
        val part = store.partFileFor(model)
        var have = if (part.isFile) part.length() else 0L
        if (have > model.sizeBytes) {
            part.delete()
            have = 0
        }

        if (have < model.sizeBytes) {
            val conn = (URL(model.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            try {
                val code = conn.responseCode
                val append = when (code) {
                    HttpURLConnection.HTTP_PARTIAL -> true
                    HttpURLConnection.HTTP_OK -> { have = 0; false }
                    else -> throw IOException("download failed: HTTP $code")
                }
                val startAt = have
                val started = System.nanoTime()
                try {
                conn.inputStream.use { input ->
                    FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var lastEmit = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            have += n
                            if (have - lastEmit >= 8L * 1024 * 1024) {
                                lastEmit = have
                                emit(DownloadState.Downloading(have, model.sizeBytes))
                            }
                        }
                    }
                }
                } finally {
                    NetMeter.count("model", model.fileName, have - startAt, (System.nanoTime() - started) / 1_000_000)
                }
            } finally {
                conn.disconnect()
            }
        }

        if (part.length() != model.sizeBytes) {
            throw IOException("download incomplete: ${part.length()} of ${model.sizeBytes} bytes")
        }
        emit(DownloadState.Verifying)
        val actual = sha256(part)
        if (!actual.equals(model.sha256, ignoreCase = true)) {
            part.delete()
            throw IOException("checksum mismatch for ${model.fileName}; the download was removed")
        }
        if (!part.renameTo(store.fileFor(model))) throw IOException("could not move ${part.name} into place")
        emit(DownloadState.Done)
    }.flowOn(Dispatchers.IO)

    private suspend fun sha256(file: java.io.File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
