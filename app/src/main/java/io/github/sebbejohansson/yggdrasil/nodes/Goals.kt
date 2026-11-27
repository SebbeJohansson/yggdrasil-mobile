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

/**
 * Something that could become a goal (Sindri's GET /v1/goals/candidates): an
 * open TODO line, a Linear issue or a GitHub issue of a project there.
 */
data class GoalCandidate(
    /** Ready for POST /v1/goals: todo:<repo>:<line>, linear:<ID> or github:<owner>/<repo>#<n>. */
    val source: String,
    val title: String,
    val context: String,
    /** A TODO line and a GitHub issue come with their project; a Linear issue with none. */
    val repos: List<String>,
    val url: String?,
    /** The goal that already has this source. */
    val goal: Long?,
    val labels: List<String>,
)

object Goals {
    /** Where candidates come from, as the desktop's import: TODO, Linear and GitHub. */
    val SOURCES = listOf("todo" to "TODO", "linear" to "Linear", "github" to "GitHub")

    /** Whose issues: yours (Sindri's default), nobody's or everyone's. */
    val ASSIGNEES = listOf("mine" to "Mine", "unassigned" to "Unassigned", "all" to "Everyone's")

    fun parseCandidates(json: String): List<GoalCandidate> = JSONObject(json).optJSONArray("candidates").objects().mapNotNull { c ->
        val source = c.str("source") ?: return@mapNotNull null
        val title = c.str("title")?.trim()?.ifEmpty { null } ?: return@mapNotNull null
        GoalCandidate(source, title.take(200), c.str("context") ?: "", c.optJSONArray("repos").strings(), c.str("url"),
            c.optLong("goal").takeIf { c.has("goal") && !c.isNull("goal") && it > 0 }, c.optJSONArray("labels").strings())
    }

    /** A short tag for where a candidate is from: app#7 for a GitHub issue, AI-3 for a Linear one, the file of a docs plan line. */
    fun candidateTag(source: String): String? = when {
        source.startsWith("github:") -> source.substringAfter('/').ifEmpty { null }
        source.startsWith("linear:") -> source.removePrefix("linear:").ifEmpty { null }
        else -> Regex("""^todo:[^:]+:([^\s:]+\.md):""", RegexOption.IGNORE_CASE).find(source)?.groupValues?.get(1)
    }

    /** TODO reads one project, so it needs one; Linear and GitHub take one or none. */
    fun canList(from: String, project: String?): Boolean = from != "todo" || project != null

    /** The repos of the goal made from [c]: its own, else the ones picked. */
    fun importRepos(c: GoalCandidate, picked: List<String>): List<String> = c.repos.ifEmpty { picked }

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

    /** Why a machine offers no projects for a new goal: none cloned there, or it did not answer for them. */
    fun noProjects(label: String, answered: Boolean): String =
        if (answered) "No projects cloned on $label: Clone from GitHub to start a goal on one."
        else "$label is not answering for its projects."

    private val GITHUB_REPO = Regex("""^(?:(?:https?://)?(?:www\.)?github\.com/|git@github\.com:)?([A-Za-z0-9][A-Za-z0-9-]*)/([A-Za-z0-9._-]+?)(?:\.git)?/?$""")

    /** owner/name from what was typed (owner/name or its github.com link), or null; Sindri reads it the same way (sindri/clone.go). */
    fun gitHubRepoOf(text: String): String? {
        val m = GITHUB_REPO.matchEntire(text.trim()) ?: return null
        val name = m.groupValues[2]
        return if (name == "." || name == "..") null else "${m.groupValues[1]}/$name"
    }

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
