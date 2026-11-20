package io.github.sebbejohansson.yggdrasil

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import io.github.sebbejohansson.yggdrasil.ui.AuroraSky
import io.github.sebbejohansson.yggdrasil.ui.Backdrop
import io.github.sebbejohansson.yggdrasil.ui.Hud
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Bifröst at night, still, on a calm and a stormy day: `app/build/screenshots/aurora-*.png`. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class AuroraScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun calmAndStormy() {
        compose.mainClock.autoAdvance = false
        var storm by androidx.compose.runtime.mutableDoubleStateOf(0.0)
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Hud.crust)) {
                Backdrop(needs = 0, modifier = Modifier.fillMaxSize())
                AuroraSky(storm, shown = false, alpha = 1f, modifier = Modifier.fillMaxSize())
            }
        }
        val out = File("build/screenshots").also { it.mkdirs() }
        for ((name, s) in listOf("calm" to 0.0, "stormy" to 0.95)) {
            storm = s
            compose.mainClock.advanceTimeBy(500)
            val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
            File(out, "aurora-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
