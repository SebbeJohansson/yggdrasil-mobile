package io.github.sebbejohansson.yggdrasil.norn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The desktop's internal/pseudo/pseudo_test.go, ported. */
class PseudoTest {
    private val people = listOf(
        Person("Anna Svensson", emails = listOf("anna.svensson@example.com"), role = "invited to the meeting"),
        Person("Björn Öberg", emails = listOf("bjorn@example.com")),
        Person("Per Holm"),
    )

    @Test fun pseudonymizeKnownPeople() {
        val m = PseudoMap()
        val out = m.pseudonymize("Anna Svensson wrote to bjorn@example.com. Annas deck is late; Björn agreed. ANNA SVENSSON again.", people)
        // Addresses are numbered first: bjorn@ before Anna's name.
        assertEquals("Person 2 wrote to Person 1. Person 2s deck is late; Person 1 agreed. Person 2 again.", out)
        assertTrue(m.leaks(out, people).isEmpty())
    }

    @Test fun firstNamesStandAlone() {
        val out = PseudoMap().pseudonymize("Annabel met Anna. Per månad betalar Per Holm.", people)
        assertTrue("Anna swapped inside a word, or not alone: $out", out.contains("Annabel") && !out.contains("Anna."))
        assertTrue("the word Per was taken for a name: $out", out.split(". ", limit = 2)[1].startsWith("Per månad"))
    }

    @Test fun foundNamesAndUnknownEmails() {
        val m = PseudoMap()
        val out = m.pseudonymize("Ask Kalle Ek, or mail stranger@other.se.", emptyList(), listOf("Kalle Ek", "Nobody Here"))
        assertEquals("Ask Person 2, or mail Person 1.", out)
        assertEquals("a name not in the text got a placeholder", 2, m.entries.size)
    }

    @Test fun restoreAndStableAcrossTurns() {
        val m = PseudoMap()
        val first = m.pseudonymize("Anna Svensson asked.", people)
        val second = m.pseudonymize("And Björn Öberg, with Anna.", people)
        assertEquals("Person 1 asked.", first)
        assertEquals("And Person 2, with Person 1.", second)
        assertEquals(
            "Tell Anna Svensson that Björn Öberg agreed; Person 9 is not ours.",
            m.restore("Tell person 1 that Person 2 agreed; Person 9 is not ours."),
        )
    }

    @Test fun leaksCatchesWhatSlippedThrough() {
        assertEquals(2, PseudoMap().leaks("from someone@x.io about anna svensson", people).size)
    }

    @Test fun legend() {
        val m = PseudoMap()
        val out = m.pseudonymize("Anna Svensson and Per Holm", people)
        val legend = m.legend(out)
        assertTrue(legend, legend.contains("- Person 1: invited to the meeting"))
        assertTrue(legend, legend.contains("- Person 2\n") || legend.endsWith("- Person 2"))
        assertEquals("", m.legend("no one here"))
    }

    @Test fun aPersonsOtherNamesShareTheirPlaceholder() {
        val known = listOf(Person("Anna Berg", aliases = listOf("Anna B."), emails = listOf("anna@example.com")))
        val m = PseudoMap()
        assertEquals("Person 1 said Person 1 (Person 1) has it.", m.pseudonymize("Anna B. said Anna Berg (anna@example.com) has it.", known))
        assertEquals(1, m.entries.size)
        // Only the other name in the text is enough to find them.
        val again = PseudoMap()
        assertEquals("Ask Person 1 first.", again.pseudonymize("Ask Anna B. first.", known))
        assertEquals("Anna Berg", again.entries[0].name)
        assertEquals("the other name is not a leak", 1, again.leaks("Anna B. still here", known).size)
    }

    @Test fun tokenInIsNotFooledByLongerOnes() {
        assertTrue(Pseudo.tokenIn("see Person 1.", "Person 1"))
        assertFalse(Pseudo.tokenIn("see Person 12.", "Person 1"))
    }
}
