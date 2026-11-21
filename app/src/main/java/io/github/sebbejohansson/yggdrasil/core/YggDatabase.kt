package io.github.sebbejohansson.yggdrasil.core

import java.time.LocalDate
import io.github.sebbejohansson.yggdrasil.norn.PromiseStore
import io.github.sebbejohansson.yggdrasil.norn.PromiseState
import io.github.sebbejohansson.yggdrasil.norn.Promise
import io.github.sebbejohansson.yggdrasil.hermodr.Focus
import io.github.sebbejohansson.yggdrasil.hermodr.FocusSession
import io.github.sebbejohansson.yggdrasil.hermodr.FocusStore
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
import io.github.sebbejohansson.yggdrasil.huginn.HuginnStore
import io.github.sebbejohansson.yggdrasil.huginn.Reaction
import io.github.sebbejohansson.yggdrasil.huginn.ReactionState
import io.github.sebbejohansson.yggdrasil.huginn.Rule
import io.github.sebbejohansson.yggdrasil.huginn.Trigger
import io.github.sebbejohansson.yggdrasil.llm.LlmRole
import io.github.sebbejohansson.yggdrasil.norn.ChatLine
import io.github.sebbejohansson.yggdrasil.norn.ChatStore
import io.github.sebbejohansson.yggdrasil.norn.Conversation
import io.github.sebbejohansson.yggdrasil.norn.Memory
import io.github.sebbejohansson.yggdrasil.norn.MemoryStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import io.github.sebbejohansson.yggdrasil.history.HistoryEvent
import io.github.sebbejohansson.yggdrasil.history.HistoryKind
import io.github.sebbejohansson.yggdrasil.history.HistoryStore
import org.json.JSONArray
import org.json.JSONObject
import io.github.sebbejohansson.yggdrasil.gjallar.AlertState
import io.github.sebbejohansson.yggdrasil.gjallar.AlertStore
import io.github.sebbejohansson.yggdrasil.gjallar.Severity
import io.github.sebbejohansson.yggdrasil.hermodr.Message
import io.github.sebbejohansson.yggdrasil.hermodr.MessageState
import io.github.sebbejohansson.yggdrasil.hermodr.MessageStore
import io.github.sebbejohansson.yggdrasil.hermodr.Priority
import io.github.sebbejohansson.yggdrasil.saga.AlertSnapshot
import io.github.sebbejohansson.yggdrasil.saga.AskState
import io.github.sebbejohansson.yggdrasil.saga.Asked
import io.github.sebbejohansson.yggdrasil.saga.DeviceSessions
import io.github.sebbejohansson.yggdrasil.saga.Mirror
import io.github.sebbejohansson.yggdrasil.saga.Outgoing
import io.github.sebbejohansson.yggdrasil.saga.SagaStore
import io.github.sebbejohansson.yggdrasil.saga.SessionAsk
import java.time.Instant

/** One SQLite file for every part's state: `ygg.db`. Times are epoch millis. */
class YggDatabase(context: Context) : SQLiteOpenHelper(context, "ygg.db", null, 10) {

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
        createHuginn(db)
        createNorn(db)
        createHistory(db)
        createSaga(db)
        createSagaSessions(db)
        createFocus(db)
        createPromises(db)
        createPerf(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 5) createHistory(db)
        if (oldVersion < 2) createDellingr(db)
        if (oldVersion < 3) createHuginn(db)
        if (oldVersion < 4) createNorn(db)
        if (oldVersion < 6) createSaga(db)
        if (oldVersion < 7) createSagaSessions(db)
        if (oldVersion < 8) createFocus(db)
        if (oldVersion < 9) createPromises(db)
        if (oldVersion < 10) createPerf(db)
    }

    // Version 10: what the parts pull, for the Performance page. A week is kept.
    private fun createPerf(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS perf (
                id INTEGER PRIMARY KEY AUTOINCREMENT, part TEXT NOT NULL, kind TEXT NOT NULL, at INTEGER NOT NULL,
                ms INTEGER NOT NULL, ok INTEGER NOT NULL, failure TEXT, who TEXT, amount INTEGER NOT NULL DEFAULT 0)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS perf_at ON perf(at)")
    }

    // Version 9: the promise ledger.
    private fun createPromises(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS promises (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_key TEXT NOT NULL, norm TEXT NOT NULL,
                what TEXT NOT NULL, to_name TEXT NOT NULL DEFAULT '', due TEXT,
                state TEXT NOT NULL, source TEXT NOT NULL, said TEXT,
                created_at INTEGER NOT NULL, changed_at INTEGER NOT NULL, reminded_at INTEGER,
                UNIQUE (source_key, norm))""",
        )
    }

    // Version 8: the focus going on (one row), and the log of every focus.
    private fun createFocus(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS focus (id INTEGER PRIMARY KEY CHECK (id = 1), started INTEGER NOT NULL, until INTEGER NOT NULL, intent TEXT)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS focus_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT, started INTEGER NOT NULL, ended INTEGER, until INTEGER NOT NULL, intent TEXT)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS focus_log_started ON focus_log(started)")
    }

    // Version 7: other machines' coding sessions, and what you asked of them.
    private fun createSagaSessions(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS saga_device_sessions (device TEXT PRIMARY KEY, at INTEGER NOT NULL, body TEXT NOT NULL)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS saga_asked (
                id TEXT PRIMARY KEY, kind TEXT NOT NULL, body TEXT NOT NULL, at INTEGER NOT NULL,
                state TEXT NOT NULL, error TEXT, url TEXT)""",
        )
    }

    // Version 6: Sága's outbox, marks, what it sent, and the other devices' alerts.
    private fun createSaga(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS saga_outbox (seq INTEGER PRIMARY KEY, at INTEGER NOT NULL, kind TEXT NOT NULL, body TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS saga_marks (name TEXT PRIMARY KEY, value INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS saga_published (source TEXT NOT NULL, key TEXT NOT NULL, fingerprint TEXT NOT NULL, PRIMARY KEY (source, key))",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS saga_mirrors (
                device TEXT NOT NULL, source TEXT NOT NULL, key TEXT NOT NULL, body TEXT NOT NULL,
                state TEXT NOT NULL, received_at INTEGER NOT NULL, PRIMARY KEY (device, source, key))""",
        )
    }

    // Version 5: the world tree's history, kept for good.
    private fun createHistory(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS history (
                id TEXT PRIMARY KEY, kind TEXT NOT NULL, at INTEGER NOT NULL, words TEXT NOT NULL, started_at INTEGER)""",
        )
    }

    // Version 4: Norn's memories and saved chats.
    private fun createNorn(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE memories (
                id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, source TEXT NOT NULL,
                created_at INTEGER NOT NULL, embedding BLOB, model TEXT)""",
        )
        db.execSQL("CREATE TABLE conversations (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL(
            """CREATE TABLE chat_lines (
                id INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id INTEGER NOT NULL,
                role TEXT NOT NULL, shown TEXT NOT NULL, sent TEXT NOT NULL, at INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE INDEX chat_lines_conv ON chat_lines(conversation_id, id)")
    }

    // Version 3: Huginn's rules, what it did, and the calendar events it already told.
    private fun createHuginn(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE huginn_rules (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, built_in INTEGER NOT NULL, enabled INTEGER NOT NULL,
                trigger_json TEXT NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL,
                push INTEGER NOT NULL, hold INTEGER NOT NULL)""",
        )
        db.execSQL(
            """CREATE TABLE huginn_reactions (
                id TEXT PRIMARY KEY, rule_id TEXT NOT NULL, rule_name TEXT NOT NULL, event TEXT NOT NULL,
                event_at INTEGER NOT NULL, title TEXT NOT NULL, text TEXT, state TEXT NOT NULL, reason TEXT, at INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE INDEX huginn_reactions_at ON huginn_reactions(at)")
        db.execSQL("CREATE TABLE huginn_seen (key TEXT PRIMARY KEY, at INTEGER NOT NULL)")
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
    val huginn: HuginnStore by lazy { SqliteHuginnStore(this) }
    val memories: MemoryStore by lazy { SqliteMemoryStore(this) }
    val chats: ChatStore by lazy { SqliteChatStore(this) }
    val history: HistoryStore by lazy { SqliteHistoryStore(this) }
    val saga: SagaStore by lazy { SqliteSagaStore(this) }
    val focus: FocusStore by lazy { SqliteFocusStore(this) }
    val promises: PromiseStore by lazy { SqlitePromiseStore(this) }
    val perf: PerfStore by lazy { SqlitePerfStore(this) }
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

private class SqliteHuginnStore(private val h: YggDatabase) : HuginnStore {
    private fun Trigger.json() = JSONObject().apply {
        put("event", event); put("app", app); put("from", from); put("text", text); put("source", source)
        important?.let { put("important", it) }
        urgent?.let { put("urgent", it) }
        direct?.let { put("direct", it) }
        put("severity", JSONArray(severity.toList()))
    }.toString()

    private fun trigger(s: String): Trigger {
        val o = JSONObject(s)
        fun bool(k: String) = if (o.has(k)) o.getBoolean(k) else null
        val sev = o.optJSONArray("severity")
        return Trigger(
            event = o.getString("event"), app = o.optString("app"), from = o.optString("from"), text = o.optString("text"),
            important = bool("important"), urgent = bool("urgent"), direct = bool("direct"),
            severity = if (sev == null) emptySet() else (0 until sev.length()).map { sev.getString(it) }.toSet(),
            source = o.optString("source"),
        )
    }

    private fun Cursor.rule() = Rule(
        id = str("id"), name = str("name"), on = trigger(str("trigger_json")),
        kind = io.github.sebbejohansson.yggdrasil.huginn.ActionKind.valueOf(str("kind")), text = str("text"),
        push = long("push") != 0L, hold = long("hold") != 0L, enabled = long("enabled") != 0L, builtIn = long("built_in") != 0L,
    )

    private fun Cursor.reaction() = Reaction(
        id = str("id"), ruleId = str("rule_id"), ruleName = str("rule_name"), event = str("event"),
        eventAt = instant("event_at"), title = str("title"), text = strOrNull("text"),
        state = ReactionState.valueOf(str("state")), reason = strOrNull("reason"), at = instant("at"),
    )

    override fun rules() = h.readableDatabase.query("huginn_rules", null, null, null, null, null, null).all { rule() }

    override fun putRule(rule: Rule) {
        val v = ContentValues().apply {
            put("id", rule.id); put("name", rule.name); put("built_in", if (rule.builtIn) 1 else 0)
            put("enabled", if (rule.enabled) 1 else 0); put("trigger_json", rule.on.json()); put("kind", rule.kind.name)
            put("text", rule.text); put("push", if (rule.push) 1 else 0); put("hold", if (rule.hold) 1 else 0)
        }
        h.writableDatabase.insertWithOnConflict("huginn_rules", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun deleteRule(id: String) {
        h.writableDatabase.delete("huginn_rules", "id = ?", arrayOf(id))
    }

    override fun putReaction(r: Reaction) {
        val v = ContentValues().apply {
            put("id", r.id); put("rule_id", r.ruleId); put("rule_name", r.ruleName); put("event", r.event)
            putInstant("event_at", r.eventAt); put("title", r.title); put("text", r.text); put("state", r.state.name)
            put("reason", r.reason); putInstant("at", r.at)
        }
        h.writableDatabase.insertWithOnConflict("huginn_reactions", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun reactions(limit: Int) =
        h.readableDatabase.query("huginn_reactions", null, null, null, null, null, "at DESC", "$limit").all { reaction() }

    override fun forgetReactionsBefore(t: Instant) {
        h.writableDatabase.delete("huginn_reactions", "at < ?", arrayOf("${t.toEpochMilli()}"))
    }

    override fun firstSight(key: String, at: Instant): Boolean {
        val v = ContentValues().apply { put("key", key); putInstant("at", at) }
        return h.writableDatabase.insertWithOnConflict("huginn_seen", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    override fun forgetSightsBefore(t: Instant) {
        h.writableDatabase.delete("huginn_seen", "at < ?", arrayOf("${t.toEpochMilli()}"))
    }
}

private fun FloatArray.toBlob(): ByteArray =
    ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> forEach { b.putFloat(it) } }.array()

private fun ByteArray.toFloats(): FloatArray {
    val b = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(size / 4) { b.getFloat() }
}

private class SqliteMemoryStore(private val h: YggDatabase) : MemoryStore {
    private fun Cursor.memory(): Memory {
        val blob = getColumnIndexOrThrow("embedding").let { if (isNull(it)) null else getBlob(it) }
        return Memory(long("id"), str("text"), str("source"), instant("created_at"), blob?.toFloats(), strOrNull("model"))
    }

    override fun memories() = h.readableDatabase.query("memories", null, null, null, null, null, "id DESC").all { memory() }

    override fun add(text: String, source: String, at: Instant, embedding: FloatArray?, model: String?): Memory {
        val v = ContentValues().apply {
            put("text", text); put("source", source); putInstant("created_at", at)
            if (embedding != null) put("embedding", embedding.toBlob()) else putNull("embedding")
            put("model", model)
        }
        val id = h.writableDatabase.insertOrThrow("memories", null, v)
        return Memory(id, text, source, at, embedding, model)
    }

    override fun setEmbedding(id: Long, embedding: FloatArray, model: String) {
        val v = ContentValues().apply { put("embedding", embedding.toBlob()); put("model", model) }
        h.writableDatabase.update("memories", v, "id = ?", arrayOf("$id"))
    }

    override fun delete(id: Long) {
        h.writableDatabase.delete("memories", "id = ?", arrayOf("$id"))
    }
}

private class SqliteChatStore(private val h: YggDatabase) : ChatStore {
    override fun conversations(limit: Int) = h.readableDatabase.query(
        "conversations", null, null, null, null, null, "updated_at DESC", "$limit",
    ).all { Conversation(long("id"), str("title"), instant("updated_at")) }

    override fun lines(conversationId: Long) = h.readableDatabase.query(
        "chat_lines", null, "conversation_id = ?", arrayOf("$conversationId"), null, null, "id ASC",
    ).all { ChatLine(LlmRole.valueOf(str("role")), str("shown"), str("sent")) }

    override fun create(title: String, at: Instant): Long {
        val v = ContentValues().apply { put("title", title); putInstant("updated_at", at) }
        return h.writableDatabase.insertOrThrow("conversations", null, v)
    }

    override fun append(conversationId: Long, line: ChatLine, at: Instant) {
        val db = h.writableDatabase
        db.insertOrThrow("chat_lines", null, ContentValues().apply {
            put("conversation_id", conversationId); put("role", line.role.name)
            put("shown", line.shown); put("sent", line.sent); putInstant("at", at)
        })
        db.update("conversations", ContentValues().apply { putInstant("updated_at", at) }, "id = ?", arrayOf("$conversationId"))
    }

    override fun delete(conversationId: Long) {
        h.writableDatabase.delete("chat_lines", "conversation_id = ?", arrayOf("$conversationId"))
        h.writableDatabase.delete("conversations", "id = ?", arrayOf("$conversationId"))
    }
}

private class SqlitePerfStore(private val h: YggDatabase) : PerfStore {
    override fun add(step: PerfStep) {
        h.writableDatabase.insert("perf", null, ContentValues().apply {
            put("part", step.part); put("kind", step.kind); put("at", step.at.toEpochMilli()); put("ms", step.ms)
            put("ok", if (step.ok) 1 else 0); put("failure", step.failure); put("who", step.who); put("amount", step.amount)
        })
    }

    override fun since(t: Instant) =
        h.readableDatabase.query("perf", null, "at >= ?", arrayOf(t.toEpochMilli().toString()), null, null, "at ASC").all {
            PerfStep(str("part"), str("kind"), instant("at"), long("ms"), long("ok") == 1L, strOrNull("failure"), strOrNull("who"), long("amount"))
        }

    override fun deleteBefore(t: Instant) {
        h.writableDatabase.delete("perf", "at < ?", arrayOf(t.toEpochMilli().toString()))
    }
}

private class SqlitePromiseStore(private val h: YggDatabase) : PromiseStore {
    private val db get() = h.writableDatabase

    private fun Cursor.promise() = Promise(
        long("id"), str("what"), str("to_name"), strOrNull("due")?.let(LocalDate::parse), PromiseState.valueOf(str("state")),
        str("source"), strOrNull("said"), instant("created_at"), instant("changed_at"), instantOrNull("reminded_at"),
    )

    private fun values(p: Promise) = ContentValues().apply {
        put("what", p.what); put("to_name", p.to); put("due", p.due?.toString()); put("state", p.state.name); put("source", p.source)
        put("said", p.said); put("created_at", p.createdAt.toEpochMilli()); put("changed_at", p.changedAt.toEpochMilli())
        if (p.remindedAt != null) put("reminded_at", p.remindedAt.toEpochMilli()) else putNull("reminded_at")
    }

    override fun all() = db.query("promises", null, null, null, null, null, "id DESC").all { promise() }

    override fun get(id: Long) = db.query("promises", null, "id = ?", arrayOf(id.toString()), null, null, null).all { promise() }.firstOrNull()

    override fun add(p: Promise, sourceKey: String, norm: String): Long? {
        val v = values(p).apply { put("source_key", sourceKey); put("norm", norm) }
        return db.insertWithOnConflict("promises", null, v, SQLiteDatabase.CONFLICT_IGNORE).takeIf { it > 0 }
    }

    override fun update(p: Promise) {
        db.update("promises", values(p), "id = ?", arrayOf(p.id.toString()))
    }

    override fun deleteClosedBefore(t: Instant) {
        db.delete("promises", "state != 'OPEN' AND changed_at < ?", arrayOf(t.toEpochMilli().toString()))
    }
}

private class SqliteFocusStore(private val h: YggDatabase) : FocusStore {
    private val db get() = h.writableDatabase

    override fun current(): Focus? = db.query("focus", null, "id = 1", null, null, null, null).all {
        Focus(instant("started"), instant("until"), strOrNull("intent"))
    }.firstOrNull()

    override fun set(focus: Focus?) {
        if (focus == null) {
            db.delete("focus", "id = 1", null)
            return
        }
        val v = ContentValues().apply {
            put("id", 1); put("started", focus.startedAt.toEpochMilli()); put("until", focus.until.toEpochMilli()); put("intent", focus.intent)
        }
        db.insertWithOnConflict("focus", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun opened(focus: Focus) {
        db.insert("focus_log", null, ContentValues().apply {
            put("started", focus.startedAt.toEpochMilli()); put("until", focus.until.toEpochMilli()); put("intent", focus.intent)
        })
    }

    override fun extended(focus: Focus) {
        db.update("focus_log", ContentValues().apply { put("until", focus.until.toEpochMilli()); put("intent", focus.intent) }, "ended IS NULL", null)
    }

    override fun closed(at: Instant) {
        db.update("focus_log", ContentValues().apply { put("ended", at.toEpochMilli()) }, "ended IS NULL", null)
    }

    override fun sessions(since: Instant) =
        db.query("focus_log", null, "started >= ?", arrayOf(since.toEpochMilli().toString()), null, null, "started ASC").all {
            FocusSession(instant("started"), instantOrNull("ended"), instant("until"), strOrNull("intent"))
        }
}

private class SqliteHistoryStore(private val h: YggDatabase) : HistoryStore {
    override fun record(e: HistoryEvent) {
        val v = ContentValues().apply {
            put("id", e.id); put("kind", e.kind.name); put("at", e.at); put("words", e.words)
            if (e.startedAt != null) put("started_at", e.startedAt) else putNull("started_at")
        }
        h.writableDatabase.insertWithOnConflict("history", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    override fun all() = h.readableDatabase.query("history", null, null, null, null, null, "at ASC").all {
        HistoryEvent(HistoryKind.valueOf(str("kind")), str("id"), long("at"), str("words"),
            getColumnIndexOrThrow("started_at").let { if (isNull(it)) null else getLong(it) })
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

    override fun putDeviceSessions(d: DeviceSessions, body: String) {
        val kept = db.query("saga_device_sessions", arrayOf("at"), "device = ?", arrayOf(d.device), null, null, null)
            .use { if (it.moveToFirst()) it.getLong(0) else null }
        if (kept != null && kept > d.heardAt.toEpochMilli()) return
        db.insertWithOnConflict(
            "saga_device_sessions", null,
            ContentValues().apply { put("device", d.device); putInstant("at", d.heardAt); put("body", body) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun deviceSessions() = db.query("saga_device_sessions", null, null, null, null, null, "device ASC").all {
        DeviceSessions.parse(str("device"), str("body"), long("at"))
    }.filterNotNull()

    override fun putAsked(a: Asked) {
        db.insertWithOnConflict(
            "saga_asked", null,
            ContentValues().apply {
                put("id", a.ask.id); put("kind", a.ask.kind); put("body", a.ask.toJson().toString())
                putInstant("at", a.at); put("state", a.state.name); put("error", a.error); put("url", a.url)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun asked(since: Instant) =
        db.query("saga_asked", null, "at >= ?", arrayOf("${since.toEpochMilli()}"), null, null, "at DESC", "100").all {
            SessionAsk.parse(str("kind"), str("body"))?.let { ask ->
                Asked(
                    ask, instant("at"),
                    AskState.entries.firstOrNull { it.name == str("state") } ?: AskState.WAITING,
                    strOrNull("error"), strOrNull("url"),
                )
            }
        }.filterNotNull()

    override fun answerAsked(id: String, state: AskState, error: String?, url: String?) {
        db.update(
            "saga_asked",
            ContentValues().apply { put("state", state.name); put("error", error); put("url", url) },
            "id = ?", arrayOf(id),
        )
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
