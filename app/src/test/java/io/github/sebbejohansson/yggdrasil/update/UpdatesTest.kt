package io.github.sebbejohansson.yggdrasil.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdatesTest {
    private fun v(s: String) = Version.parse(s)!!

    @Test fun versionsCompareAsSemver() {
        assertTrue(v("0.8.0") > v("0.7.9"))
        assertTrue(v("v1.0.0") > v("0.99.99"))
        assertTrue(v("0.10.0") > v("0.9.0"))
        assertTrue(v("1.0.0") > v("1.0.0-rc.1"))
        assertTrue(v("1.0.0-rc.10") > v("1.0.0-rc.2"))
        assertTrue(v("1.0.0-rc.1") > v("1.0.0-beta"))
        assertEquals(0, v("v0.8.0").compareTo(v("0.8.0")))
        assertNull(Version.parse("0.8"))
        assertNull(Version.parse("latest"))
        assertEquals("1.0.0-rc.1", v("v1.0.0-rc.1").toString())
    }

    private val github = """
        {"tag_name":"v0.9.0","name":"v0.9.0","draft":false,"prerelease":false,"published_at":"2026-11-21T12:00:00Z",
         "body":"## What's Changed\n- Ygg as the home screen\n- Smaller APK\n\n**Full Changelog**: x",
         "assets":[
           {"name":"yggdrasil-0.9.0.apk.sha256","size":90,"url":"https://api/sha","browser_download_url":"https://e/sha"},
           {"name":"yggdrasil-0.9.0.apk","size":27720680,"url":"https://api/apk","browser_download_url":"https://e/apk"}]}
    """.trimIndent()

    @Test fun readsGithubsLatestRelease() {
        val r = Updates.parse(JSONObject(github))!!
        assertEquals(v("0.9.0"), r.version)
        assertEquals("https://api/apk", r.apkUrl)
        assertEquals("https://api/sha", r.shaUrl)
        assertEquals(27720680L, r.apkSize)
        assertEquals("What's Changed\nYgg as the home screen\nSmaller APK", r.summary())
    }

    @Test fun skipsReleasesWithoutAnApkOrNotFinal() {
        assertNull(Updates.parse(JSONObject(github.replace("\"prerelease\":false", "\"prerelease\":true"))))
        assertNull(Updates.parse(JSONObject(github.replace(".apk\"", ".zip\""))))
        assertNull(Updates.parse(JSONObject(github.replace("v0.9.0", "nightly"))))
    }

    @Test fun decidesWhenToUpdate() {
        val r = Updates.parse(JSONObject(github))!!
        assertTrue(Updates.isNewer(r, "0.8.0"))
        assertFalse(Updates.isNewer(r, "0.9.0"))
        assertFalse(Updates.isNewer(r, "1.0.0"))
        assertFalse(Updates.isNewer(r, "0.1.0-dev-not-a-version!"))
        assertTrue(Updates.due(0, 6 * 3_600_000))
        assertFalse(Updates.due(1_000, 6 * 3_600_000))
    }

    @Test fun readsSha256sumLines() {
        val hex = "a".repeat(64)
        assertEquals(hex, Updates.shaFrom("$hex  yggdrasil-0.9.0.apk\n"))
        assertNull(Updates.shaFrom("not a hash"))
    }

    @Test fun explainsRefusalsAndChecksTokens() {
        assertEquals("The repo is private: add a GitHub token below.", Updates.refusal(404, hasToken = false))
        assertTrue(Updates.refusal(404, hasToken = true).contains("Contents: read"))
        assertTrue(Updates.refusal(401, hasToken = true).contains("expired"))
        assertEquals("github_pat_" + "a".repeat(30), Updates.tokenShape("  github_pat_" + "a".repeat(30) + "\n"))
        assertEquals("ghp_" + "B1".repeat(15), Updates.tokenShape("ghp_" + "B1".repeat(15)))
        assertNull(Updates.tokenShape("hunter2"))
        assertNull(Updates.tokenShape("github_pat_short"))
    }
}
