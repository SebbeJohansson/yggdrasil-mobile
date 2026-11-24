package io.github.sebbejohansson.yggdrasil.nodes

import org.json.JSONArray
import org.json.JSONObject

/**
 * A machine's goals (Sindri's GET /v1/goals): long-running work across its
 * repos, as the desktop widget's Goals page shows them. The phone lists the
 * ones in play, starts a draft, resumes one waiting on Claude usage, and
 * makes a new draft; the rest (review, races, merging) is the desktop's.
 */
data class GoalPart(val repo: String?, val agent: String, val status: String, val label: String?)

data class MachineGoal(
    val id: Long,
    val title: String,
    val repos: List<String>,
    /** draft, running, needs-you, done, merged or closed. */
    val state: String,
    /** What to do next: start, resume, answer, review, pick or none. */
    val nextAction: String,
    val nextText: String,
    val parts: List<GoalPart>,
) {
    /** A draft that names its projects can be started from here. */
    val startable: Boolean get() = state == "draft" && repos.isNotEmpty()
    val resumable: Boolean get() = nextAction == "resume"
}

object Goals {
    /** The states shown, in the order to act on them; merged and closed goals are done with. */
    val ORDER = listOf("needs-you", "running", "done", "draft")

    val LABEL = mapOf("needs-you" to "Needs you", "running" to "Moving", "done" to "Ready to review", "draft" to "Draft")

    fun parse(json: String): List<MachineGoal> = JSONObject(json).optJSONArray("goals").objects().mapNotNull(::goal)

    fun parseOne(json: String): MachineGoal? = runCatching { goal(JSONObject(json)) }.getOrNull()

    private fun goal(g: JSONObject): MachineGoal? {
        val id = g.optLong("id").takeIf { it > 0 } ?: return null
        val title = g.str("title") ?: return null
        val next = g.optJSONObject("next")
        return MachineGoal(
            id = id,
            title = title,
            repos = g.optJSONArray("repos").strings(),
            state = g.str("state") ?: "draft",
            nextAction = next?.str("action") ?: "none",
            nextText = next?.str("text") ?: "",
            parts = g.optJSONArray("tasks").objects().mapNotNull { t ->
                val agent = t.str("agent") ?: return@mapNotNull null
                GoalPart(t.str("repo"), agent, t.str("status") ?: "", t.str("label"))
            },
        )
    }

    /** The goals in play, in the order to act on them; newest first within a state. */
    fun inPlay(goals: List<MachineGoal>): List<MachineGoal> =
        goals.filter { it.state in ORDER }.sortedWith(compareBy<MachineGoal> { ORDER.indexOf(it.state) }.thenByDescending { it.id })

    /** The checks on a new goal, as Sindri's: a title of at most 200, at most 4 projects. */
    fun checkNew(title: String, repos: List<String>): String? = when {
        title.isBlank() -> "What should be done?"
        title.trim().length > 200 -> "At most 200 characters; put the rest on the desktop as context"
        repos.size > 4 -> "At most 4 projects"
        else -> null
    }

    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) (opt(k) as? String)?.ifEmpty { null } else null
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifEmpty { null } }
}
