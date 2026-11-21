package io.github.sebbejohansson.yggdrasil.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** The numbers here are what my-widget's own tree.ts gives for the same input (run in Node). */
class TreeTest {
    @Test fun seedsAndRandomAreTheJavaScripts() {
        assertEquals(2293485191L, Tree.seedOf("goal:12"))
        assertEquals(2906411959L, Tree.seedOf("memory:1"))
        assertEquals(2029592507L, Tree.seedOf("Veðrfölnir"))
        val r = Tree.random(Tree.seedOf("memory:1"))
        assertEquals(0.7510196298826486, r(), 1e-15)
        assertEquals(0.2327027483843267, r(), 1e-15)
        assertEquals(0.5138904245104641, r(), 1e-15)
    }

    private val events = listOf(
        HistoryEvent(HistoryKind.BRANCH, "memory:1", 1_763_000_000_000, "a"),
        HistoryEvent(HistoryKind.BRANCH, "memory:2", 1_763_100_000_000, "b"),
        HistoryEvent(HistoryKind.SCAR, "healed:1:1763150000000", 1_763_150_000_000, "c"),
        HistoryEvent(HistoryKind.BRANCH, "memory:3", 1_763_200_000_000, "d"),
        HistoryEvent(HistoryKind.RAVEN, "raven:x1", 1_763_400_000_000, "e"),
    )

    @Test fun growsTheSameTreeAsTheDesktop() {
        val t = Tree.grow(events)
        val grown = t.branches.drop(7)
        fun check(b: Tree.Branch, parent: Int, x: Double, y: Double, leaves: Int, leaf0: Double) {
            assertEquals(b.id, parent, b.parent)
            assertEquals(b.id, x, b.curve.b.x, 1e-6)
            assertEquals(b.id, y, b.curve.b.y, 1e-6)
            assertEquals(b.id, leaves, b.leaves.size)
            assertEquals(b.id, leaf0, b.leaves[0].t, 1e-6)
        }
        check(grown[0], 6, 525.547818, 382.366229, 10, 0.740427)
        check(grown[1], 0, 589.547278, 453.072794, 16, 0.925808)
        check(grown[2], 8, 648.934800, 486.311192, 12, 0.776860)
        assertEquals(0, t.scars.single().branch)
        assertEquals(0.449851, t.scars.single().t, 1e-6)
        assertEquals(-0.559812, t.scars.single().across, 1e-6)
        assertEquals(9, t.ravens.single().branch)
        assertEquals(0.864935, t.ravens.single().t, 1e-6)
        assertEquals(1, t.ravens.single().facing)
        val trunk = Tree.at(t, 1_763_500_000_000).branches.first { it.branch.role == Tree.Role.TRUNK }
        assertEquals(33.838367, trunk.width, 1e-6)
    }

    @Test fun aNewEventNeverMovesAnOldBranch() {
        val before = Tree.grow(events).branches
        val after = Tree.grow(events + HistoryEvent(HistoryKind.BRANCH, "memory:9", 1_763_600_000_000, "z")).branches
        assertEquals(before, after.take(before.size))
    }

    @Test fun theTreeAtAMomentHasOnlyWhatHadHappened() {
        val t = Tree.grow(events)
        val early = Tree.at(t, 1_763_050_000_000)
        assertEquals(8, early.branches.size) // the skeleton and memory:1, half grown
        assertTrue(early.scars.isEmpty() && early.ravens.isEmpty())
    }

    @Test fun seasons() {
        val utc = ZoneOffset.UTC
        fun at(day: String) = Instant.parse("${day}T12:00:00Z").toEpochMilli()
        assertEquals("midsummer", Tree.seasonAt(emptyList(), at("2026-07-15"), utc).words)
        assertEquals("late autumn", Tree.seasonAt(emptyList(), at("2026-11-25"), utc).words)
        assertEquals("early spring", Tree.seasonAt(emptyList(), at("2026-03-05"), utc).words)
        val busy = Tree.seasonAt(List(12) { at("2026-07-14") }, at("2026-07-15"), utc)
        assertTrue(busy.density > Tree.seasonAt(emptyList(), at("2026-07-15"), utc).density)
    }
}

/** The desktop's futhark.test.ts cases. */
class FutharkTest {
    @Test fun twentyFourRunes() {
        assertEquals(24, Futhark.RUNES.toSet().size)
    }

    @Test fun lettersPairsAndSounds() {
        mapOf(
            "fuark" to "ᚠᚢᚨᚱᚲ", "loki" to "ᛚᛟᚲᛁ", "bjm" to "ᛒᛃᛗ", "thor" to "ᚦᛟᚱ", "king" to "ᚲᛁᛜ", "ship" to "ᛊᛁᛈ",
            "rock" to "ᚱᛟᚲ", "when" to "ᚹᛖᚾ", "queen" to "ᚲᚹᛖᚾ", "cat" to "ᚲᚨᛏ", "peace" to "ᛈᛖᚨᛊᛖ", "fox" to "ᚠᛟᚲᛊ",
            "raven" to "ᚱᚨᚠᛖᚾ", "year" to "ᛃᛖᚨᚱ", "my" to "ᛗᛁ", "tree" to "ᛏᚱᛖ", "hammer" to "ᚺᚨᛗᛖᚱ", "kck" to "ᚲ",
        ).forEach { (word, runes) -> assertEquals(word, runes, Futhark.word(word)) }
    }

    @Test fun wordsAndSentences() {
        assertEquals("ᚺᛖᚱᛖ᛫ᚦᛖ᛫ᚠᛁᚱᛊᛏ᛫ᛒᚱᚨᚾᚲ᛫ᚷᚱᛖᚹ", Futhark.of("Here the first branch grew"))
        assertEquals("ᛚᛟᚲᛁ᛫ᚲᚨᛗᛖ᛬ᛟᛞᛁᚾ᛫ᛊᚨᚹ", Futhark.of("Loki came. Odin saw"))
        assertEquals(Futhark.of("Vethrfolnir"), Futhark.of("Veðrfölnir"))
        assertEquals("one hundred five", Futhark.numberWords(105))
    }

    @Test fun everyRuneHasStrokes() {
        assertTrue(Futhark.RUNES.all { it in Futhark.STROKES })
    }
}

class RunestonesTest {
    private val utc = ZoneOffset.UTC
    private fun ms(s: String) = Instant.parse(s).toEpochMilli()
    private fun branch(n: Int, at: String) = HistoryEvent(HistoryKind.BRANCH, "memory:$n", ms(at), "fact $n")

    @Test fun firstBranchBusyDayAndMilestone() {
        val events = (1..100).map { branch(it, "2026-11-0${1 + (it % 2)}T10:%02d:00Z".format(it % 60)) }
        val stones = Runestones.carve(events, emptyList(), ms("2026-11-20T00:00:00Z"), utc).map { it.id }
        assertTrue(stones.containsAll(listOf("first-branch", "busy-day", "branches:100")))
    }

    @Test fun ravenAndLongStorm() {
        val events = listOf(
            HistoryEvent(HistoryKind.RAVEN, "raven:1", ms("2026-11-03T08:00:00Z"), "Kim · Slack"),
            HistoryEvent(HistoryKind.SCAR, "healed:1:1", ms("2026-11-04T08:00:00Z"), "Storage is 95% full healed", ms("2026-11-03T08:00:00Z")),
            HistoryEvent(HistoryKind.SCAR, "healed:2:1", ms("2026-11-04T09:00:00Z"), "Short one healed", ms("2026-11-04T08:30:00Z")),
        )
        val stones = Runestones.carve(events, emptyList(), ms("2026-11-05T00:00:00Z"), utc)
        assertEquals(listOf("first-raven", "long-storm:healed:1:1"), stones.map { it.id })
        assertTrue(stones[1].meaning.endsWith("after 24 hours"))
    }

    @Test fun aQuietWeekAfterTheFirstAlertButNotWhileOneIsOpen() {
        val scar = HistoryEvent(HistoryKind.SCAR, "healed:1:1", ms("2026-11-04T10:00:00Z"), "healed", ms("2026-11-04T08:00:00Z"))
        val quiet = Runestones.carve(listOf(scar), emptyList(), ms("2026-11-20T00:00:00Z"), utc)
        assertEquals("quiet-week:2026-11-09", quiet.single().id)
        val open = Runestones.carve(listOf(scar), listOf(Runestones.OpenAlert(9, ms("2026-11-05T00:00:00Z"))), ms("2026-11-20T00:00:00Z"), utc)
        assertTrue(open.isEmpty())
    }

    @Test fun carvingFitsTheBand() {
        val shape = Runestones.shape("first-branch")
        val runes = Runestones.carveRunes(shape, Futhark.of("Here the first branch grew"))
        assertTrue(runes.isNotEmpty())
        assertTrue(runes.all { it.scale in 0.8..1.6 })
    }
}
