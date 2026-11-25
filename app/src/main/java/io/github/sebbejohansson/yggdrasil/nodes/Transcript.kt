package io.github.sebbejohansson.yggdrasil.nodes

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * A Claude Code transcript as something to read: your prompts, the agent's
 * replies, and each tool call with what came back. A port of the desktop
 * widget's reader (my-widget's utils/agents/transcriptView.ts), for a session
 * on another machine, whose transcript that machine's Sindri sends
 * (GET /v1/sessions/claude/{id}/transcript). Pure, so it is tested here.
 */
sealed interface TranscriptEntry {
    val at: Instant?

    data class Prompt(val text: String, override val at: Instant?) : TranscriptEntry
    data class Reply(val text: String, override val at: Instant?) : TranscriptEntry
    data class Thinking(val text: String, override val at: Instant?) : TranscriptEntry
    data class Tool(
        val id: String,
        val name: String,
        val detail: String,
        override val at: Instant?,
        var output: String? = null,
        var failed: Boolean = false,
    ) : TranscriptEntry
}

/** What a machine's Sindri sent of a transcript; [stamp] is what the next read sends as `since`. */
data class RemoteTranscript(val size: Long, val modified: Long, val text: String, val unchanged: Boolean) {
    val stamp: String get() = "$size:$modified"
}

object Transcript {
    /** A tool's output past this is cut; the transcript has the whole thing. */
    const val OUTPUT_MAX = 2_000

    private val DETAIL_KEYS = listOf("command", "file_path", "path", "pattern", "url", "query", "description", "prompt", "skill")

    /**
     * The readable entries in [text], oldest first. [text] may be a tail that
     * starts mid-line: a line that does not parse is skipped, which drops
     * exactly that partial first line. Meta and sidechain (subagent) entries
     * are left out, as the desktop does.
     */
    fun read(text: String): List<TranscriptEntry> {
        val entries = mutableListOf<TranscriptEntry>()
        val tools = mutableMapOf<String, TranscriptEntry.Tool>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val entry = runCatching { JSONObject(line) }.getOrNull() ?: continue
            val type = entry.optString("type")
            if ((type != "user" && type != "assistant") || entry.optBoolean("isMeta") || entry.optBoolean("isSidechain")) continue
            val at = entry.optString("timestamp").takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val content = entry.optJSONObject("message")?.opt("content")

            if (type == "user") {
                if (content is String) {
                    promptText(content)?.let { entries += TranscriptEntry.Prompt(it, at) }
                    continue
                }
                for (block in (content as? JSONArray).objects()) {
                    when (block.optString("type")) {
                        "tool_result" -> tools[block.optString("tool_use_id")]?.let { tool ->
                            tool.output = clip(textOf(block.opt("content")).trim(), OUTPUT_MAX)
                            tool.failed = block.optBoolean("is_error")
                        }
                        "text" -> promptText(block.optString("text"))?.let { entries += TranscriptEntry.Prompt(it, at) }
                    }
                }
                continue
            }

            for (block in (content as? JSONArray).objects()) {
                when (block.optString("type")) {
                    "text" -> block.optString("text").trim().takeIf { it.isNotEmpty() }?.let { entries += TranscriptEntry.Reply(it, at) }
                    "thinking" -> block.optString("thinking").trim().takeIf { it.isNotEmpty() }?.let { entries += TranscriptEntry.Thinking(it, at) }
                    "tool_use" -> {
                        val id = block.optString("id").ifEmpty { null } ?: continue
                        val tool = TranscriptEntry.Tool(id, block.optString("name").ifEmpty { "tool" }, detailOf(block.optJSONObject("input")), at)
                        tools[id] = tool
                        entries += tool
                    }
                }
            }
        }
        return entries
    }

    /**
     * What you typed, without what the harness wrapped around it: system
     * reminders are dropped, a slash command reads as the command, and a
     * local command's output is not something you said.
     */
    fun promptText(raw: String): String? {
        if (raw.startsWith("<local-command") || raw.startsWith("Caveat:")) return null
        Regex("<command-name>([^<]*)</command-name>").find(raw)?.groupValues?.get(1)?.let { command ->
            val args = Regex("<command-args>([^<]*)</command-args>").find(raw)?.groupValues?.get(1)?.trim()
            return if (args.isNullOrEmpty()) command else "$command $args"
        }
        return raw.replace(Regex("<system-reminder>[\\s\\S]*?</system-reminder>"), "").trim().ifEmpty { null }
    }

    private fun detailOf(input: JSONObject?): String {
        if (input == null) return ""
        for (key in DETAIL_KEYS) {
            val value = input.optString(key).trim()
            if (input.opt(key) is String && value.isNotEmpty()) return clip(value, 300)
        }
        return clip(input.toString(), 300)
    }

    private fun textOf(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> content.objects().filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") }
        else -> ""
    }

    private fun clip(text: String, max: Int): String = if (text.length > max) text.take(max - 1).trimEnd() + "…" else text
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
