package io.github.sebbejohansson.yggdrasil.nodes

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * A machine's cleanup of quiet projects (Yggdrasil's sindri/cleanup.go):
 * after N quiet days a project's git-ignored node_modules, build output and
 * caches go. GET /v1/cleanup lists them, POST /v1/cleanup cleans now.
 */
data class CleanupProject(
    val name: String,
    val lastActive: Instant?,
    /** Quiet long enough: what Clean now takes. */
    val inactive: Boolean,
    val folders: Int,
    val bytes: Long,
)

data class CleanupRun(val at: Instant, val folders: Int, val bytes: Long, val errors: List<String>)

data class CleanupStatus(
    /** Days quiet before a project is cleaned; 0 means the machine only lists. */
    val afterDays: Int,
    val projects: List<CleanupProject>,
    /** What Clean now would free. */
    val bytes: Long,
    val last: CleanupRun?,
) {
    val quiet: List<CleanupProject> get() = projects.filter { it.inactive && it.folders > 0 }
}

object Cleanup {
    fun parseStatus(json: String): CleanupStatus {
        val o = JSONObject(json)
        return CleanupStatus(
            afterDays = o.optInt("afterDays"),
            projects = o.optJSONArray("projects").objects().map { p ->
                CleanupProject(
                    name = p.optString("name"),
                    lastActive = p.optLong("lastActive").takeIf { it > 0 }?.let(Instant::ofEpochMilli),
                    inactive = p.optBoolean("inactive"),
                    folders = p.optJSONArray("targets")?.length() ?: 0,
                    bytes = p.optLong("bytes"),
                )
            }.sortedByDescending { it.bytes },
            bytes = o.optLong("bytes"),
            last = o.optJSONObject("last")?.let(::parseRun),
        )
    }

    fun parseRun(o: JSONObject): CleanupRun = CleanupRun(
        at = Instant.ofEpochMilli(o.optLong("at")),
        folders = o.optJSONArray("removed")?.length() ?: 0,
        bytes = o.optLong("bytes"),
        errors = o.optJSONArray("errors")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
    )

    /** "1.4 GB", "230 MB", "12 KB". */
    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "${bytes shr 20} MB"
        bytes >= 1L shl 10 -> "${bytes shr 10} KB"
        else -> "$bytes B"
    }

    /** The card's line: what can go now, and the rule. */
    fun summary(s: CleanupStatus): String {
        val rule = if (s.afterDays > 0) "cleans projects quiet for ${s.afterDays} days" else "automatic cleaning off"
        val quiet = s.quiet
        return if (quiet.isEmpty()) "Nothing to clean · $rule"
        else "${quiet.size} quiet project${if (quiet.size == 1) "" else "s"} · ${size(s.bytes)} to free · $rule"
    }

    /** A 404 is a Yggdrasil from before cleanup: the card is left out. */
    fun unsupported(message: String?): Boolean = message != null && "answered 404" in message

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
