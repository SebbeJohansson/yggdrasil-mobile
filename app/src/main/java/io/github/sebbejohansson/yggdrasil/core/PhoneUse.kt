package io.github.sebbejohansson.yggdrasil.core

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager

/** The whole phone right now: memory, storage, heat. Needs no permission. */
data class PhoneNow(
    val ramTotal: Long,
    val ramAvailable: Long,
    val lowMemory: Boolean,
    val storageTotal: Long,
    val storageFree: Long,
    /** PowerManager's thermal status: 0 none … 6 shutdown. */
    val thermalStatus: Int,
    /** How close to throttling over the next 10 s: 1.0 is where it starts. NaN where the phone doesn't say. */
    val thermalHeadroom: Float,
) {
    val thermalWords: String get() = when (thermalStatus) {
        PowerManager.THERMAL_STATUS_NONE -> "cool"
        PowerManager.THERMAL_STATUS_LIGHT -> "warm"
        PowerManager.THERMAL_STATUS_MODERATE -> "hot: slowing down a little"
        PowerManager.THERMAL_STATUS_SEVERE -> "hot: slowing down"
        PowerManager.THERMAL_STATUS_CRITICAL -> "very hot: slowed right down"
        else -> "too hot: shutting things off"
    }

    companion object {
        fun read(context: Context): PhoneNow {
            val mem = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            val fs = StatFs(Environment.getDataDirectory().path)
            val power = context.getSystemService(PowerManager::class.java)
            return PhoneNow(
                ramTotal = mem.totalMem,
                ramAvailable = mem.availMem,
                lowMemory = mem.lowMemory,
                storageTotal = fs.totalBytes,
                storageFree = fs.availableBytes,
                thermalStatus = power.currentThermalStatus,
                thermalHeadroom = runCatching { power.getThermalHeadroom(10) }.getOrDefault(Float.NaN),
            )
        }
    }
}

/** One app's share of the phone over a window: screen time, data, storage. */
data class AppUse(
    val packageName: String,
    val label: String,
    val foregroundMs: Long = 0,
    val mobileBytes: Long = 0,
    val wifiBytes: Long = 0,
    /** App, data and cache; null until measured. */
    val storageBytes: Long? = null,
    val cacheBytes: Long? = null,
) {
    val dataBytes: Long get() = mobileBytes + wifiBytes
}

/**
 * Other apps' use of the phone, as far as Android tells an app: screen time
 * (UsageStatsManager), data by app (NetworkStatsManager) and storage
 * (StorageStatsManager), all behind Usage access, which you grant in
 * Android's settings. Not CPU, memory or battery per app: Android keeps
 * those to itself. Nothing of this leaves the phone.
 */
object PhoneUse {
    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    /** Android's page to grant it, opened at Ygg where the phone allows. */
    fun usageAccessIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Android's own battery use by app. */
    fun batteryIntent(): Intent = Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Joins what each source says by package, drops what used nothing, and
     * ranks by screen time, then data. Pure, for tests.
     */
    fun merge(
        foreground: Map<String, Long>,
        mobile: Map<String, Long>,
        wifi: Map<String, Long>,
        label: (String) -> String,
    ): List<AppUse> = (foreground.keys + mobile.keys + wifi.keys).map { p ->
        AppUse(p, label(p), foreground[p] ?: 0, mobile[p] ?: 0, wifi[p] ?: 0)
    }.filter { it.foregroundMs > 0 || it.dataBytes > 0 }
        .sortedWith(compareByDescending<AppUse> { it.foregroundMs }.thenByDescending { it.dataBytes })

    /** Each app's screen time and data in [fromMs, toMs]. Call off the main thread. */
    fun apps(context: Context, fromMs: Long, toMs: Long): List<AppUse> {
        val pm = context.packageManager
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val foreground = runCatching {
            usage.queryAndAggregateUsageStats(fromMs, toMs).mapValues { it.value.totalTimeInForeground }.filterValues { it > 0 }
        }.getOrDefault(emptyMap())
        val net = context.getSystemService(NetworkStatsManager::class.java)
        fun bytes(type: Int): Map<String, Long> = runCatching {
            val out = HashMap<String, Long>()
            val stats = net.querySummary(type, null, fromMs, toMs)
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                val name = when (bucket.uid) {
                    NetworkStats.Bucket.UID_REMOVED -> "(removed apps)"
                    NetworkStats.Bucket.UID_TETHERING -> "(hotspot)"
                    Process.SYSTEM_UID -> "android"
                    else -> pm.getPackagesForUid(bucket.uid)?.firstOrNull() ?: "(uid ${bucket.uid})"
                }
                out[name] = (out[name] ?: 0) + bucket.rxBytes + bucket.txBytes
            }
            stats.close()
            out
        }.getOrDefault(emptyMap())
        return merge(foreground, bytes(ConnectivityManager.TYPE_MOBILE), bytes(ConnectivityManager.TYPE_WIFI)) { label(pm, it) }
    }

    /** What each launchable app takes in storage, largest first. Seconds for many apps. */
    fun storage(context: Context): List<AppUse> {
        val pm = context.packageManager
        val stats = context.getSystemService(StorageStatsManager::class.java)
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.distinct()
        return launchable.mapNotNull { p ->
            runCatching {
                val s = stats.queryStatsForPackage(StorageManager.UUID_DEFAULT, p, Process.myUserHandle())
                AppUse(p, label(pm, p), storageBytes = s.appBytes + s.dataBytes + s.cacheBytes, cacheBytes = s.cacheBytes)
            }.getOrNull()
        }.sortedByDescending { it.storageBytes }
    }

    private fun label(pm: PackageManager, p: String): String =
        if (p.startsWith("(")) p else runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
}
