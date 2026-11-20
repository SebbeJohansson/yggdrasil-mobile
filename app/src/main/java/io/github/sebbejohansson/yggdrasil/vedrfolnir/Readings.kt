package io.github.sebbejohansson.yggdrasil.vedrfolnir

import io.github.sebbejohansson.yggdrasil.gjallar.Severity
import java.time.Instant

/** How hot the phone is, as Android's PowerManager says it. */
enum class Thermal { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN }

/** One look at the phone. */
data class Readings(
    val at: Instant,
    /** Null when the phone doesn't say: nothing is raised on a guess. */
    val batteryPercent: Int?,
    val charging: Boolean,
    /** Battery temperature in °C, when the phone reports it. */
    val batteryTempC: Float?,
    val storageFreeBytes: Long,
    val storageTotalBytes: Long,
    val memoryAvailBytes: Long,
    val memoryTotalBytes: Long,
    val thermal: Thermal,
) {
    val storageUsedPercent: Double
        get() = if (storageTotalBytes <= 0) 0.0 else 100.0 * (storageTotalBytes - storageFreeBytes) / storageTotalBytes
}

/**
 * One check: [ok] false means an alert under [key] should be open. As on
 * the desktop Veðrfölnir, one problem is one alert, and it resolves when the
 * check passes again.
 */
data class Check(
    val key: String,
    val ok: Boolean,
    val severity: Severity,
    val title: String,
    val detail: String? = null,
)

data class Thresholds(
    val batteryWarning: Int = 15,
    val batteryCritical: Int = 5,
    val storageWarning: Double = 90.0,
    val storageCritical: Double = 95.0,
    val batteryHotC: Float = 45f,
)

object Checks {
    fun of(r: Readings, t: Thresholds = Thresholds()): List<Check> = listOf(battery(r, t), storage(r, t), heat(r, t))

    private fun battery(r: Readings, t: Thresholds): Check {
        val percent = r.batteryPercent
        val low = percent != null && !r.charging && percent <= t.batteryWarning
        return Check(
            key = "battery-low",
            ok = !low,
            severity = if (percent != null && percent <= t.batteryCritical) Severity.CRITICAL else Severity.WARNING,
            title = "Battery at ${r.batteryPercent}%",
            detail = "Not charging. The model and the background checks stop when it runs out.",
        )
    }

    private fun storage(r: Readings, t: Thresholds): Check {
        val used = r.storageUsedPercent
        return Check(
            key = "storage",
            // A size of 0 means Android didn't say.
            ok = r.storageTotalBytes <= 0 || used < t.storageWarning,
            severity = if (used >= t.storageCritical) Severity.CRITICAL else Severity.WARNING,
            title = "Storage is %.0f%% full".format(used),
            detail = "%.1f GB free of %.1f GB".format(r.storageFreeBytes / 1e9, r.storageTotalBytes / 1e9),
        )
    }

    // Android's own levels: severe throttles hard, critical is close to shutting things down.
    private fun heat(r: Readings, t: Thresholds): Check {
        val hotBattery = r.batteryTempC != null && r.batteryTempC >= t.batteryHotC
        val hot = r.thermal >= Thermal.SEVERE || hotBattery
        val critical = r.thermal >= Thermal.CRITICAL
        return Check(
            key = "heat",
            ok = !hot,
            severity = if (critical) Severity.CRITICAL else Severity.WARNING,
            title = if (r.thermal >= Thermal.SEVERE) "Phone is ${r.thermal.name.lowercase()} hot" else "Battery is hot",
            detail = listOfNotNull(
                r.batteryTempC?.let { "Battery %.0f °C".format(it) },
                "the model runs slower while it cools".takeIf { hot },
            ).joinToString(", ").replaceFirstChar { it.uppercase() },
        )
    }
}
