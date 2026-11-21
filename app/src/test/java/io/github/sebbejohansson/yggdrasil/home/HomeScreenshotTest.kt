package io.github.sebbejohansson.yggdrasil.home

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.sebbejohansson.yggdrasil.YggApp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/** The home screen with a phone's worth of apps, into `app/build/screenshots/`. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class HomeScreenshotTest {
    private val labels = listOf(
        "Calendar", "Camera", "Chrome", "Clock", "Contacts", "Files", "Gmail", "Google Maps", "Keep Notes",
        "Messages", "Outlook", "Phone", "Photos", "Settings", "Slack", "Spotify", "Teams", "YouTube Music",
    )
    private fun pkg(label: String) = "com.example." + label.lowercase().replace(" ", "")

    // Before the activity starts: the apps installed, today's boot already seen.
    @get:Rule(order = 0) val phone = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<YggApp>()
            val pm = shadowOf(app.packageManager)
            for (label in labels) {
                val p = pkg(label)
                val appInfo = ApplicationInfo().apply { packageName = p; nonLocalizedLabel = label; enabled = true }
                val activity = ActivityInfo().apply {
                    packageName = p; name = "$p.Main"; nonLocalizedLabel = label; applicationInfo = appInfo; exported = true; enabled = true
                }
                pm.installPackage(PackageInfo().apply { packageName = p; applicationInfo = appInfo; activities = arrayOf(activity) })
                pm.addOrUpdateActivity(activity)
                pm.addResolveInfoForIntent(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    ResolveInfo().apply { activityInfo = activity; nonLocalizedLabel = label },
                )
            }
            app.container.bootSettings.lastDay = LocalDate.now()
            app.container.homeSettings.setPins(listOf("Phone", "Messages", "Spotify").map { AppEntry.keyOf(pkg(it), pkg(it) + ".Main", 0) })
        }
    }

    @get:Rule(order = 1) val compose = createAndroidComposeRule<HomeActivity>()

    private val out = File("build/screenshots").also { it.mkdirs() }

    private fun shot(name: String) {
        repeat(6) {
            Thread.sleep(200)
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(500)
        }
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun homeScreen() {
        compose.mainClock.autoAdvance = false
        shot("home-apps")
        compose.onNodeWithText("Search apps").performTextInput("gm")
        shot("home-search")

        // Tapping an app opens it.
        compose.onNodeWithText("Google Maps").performClick()
        val started = shadowOf(ApplicationProvider.getApplicationContext<YggApp>()).nextStartedActivity
        assertEquals(pkg("Google Maps"), started?.component?.packageName)

        // Home from another page comes back to the apps.
        compose.onNodeWithText("NORN").performClick()
        compose.activity.runOnUiThread {
            compose.activity.onNewIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        }
        shot("home-back")
        compose.onNodeWithText("Pinned", substring = true, ignoreCase = true).assertExists()
    }
}
