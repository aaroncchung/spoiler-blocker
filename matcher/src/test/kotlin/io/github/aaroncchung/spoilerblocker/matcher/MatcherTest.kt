package io.github.aaroncchung.spoilerblocker.matcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hand-written examples around the document's blocker for the 2026 Japanese
 * Grand Prix. None of the text comes from a real phone.
 */
class MatcherTest {

    private val matcher = Matcher(
        BlockerTerms(strong = listOf("Japanese Grand Prix", "Suzuka", "Max", "podium")),
    )

    private fun check(vararg texts: String): Verdict = matcher.check(Candidate(texts.toList()))

    private fun blockedBy(term: String): Verdict = Verdict.Blocked(Reason.StrongTerm(term))

    @Test
    fun `a text that contains a term is blocked and the reason names the term`() {
        assertEquals(blockedBy("Suzuka"), check("Onboard lap of Suzuka"))
        assertEquals(blockedBy("podium"), check("Who made the podium?"))
    }

    @Test
    fun `a text without any term is allowed`() {
        assertEquals(Verdict.Allowed, check("Ten easy weeknight dinners"))
    }

    @Test
    fun `upper and lower case are the same`() {
        assertEquals(blockedBy("Suzuka"), check("SUZUKA QUALIFYING RESULTS"))
        assertEquals(blockedBy("Suzuka"), check("back at suzuka again"))
        assertEquals(blockedBy("Japanese Grand Prix"), check("japanese GRAND prix preview"))
    }

    @Test
    fun `the reason gives the term as it was typed`() {
        val shouting = Matcher(BlockerTerms(strong = listOf("  SUZUKA ")))

        val verdict = shouting.check(Candidate(listOf("A wet weekend in Suzuka")))

        assertEquals(blockedBy("  SUZUKA "), verdict)
    }

    @Test
    fun `only whole words match`() {
        assertEquals(Verdict.Allowed, check("Maximum attack in the final sector"))
        assertEquals(Verdict.Allowed, check("The climax of the season"))
        assertEquals(Verdict.Allowed, check("Podiums are overrated"))
        assertEquals(blockedBy("Max"), check("Can Max do it again?"))
    }

    @Test
    fun `P1 is not found in P10`() {
        val p1 = Matcher(BlockerTerms(strong = listOf("P1")))

        assertEquals(Verdict.Allowed, p1.check(Candidate(listOf("He starts from P10"))))
        assertEquals(blockedBy("P1"), p1.check(Candidate(listOf("He starts from P1"))))
    }

    @Test
    fun `punctuation next to a word does not hide it`() {
        assertEquals(blockedBy("Suzuka"), check("Suzuka!"))
        assertEquals(blockedBy("Suzuka"), check("(Suzuka)"))
        assertEquals(blockedBy("Suzuka"), check("\"Suzuka\", they said."))
        assertEquals(blockedBy("Suzuka"), check("#Suzuka"))
        assertEquals(blockedBy("Suzuka"), check("SUZUKA🏁")) // chequered flag emoji
    }

    @Test
    fun `a possessive does not hide a word`() {
        assertEquals(blockedBy("Max"), check("Max's best lap"))
        assertEquals(blockedBy("Max"), check("Max’s best lap")) // curly apostrophe
    }

    @Test
    fun `a term of several words matches as a phrase`() {
        assertEquals(blockedBy("Japanese Grand Prix"), check("2026 Japanese Grand Prix: race highlights"))
    }

    @Test
    fun `whatever separates the words of a phrase does not matter`() {
        assertEquals(blockedBy("Japanese Grand Prix"), check("Japanese   Grand\nPrix"))
        assertEquals(blockedBy("Japanese Grand Prix"), check("Japanese-Grand-Prix"))
        assertEquals(blockedBy("Japanese Grand Prix"), check("Japanese, Grand... Prix?"))

        val spacedOut = Matcher(BlockerTerms(strong = listOf("Japanese \t Grand--Prix")))
        assertEquals(
            Verdict.Blocked(Reason.StrongTerm("Japanese \t Grand--Prix")),
            spacedOut.check(Candidate(listOf("The Japanese Grand Prix"))),
        )
    }

    @Test
    fun `the words of a phrase must be together and in order`() {
        assertEquals(Verdict.Allowed, check("A grand Japanese dinner"))
        assertEquals(Verdict.Allowed, check("Prix Grand Japanese"))
        assertEquals(Verdict.Allowed, check("Japanese F1 Grand Prix"))
        assertEquals(Verdict.Allowed, check("Japanese Grand"))
        assertEquals(Verdict.Allowed, check("The Monaco Grand Prix"))
    }

    @Test
    fun `a phrase does not straddle two texts`() {
        assertEquals(Verdict.Allowed, check("Learn Japanese", "Grand Prix of cooking"))
        assertEquals(Verdict.Allowed, check("Japanese Grand", "Prix"))
    }

    @Test
    fun `every text is looked at`() {
        assertEquals(blockedBy("Suzuka"), check("You will not believe this", "Live from Suzuka"))
    }

    @Test
    fun `when several terms match the first in the list is reported`() {
        // "Suzuka" comes before "Max" in the blocker, whatever the order in the text.
        assertEquals(blockedBy("Suzuka"), check("Max on pole", "Sunshine at Suzuka"))
        assertEquals(blockedBy("Suzuka"), check("Max wins at Suzuka"))
    }

    @Test
    fun `blank terms are ignored`() {
        val blanks = Matcher(BlockerTerms(strong = listOf("", "   ", "\t\n", "Suzuka")))

        assertEquals(Verdict.Allowed, blanks.check(Candidate(listOf("Nothing to see here"))))
        assertEquals(Verdict.Allowed, blanks.check(Candidate(listOf(""))))
        assertEquals(Verdict.Allowed, blanks.check(Candidate(listOf("   "))))
        assertEquals(blockedBy("Suzuka"), blanks.check(Candidate(listOf("Suzuka in the rain"))))
    }

    @Test
    fun `a term of only punctuation is ignored`() {
        val punctuation = Matcher(BlockerTerms(strong = listOf("!!!", "-")))

        assertEquals(Verdict.Allowed, punctuation.check(Candidate(listOf("What a race!!! - wow"))))
    }

    @Test
    fun `no terms allows everything`() {
        val empty = Matcher(BlockerTerms(strong = emptyList()))

        assertEquals(Verdict.Allowed, empty.check(Candidate(listOf("Max wins the Japanese Grand Prix"))))
    }

    @Test
    fun `a candidate without text is allowed`() {
        assertEquals(Verdict.Allowed, check())
        assertEquals(Verdict.Allowed, check(""))
        assertEquals(Verdict.Allowed, check("", "  ", "?!"))
    }
}
