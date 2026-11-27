package io.github.sebbejohansson.yggdrasil.nodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpgradesTest {
    @Test
    fun readsSindrisUpgradeStatus() {
        val running = Upgrades.parseStatus("""{"version":"v0.3.9","last":{"version":"v0.3.10","from":"v0.3.9","startedAt":1,"running":true,"log":""}}""")
        assertEquals("v0.3.9", running.version)
        assertTrue(running.last!!.running)
        assertNull(running.last!!.ok)
        assertEquals("Upgrading v0.3.9 → v0.3.10… Sindri restarts on the way.", Upgrades.line(running.last!!))

        assertNull(Upgrades.parseStatus("""{"version":"v0.3.9","last":null}""").last)
    }

    @Test
    fun saysHowItEnded() {
        val done = Upgrades.parseRun(org.json.JSONObject("""{"version":"v0.3.10","from":"v0.3.9","running":false,"ok":true,"exitCode":0,"log":"ok"}"""))
        assertEquals("Upgraded v0.3.9 → v0.3.10.", Upgrades.line(done))
        val failed = Upgrades.parseRun(org.json.JSONObject("""{"version":"v0.3.10","from":"v0.3.9","running":false,"ok":false,"exitCode":1,"log":"downloading\nsha256 mismatch\n\n"}"""))
        assertEquals("Upgrade to v0.3.10 failed: sha256 mismatch", Upgrades.line(failed))
    }

    @Test
    fun anOldYggdrasilIsToldApart() {
        assertTrue(Upgrades.refusal("VPS's sindri answered 404: not found").contains("too old"))
        assertEquals("VPS's sindri answered 409: this machine already runs v0.3.10",
            Upgrades.refusal("VPS's sindri answered 409: this machine already runs v0.3.10"))
    }

    @Test
    fun anUpgradeToLatestNamesWhereItEnded() {
        val run = Upgrades.parseRun(org.json.JSONObject("""{"version":"latest","from":"v0.3.10","running":false,"ok":true,"log":""}"""))
        assertEquals("Upgraded v0.3.10 → v0.3.11.", Upgrades.line(run, now = "v0.3.11"))
        val going = Upgrades.parseRun(org.json.JSONObject("""{"version":"latest","from":"v0.3.10","running":true,"log":""}"""))
        assertEquals("Upgrading v0.3.10 → the newest release… Sindri restarts on the way.", Upgrades.line(going))
    }

    @Test
    fun startOnAMachineNeedsANewEnoughYggdrasil() {
        assertTrue(!YggReleases.startsThere("v0.3.10"))
        assertTrue(YggReleases.startsThere("v0.3.11"))
        assertTrue(YggReleases.startsThere("v0.4.0"))
        assertTrue(YggReleases.startsThere("1a2b3c4"))
        assertTrue(!YggReleases.startsThere(null))
    }

    @Test
    fun aSessionNotTypedIntoHasNoTranscriptYet() {
        assertTrue(Transcript.notYet("VPS's sindri answered 404: no transcript for that session here"))
        assertTrue(!Transcript.notYet("VPS's sindri answered 403: only the owner may read a transcript"))
    }
}
