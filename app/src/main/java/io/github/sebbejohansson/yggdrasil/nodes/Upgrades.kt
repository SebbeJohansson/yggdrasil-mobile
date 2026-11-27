package io.github.sebbejohansson.yggdrasil.nodes

import org.json.JSONObject

/**
 * Upgrading a machine's Yggdrasil from the phone, through its Sindri
 * (Yggdrasil's sindri/upgrade.go, the widget's utils/upgrade.ts): POST
 * /v1/upgrade runs `ygg upgrade <version>` in a systemd unit of its own, which
 * restarts Sindri on the way, so GET /v1/upgrade is read again until the
 * Sindri that comes back says how it went.
 */
data class UpgradeRun(
    val version: String,
    val from: String,
    val running: Boolean,
    /** Null while it runs, or when it died without an exit code. */
    val ok: Boolean?,
    val log: String,
)

/** GET /v1/upgrade: what the machine runs now, and the last upgrade started from a dashboard. */
data class UpgradeStatus(val version: String?, val last: UpgradeRun?)

object Upgrades {
    /** What Upgrade asks for: whatever is newest when the machine looks, not what the phone last saw. */
    const val LATEST = "latest"

    fun parseStatus(json: String): UpgradeStatus {
        val o = JSONObject(json)
        return UpgradeStatus(o.str("version"), o.optJSONObject("last")?.let(::parseRun))
    }

    fun parseRun(o: JSONObject): UpgradeRun = UpgradeRun(
        version = o.str("version") ?: "?",
        from = o.str("from") ?: "?",
        running = o.optBoolean("running"),
        ok = if (o.has("ok") && !o.isNull("ok")) o.optBoolean("ok") else null,
        log = o.str("log") ?: "",
    )

    /** The last line of an upgrade's output worth showing: what failed, usually. */
    fun lastLine(log: String): String = log.trim().lines().lastOrNull { it.isNotBlank() }?.trim() ?: ""

    /**
     * One line about an upgrade: running, done, or why not. A run asked for
     * "latest" names the version it ended on, [now], once it is done.
     */
    fun line(run: UpgradeRun, now: String? = null): String = when {
        run.running -> "Upgrading ${run.from} → ${if (run.version == LATEST) "the newest release" else run.version}… Sindri restarts on the way."
        run.ok == true -> "Upgraded ${run.from} → ${if (run.version == LATEST) now ?: "the newest release" else run.version}."
        run.ok == false -> "Upgrade to ${run.version} failed" + lastLine(run.log).let { if (it.isBlank()) "." else ": $it" }
        else -> "Upgrade to ${run.version} stopped without saying how it went" + lastLine(run.log).let { if (it.isBlank()) "." else ": $it" }
    }

    /** Sindri's refusal in words; a 404 is a Yggdrasil from before the route. */
    fun refusal(message: String?): String = when {
        message == null -> "the upgrade did not start"
        Regex("answered 404").containsMatchIn(message) -> "this machine's Yggdrasil is too old to upgrade from here: run `ygg upgrade latest` on it once"
        else -> message
    }

    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) (opt(k) as? String)?.ifEmpty { null } else null
}
