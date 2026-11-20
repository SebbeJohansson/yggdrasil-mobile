package io.github.sebbejohansson.yggdrasil.model

import android.app.ActivityManager
import android.content.Context
import java.io.File

/**
 * Where model files live: the app's external files dir, so a model can also
 * be pushed by hand during development:
 *
 *     adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/<appId>/files/models/
 */
class ModelStore(private val context: Context) {

    val dir: File by lazy {
        (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).also { it.mkdirs() }
    }

    fun fileFor(model: LocalModel) = File(dir, model.fileName)

    fun partFileFor(model: LocalModel) = File(dir, model.fileName + ".part")

    /** Only a file of the expected size counts; a half download doesn't. */
    fun isDownloaded(model: LocalModel): Boolean =
        fileFor(model).let { it.isFile && it.length() == model.sizeBytes }

    fun delete(model: LocalModel) {
        fileFor(model).delete()
        partFileFor(model).delete()
    }

    fun totalDeviceRamBytes(): Long {
        val am = context.getSystemService(ActivityManager::class.java)
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    }

    fun fitsDevice(model: LocalModel) = totalDeviceRamBytes() >= model.minDeviceRamBytes
}
