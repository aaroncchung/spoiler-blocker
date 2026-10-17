package io.github.aaroncchung.spoilerblocker.matcher

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * A sanity check, not a benchmark. The time to check a candidate must not
 * grow with the number of terms.
 *
 * Far more terms are used here than a real blocker has. That makes the gap
 * wide: on the PC this was written on the test takes about 0.2 seconds, and
 * 22 seconds if the matcher is changed to try every term at every word. The
 * time limit sits between the two.
 */
class MatcherSpeedTest {

    // Twenty different syllables of two letters each.
    private val syllables = listOf(
        "ka", "su", "zu", "mo", "na", "co", "pa", "za", "si", "ve",
        "to", "ne", "ba", "ku", "im", "la", "me", "bo", "ri", "fu",
    )

    /**
     * An invented word of four syllables for each number: word(0) is
     * "kakakaka", word(1) is "sukakaka". Different numbers give different
     * words. Letters only, so normalisation never cuts one up.
     */
    private fun word(number: Int): String =
        syllables[number % 20] +
            syllables[number / 20 % 20] +
            syllables[number / 400 % 20] +
            syllables[number / 8000 % 20]

    // Strong terms and sources are two neighbouring words. Weak terms are
    // single words from a range the other two never use.
    private val strong = List(50_000) { "${word(it)} ${word(it + 1)}" }
    private val sources = List(5_000) { "${word(it)} ${word(it + 1)}" }
    private val weak = List(50_000) { word(60_000 + it) }

    /**
     * A hundred words that each start a strong term, and some a source, but
     * are never followed by the second word. The matcher has to look at every
     * word and finds nothing.
     */
    private fun textWithoutTerms(random: Random): String {
        val numbers = mutableListOf(random.nextInt(50_000))
        while (numbers.size < 100) {
            val next = random.nextInt(50_000)
            if (next != numbers.last() + 1) numbers += next
        }
        return numbers.joinToString(separator = " ") { word(it) }
    }

    @Test(timeout = 5_000) // milliseconds
    fun `many candidates against very many terms`() {
        val random = Random(2026) // a fixed seed: the same texts on every run
        val matcher = Matcher(BlockerTerms(strong, weak, sources, Breadth.NARROW))
        val candidates = List(300) {
            Candidate(
                texts = listOf(textWithoutTerms(random), textWithoutTerms(random)),
                sources = listOf(textWithoutTerms(random)),
            )
        }

        val blockedCount = candidates.count { matcher.check(it) is Verdict.Blocked }

        assertEquals(0, blockedCount)
        // The same matcher does find terms, so the zero above is not an accident.
        assertEquals(
            Verdict.Blocked(Reason.StrongTerm(strong[49_998])),
            matcher.check(Candidate(listOf(strong[49_998] + " " + textWithoutTerms(random)))),
        )
        assertEquals(
            Verdict.Blocked(Reason.WeakTerms(listOf(weak[7], weak[49_999]))),
            matcher.check(Candidate(listOf(weak[49_999], textWithoutTerms(random) + " " + weak[7]))),
        )
    }
}
