package io.github.sebbejohansson.yggdrasil.nodes

import android.content.Context
import androidx.core.content.edit
import io.github.sebbejohansson.yggdrasil.core.NetMeter
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Clock

/**
 * Yggdrasil's releases on GitHub (Yggdrasil's docs/releases.md), to say when
 * a machine runs an older one than the newest, as the desktop widget's
 * Health page does (my-widget's utils/releases.ts). A machine's version is
 * its /health's: a release's tag (v0.2.0), or a commit for one built from
 * a checkout.
 */
object YggReleases {
    const val LATEST_URL = "https://api.github.com/repos/SebbeJohansson/yggdrasil/releases/latest"

    private val VERSION = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?$")

    private data class Parsed(val major: Int, val minor: Int, val patch: Int, val pre: String)

    private fun parse(version: String): Parsed? = VERSION.matchEntire(version.trim())?.destructured?.let { (a, b, c, pre) ->
        Parsed(a.toInt(), b.toInt(), c.toInt(), pre)
    }

    fun isRelease(version: String?): Boolean = version != null && parse(version) != null

    /** Below zero when [a] is older than [b]; a prerelease is older than its release. */
    fun compare(a: String, b: String): Int {
        val l = parse(a) ?: return 0
        val r = parse(b) ?: return 0
        return compareValuesBy(l, r, { it.major }, { it.minor }, { it.patch }).takeIf { it != 0 }
            ?: when {
                l.pre == r.pre -> 0
                l.pre.isEmpty() -> 1
                r.pre.isEmpty() -> -1
                else -> l.pre.compareTo(r.pre)
            }
    }

    /** Where a machine stands: behind, current, from source (a checkout), or unknown. */
    enum class Standing { BEHIND, CURRENT, SOURCE, UNKNOWN }

    fun standing(version: String?, latest: String?): Standing = when {
        version.isNullOrBlank() -> Standing.UNKNOWN
        !isRelease(version) -> Standing.SOURCE
        latest == null || !isRelease(latest) -> Standing.UNKNOWN
        compare(version, latest) < 0 -> Standing.BEHIND
        else -> Standing.CURRENT
    }

    /** The tag in GitHub's answer for releases/latest, or null. */
    fun parseLatest(json: String): String? = runCatching { JSONObject(json).optString("tag_name") }.getOrNull()?.takeIf(::isRelease)
}

/** The newest release's tag, asked of GitHub at most every 6 hours and kept across restarts. */
class LatestYggRelease(context: Context, private val clock: Clock = Clock.systemUTC(), private val fetch: () -> String? = ::fetchLatest) {
    private val prefs = context.getSharedPreferences("ygg_release", Context.MODE_PRIVATE)

    /** The tag known now, asking GitHub first when the last answer is old. Blocking. */
    fun get(): String? {
        val tag = prefs.getString("tag", null)
        val at = prefs.getLong("at", 0)
        if (tag != null && clock.millis() - at < MAX_AGE_MS) return tag
        val fresh = runCatching { fetch() }.getOrNull()?.let(YggReleases::parseLatest)
        if (fresh != null) prefs.edit { putString("tag", fresh); putLong("at", clock.millis()) }
        return fresh ?: tag
    }

    companion object {
        private const val MAX_AGE_MS = 6 * 60 * 60_000L

        private fun fetchLatest(): String? {
            val connection = URL(YggReleases.LATEST_URL).openConnection() as HttpURLConnection
            val start = System.nanoTime()
            var moved = 0L
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                if (connection.responseCode != 200) return null
                return connection.inputStream.bufferedReader().use { it.readText() }.also { moved = it.length.toLong() }
            } finally {
                NetMeter.count("nodes", "github", moved, (System.nanoTime() - start) / 1_000_000)
                connection.disconnect()
            }
        }
    }
}
