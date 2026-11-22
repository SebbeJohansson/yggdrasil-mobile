package io.github.sebbejohansson.yggdrasil.core

import android.content.Context
import androidx.core.content.edit
import io.github.sebbejohansson.yggdrasil.gjallar.Gjallar
import io.github.sebbejohansson.yggdrasil.gjallar.Severity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** One check's result this round: ok or not, and what to say when not. Skip leaves it as it was. */
data class Check(
    val key: String,
    val ok: Boolean,
    val severity: Severity = Severity.WARNING,
    val title: String = "",
    val detail: String? = null,
    val skip: Boolean = false,
)

/** What the watch keeps for one check, as the desktop's watch.State. */
data class WatchState(
    val key: String,
    val down: Boolean = false,
    val severity: Severity = Severity.WARNING,
    val title: String = "",
    val detail: String? = null,
    val fails: Int = 0,
    val passes: Int = 0,
    /** When it last went down or up. */
    val since: Long = 0,
    val checkedAt: Long = 0,
    /** Its alert is open in Gjallar. */
    val raised: Boolean = false,
)

/**
 * The desktop's internal/watch, for Ratatoskr and Munin: a check is down
 * after two failures in a row and up after two passes, so one slow answer
 * raises nothing. Pure, for tests; [Watch] keeps it and talks to Gjallar.
 */
object WatchRules {
    const val DOWN_AFTER = 2
    const val UP_AFTER = 2

    fun observe(states: Map<String, WatchState>, results: List<Check>, now: Long): Map<String, WatchState> {
        val out = states.toMutableMap()
        for (r in results) {
            val s = out[r.key] ?: WatchState(r.key, since = now)
            if (r.skip) continue
            out[r.key] = if (r.ok) {
                val passes = s.passes + 1
                val up = s.down && passes >= UP_AFTER
                s.copy(passes = passes, fails = 0, down = if (up) false else s.down, since = if (up) now else s.since, checkedAt = now)
            } else {
                val fails = s.fails + 1
                val down = !s.down && fails >= DOWN_AFTER
                s.copy(fails = fails, passes = 0, down = s.down || down, since = if (down) now else s.since, checkedAt = now,
                    severity = r.severity, title = r.title, detail = r.detail)
            }
        }
        return out
    }

    /** What to do in Gjallar: raise the down ones not raised, resolve the up ones still raised. */
    fun plan(states: Map<String, WatchState>, outOfBand: Set<String> = emptySet()): Pair<List<WatchState>, List<WatchState>> {
        val sendable = states.values.filter { it.key !in outOfBand }
        return sendable.filter { it.down && !it.raised } to sendable.filter { !it.down && it.raised }
    }
}

/**
 * A watch over one part's checks, kept in preferences (the app's process
 * comes and goes). [round] observes a round's results, forgets checks no
 * longer made (resolving their alerts) and syncs with Gjallar; a refused
 * raise is tried again next round. On its first round it adopts the part's
 * alerts still open in Gjallar, so one left from before is resolved in time.
 */
class Watch(
    context: Context,
    private val source: String,
    private val gjallar: Gjallar,
    /** The part's open alert keys in Gjallar, as the desktop's GET /v1/alerts/mine. */
    private val mine: suspend () -> Set<String>,
    /** Keys never sent to Gjallar (told some other way). */
    private val outOfBand: Set<String> = emptySet(),
) {
    private val prefs = context.getSharedPreferences("watch.$source", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private var adopted = false

    fun states(): List<WatchState> = load().values.sortedBy { it.key }

    suspend fun round(results: List<Check>, now: Long = System.currentTimeMillis()): List<WatchState> = lock.withLock {
        var states = WatchRules.observe(load(), results, now)
        if (!adopted) {
            val open = runCatching { mine() }.getOrDefault(emptySet())
            open.forEach { k -> states = states + (k to (states[k] ?: WatchState(k, down = false, since = now)).copy(raised = true)) }
            adopted = true
        }
        // A check no longer made is forgotten; its alert, if open, goes.
        val seen = results.map { it.key }.toSet()
        states.values.filter { it.key !in seen }.forEach { gone ->
            if (gone.raised && gone.key !in outOfBand) runCatching { gjallar.resolve(source, gone.key) }
        }
        states = states.filterKeys { it in seen }
        val (raise, resolve) = WatchRules.plan(states, outOfBand)
        val synced = states.toMutableMap()
        raise.forEach { s ->
            if (runCatching { gjallar.raise(source, s.key, s.severity, s.title.take(200), s.detail?.take(2000)) }.isSuccess) synced[s.key] = s.copy(raised = true)
        }
        resolve.forEach { s ->
            if (runCatching { gjallar.resolve(source, s.key) }.isSuccess) synced[s.key] = s.copy(raised = false)
        }
        save(synced)
        synced.values.sortedBy { it.key }
    }

    private fun load(): Map<String, WatchState> = runCatching {
        val a = JSONArray(prefs.getString("states", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.associate { o ->
            o.getString("key") to WatchState(
                key = o.getString("key"), down = o.optBoolean("down"),
                severity = runCatching { Severity.valueOf(o.optString("severity")) }.getOrDefault(Severity.WARNING),
                title = o.optString("title"), detail = o.optString("detail").ifEmpty { null },
                fails = o.optInt("fails"), passes = o.optInt("passes"), since = o.optLong("since"),
                checkedAt = o.optLong("checkedAt"), raised = o.optBoolean("raised"),
            )
        }
    }.getOrDefault(emptyMap())

    private fun save(states: Map<String, WatchState>) {
        val a = JSONArray()
        states.values.forEach { s ->
            a.put(JSONObject().put("key", s.key).put("down", s.down).put("severity", s.severity.name).put("title", s.title)
                .put("detail", s.detail ?: "").put("fails", s.fails).put("passes", s.passes).put("since", s.since)
                .put("checkedAt", s.checkedAt).put("raised", s.raised))
        }
        prefs.edit { putString("states", a.toString()) }
    }
}
