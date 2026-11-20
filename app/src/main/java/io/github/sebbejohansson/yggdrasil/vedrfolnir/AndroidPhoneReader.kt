package io.github.sebbejohansson.yggdrasil.vedrfolnir

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import java.time.Instant

/** Reads battery, storage, memory and heat from Android. No permissions needed. */
class AndroidPhoneReader(private val context: Context) : PhoneReader {

    override fun read(): Readings {
        // The battery's last broadcast is sticky: reading it costs nothing and needs no receiver.
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE

        val stat = StatFs(Environment.getDataDirectory().path)

        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

        val pm = context.getSystemService(PowerManager::class.java)
        val thermal = Thermal.entries.getOrElse(pm.currentThermalStatus) { Thermal.NONE }

        return Readings(
            at = Instant.now(),
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            // Tenths of a degree.
            batteryTempC = temp.takeIf { it != Int.MIN_VALUE }?.let { it / 10f },
            storageFreeBytes = stat.availableBytes,
            storageTotalBytes = stat.totalBytes,
            memoryAvailBytes = mem.availMem,
            memoryTotalBytes = mem.totalMem,
            thermal = thermal,
        )
    }
}
