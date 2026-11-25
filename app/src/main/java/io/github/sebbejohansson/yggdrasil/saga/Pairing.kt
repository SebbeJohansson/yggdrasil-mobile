package io.github.sebbejohansson.yggdrasil.saga

import java.net.URLDecoder

/** What a pairing link carries for the phone: where Mímir is, and this machine's token there. */
data class Pairing(val host: String, val token: String, val label: String?) {
    /** The https URL Settings → Connection takes for Mímir. */
    val url: String get() = "https://$host"
}

/**
 * Reads the link `ygg pair` shows as a QR code:
 * `yggdrasil://pair?v=1&id=…&label=…&host=<tailnet name>&token=…[&work=1]`
 * (values percent-encoded; the contract is in the desktop's docs).
 *
 * Null when it isn't one, so a stray scan of some other code is ignored
 * instead of half-filling the form. Also null for `work=1`: a work machine's
 * token never goes onto a personal phone. The URL is `https://<host>`, the
 * shape Settings → Connection already takes; the phone has no other port to
 * pick. Pure parsing, apart from the camera, so it is tested.
 */
fun parsePairing(raw: String): Pairing? = parsePairingResult(raw).getOrNull()

/** The same, saying why when it is refused, for the screen to show. */
fun parsePairingResult(raw: String): Result<Pairing> {
    fun no(why: String) = Result.failure<Pairing>(IllegalArgumentException(why))
    val text = raw.trim()
    if (!text.startsWith("yggdrasil://pair", ignoreCase = true)) return no("That isn't a pairing code (yggdrasil://pair?…).")
    val q = text.substringAfter('?', "").substringBefore('#')
    val f = runCatching {
        q.split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
    }.getOrNull() ?: return no("That pairing code is damaged.")
    if ((f["v"] ?: "1") != "1") return no("That pairing code is from a newer version.")
    if (f["work"] == "1") return no("That is a work machine: it is never added to a personal phone.")
    val host = f["host"]?.trim()?.removePrefix("https://")?.trimEnd('/') ?: return no("That pairing code has no host.")
    val token = f["token"]?.trim() ?: return no("That pairing code has no token.")
    if (host.isBlank() || host.contains('/') || host.contains(' ') || token.isBlank()) return no("That pairing code has no usable host or token.")
    return Result.success(Pairing(host, token, f["label"]?.trim()?.ifBlank { null }))
}
