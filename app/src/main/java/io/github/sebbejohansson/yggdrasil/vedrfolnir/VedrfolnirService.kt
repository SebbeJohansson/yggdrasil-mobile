package io.github.sebbejohansson.yggdrasil.vedrfolnir

import io.github.sebbejohansson.yggdrasil.gjallar.Gjallar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Reads the phone. [AndroidPhoneReader] in the app, a fake in tests. */
fun interface PhoneReader {
    fun read(): Readings
}

/**
 * Veðrfölnir, the hawk between the eagle's eyes: it reports on the machine,
 * here the phone itself. Each [check] reads the phone, raises an alert for
 * every failing check and resolves the ones that pass again. Like the
 * desktop part it fixes nothing.
 */
class VedrfolnirService(
    private val reader: PhoneReader,
    private val gjallar: Gjallar,
    private val thresholds: () -> Thresholds = { Thresholds() },
) {
    private val _latest = MutableStateFlow<Readings?>(null)

    /** The last readings, for the screen. */
    val latest: StateFlow<Readings?> = _latest.asStateFlow()

    private val _failing = MutableStateFlow<Set<String>>(emptySet())

    /** Keys of the checks failing at the last [check]. */
    val failing: StateFlow<Set<String>> = _failing.asStateFlow()

    private val lock = Mutex()

    /** Reads the phone, without raising anything. Cheap; the screen calls it every few seconds. */
    fun sample(): Readings = reader.read().also { _latest.value = it }

    suspend fun check(): List<Check> = lock.withLock {
        val r = sample()
        val checks = Checks.of(r, thresholds())
        for (c in checks) {
            if (c.ok) gjallar.resolve(SOURCE, c.key)
            else gjallar.raise(SOURCE, c.key, c.severity, c.title, c.detail)
        }
        _failing.value = checks.filterNot { it.ok }.map { it.key }.toSet()
        checks
    }

    companion object {
        const val SOURCE = "vedrfolnir"
    }
}
