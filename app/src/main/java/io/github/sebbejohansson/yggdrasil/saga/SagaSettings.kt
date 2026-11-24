package io.github.sebbejohansson.yggdrasil.saga

import android.content.Context
import androidx.core.content.edit
import io.github.sebbejohansson.yggdrasil.update.KeystoreTokenVault
import io.github.sebbejohansson.yggdrasil.update.TokenVault

/**
 * Where Mímir is and this phone's token there. The URL and name are in
 * SharedPreferences; the token is encrypted with a key in the Android
 * Keystore, as the bridge key and the GitHub token are: through Mímir it can
 * answer the desktop agents' permission prompts.
 */
class SagaSettings(
    context: Context,
    private val vault: TokenVault = KeystoreTokenVault(context, prefsName = "saga_token", alias = "ygg-mimir-token"),
) {
    private val prefs = context.getSharedPreferences("saga", Context.MODE_PRIVATE)

    init {
        // A token kept in plain text before moves into the vault, once.
        // The plain copy goes only once the vault holds it.
        prefs.getString("token", null)?.trim()?.let { old ->
            if (old.isNotBlank() && vault.get() == null) runCatching { vault.set(old) }
            if (old.isBlank() || vault.get() == old) prefs.edit { remove("token") }
        }
    }

    var url: String
        get() = prefs.getString("url", "") ?: ""
        set(v) = prefs.edit { putString("url", v.trim()) }

    var token: String
        get() = vault.get() ?: ""
        set(v) = vault.set(v.trim().ifBlank { null })

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
