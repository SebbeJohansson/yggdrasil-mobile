package io.github.sebbejohansson.yggdrasil

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.sebbejohansson.yggdrasil.gjallar.Severity
import io.github.sebbejohansson.yggdrasil.hermodr.MessageRequest
import io.github.sebbejohansson.yggdrasil.hermodr.Priority
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws each tab on the JVM, about the size of a Pixel 9 Pro XL, into
 * `app/build/screenshots/`. Not a pixel comparison: it's there to look at
 * the screens without a phone, and it fails only if a screen can't draw.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class ScreenshotTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val out = File("build/screenshots").also { it.mkdirs() }

    private fun shot(name: String) {
        // Let the screens' reads on Dispatchers.IO land, then draw a few frames.
        repeat(6) {
            Thread.sleep(200)
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(500)
        }
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun everyTab() {
        // The backdrop and the reactor animate for ever: step the clock by hand.
        compose.mainClock.autoAdvance = false
        val c = ApplicationProvider.getApplicationContext<YggApp>().container
        runBlocking {
            c.gjallar.raise("vedrfolnir", "battery", Severity.WARNING, "Battery below 15%", "Charging would help.")
            c.gjallar.raise("dellingr", "job-failed", Severity.CRITICAL, "Morning brief job failed", "Norn didn't answer in 60 s.")
            c.gjallar.raise("munin", "heartbeat", Severity.INFO, "Hugging Face slow", "Model download at 2 MB/s.")
            c.gjallar.raise("test", "old", Severity.WARNING, "Storage almost full")
            c.gjallar.resolve("test", "old")
            c.hermodr.send(MessageRequest("norn", "Meeting in 10 minutes", "Design review with the app team.", priority = Priority.HIGH))
            c.hermodr.send(MessageRequest("huginn", "New reply from your manager", "About the release plan.", priority = Priority.NORMAL))
            c.hermodr.send(MessageRequest("dellingr", "Weekly recap is ready", priority = Priority.LOW, push = false))
        }

        shot("1-norn")
        compose.onNodeWithText("ALERTS").performClick()
        shot("2-alerts")
        compose.onNodeWithText("MESSAGES").performClick()
        shot("3-messages")
    }
}
