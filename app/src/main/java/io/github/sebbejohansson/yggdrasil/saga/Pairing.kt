package io.github.sebbejohansson.yggdrasil.saga

import java.net.URLDecoder

/** What a pairing link carries for the phone: where Mímir is, and this machine's token there. */
data class Pairing(val url: String, val token: String, val label: String?)

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
fun parsePairing(raw: String): Pairing? {
    val text = raw.trim()
    if (!text.startsWith("yggdrasil://pair", ignoreCase = true)) return null
    val q = text.substringAfter('?', "").substringBefore('#')
    val f = runCatching {
        q.split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
    }.getOrNull() ?: return null
    if ((f["v"] ?: "1") != "1") return null
    if (f["work"] == "1") return null
    val host = f["host"]?.trim()?.removePrefix("https://")?.trimEnd('/') ?: return null
    val token = f["token"]?.trim() ?: return null
    if (host.isBlank() || host.contains('/') || host.contains(' ') || token.isBlank()) return null
    return Pairing("https://$host", token, f["label"]?.trim()?.ifBlank { null })
}
