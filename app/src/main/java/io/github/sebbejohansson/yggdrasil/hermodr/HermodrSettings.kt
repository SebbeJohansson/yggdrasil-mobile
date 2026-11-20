package io.github.sebbejohansson.yggdrasil.hermodr

import android.content.Context
import androidx.core.content.edit
import java.time.LocalTime

/** Quiet hours, kept in SharedPreferences. Default 22:00–07:00. */
class HermodrSettings(context: Context) {
    private val prefs = context.getSharedPreferences("hermodr", Context.MODE_PRIVATE)

    var quietHours: QuietHours
        get() = QuietHours(
            enabled = prefs.getBoolean("quiet_enabled", true),
            start = LocalTime.ofSecondOfDay(prefs.getInt("quiet_start", 22 * 3600).toLong()),
            end = LocalTime.ofSecondOfDay(prefs.getInt("quiet_end", 7 * 3600).toLong()),
        )
        set(q) = prefs.edit {
            putBoolean("quiet_enabled", q.enabled)
            putInt("quiet_start", q.start.toSecondOfDay())
            putInt("quiet_end", q.end.toSecondOfDay())
        }
}
