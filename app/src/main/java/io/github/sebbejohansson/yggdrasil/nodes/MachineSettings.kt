package io.github.sebbejohansson.yggdrasil.nodes

import android.content.Context
import androidx.core.content.edit
import io.github.sebbejohansson.yggdrasil.update.KeystoreTokenVault
import io.github.sebbejohansson.yggdrasil.update.TokenVault
import org.json.JSONArray
import org.json.JSONObject

/**
 * The machines reached directly (Settings → Connection → Machines). The list
 * is in SharedPreferences; each machine's owner token is encrypted with a key
 * of its own in the Android Keystore, as the Mímir token is: it drives that
 * machine's coding agents.
 */
class MachineSettings(
    private val context: Context,
    private val vaultFor: (String) -> TokenVault = { id ->
        KeystoreTokenVault(context, prefsName = "machine_token_$id", alias = "ygg-machine-token-$id")
    },
) {
    private val prefs = context.getSharedPreferences("machines", Context.MODE_PRIVATE)

    fun list(): List<Machine> = runCatching {
        val array = JSONArray(prefs.getString("list", "[]"))
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            Machine(o.optString("id"), o.optString("label"), o.optString("host")).takeIf { it.id.isNotBlank() && it.host.isNotBlank() }
        }
    }.getOrDefault(emptyList())

    fun token(id: String): String = vaultFor(id).get() ?: ""

    /** Adds [machine], or replaces the one with its id. A blank [token] keeps the one there. */
    fun save(machine: Machine, token: String) {
        if (token.isNotBlank()) vaultFor(machine.id).set(token.trim())
        write(list().filter { it.id != machine.id } + machine)
    }

    fun remove(id: String) {
        vaultFor(id).set(null)
        write(list().filter { it.id != id })
    }

    private fun write(machines: List<Machine>) = prefs.edit {
        putString("list", JSONArray(machines.map { JSONObject().put("id", it.id).put("label", it.label).put("host", it.host) }).toString())
    }
}
