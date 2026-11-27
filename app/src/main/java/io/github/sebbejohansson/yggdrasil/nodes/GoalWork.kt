package io.github.sebbejohansson.yggdrasil.nodes

import org.json.JSONArray
import org.json.JSONObject

/**
 * One goal part's agent on a machine, as Sindri's GET /v1/agents/{id} gives
 * it: what it is doing, what it last said, and what it waits on you for (a
 * tool to allow, or questions from AskUserQuestion), answered with POST
 * /v1/agents/{id}/permissions/{requestId}.
 */
data class AgentQuestion(val question: String, val header: String?, val multiSelect: Boolean, val options: List<String>)

data class AgentPending(
    val requestId: String,
    val toolName: String,
    val summary: String,
    /** An AskUserQuestion's questions; empty for a tool to allow or deny. */
    val questions: List<AgentQuestion>,
) {
    val isQuestion: Boolean get() = questions.isNotEmpty()
}

data class AgentDetail(
    val id: String,
    /** starting, working, needs-approval, waiting, done, failed or aborted. */
    val status: String,
    val tool: String?,
    val lastText: String?,
    val result: String?,
    val pending: List<AgentPending>,
    /** Its last turn ended on a question for you. */
    val asksYou: Boolean,
    val lastError: String?,
    val remoteUrl: String?,
    val usageLimited: Boolean,
) {
    /** Busy now: a reply waits for the turn (sent with queue). */
    val working: Boolean get() = status == "working" || status == "starting"
}

/** A part's branch as Sindri reviews it (GET /v1/agents/{id}/review). */
data class ReviewFile(val path: String, val added: Int?, val removed: Int?)

data class ReviewCommit(val hash: String, val subject: String)

data class GoalReview(
    val branch: String,
    val base: String,
    val merged: Boolean,
    val conflicts: List<String>,
    val commits: List<ReviewCommit>,
    val files: List<ReviewFile>,
    val diff: String,
    val diffTruncated: Boolean,
    val uncommitted: List<String>,
) {
    /** Something to merge: commits not merged yet, and nothing in the way. */
    val mergeable: Boolean get() = !merged && commits.isNotEmpty() && conflicts.isEmpty()
}

object GoalWork {
    fun parseAgent(json: String): AgentDetail {
        val o = JSONObject(json)
        return AgentDetail(
            id = o.optString("id"),
            status = o.str("status") ?: "",
            tool = o.str("tool"),
            lastText = o.str("lastText"),
            result = o.str("result"),
            pending = o.optJSONArray("pending").objects().mapNotNull { p ->
                val id = p.str("requestId") ?: return@mapNotNull null
                AgentPending(
                    requestId = id,
                    toolName = p.str("toolName") ?: "",
                    summary = p.str("summary") ?: "",
                    questions = p.optJSONArray("questions").objects().mapNotNull { q ->
                        val text = q.str("question") ?: return@mapNotNull null
                        AgentQuestion(text, q.str("header"), q.optBoolean("multiSelect"),
                            q.optJSONArray("options").objects().mapNotNull { it.str("label") })
                    },
                )
            },
            asksYou = o.optBoolean("asksYou"),
            lastError = o.str("lastError"),
            remoteUrl = o.str("remoteUrl"),
            usageLimited = o.optBoolean("usageLimited"),
        )
    }

    fun parseReview(json: String): GoalReview {
        val o = JSONObject(json)
        return GoalReview(
            branch = o.str("branch") ?: "",
            base = o.str("base") ?: "",
            merged = o.optBoolean("merged"),
            conflicts = o.optJSONArray("conflicts").strings(),
            commits = o.optJSONArray("commits").objects().mapNotNull { c ->
                ReviewCommit(c.str("hash")?.take(7) ?: return@mapNotNull null, c.str("subject") ?: "")
            },
            files = o.optJSONArray("files").objects().mapNotNull { f ->
                ReviewFile(f.str("path") ?: return@mapNotNull null,
                    f.optInt("added", -1).takeIf { it >= 0 && !f.isNull("added") }, f.optInt("removed", -1).takeIf { it >= 0 && !f.isNull("removed") })
            },
            diff = o.str("diff") ?: "",
            diffTruncated = o.optBoolean("diffTruncated"),
            uncommitted = o.optJSONArray("uncommitted").strings(),
        )
    }

    /** "+12 −3", or "binary". */
    fun fileChange(f: ReviewFile): String =
        if (f.added == null && f.removed == null) "binary" else "+${f.added ?: 0} −${f.removed ?: 0}"

    /**
     * The answers to send for a question: by question text, as Sindri takes
     * them; a multi-select's picks joined with ", ". Null while one is unanswered.
     */
    fun answers(questions: List<AgentQuestion>, picked: Map<String, Set<String>>, typed: Map<String, String>): Map<String, String>? {
        val out = linkedMapOf<String, String>()
        for (q in questions) {
            val text = typed[q.question]?.trim()?.ifEmpty { null } ?: picked[q.question]?.takeIf { it.isNotEmpty() }?.let { set ->
                q.options.filter { it in set }.joinToString(", ")
            } ?: return null
            out[q.question] = text
        }
        return out
    }

    /** A diff line's kind, to colour it: added, removed, a hunk or file header, or context. */
    fun lineKind(line: String): Char = when {
        line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff ") || line.startsWith("index ") -> 'f'
        line.startsWith("@@") -> 'h'
        line.startsWith("+") -> '+'
        line.startsWith("-") -> '-'
        else -> ' '
    }

    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) (opt(k) as? String)?.ifEmpty { null } else null
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifEmpty { null } }
}
