package io.github.sebbejohansson.yggdrasil.saga

import java.net.URLDecoder
import org.json.JSONObject

/** What a pairing code carries: where Mímir is, this phone's token there, and the name it was given. */
data class Pairing(val url: String, val token: String, val device: String?)

/**
 * Reads what the desktop shows as a QR code or a link when you pair a device:
 * `ygg://pair?url=…&token=…&device=…` (values percent-encoded), or the same
 * three keys as a JSON object. Null when it isn't one, so a stray scan of
 * some other code is ignored instead of half-filling the form.
 *
 * Pure parsing, kept apart from the camera so it can be tested. Only https
 * URLs pass: Android refuses plain HTTP, and a token sent in the clear
 * is not worth saving.
 */
fun parsePairing(raw: String): Pairing? {
    val text = raw.trim()
    val fields = when {
        text.startsWith("{") -> jsonFields(text)
        text.startsWith("ygg://pair", ignoreCase = true) -> queryFields(text)
        else -> null
    } ?: return null
    val url = fields["url"]?.trim()?.trimEnd('/') ?: return null
    val token = fields["token"]?.trim() ?: return null
    if (!url.startsWith("https://") || url.length <= "https://".length || token.isBlank()) return null
    return Pairing(url, token, fields["device"]?.trim()?.ifBlank { null })
}

private fun jsonFields(text: String): Map<String, String>? = runCatching {
    val o = JSONObject(text)
    listOf("url", "token", "device").mapNotNull { k -> o.optString(k, "").takeIf { it.isNotEmpty() }?.let { k to it } }.toMap()
}.getOrNull()

private fun queryFields(text: String): Map<String, String>? {
    val q = text.substringAfter('?', "").substringBefore('#')
    if (q.isEmpty()) return null
    return runCatching {
        q.split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
    }.getOrNull()
}
