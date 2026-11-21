package io.github.sebbejohansson.yggdrasil

import android.app.ActivityManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Environment
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
import org.robolectric.shadows.ShadowStatFs
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
        capture(name)
    }

    /** Lets taps and reads land with the clock stepped by hand. */
    private fun settle() {
        repeat(4) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(400)
        }
    }

    private fun capture(name: String) {
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

        // First open of the day: the boot sequence waits for a tap.
        shot("0-boot")
        compose.onNodeWithText("TAP TO INITIALIZE").performClick()
        // Every line in (190 ms each) and the verdict up, before the hold ends.
        compose.mainClock.advanceTimeBy(3_000)
        capture("0-boot-check")
        repeat(10) { compose.mainClock.advanceTimeBy(1_000) }
        shot("1-norn")
        compose.onNodeWithText("ALERTS").performClick()
        shot("2-alerts")
        compose.onNodeWithText("MESSAGES").performClick()
        shot("3-messages")
        // Robolectric's phone has no battery or disk: give it a believable one.
        val app = ApplicationProvider.getApplicationContext<YggApp>()
        app.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 13).putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
                .putExtra(BatteryManager.EXTRA_TEMPERATURE, 312),
        )
        ShadowStatFs.registerStats(Environment.getDataDirectory(), 62_500_000, 27_000_000, 27_000_000)
        shadowOf(app.getSystemService(ActivityManager::class.java)).setMemoryInfo(
            ActivityManager.MemoryInfo().apply { totalMem = 16_000_000_000; availMem = 6_400_000_000 },
        )
        runBlocking { c.vedrfolnir.check() }
        compose.onNodeWithText("PARTS").performClick()
        shot("4-phone")
        runBlocking {
            c.dellingr.create("Morning brief", "every weekday at 8", io.github.sebbejohansson.yggdrasil.dellingr.ActionKind.ASK,
                "Give me a short, calm start to the day.", push = true)
            c.dellingr.create("Stretch", "every 2 hours", io.github.sebbejohansson.yggdrasil.dellingr.ActionKind.NOTIFY,
                "Stand up and stretch.", push = false)
            c.dellingr.runNow(c.dellingr.jobs().first { it.name == "Stretch" }.id)
        }
        compose.onNodeWithText("Jobs").performClick()
        shot("5-jobs")
        runBlocking {
            c.people.names = setOf("Kim Svensson")
            val now = java.time.Instant.now()
            c.huginn.handle(io.github.sebbejohansson.yggdrasil.huginn.Sources.notification(
                io.github.sebbejohansson.yggdrasil.huginn.PhoneNotification("a", "com.slack", "Slack", "Kim Svensson", "Got a minute before the review?", null, false, now),
                c.people.names,
            ))
            c.huginn.handle(io.github.sebbejohansson.yggdrasil.huginn.Sources.notification(
                io.github.sebbejohansson.yggdrasil.huginn.PhoneNotification("b", "com.microsoft.teams", "Teams", "Ops", "Prod is down, need you ASAP", null, false, now),
                emptySet(),
            ))
        }
        compose.onNodeWithText("Huginn").performClick()
        shot("6-huginn")
        runBlocking {
            c.brain.remember("The user's manager is Ada Lind.")
            c.brain.remember("The user's team ships the app every second Thursday.")
            c.brain.remember("The user takes the 8:12 train to work.")
            c.nornChat.startNew()
            c.nornChat.ask("What should I bring to the release review?", io.github.sebbejohansson.yggdrasil.llm.Privacy.ON_DEVICE_ONLY)
            c.nornChat.answered("The release notes, the crash numbers since the last build, and anything Ada asked to see.")
        }
        compose.onNodeWithText("NORN").performClick()
        settle()
        compose.onNodeWithText("Memory").performClick()
        shot("7-memory")
        compose.onNodeWithText("Back").performClick()
        settle()
        compose.onNodeWithText("Chats").performClick()
        shot("8-chats")
        compose.onNodeWithText("What should I bring to the release review?").performClick()
        shot("9-norn-chat")

        // A season of history, so the tree has grown and stones are carved.
        runBlocking {
            val day = 86_400_000L
            val start = System.currentTimeMillis() - 120 * day
            val facts = listOf("Works on the mobile app", "Manager is Ada Lind", "Likes short answers", "Runs on Sundays",
                "Team ships every second Thursday", "Takes the 8:12 train", "Prefers Swedish fika at 3", "Reviews PRs mornings")
            for (i in 0 until 40) {
                c.history.record(io.github.sebbejohansson.yggdrasil.history.HistoryEvent(
                    io.github.sebbejohansson.yggdrasil.history.HistoryKind.BRANCH, "seed:$i", start + (i * 2.9 * day).toLong(), facts[i % facts.size]))
            }
            for (i in 0 until 5) {
                c.history.record(io.github.sebbejohansson.yggdrasil.history.HistoryEvent(
                    io.github.sebbejohansson.yggdrasil.history.HistoryKind.BRANCH, "busy:$i", start + 30 * day + i * 3_600_000L, facts[i]))
            }
            c.history.record(io.github.sebbejohansson.yggdrasil.history.HistoryEvent(
                io.github.sebbejohansson.yggdrasil.history.HistoryKind.SCAR, "healed:storm", start + 60 * day, "Battery below 15% healed", start + 59 * day))
            c.history.record(io.github.sebbejohansson.yggdrasil.history.HistoryEvent(
                io.github.sebbejohansson.yggdrasil.history.HistoryKind.SCAR, "healed:disk", start + 90 * day, "Storage almost full healed", start + 90 * day - 3_600_000L))
            for (i in 0 until 4) {
                c.history.record(io.github.sebbejohansson.yggdrasil.history.HistoryEvent(
                    io.github.sebbejohansson.yggdrasil.history.HistoryKind.RAVEN, "raven:$i", start + (45 + i * 18) * day, "Kim Svensson asked for a minute"))
            }
        }
        compose.onNodeWithText("PARTS").performClick()
        settle()
        compose.onNodeWithText("Tree").performClick()
        shot("10-tree")
        compose.onNodeWithText("Runes").performClick()
        shot("11-runes")
    }
}
