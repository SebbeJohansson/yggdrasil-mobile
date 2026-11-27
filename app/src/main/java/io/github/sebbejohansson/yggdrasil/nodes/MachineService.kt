package io.github.sebbejohansson.yggdrasil.nodes

import io.github.sebbejohansson.yggdrasil.saga.AskState
import io.github.sebbejohansson.yggdrasil.saga.Asked
import io.github.sebbejohansson.yggdrasil.saga.DeviceSessions
import io.github.sebbejohansson.yggdrasil.saga.Mirror
import io.github.sebbejohansson.yggdrasil.saga.SessionAsk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.Clock
import kotlin.coroutines.CoroutineContext

/**
 * The machines reached directly, read and driven now (nodes/Machines.kt).
 * A machine whose /health says it is a work machine is never read or driven
 * from here: the tailnet should not let the phone reach it at all, and this
 * holds even if it does.
 */
class MachineService(
    private val settings: MachineSettings,
    /** This phone's name, as Mímir knows it: what the Code tab drives as. */
    private val me: () -> String,
    private val clock: Clock = Clock.systemUTC(),
    private val io: CoroutineContext = Dispatchers.IO,
    private val clientFor: (Machine, String) -> MachineClient = ::MachineClient,
    /** The newest Yggdrasil release's tag, or null when unknown (nodes/YggReleases.kt); true asks GitHub now. */
    private val latestRelease: (Boolean) -> String? = { null },
) {
    private val _latest = MutableStateFlow<String?>(null)
    /** The newest Yggdrasil release, to say which machines are behind it. */
    val latest: StateFlow<String?> = _latest.asStateFlow()

    private val _status = MutableStateFlow<List<MachineStatus>>(emptyList())
    /** How each machine answered last. */
    val status: StateFlow<List<MachineStatus>> = _status.asStateFlow()

    private val _sessions = MutableStateFlow<List<DeviceSessions>>(emptyList())
    /** The sessions of every machine that answered last time. */
    val sessions: StateFlow<List<DeviceSessions>> = _sessions.asStateFlow()

    private val _alerts = MutableStateFlow<List<Mirror>>(emptyList())
    /** The open alerts of every machine whose Gjallar answered, as the Inbox shows another machine's. */
    val alerts: StateFlow<List<Mirror>> = _alerts.asStateFlow()

    private val _goals = MutableStateFlow<Map<String, List<MachineGoal>>>(emptyMap())
    /** Each machine's goals in play, by machine id, as read last time. */
    val goals: StateFlow<Map<String, List<MachineGoal>>> = _goals.asStateFlow()

    private val _asked = MutableStateFlow<List<Asked>>(emptyList())
    /** What was asked of a machine directly, and how it went, newest first. */
    val asked: StateFlow<List<Asked>> = _asked.asStateFlow()

    fun machines(): List<Machine> = settings.list()

    /** Asks every machine for its health and sessions, all at once. */
    suspend fun refresh() = refresh(force = false)

    /**
     * Check for updates: asks GitHub for the newest Yggdrasil release now,
     * past the 30-minute cache, then reads every machine again, so each card
     * says at once whether it is behind. Null when GitHub did not answer.
     */
    suspend fun checkForUpdates(): String? {
        refresh(force = true)
        return _latest.value
    }

    private suspend fun refresh(force: Boolean) = withContext(io) {
        if (force || settings.list().isNotEmpty()) _latest.value = runCatching { latestRelease(force) }.getOrNull() ?: _latest.value
        val reads = coroutineScope {
            settings.list().map { machine -> async { read(machine) } }.awaitAll()
        }
        _status.value = reads.map { it.status }
        _sessions.value = reads.mapNotNull { it.sessions }
        _alerts.value = reads.flatMap { it.alerts }
        _alertsFrom.value = reads.filter { it.alertsRead }.map { it.status.machine.id }.toSet()
        _goals.value = reads.mapNotNull { r -> r.goals?.let { r.status.machine.id to it } }.toMap()
    }

    private val _alertsFrom = MutableStateFlow<Set<String>>(emptySet())
    /** The machines whose Gjallar answered last time: their Mímir copies of alerts are left out. */
    val alertsFrom: StateFlow<Set<String>> = _alertsFrom.asStateFlow()

    private class Read(
        val status: MachineStatus,
        val sessions: DeviceSessions? = null,
        val alerts: List<Mirror> = emptyList(),
        val alertsRead: Boolean = false,
        val goals: List<MachineGoal>? = null,
    )

    /**
     * One machine: its health first, and nothing more from a work machine;
     * then its sessions, its parts and its alerts. A part that does not answer
     * leaves only its own piece out.
     */
    private fun read(machine: Machine): Read {
        val client = clientFor(machine, settings.token(machine.id))
        val health = runCatching { client.health() }.getOrElse {
            return Read(MachineStatus(machine, error = it.message ?: "not answering"))
        }
        if (health.work) return Read(MachineStatus(machine, health, refused = true))
        val now = clock.instant()
        val sessions = runCatching { client.sessions(me(), now) }
        val parts = runCatching { client.parts() }.getOrDefault(emptyList())
        val alerts = runCatching { client.alerts() }
        return Read(
            MachineStatus(machine, health, error = sessions.exceptionOrNull()?.let { it.message ?: "sessions not answering" }, parts = parts),
            sessions.getOrNull(),
            alerts.getOrDefault(emptyList()).map { Mirror(machine.id, it.alert, now, directId = it.id, label = machine.label) },
            alertsRead = alerts.isSuccess,
            goals = runCatching { Goals.inPlay(client.goals()) }.getOrNull(),
        )
    }

    /** Seen, Snooze or Mute on a machine's alert, at its Gjallar now; then reads again. */
    suspend fun onAlert(m: Mirror, act: (MachineClient) -> Unit) = withContext(io) {
        val machine = settings.list().firstOrNull { it.id == m.device } ?: return@withContext
        if (_status.value.any { it.machine.id == machine.id && it.refused }) return@withContext
        runCatching { act(clientFor(machine, settings.token(machine.id))) }
        runCatching { refresh() }
    }

    /** Whether [device] is a machine reached directly, which answered last time. */
    fun isDirect(device: String): Boolean = _sessions.value.any { it.device == device }

    /** Does [ask] on its machine now, and reads it again so the Code tab shows the result. */
    suspend fun ask(ask: SessionAsk) = withContext(io) {
        val machine = settings.list().firstOrNull { it.id == ask.origin }
        val pane = _sessions.value.firstOrNull { it.device == ask.origin }?.sessions?.firstOrNull { it.id == ask.session }?.pane
        val result = when {
            machine == null -> Result.failure(IllegalStateException("no machine called ${ask.origin} here"))
            _status.value.any { it.machine.id == machine.id && it.refused } -> Result.failure(IllegalStateException("${machine.label} is a work machine"))
            else -> Machines.call(ask, pane).mapCatching { clientFor(machine, settings.token(machine.id)).send(it) }
        }
        val done = Asked(
            ask = ask,
            at = clock.instant(),
            state = if (result.isSuccess) AskState.DONE else AskState.FAILED,
            error = result.exceptionOrNull()?.message,
            url = result.getOrNull(),
        )
        _asked.value = (listOf(done) + _asked.value).take(50)
        if (machine != null) runCatching { refresh() }
    }

    /**
     * Asks [question] of [id]'s Norn, the assistant the phone's Ask tab uses
     * when it is set to that machine (Settings → Assistant → Answered by).
     * The machine is asked for its health first: a work machine is never asked.
     */
    suspend fun askNorn(id: String, question: String, session: String): String = withContext(io) {
        val machine = settings.list().firstOrNull { it.id == id } ?: error("no machine called $id here any more")
        val client = clientFor(machine, settings.token(machine.id))
        if (client.health().work) error("${machine.label} is a work machine: it never answers the phone")
        client.ask(question, session)
    }

    /** The machines whose Norn was up last time, for the Answered by choice. */
    fun withNorn(): List<Machine> = _status.value.filter { s -> !s.refused && s.parts.any { it.name == "norn" && it.up } }.map { it.machine }

    /** A goal action on a machine (new, start, resume), then a read so the Code tab shows it. Null, or why it failed. */
    suspend fun onGoal(machineId: String, act: (MachineClient) -> Unit): String? = withContext(io) {
        val machine = settings.list().firstOrNull { it.id == machineId } ?: return@withContext "no machine called $machineId here"
        if (_status.value.any { it.machine.id == machineId && it.refused }) return@withContext "${machine.label} is a work machine"
        val failed = runCatching { act(clientFor(machine, settings.token(machine.id))) }.exceptionOrNull()
        runCatching { refresh() }
        failed?.let { it.message ?: "it did not work" }
    }

    /** Something read or done on [machineId]'s parts, off the main thread; refused for a work machine. */
    suspend fun <T> on(machineId: String, act: (MachineClient) -> T): Result<T> = withContext(io) {
        val machine = settings.list().firstOrNull { it.id == machineId }
            ?: return@withContext Result.failure(IllegalStateException("no machine called $machineId here"))
        if (_status.value.any { it.machine.id == machineId && it.refused }) {
            return@withContext Result.failure(IllegalStateException("${machine.label} is a work machine"))
        }
        runCatching { act(clientFor(machine, settings.token(machine.id))) }
    }

    /** The end of a session's transcript on [machineId], or unchanged since [since]. */
    suspend fun transcript(machineId: String, session: String, directory: String?, tail: Int, since: String?): RemoteTranscript = withContext(io) {
        val machine = settings.list().firstOrNull { it.id == machineId } ?: error("no machine called $machineId here")
        if (_status.value.any { it.machine.id == machineId && it.refused }) error("${machine.label} is a work machine")
        clientFor(machine, settings.token(machine.id)).transcript(session, directory, tail, since)
    }

    fun save(machine: Machine, token: String) = settings.save(machine, token)

    fun remove(id: String) {
        settings.remove(id)
        _status.value = _status.value.filter { it.machine.id != id }
        _sessions.value = _sessions.value.filter { it.device != id }
        _alerts.value = _alerts.value.filter { it.device != id }
    }
}
