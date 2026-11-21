package io.github.sebbejohansson.yggdrasil.update

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where the GitHub token is kept. */
interface TokenVault {
    fun get(): String?
    fun set(token: String?)
}

/**
 * The token encrypted with a key that lives in the phone's Android Keystore
 * and never leaves it. The ciphertext is in app-private preferences; backups
 * are off, so it stays on this phone.
 */
class KeystoreTokenVault(context: Context) : TokenVault {
    private val prefs = context.getSharedPreferences("updates_token", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    override fun get(): String? = runCatching {
        val sealed = prefs.getString("token", null) ?: return null
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV))
        String(cipher.doFinal(bytes, IV, bytes.size - IV), Charsets.UTF_8)
    }.getOrNull()

    override fun set(token: String?) {
        if (token == null) {
            prefs.edit { remove("token") }
            return
        }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit { putString("token", Base64.encodeToString(sealed, Base64.NO_WRAP)) }
    }

    private companion object {
        const val ALIAS = "ygg-update-token"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV = 12
    }
}

/** For tests and previews. */
class InMemoryTokenVault(private var token: String? = null) : TokenVault {
    override fun get() = token
    override fun set(token: String?) { this.token = token }
}
