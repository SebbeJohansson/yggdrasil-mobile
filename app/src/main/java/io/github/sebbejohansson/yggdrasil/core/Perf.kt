package io.github.sebbejohansson.yggdrasil.core

import java.util.concurrent.Executors
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil

/**
 * One timed step: a model call, a part's loop round, a download. [who] says
 * which model or server answered; [amount] is what it moved: characters
 * written for a model call, bytes for the network.
 */
data class PerfStep(
    val part: String,
    val kind: String,
    val at: Instant,
    val ms: Long,
    val ok: Boolean = true,
    val failure: String? = null,
    val who: String? = null,
    val amount: Long = 0,
)

interface PerfStore {
    fun add(step: PerfStep)
    fun since(t: Instant): List<PerfStep>
    fun deleteBefore(t: Instant)
}

class InMemoryPerfStore : PerfStore {
    private val rows = mutableListOf<PerfStep>()
    @Synchronized override fun add(step: PerfStep) { rows += step }
    @Synchronized override fun since(t: Instant) = rows.filter { !it.at.isBefore(t) }
    @Synchronized override fun deleteBefore(t: Instant) { rows.removeAll { it.at.isBefore(t) } }
}

/**
 * What the parts pull, as the dashboard's Performance page (`perf.vue`):
 * every model call by purpose, every loop round, every download, timed and
 * kept for a week. Recording never fails the thing it measures.
 */
class Perf(
    private val store: PerfStore,
    private val now: () -> Instant = Instant::now,
    /** Where writes happen: off the caller's thread, which may be the main one. */
    private val writer: Executor = Executors.newSingleThreadExecutor(),
) {
    val changes = Changes()

    fun record(step: PerfStep) {
        writer.execute {
            runCatching { store.add(step) }
            changes.bump()
        }
    }

    /** Bytes moved for [part]: a download, a sync. */
    fun bytes(part: String, kind: String, bytes: Long, ms: Long = 0, who: String? = null) {
        if (bytes > 0) record(PerfStep(part, kind, now(), ms, amount = bytes, who = who))
    }

    /** Times [block] as a step of [part]. A cancelled one (you stopped it) isn't counted. */
    suspend fun <T> time(part: String, kind: String, who: String? = null, block: suspend () -> T): T {
        val start = System.nanoTime()
        val at = now()
        try {
            return block().also { record(PerfStep(part, kind, at, (System.nanoTime() - start) / 1_000_000, who = who)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            record(PerfStep(part, kind, at, (System.nanoTime() - start) / 1_000_000, false, PerfMath.failureKind(e), who))
            throw e
        }
    }

    fun since(t: Instant): List<PerfStep> = runCatching { store.since(t) }.getOrDefault(emptyList())

    /** Keeps a week. */
    fun prune() = runCatching { store.deleteBefore(now().minus(Duration.ofDays(7))) }
}

/** The pure part: rows, percentiles, failure kinds. */
object PerfMath {
    data class Row(
        val part: String,
        val kind: String,
        val who: String?,
        val count: Int,
        val failed: Int,
        val p50: Long,
        val p95: Long,
        val totalMs: Long,
        val amount: Long,
        /** The most common failure, with how many. */
        val topFailure: Pair<String, Int>?,
    )

    /** The nearest-rank percentile of [ms]; zero when empty. */
    fun percentile(ms: List<Long>, p: Double): Long {
        if (ms.isEmpty()) return 0
        val sorted = ms.sorted()
        val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /** One row per part, kind and model, the busiest first. */
    fun rows(steps: List<PerfStep>): List<Row> = steps.groupBy { Triple(it.part, it.kind, it.who) }.map { (key, list) ->
        val times = list.map { it.ms }
        Row(
            key.first, key.second, key.third, list.size, list.count { !it.ok },
            percentile(times, 50.0), percentile(times, 95.0), times.sum(), list.sumOf { it.amount },
            list.mapNotNull { it.failure }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.toPair(),
        )
    }.sortedWith(compareByDescending<Row> { it.totalMs }.thenByDescending { it.amount })

    /** A failure as a kind worth counting, not a message: "timeout", "no model", or the exception's name. */
    fun failureKind(e: Throwable): String {
        val name = e.javaClass.simpleName
        val text = (e.message ?: "").lowercase()
        return when {
            name.contains("Timeout", ignoreCase = true) || text.contains("timed out") || text.contains("timeout") -> "timeout"
            name == "NoProviderException" -> "no model ready"
            name.contains("UnknownHost") || name.contains("Connect") -> "no connection"
            text.contains("http ") -> text.substringAfter("http ").take(3).let { "HTTP $it" }
            else -> name.ifEmpty { "error" }
        }
    }

    /** "850 ms", "4.2 s", "3 min". */
    fun time(ms: Long): String = when {
        ms < 1_000 -> "$ms ms"
        ms < 60_000 -> "%.1f s".format(ms / 1000.0)
        ms < 3_600_000 -> "${ms / 60_000} min"
        else -> "%.1f h".format(ms / 3_600_000.0)
    }

    /** "512 B", "3.4 MB", "2.6 GB". */
    fun bytes(b: Long): String = when {
        b < 1_000 -> "$b B"
        b < 1_000_000 -> "%.0f kB".format(b / 1e3)
        b < 1_000_000_000 -> "%.1f MB".format(b / 1e6)
        else -> "%.2f GB".format(b / 1e9)
    }
}

/**
 * Where network code reports what it moved, so the Performance page can
 * say which part pulls the data. Set once by the app; nothing until then.
 */
object NetMeter {
    @Volatile var sink: ((part: String, kind: String, bytes: Long, ms: Long) -> Unit)? = null

    fun count(part: String, kind: String, bytes: Long, ms: Long = 0) {
        runCatching { sink?.invoke(part, kind, bytes, ms) }
    }
}
