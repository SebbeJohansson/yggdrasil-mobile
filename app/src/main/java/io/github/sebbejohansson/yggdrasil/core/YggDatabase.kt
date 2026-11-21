package io.github.sebbejohansson.yggdrasil.core

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.sebbejohansson.yggdrasil.dellingr.ActionKind
import io.github.sebbejohansson.yggdrasil.dellingr.Job
import io.github.sebbejohansson.yggdrasil.dellingr.JobStore
import io.github.sebbejohansson.yggdrasil.dellingr.Run
import io.github.sebbejohansson.yggdrasil.dellingr.RunState
import io.github.sebbejohansson.yggdrasil.gjallar.Alert
import io.github.sebbejohansson.yggdrasil.gjallar.AlertState
import io.github.sebbejohansson.yggdrasil.gjallar.AlertStore
import io.github.sebbejohansson.yggdrasil.gjallar.Severity
import io.github.sebbejohansson.yggdrasil.hermodr.Message
import io.github.sebbejohansson.yggdrasil.hermodr.MessageState
import io.github.sebbejohansson.yggdrasil.hermodr.MessageStore
import io.github.sebbejohansson.yggdrasil.hermodr.Priority
import io.github.sebbejohansson.yggdrasil.saga.AlertSnapshot
import io.github.sebbejohansson.yggdrasil.saga.Mirror
import io.github.sebbejohansson.yggdrasil.saga.Outgoing
import io.github.sebbejohansson.yggdrasil.saga.SagaStore
import java.time.Instant

/** One SQLite file for every part's state: `ygg.db`. Times are epoch millis. */
class YggDatabase(context: Context) : SQLiteOpenHelper(context, "ygg.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE alerts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source TEXT NOT NULL, key TEXT NOT NULL,
                severity TEXT NOT NULL, title TEXT NOT NULL, detail TEXT, link TEXT,
                state TEXT NOT NULL,
                opened_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL,
                resolved_at INTEGER, snoozed_until INTEGER, last_critical_push_at INTEGER)""",
        )
        db.execSQL("CREATE INDEX alerts_source_key ON alerts(source, key)")
        db.execSQL("CREATE TABLE mutes (source TEXT NOT NULL, key TEXT NOT NULL, PRIMARY KEY (source, key))")
        db.execSQL(
            """CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sender TEXT NOT NULL, title TEXT NOT NULL, text TEXT, link TEXT,
                priority TEXT NOT NULL, hold INTEGER NOT NULL, push INTEGER NOT NULL, tag TEXT,
                state TEXT NOT NULL, created_at INTEGER NOT NULL,
                sent_at INTEGER, seen_at INTEGER, failure TEXT)""",
        )
        db.execSQL("CREATE INDEX messages_state ON messages(state)")
        db.execSQL("CREATE INDEX messages_tag ON messages(tag)")
        createDellingr(db)
        createSaga(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createDellingr(db)
        if (oldVersion < 3) createSaga(db)
    }

    // Version 3: Sága's outbox, marks, what it sent, and the other devices' alerts.
    private fun createSaga(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE saga_outbox (seq INTEGER PRIMARY KEY, at INTEGER NOT NULL, kind TEXT NOT NULL, body TEXT NOT NULL)")
        db.execSQL("CREATE TABLE saga_marks (name TEXT PRIMARY KEY, value INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE saga_published (source TEXT NOT NULL, key TEXT NOT NULL, fingerprint TEXT NOT NULL, PRIMARY KEY (source, key))",
        )
        db.execSQL(
            """CREATE TABLE saga_mirrors (
                device TEXT NOT NULL, source TEXT NOT NULL, key TEXT NOT NULL, body TEXT NOT NULL,
                state TEXT NOT NULL, received_at INTEGER NOT NULL, PRIMARY KEY (device, source, key))""",
        )
    }

    // Version 2: Dellingr's jobs and their runs.
    private fun createDellingr(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE jobs (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, cron TEXT NOT NULL,
                kind TEXT NOT NULL, text TEXT NOT NULL, push INTEGER NOT NULL, enabled INTEGER NOT NULL,
                next_run_at INTEGER, last_run_at INTEGER, last_result TEXT, failures INTEGER NOT NULL)""",
        )
        db.execSQL(
            """CREATE TABLE runs (
                id TEXT PRIMARY KEY, job_id TEXT NOT NULL, job_name TEXT NOT NULL,
                started_at INTEGER NOT NULL, ended_at INTEGER, state TEXT NOT NULL,
                missed INTEGER NOT NULL, by_hand INTEGER NOT NULL, text TEXT, error TEXT)""",
        )
        db.execSQL("CREATE INDEX runs_started ON runs(started_at)")
    }

    val alerts: AlertStore by lazy { SqliteAlertStore(this) }
    val messages: MessageStore by lazy { SqliteMessageStore(this) }
    val jobs: JobStore by lazy { SqliteJobStore(this) }
    val saga: SagaStore by lazy { SqliteSagaStore(this) }
}

private fun Cursor.str(c: String): String = getString(getColumnIndexOrThrow(c))
private fun Cursor.strOrNull(c: String): String? = getColumnIndexOrThrow(c).let { if (isNull(it)) null else getString(it) }
private fun Cursor.long(c: String): Long = getLong(getColumnIndexOrThrow(c))
private fun Cursor.instant(c: String): Instant = Instant.ofEpochMilli(long(c))
private fun Cursor.instantOrNull(c: String): Instant? =
    getColumnIndexOrThrow(c).let { if (isNull(it)) null else Instant.ofEpochMilli(getLong(it)) }

private fun ContentValues.putInstant(k: String, v: Instant?) = if (v == null) putNull(k) else put(k, v.toEpochMilli())

private fun <T> Cursor.all(read: Cursor.() -> T): List<T> = use { c ->
    buildList { while (c.moveToNext()) add(c.read()) }
}

private class SqliteAlertStore(private val h: YggDatabase) : AlertStore {
    private fun Cursor.alert() = Alert(
        id = long("id"), source = str("source"), key = str("key"),
        severity = Severity.valueOf(str("severity")), title = str("title"),
        detail = strOrNull("detail"), link = strOrNull("link"), state = AlertState.valueOf(str("state")),
        openedAt = instant("opened_at"), lastSeenAt = instant("last_seen_at"),
        resolvedAt = instantOrNull("resolved_at"), snoozedUntil = instantOrNull("snoozed_until"),
        lastCriticalPushAt = instantOrNull("last_critical_push_at"),
    )

    private fun Alert.values() = ContentValues().apply {
        put("source", source); put("key", key); put("severity", severity.name); put("title", title)
        put("detail", detail); put("link", link); put("state", state.name)
        putInstant("opened_at", openedAt); putInstant("last_seen_at", lastSeenAt)
        putInstant("resolved_at", resolvedAt); putInstant("snoozed_until", snoozedUntil)
        putInstant("last_critical_push_at", lastCriticalPushAt)
    }

    override fun find(source: String, key: String) = h.readableDatabase.query(
        "alerts", null, "source = ? AND key = ?", arrayOf(source, key), null, null, "id DESC", "1",
    ).all { alert() }.firstOrNull()

    override fun get(id: Long) =
        h.readableDatabase.query("alerts", null, "id = ?", arrayOf("$id"), null, null, null).all { alert() }.firstOrNull()

    override fun insert(a: Alert) = h.writableDatabase.insertOrThrow("alerts", null, a.values())

    override fun update(a: Alert) {
        h.writableDatabase.update("alerts", a.values(), "id = ?", arrayOf("${a.id}"))
    }

    override fun list(states: Set<AlertState>, limit: Int): List<Alert> {
        if (states.isEmpty()) return emptyList()
        val marks = states.joinToString(",") { "?" }
        return h.readableDatabase.query(
            "alerts", null, "state IN ($marks)", states.map { it.name }.toTypedArray(),
            null, null, "last_seen_at DESC", "$limit",
        ).all { alert() }
    }

    override fun isMuted(source: String, key: String) = h.readableDatabase.query(
        "mutes", arrayOf("source"), "source = ? AND key = ?", arrayOf(source, key), null, null, null,
    ).use { it.moveToFirst() }

    override fun setMuted(source: String, key: String, muted: Boolean) {
        val db = h.writableDatabase
        if (muted) {
            db.insertWithOnConflict(
                "mutes", null, ContentValues().apply { put("source", source); put("key", key) },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        } else {
            db.delete("mutes", "source = ? AND key = ?", arrayOf(source, key))
        }
    }

    override fun deleteResolvedBefore(t: Instant) {
        h.writableDatabase.delete("alerts", "state = ? AND resolved_at < ?", arrayOf(AlertState.RESOLVED.name, "${t.toEpochMilli()}"))
    }
}

private class SqliteMessageStore(private val h: YggDatabase) : MessageStore {
    private fun Cursor.message() = Message(
        id = long("id"), sender = str("sender"), title = str("title"), text = strOrNull("text"),
        link = strOrNull("link"), priority = Priority.valueOf(str("priority")),
        hold = long("hold") != 0L, push = long("push") != 0L, tag = strOrNull("tag"),
        state = MessageState.valueOf(str("state")), createdAt = instant("created_at"),
        sentAt = instantOrNull("sent_at"), seenAt = instantOrNull("seen_at"), failure = strOrNull("failure"),
    )

    private fun Message.values() = ContentValues().apply {
        put("sender", sender); put("title", title); put("text", text); put("link", link)
        put("priority", priority.name); put("hold", if (hold) 1 else 0); put("push", if (push) 1 else 0)
        put("tag", tag); put("state", state.name); putInstant("created_at", createdAt)
        putInstant("sent_at", sentAt); putInstant("seen_at", seenAt); put("failure", failure)
    }

    private fun where(sel: String, args: Array<String>, order: String? = null, limit: String? = null) =
        h.readableDatabase.query("messages", null, sel, args, null, null, order, limit).all { message() }

    override fun insert(m: Message) = h.writableDatabase.insertOrThrow("messages", null, m.values())

    override fun update(m: Message) {
        h.writableDatabase.update("messages", m.values(), "id = ?", arrayOf("${m.id}"))
    }

    override fun get(id: Long) = where("id = ?", arrayOf("$id")).firstOrNull()
    override fun held() = where("state = ?", arrayOf(MessageState.HELD.name), "id ASC")
    override fun unseenWithTag(tag: String) = where("tag = ? AND seen_at IS NULL", arrayOf(tag))
    override fun recent(limit: Int) =
        h.readableDatabase.query("messages", null, null, null, null, null, "id DESC", "$limit").all { message() }

    override fun deleteCreatedBefore(t: Instant) {
        h.writableDatabase.delete("messages", "created_at < ?", arrayOf("${t.toEpochMilli()}"))
    }
}

private class SqliteJobStore(private val h: YggDatabase) : JobStore {
    private fun Cursor.job() = Job(
        id = str("id"), name = str("name"), cron = str("cron"), kind = ActionKind.valueOf(str("kind")),
        text = str("text"), push = long("push") != 0L, enabled = long("enabled") != 0L,
        nextRunAt = instantOrNull("next_run_at"), lastRunAt = instantOrNull("last_run_at"),
        lastResult = strOrNull("last_result")?.let(RunState::valueOf), failures = long("failures").toInt(),
    )

    private fun Job.values() = ContentValues().apply {
        put("id", id); put("name", name); put("cron", cron); put("kind", kind.name); put("text", text)
        put("push", if (push) 1 else 0); put("enabled", if (enabled) 1 else 0)
        putInstant("next_run_at", nextRunAt); putInstant("last_run_at", lastRunAt)
        put("last_result", lastResult?.name); put("failures", failures)
    }

    private fun Cursor.run() = Run(
        id = str("id"), jobId = str("job_id"), jobName = str("job_name"), startedAt = instant("started_at"),
        endedAt = instantOrNull("ended_at"), state = RunState.valueOf(str("state")),
        missed = long("missed") != 0L, byHand = long("by_hand") != 0L, text = strOrNull("text"), error = strOrNull("error"),
    )

    private fun Run.values() = ContentValues().apply {
        put("id", id); put("job_id", jobId); put("job_name", jobName); putInstant("started_at", startedAt)
        putInstant("ended_at", endedAt); put("state", state.name); put("missed", if (missed) 1 else 0)
        put("by_hand", if (byHand) 1 else 0); put("text", text); put("error", error)
    }

    override fun jobs() = h.readableDatabase.query("jobs", null, null, null, null, null, "name").all { job() }
    override fun job(id: String) =
        h.readableDatabase.query("jobs", null, "id = ?", arrayOf(id), null, null, null).all { job() }.firstOrNull()
    override fun putJob(job: Job) {
        h.writableDatabase.insertWithOnConflict("jobs", null, job.values(), SQLiteDatabase.CONFLICT_REPLACE)
    }
    override fun deleteJob(id: String) {
        h.writableDatabase.delete("runs", "job_id = ?", arrayOf(id))
        h.writableDatabase.delete("jobs", "id = ?", arrayOf(id))
    }
    override fun putRun(run: Run) {
        h.writableDatabase.insertWithOnConflict("runs", null, run.values(), SQLiteDatabase.CONFLICT_REPLACE)
    }
    override fun runs(jobId: String?, limit: Int) = h.readableDatabase.query(
        "runs", null, if (jobId == null) null else "job_id = ?", jobId?.let { arrayOf(it) },
        null, null, "started_at DESC", "$limit",
    ).all { run() }
    override fun forgetRunsBefore(t: Instant) {
        h.writableDatabase.delete("runs", "started_at < ?", arrayOf("${t.toEpochMilli()}"))
    }
}

private class SqliteSagaStore(private val h: YggDatabase) : SagaStore {
    private val db get() = h.writableDatabase

    override fun mark(name: String): Long? =
        db.query("saga_marks", arrayOf("value"), "name = ?", arrayOf(name), null, null, null)
            .use { if (it.moveToFirst()) it.getLong(0) else null }

    override fun setMark(name: String, value: Long) {
        db.insertWithOnConflict(
            "saga_marks", null, ContentValues().apply { put("name", name); put("value", value) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun enqueue(at: Instant, kind: String, body: String): Long = transaction {
        val seq = (mark("seq") ?: 0) + 1
        db.insertOrThrow(
            "saga_outbox", null,
            ContentValues().apply { put("seq", seq); put("at", at.toEpochMilli()); put("kind", kind); put("body", body) },
        )
        setMark("seq", seq)
        seq
    }

    override fun outbox(limit: Int) =
        db.query("saga_outbox", null, null, null, null, null, "seq ASC", "$limit").all {
            Outgoing(long("seq"), long("at"), str("kind"), str("body"))
        }

    override fun outboxSize() = db.rawQuery("SELECT COUNT(*) FROM saga_outbox", null).use { it.moveToFirst(); it.getInt(0) }

    override fun sent(lastSeq: Long) {
        db.delete("saga_outbox", "seq <= ?", arrayOf("$lastSeq"))
    }

    override fun renumber(lastSeq: Long) = transaction {
        val first = db.rawQuery("SELECT MIN(seq) FROM saga_outbox", null).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
        if (first == null) {
            setMark("seq", lastSeq)
        } else {
            val shift = lastSeq + 1 - first
            // Through negative numbers, so no two rows share a seq on the way.
            db.execSQL("UPDATE saga_outbox SET seq = -(seq + ?)", arrayOf(shift))
            db.execSQL("UPDATE saga_outbox SET seq = -seq")
            setMark("seq", db.rawQuery("SELECT MAX(seq) FROM saga_outbox", null).use { it.moveToFirst(); it.getLong(0) })
        }
    }

    override fun published(source: String, key: String): String? =
        db.query("saga_published", arrayOf("fingerprint"), "source = ? AND key = ?", arrayOf(source, key), null, null, null)
            .use { if (it.moveToFirst()) it.getString(0) else null }

    override fun setPublished(source: String, key: String, fingerprint: String) {
        db.insertWithOnConflict(
            "saga_published", null,
            ContentValues().apply { put("source", source); put("key", key); put("fingerprint", fingerprint) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun mirrors() = db.query("saga_mirrors", null, null, null, null, null, null).all {
        AlertSnapshot.parse(str("body"))?.let { Mirror(str("device"), it, instant("received_at")) }
    }.filterNotNull()

    override fun putMirror(m: Mirror) {
        db.insertWithOnConflict(
            "saga_mirrors", null,
            ContentValues().apply {
                put("device", m.device); put("source", m.alert.source); put("key", m.alert.key)
                put("body", m.alert.toJson().toString()); put("state", m.alert.state.name)
                putInstant("received_at", m.receivedAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun deleteMirrorsResolvedBefore(t: Instant) {
        db.delete("saga_mirrors", "state = ? AND received_at < ?", arrayOf(AlertState.RESOLVED.name, "${t.toEpochMilli()}"))
    }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            return block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }
}
