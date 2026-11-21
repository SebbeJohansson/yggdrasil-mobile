package io.github.sebbejohansson.yggdrasil.saga

import android.content.Context
import androidx.core.content.edit

/** Where Mímir is and this phone's token there, kept in SharedPreferences (no backup: allowBackup is off). */
class SagaSettings(context: Context) {
    private val prefs = context.getSharedPreferences("saga", Context.MODE_PRIVATE)

    var url: String
        get() = prefs.getString("url", "") ?: ""
        set(v) = prefs.edit { putString("url", v.trim()) }

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit { putString("token", v.trim()) }

    /** This phone's name at Mímir: the name its token was given there. */
    var device: String
        get() = prefs.getString("device", "phone")?.ifBlank { "phone" } ?: "phone"
        set(v) = prefs.edit { putString("device", v.trim()) }

    /** Mímir, or null while the URL or token is missing. Only HTTPS: Android refuses plain HTTP. */
    fun client(): MimirClient? {
        val u = url
        val t = token
        if (!u.startsWith("https://") || t.isBlank()) return null
        return HttpMimirClient(u, t)
    }
}
