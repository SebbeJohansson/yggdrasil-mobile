package io.github.sebbejohansson.yggdrasil.saga

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock
import java.time.Instant

/**
 * Sessions you archived in the Code tab: hidden from its lists, on this phone
 * only. Nothing is stopped or deleted where the session runs; Archived (n)
 * under each list shows them again. A session that does something after it
 * was archived (newer activity) comes back by itself, so work you are waiting
 * on never hides.
 *
 * A key is "<device>|<session id>" (Archive.key); "phone" is this phone's own.
 */
object Archive {
    fun key(device: String, session: String) = "$device|$session"

    /**
     * Whether a session stays hidden: archived, and nothing has happened in it
     * since. [lastActivity] null (cloud sessions, panes with no transcript) keeps it hidden.
     */
    fun hidden(archived: Map<String, Instant>, key: String, lastActivity: Instant?): Boolean {
        val at = archived[key] ?: return false
        return lastActivity == null || !lastActivity.isAfter(at)
    }
}

class SessionArchive(context: Context, private val clock: Clock = Clock.systemUTC()) {
    private val prefs = context.getSharedPreferences("code_archive", Context.MODE_PRIVATE)
    private val _archived = MutableStateFlow(read())
    /** Archived sessions by key, with when. */
    val archived: StateFlow<Map<String, Instant>> = _archived.asStateFlow()

    private fun read(): Map<String, Instant> =
        prefs.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to Instant.ofEpochMilli(it) } }.toMap()

    fun archive(key: String) {
        prefs.edit { putLong(key, clock.millis()) }
        _archived.value = read()
    }

    fun unarchive(key: String) {
        prefs.edit { remove(key) }
        _archived.value = read()
    }
}
