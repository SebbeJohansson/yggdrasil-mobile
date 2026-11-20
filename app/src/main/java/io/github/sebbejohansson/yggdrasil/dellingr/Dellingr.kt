package io.github.sebbejohansson.yggdrasil.dellingr

import io.github.sebbejohansson.yggdrasil.core.Changes
import io.github.sebbejohansson.yggdrasil.gjallar.Gjallar
import io.github.sebbejohansson.yggdrasil.gjallar.Severity
import io.github.sebbejohansson.yggdrasil.hermodr.Hermodr
import io.github.sebbejohansson.yggdrasil.hermodr.MessageRequest
import io.github.sebbejohansson.yggdrasil.hermodr.Priority
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** What a job does when it runs. The desktop's `goal` needs Sindri, which the phone has not. */
enum class ActionKind {
    /** Tells you [Job.text] as it is. */
    NOTIFY,

    /** Asks Norn [Job.text] and tells you the answer. */
    ASK,
}

data class Job(
    val id: String,
    val name: String,
    val cron: String,
    val kind: ActionKind,
    val text: String,
    /** Also a notification; otherwise only listed in Messages. */
    val push: Boolean,
    val enabled: Boolean,
    val nextRunAt: Instant?,
    val lastRunAt: Instant? = null,
    val lastResult: RunState? = null,
    val failures: Int = 0,
) {
    val said: String get() = Cron.said(cron)
}

enum class RunState { RUNNING, DONE, FAILED, SKIPPED }

data class Run(
    val id: String,
    val jobId: String,
    val jobName: String,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val state: RunState = RunState.RUNNING,
    /** The phone slept through when it was due; it ran once on waking. */
    val missed: Boolean = false,
    val byHand: Boolean = false,
    val text: String? = null,
    val error: String? = null,
)

interface JobStore {
    fun jobs(): List<Job>
    fun job(id: String): Job?
    fun putJob(job: Job)
    fun deleteJob(id: String)
    fun putRun(run: Run)
    /** Newest first; all jobs' when [jobId] is null. */
    fun runs(jobId: String?, limit: Int): List<Run>
    fun forgetRunsBefore(t: Instant)
}

/** Asks Norn a question and returns the answer. */
fun interface Asker {
    suspend fun ask(prompt: String): String
}

/** Wakes the phone at [at] to run what is due, or cancels the wake-up when null. */
fun interface JobAlarm {
    fun wakeAt(at: Instant?)
}

/**
 * Dellingr, who brings the day: runs the jobs you put on a clock. Ported from
 * the desktop part's rules:
 * - A run the phone slept through runs once on waking, never once per missed tick.
 * - A job still running is skipped, not run twice at once.
 * - What a job says goes through Hermóðr with hold, so it waits out quiet
 *   hours and meetings.
 * - A job that fails 3 times in a row is `failing:<id>` with Gjallar
 *   (warning), resolved when it works again.
 * - Runs are kept for 30 days.
 */
class DellingrService(
    private val store: JobStore,
    private val asker: Asker,
    private val hermodr: Hermodr,
    private val gjallar: Gjallar,
    private val alarm: JobAlarm,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    val changes = Changes()
    private val lock = Mutex()
    private val running = mutableSetOf<String>()

    /** Words to cron and back, to show before saving. Throws [CronException] when it can't tell. */
    fun preview(words: String): Pair<String, Instant?> {
        val cron = Cron.words(words)
        return cron to nextAfter(cron, clock.instant())
    }

    suspend fun create(name: String, words: String, kind: ActionKind, text: String, push: Boolean): Job {
        require(name.isNotBlank()) { "a job needs a name" }
        require(text.isNotBlank()) { "a job needs text" }
        val cron = Cron.words(words)
        val job = Job(
            id = UUID.randomUUID().toString().take(8), name = name.trim(), cron = cron, kind = kind,
            text = text.trim(), push = push, enabled = true, nextRunAt = nextAfter(cron, clock.instant()),
        )
        write { store.putJob(job) }
        reschedule()
        return job
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = write {
        val job = store.job(id) ?: return@write
        // Enabling again starts from now: what passed while it was off is not "missed".
        store.putJob(job.copy(enabled = enabled, nextRunAt = if (enabled) nextAfter(job.cron, clock.instant()) else job.nextRunAt))
    }.also { reschedule() }

    suspend fun delete(id: String) {
        write { store.deleteJob(id) }
        gjallar.resolve(SOURCE, "failing:$id")
        reschedule()
    }

    suspend fun jobs(): List<Job> = withContext(io) {
        store.jobs().sortedWith(compareBy(nullsLast()) { j: Job -> j.nextRunAt?.takeIf { j.enabled } })
    }

    suspend fun runs(limit: Int = 50): List<Run> = withContext(io) { store.runs(null, limit) }

    /** Runs a job once now, by hand. Its schedule stays as it was. */
    suspend fun runNow(id: String) {
        val job = withContext(io) { store.job(id) } ?: return
        execute(job, Run(newId(), job.id, job.name, clock.instant(), byHand = true))
    }

    /** Runs every job that is due, then sets the next wake-up. */
    suspend fun tick() {
        val now = clock.instant()
        val due = lock.withLock {
            withContext(io) {
                store.forgetRunsBefore(now.minus(KEEP_RUNS))
                store.jobs().filter { it.enabled && it.nextRunAt != null && !it.nextRunAt.isAfter(now) }.map { job ->
                    val missed = Duration.between(job.nextRunAt, now) > LATE
                    store.putJob(job.copy(nextRunAt = nextAfter(job.cron, now)))
                    job to Run(newId(), job.id, job.name, now, missed = missed)
                }
            }
        }
        for ((job, run) in due) execute(job, run)
        reschedule()
    }

    /** Sets the alarm for the earliest next run. */
    suspend fun reschedule() {
        val next = withContext(io) { store.jobs().filter { it.enabled }.mapNotNull { it.nextRunAt }.minOrNull() }
        alarm.wakeAt(next)
        changes.bump()
    }

    private suspend fun execute(job: Job, start: Run) {
        val busy = lock.withLock { !running.add(job.id) }
        if (busy) {
            write { store.putRun(start.copy(state = RunState.SKIPPED, endedAt = clock.instant(), error = "the last run is still going")) }
            return
        }
        try {
            write { store.putRun(start) }
            val ended = try {
                val text = work(job)
                start.copy(state = RunState.DONE, text = text, endedAt = clock.instant())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                start.copy(state = RunState.FAILED, error = e.message ?: e.javaClass.simpleName, endedAt = clock.instant())
            }
            write { store.putRun(ended) }
            record(job.id, ended)
        } finally {
            lock.withLock { running.remove(job.id) }
        }
    }

    private suspend fun work(job: Job): String {
        val text = when (job.kind) {
            ActionKind.NOTIFY -> job.text
            ActionKind.ASK -> asker.ask(job.text).trim().ifEmpty { throw IllegalStateException("Norn gave no answer") }
        }
        hermodr.send(
            MessageRequest(sender = SOURCE, title = job.name, text = text, priority = Priority.NORMAL, hold = true, push = job.push),
        )
        return text
    }

    /** Counts failures in a row, and tells Gjallar after [FAILING_AFTER]. */
    private suspend fun record(jobId: String, run: Run) {
        // Read it again: it may have changed while the run went.
        val job = withContext(io) { store.job(jobId) } ?: return
        val key = "failing:${job.id}"
        val updated = if (run.state == RunState.FAILED) {
            val failures = job.failures + 1
            if (failures >= FAILING_AFTER) {
                gjallar.raise(SOURCE, key, Severity.WARNING, "The job \"${job.name}\" failed $failures times in a row", run.error)
            }
            job.copy(failures = failures)
        } else {
            if (job.failures >= FAILING_AFTER) gjallar.resolve(SOURCE, key)
            job.copy(failures = 0)
        }
        write { store.putJob(updated.copy(lastRunAt = run.startedAt, lastResult = run.state)) }
    }

    private fun nextAfter(cron: String, t: Instant): Instant? =
        Cron.parse(cron).next(t.atZone(clock.zone), clock.zone)?.toInstant()

    private suspend fun write(block: suspend () -> Unit) {
        withContext(io) { block() }
        changes.bump()
    }

    private fun newId() = UUID.randomUUID().toString().take(12)

    companion object {
        const val SOURCE = "dellingr"

        /** A due run this late counts as missed: the phone slept through it. */
        val LATE: Duration = Duration.ofMinutes(2)
        val KEEP_RUNS: Duration = Duration.ofDays(30)
        const val FAILING_AFTER = 3
    }
}

class InMemoryJobStore : JobStore {
    private val jobs = linkedMapOf<String, Job>()
    private val runs = linkedMapOf<String, Run>()
    override fun jobs() = jobs.values.toList()
    override fun job(id: String) = jobs[id]
    override fun putJob(job: Job) { jobs[job.id] = job }
    override fun deleteJob(id: String) {
        jobs.remove(id)
        runs.values.removeAll { it.jobId == id }
    }
    override fun putRun(run: Run) { runs[run.id] = run }
    override fun runs(jobId: String?, limit: Int) =
        runs.values.filter { jobId == null || it.jobId == jobId }.sortedByDescending { it.startedAt }.take(limit)
    override fun forgetRunsBefore(t: Instant) { runs.values.removeAll { it.startedAt.isBefore(t) } }
}
