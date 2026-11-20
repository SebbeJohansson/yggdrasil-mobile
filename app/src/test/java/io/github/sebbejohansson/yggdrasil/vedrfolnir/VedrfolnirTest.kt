package io.github.sebbejohansson.yggdrasil.vedrfolnir

import io.github.sebbejohansson.yggdrasil.gjallar.AlertState
import io.github.sebbejohansson.yggdrasil.gjallar.GjallarService
import io.github.sebbejohansson.yggdrasil.gjallar.InMemoryAlertStore
import io.github.sebbejohansson.yggdrasil.gjallar.Severity
import io.github.sebbejohansson.yggdrasil.hermodr.FakeNotifier
import io.github.sebbejohansson.yggdrasil.hermodr.FakeScheduler
import io.github.sebbejohansson.yggdrasil.hermodr.HermodrService
import io.github.sebbejohansson.yggdrasil.hermodr.InMemoryMessageStore
import io.github.sebbejohansson.yggdrasil.hermodr.QuietHours
import io.github.sebbejohansson.yggdrasil.hermodr.TestClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private const val GB = 1_000_000_000L

private fun fine() = Readings(
    at = Instant.parse("2026-11-20T12:00:00Z"),
    batteryPercent = 80, charging = false, batteryTempC = 30f,
    storageFreeBytes = 100 * GB, storageTotalBytes = 256 * GB,
    memoryAvailBytes = 8 * GB, memoryTotalBytes = 16 * GB,
    thermal = Thermal.NONE,
)

class ChecksTest {
    private fun failing(r: Readings) = Checks.of(r).filterNot { it.ok }.associateBy { it.key }

    @Test fun aFinePhonePassesEverything() {
        assertTrue(failing(fine()).isEmpty())
    }

    @Test fun lowBatteryWarnsThenGoesCritical() {
        assertEquals(Severity.WARNING, failing(fine().copy(batteryPercent = 15))["battery-low"]!!.severity)
        assertEquals(Severity.CRITICAL, failing(fine().copy(batteryPercent = 5))["battery-low"]!!.severity)
    }

    @Test fun anUnknownBatteryRaisesNothing() {
        assertFalse("battery-low" in failing(fine().copy(batteryPercent = null)))
    }

    @Test fun anUnknownDiskRaisesNothing() {
        assertFalse("storage" in failing(fine().copy(storageFreeBytes = 0, storageTotalBytes = 0)))
    }

    @Test fun lowBatteryWhileChargingIsFine() {
        assertFalse("battery-low" in failing(fine().copy(batteryPercent = 3, charging = true)))
    }

    @Test fun storageAt90And95Percent() {
        assertEquals(Severity.WARNING, failing(fine().copy(storageFreeBytes = 25 * GB))["storage"]!!.severity)
        assertEquals(Severity.CRITICAL, failing(fine().copy(storageFreeBytes = 10 * GB))["storage"]!!.severity)
    }

    @Test fun heatFromThrottlingOrAHotBattery() {
        assertFalse("heat" in failing(fine().copy(thermal = Thermal.MODERATE)))
        assertEquals(Severity.WARNING, failing(fine().copy(thermal = Thermal.SEVERE))["heat"]!!.severity)
        assertEquals(Severity.CRITICAL, failing(fine().copy(thermal = Thermal.CRITICAL))["heat"]!!.severity)
        assertEquals(Severity.WARNING, failing(fine().copy(batteryTempC = 46f))["heat"]!!.severity)
    }
}

class VedrfolnirServiceTest {
    private val clock = TestClock(Instant.parse("2026-11-20T12:00:00Z"))
    private val hermodr = HermodrService(InMemoryMessageStore(), FakeNotifier(), FakeScheduler(), { QuietHours(enabled = false) }, clock = clock, io = Dispatchers.Unconfined)
    private val alerts = InMemoryAlertStore()
    private val gjallar = GjallarService(alerts, hermodr, clock, Dispatchers.Unconfined)
    private var now = fine()
    private val vedrfolnir = VedrfolnirService({ now }, gjallar)

    @Test fun raisesWhileFailingAndResolvesWhenItPasses() = runTest {
        now = fine().copy(batteryPercent = 10)
        vedrfolnir.check()
        assertEquals(AlertState.OPEN, alerts.find("vedrfolnir", "battery-low")!!.state)
        assertEquals(setOf("battery-low"), vedrfolnir.failing.value)

        now = fine().copy(batteryPercent = 10, charging = true)
        vedrfolnir.check()
        assertEquals(AlertState.RESOLVED, alerts.find("vedrfolnir", "battery-low")!!.state)
        assertTrue(vedrfolnir.failing.value.isEmpty())
    }

    @Test fun passingChecksNeverOpenAnything() = runTest {
        vedrfolnir.check()
        assertTrue(alerts.list(AlertState.entries.toSet(), 10).isEmpty())
    }
}
