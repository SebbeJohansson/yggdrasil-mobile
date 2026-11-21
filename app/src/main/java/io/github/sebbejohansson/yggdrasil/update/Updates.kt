package io.github.sebbejohansson.yggdrasil.update

import org.json.JSONObject

/**
 * A version as the releases name them: `0.8.0`, `v1.0.0`, `1.0.0-rc.1`. A
 * pre-release comes before its release, as in semver.
 */
data class Version(val major: Int, val minor: Int, val patch: Int, val pre: String? = null) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }).let { if (it != 0) return it }
        return when {
            pre == other.pre -> 0
            pre == null -> 1
            other.pre == null -> -1
            else -> comparePre(pre, other.pre)
        }
    }

    override fun toString() = "$major.$minor.$patch" + (pre?.let { "-$it" } ?: "")

    companion object {
        private val shape = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+.*)?$""")

        fun parse(text: String): Version? = shape.matchEntire(text.trim())?.destructured?.let { (a, b, c, p) ->
            Version(a.toInt(), b.toInt(), c.toInt(), p.ifEmpty { null })
        }

        /** rc.2 < rc.10: numbers compare as numbers, the rest as text. */
        private fun comparePre(a: String, b: String): Int {
            val x = a.split('.')
            val y = b.split('.')
            for (i in 0 until minOf(x.size, y.size)) {
                val n = x[i].toIntOrNull()
                val m = y[i].toIntOrNull()
                val c = when {
                    n != null && m != null -> n.compareTo(m)
                    n != null -> -1
                    m != null -> 1
                    else -> x[i].compareTo(y[i])
                }
                if (c != 0) return c
            }
            return x.size.compareTo(y.size)
        }
    }
}

/** A published release with an APK to install. */
data class Release(
    val version: Version,
    val tag: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
    /** The `.sha256` file published next to the APK, when there is one. */
    val shaUrl: String?,
    val publishedAt: String?,
) {
    /** The first few lines of the notes, without Markdown headings. */
    fun summary(lines: Int = 4): String = notes.lineSequence()
        .map { it.trim().trimStart('#').trim().removePrefix("- ").removePrefix("* ") }
        .filter { it.isNotEmpty() && !it.startsWith("**Full Changelog") }
        .take(lines).joinToString("\n")
}

object Updates {
    /** The repository the releases come from. */
    const val REPO = "SebbeJohansson/yggdrasil-mobile"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

    /** How often the phone looks for a new release. */
    const val CHECK_EVERY_HOURS = 6L

    /**
     * The release in GitHub's answer for `releases/latest`, or null when it
     * has no APK to install (or is a draft or pre-release).
     */
    fun parse(json: JSONObject): Release? {
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
        val tag = json.optString("tag_name")
        val version = Version.parse(tag) ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        val all = (0 until assets.length()).map { assets.getJSONObject(it) }
        val apk = all.firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
        val name = apk.optString("name")
        val sha = all.firstOrNull { it.optString("name") == "$name.sha256" }
        return Release(
            version = version,
            tag = tag,
            notes = json.optString("body").takeUnless { json.isNull("body") }.orEmpty(),
            apkUrl = apk.optString("browser_download_url"),
            apkName = name,
            apkSize = apk.optLong("size"),
            shaUrl = sha?.optString("browser_download_url"),
            publishedAt = json.optString("published_at").ifEmpty { null },
        )
    }

    /** The hash in a `sha256sum` line: `<hex>  <file>`. */
    fun shaFrom(text: String): String? =
        text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }

    /** Whether [release] is newer than the installed [current] version name. A dev build never updates. */
    fun isNewer(release: Release, current: String): Boolean {
        val installed = Version.parse(current) ?: return false
        return release.version > installed
    }

    /** Whether it's time to look again. */
    fun due(lastCheck: Long, now: Long) = now - lastCheck >= CHECK_EVERY_HOURS * 3_600_000
}
