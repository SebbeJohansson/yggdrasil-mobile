package io.github.sebbejohansson.yggdrasil.nodes

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GoalWorkTest {
    @Test
    fun aGoalShowsItsCurrentPartsAndWhetherItIsClosed() {
        val goals = Goals.parse("""{"goals":[
            {"id":3,"title":"Fix login","repos":["app","api"],"state":"needs-you","closedAt":null,
             "next":{"action":"answer","text":"api asks you","agent":"a2"},
             "tasks":[
               {"id":1,"repo":"app","agent":"a1-old","status":"failed"},
               {"id":2,"repo":"api","agent":"a2","status":"needs-you","branch":"ygg/login-api"},
               {"id":4,"repo":"app","agent":"a1","status":"done","branch":"ygg/login-app","merged":true,"result":"**Done**"}]},
            {"id":2,"title":"Old","repos":["app"],"state":"closed","closedAt":1700,"next":{"action":"none","text":""},"tasks":[]},
            {"id":1,"title":"Shipped","repos":["app"],"state":"merged","next":{"action":"none","text":""},"tasks":[]}]}""")
        val g = goals.first()
        // A restarted part's older agent is history.
        assertEquals(listOf("a2", "a1"), g.parts.map { it.agent })
        assertEquals("a2", g.nextAgent)
        assertTrue(g.parts[1].merged)
        assertEquals("ygg/login-api", g.parts[0].branch)
        assertFalse(g.closed)
        assertTrue(goals[1].closed)
        assertEquals(listOf(3L), Goals.inPlay(goals).map { it.id })
        assertEquals(listOf(2L, 1L), Goals.finished(goals).map { it.id })
        assertEquals("waiting on Claude usage", Goals.partLabel("scheduled"))
        assertEquals("all", Goals.defaultAssignee("github"))
        assertEquals("mine", Goals.defaultAssignee("linear"))
    }

    @Test
    fun anAgentWaitingOnQuestionsOrATool() {
        val a = GoalWork.parseAgent("""{"id":"a2","status":"needs-approval","asksYou":true,"lastText":"Which DB?","pending":[
            {"requestId":"r1","toolName":"AskUserQuestion","summary":"","at":1,"questions":[
              {"question":"Which database?","header":"DB","multiSelect":false,"options":[{"label":"Postgres"},{"label":"SQLite"}]},
              {"question":"Which checks?","multiSelect":true,"options":[{"label":"lint"},{"label":"test"}]}]},
            {"requestId":"r2","toolName":"Bash","summary":"npm test","at":2}]}""")
        assertEquals(2, a.pending.size)
        assertTrue(a.pending[0].isQuestion)
        assertFalse(a.pending[1].isQuestion)
        assertEquals(listOf("Postgres", "SQLite"), a.pending[0].questions[0].options)
        assertFalse(a.working)

        val qs = a.pending[0].questions
        // Not all answered yet.
        assertNull(GoalWork.answers(qs, mapOf("Which database?" to setOf("SQLite")), emptyMap()))
        assertEquals(
            mapOf("Which database?" to "SQLite", "Which checks?" to "lint, test"),
            GoalWork.answers(qs, mapOf("Which database?" to setOf("SQLite"), "Which checks?" to setOf("test", "lint")), emptyMap()),
        )
        // Your own words win over a pick.
        assertEquals("Postgres 16", GoalWork.answers(qs, mapOf("Which checks?" to setOf("lint")), mapOf("Which database?" to " Postgres 16 "))!!["Which database?"])
    }

    @Test
    fun aBranchToReview() {
        val r = GoalWork.parseReview("""{"repo":"app","path":"/w","branch":"ygg/x","base":"main","baseCommit":"abc","worktreeExists":true,"branchExists":true,
            "merged":false,"conflicts":[],"commits":[{"hash":"1234567890","subject":"Fix it","at":1,"merged":false}],
            "files":[{"path":"a.kt","added":12,"removed":3},{"path":"logo.png","added":null,"removed":null}],
            "diff":"diff --git a b\n@@ -1 +1 @@\n-old\n+new","diffTruncated":false,"uncommitted":[],"checkout":{"branch":"main","dirty":false}}""")
        assertTrue(r.mergeable)
        assertEquals("1234567", r.commits[0].hash)
        assertEquals("+12 −3", GoalWork.fileChange(r.files[0]))
        assertEquals("binary", GoalWork.fileChange(r.files[1]))
        assertEquals(listOf('f', 'h', '-', '+'), r.diff.lines().map(GoalWork::lineKind))
        assertFalse(r.copy(conflicts = listOf("a.kt")).mergeable)
        assertFalse(r.copy(merged = true).mergeable)
    }
}
