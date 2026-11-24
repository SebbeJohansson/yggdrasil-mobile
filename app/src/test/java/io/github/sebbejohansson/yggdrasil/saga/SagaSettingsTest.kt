package io.github.sebbejohansson.yggdrasil.saga

import androidx.core.content.edit
import io.github.sebbejohansson.yggdrasil.update.InMemoryTokenVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SagaSettingsTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = app.getSharedPreferences("saga", android.content.Context.MODE_PRIVATE)

    @Test fun aPlainTokenMovesIntoTheVaultOnce() {
        prefs.edit { putString("token", " mimir-token ") }
        val vault = InMemoryTokenVault()
        val settings = SagaSettings(app, vault)
        assertEquals("mimir-token", vault.get())
        assertEquals("mimir-token", settings.token)
        assertFalse(prefs.contains("token"))
    }

    @Test fun theTokenIsKeptInTheVaultNotInPreferences() {
        val vault = InMemoryTokenVault()
        val settings = SagaSettings(app, vault)
        settings.token = "  abc  "
        assertEquals("abc", vault.get())
        assertFalse(prefs.contains("token"))
        settings.token = ""
        assertEquals(null, vault.get())
    }
}
