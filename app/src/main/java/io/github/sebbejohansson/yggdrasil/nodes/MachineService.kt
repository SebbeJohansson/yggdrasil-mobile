package io.github.sebbejohansson.yggdrasil.nodes

import io.github.sebbejohansson.yggdrasil.saga.AskState
import io.github.sebbejohansson.yggdrasil.saga.Asked
import io.github.sebbejohansson.yggdrasil.saga.DeviceSessions
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
) {
    private val _status = MutableStateFlow<List<MachineStatus>>(emptyList())
    /** How each machine answered last. */
    val status: StateFlow<List<MachineStatus>> = _status.asStateFlow()

    private val _sessions = MutableStateFlow<List<DeviceSessions>>(emptyList())
    /** The sessions of every machine that answered last time. */
    val sessions: StateFlow<List<DeviceSessions>> = _sessions.asStateFlow()

    private val _asked = MutableStateFlow<List<Asked>>(emptyList())
    /** What was asked of a machine directly, and how it went, newest first. */
    val asked: StateFlow<List<Asked>> = _asked.asStateFlow()

    fun machines(): List<Machine> = settings.list()

    /** Asks every machine for its health and sessions, all at once. */
    suspend fun refresh() = withContext(io) {
        val reads = coroutineScope {
            settings.list().map { machine -> async { read(machine) } }.awaitAll()
        }
        _status.value = reads.map { it.first }
        _sessions.value = reads.mapNotNull { it.second }
    }

    private fun read(machine: Machine): Pair<MachineStatus, DeviceSessions?> {
        val client = clientFor(machine, settings.token(machine.id))
        val health = runCatching { client.health() }.getOrElse {
            return MachineStatus(machine, error = it.message ?: "not answering") to null
        }
        if (health.work) return MachineStatus(machine, health, refused = true) to null
        return runCatching { client.sessions(me(), clock.instant()) }.fold(
            onSuccess = { MachineStatus(machine, health) to it },
            onFailure = { MachineStatus(machine, health, error = it.message ?: "sessions not answering") to null },
        )
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

    fun save(machine: Machine, token: String) = settings.save(machine, token)

    fun remove(id: String) {
        settings.remove(id)
        _status.value = _status.value.filter { it.machine.id != id }
        _sessions.value = _sessions.value.filter { it.device != id }
    }
}
