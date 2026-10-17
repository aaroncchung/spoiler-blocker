package io.github.aaroncchung.spoilerblocker.matcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four rules of "Matching rules" in docs/ARCHITECTURE.md, with the
 * document's own example blocker. Every text is invented.
 */
class MatchingRulesTest {

    private val strong = listOf("Japanese Grand Prix", "Suzuka", "#JapaneseGP")
    private val weak = listOf("Max", "podium", "P1")
    private val sources = listOf("FORMULA 1", "Sky Sports F1")

    private val narrow = Matcher(BlockerTerms(strong, weak, sources, Breadth.NARROW))
    private val broad = Matcher(BlockerTerms(strong, weak, sources, Breadth.BROAD))

    /** A post with the given texts, from the given channel or account. */
    private fun post(vararg texts: String, from: String? = null): Candidate =
        Candidate(texts = texts.toList(), sources = listOfNotNull(from))

    private fun blocked(reason: Reason): Verdict = Verdict.Blocked(reason)

    // Rule 1: sources.

    @Test
    fun `a post from a listed source is blocked whatever it says`() {
        val verdict = narrow.check(post("You will not believe what happened", from = "FORMULA 1"))

        assertEquals(blocked(Reason.Source("FORMULA 1")), verdict)
    }

    @Test
    fun `a source is recognised in its usual spellings`() {
        for (name in listOf(
            "FORMULA 1", "Formula 1", "formula 1", "Formula1", "@formula1", "FORMULA 1 • 12M subscribers",
        )) {
            assertEquals(name, blocked(Reason.Source("FORMULA 1")), narrow.check(post("New video", from = name)))
        }
        for (name in listOf(
            "Sky Sports F1", "SKY SPORTS F1", "SkySportsF1", "Skysports F1", "@skysportsf1", "sky_sports_f1",
        )) {
            assertEquals(name, blocked(Reason.Source("Sky Sports F1")), narrow.check(post("New video", from = name)))
        }
    }

    @Test
    fun `a source must be there completely`() {
        for (name in listOf("Sky Sports", "Sky Sports News", "Sky Sports F10", "Formula E", "Formula 10", "F1", "")) {
            assertEquals(name, Verdict.Allowed, narrow.check(post("New video", from = name)))
        }
    }

    @Test
    fun `a notification is checked against its app name and its title`() {
        val notification = Candidate(
            texts = listOf("FORMULA 1", "Behind the scenes at the factory"),
            sources = listOf("YouTube", "FORMULA 1"),
        )

        assertEquals(blocked(Reason.Source("FORMULA 1")), narrow.check(notification))
    }

    @Test
    fun `a source does not straddle two entries`() {
        val candidate = Candidate(texts = listOf("New video"), sources = listOf("Sky Sports", "F1"))

        assertEquals(Verdict.Allowed, narrow.check(candidate))
    }

    @Test
    fun `sources are not looked for in the texts`() {
        assertEquals(Verdict.Allowed, narrow.check(post("Is it on Sky Sports F1 tonight?", from = "Family chat")))
    }

    @Test
    fun `terms are not looked for in the sources`() {
        assertEquals(Verdict.Allowed, narrow.check(post("Our opening hours", from = "Suzuka Coffee Roasters")))
        assertEquals(Verdict.Allowed, broad.check(post("Our opening hours", from = "Max")))
    }

    // Rule 2: strong terms.

    @Test
    fun `a strong term blocks by itself`() {
        assertEquals(
            blocked(Reason.StrongTerm("Japanese Grand Prix")),
            narrow.check(post("Race highlights | 2026 Japanese Grand Prix")),
        )
        assertEquals(blocked(Reason.StrongTerm("Suzuka")), narrow.check(post("Rain at Suzuka")))
        assertEquals(blocked(Reason.StrongTerm("#JapaneseGP")), narrow.check(post("What a race #JapaneseGP")))
    }

    @Test
    fun `a strong term in any of the texts is enough`() {
        assertEquals(blocked(Reason.StrongTerm("Suzuka")), narrow.check(post("Unbelievable", "Sunday at Suzuka")))
    }

    // Rule 3: two different weak terms.

    @Test
    fun `one weak term does not block a narrow blocker`() {
        assertEquals(Verdict.Allowed, narrow.check(post("Max has a new dog")))
        assertEquals(Verdict.Allowed, narrow.check(post("A podium for the under-12s relay team")))
    }

    @Test
    fun `two different weak terms block`() {
        assertEquals(blocked(Reason.WeakTerms(listOf("Max", "podium"))), narrow.check(post("Max on the podium again")))
    }

    @Test
    fun `the two weak terms may be in different texts`() {
        val verdict = narrow.check(post("He did it from P1", "Max is unstoppable this year"))

        assertEquals(blocked(Reason.WeakTerms(listOf("Max", "P1"))), verdict)
    }

    @Test
    fun `the same weak term twice is still one weak term`() {
        assertEquals(Verdict.Allowed, narrow.check(post("Max, Max, MAX!")))
        assertEquals(Verdict.Allowed, narrow.check(post("Max has a new dog", "Good boy, Max")))
    }

    @Test
    fun `the reason lists every weak term found, in the order of the blocker`() {
        val verdict = narrow.check(post("From P1 to the podium: Max does it"))

        assertEquals(blocked(Reason.WeakTerms(listOf("Max", "podium", "P1"))), verdict)
    }

    // Rule 4: one weak term and a broad blocker.

    @Test
    fun `one weak term blocks a broad blocker`() {
        assertEquals(blocked(Reason.WeakTermBroad("Max")), broad.check(post("Max has a new dog")))
        assertEquals(blocked(Reason.WeakTermBroad("P1")), broad.check(post("Starting from P1")))
    }

    @Test
    fun `two weak terms give the same reason for a broad blocker as for a narrow one`() {
        assertEquals(blocked(Reason.WeakTerms(listOf("Max", "podium"))), broad.check(post("Max on the podium again")))
    }

    @Test
    fun `no term at all is allowed even for a broad blocker`() {
        assertEquals(Verdict.Allowed, broad.check(post("Ten easy weeknight dinners")))
        assertEquals(Verdict.Allowed, broad.check(post("Maximum effort at P10")))
    }

    // The order of the rules.

    @Test
    fun `a source is reported before a strong term`() {
        val verdict = narrow.check(post("Suzuka: Max on the podium", from = "Sky Sports F1"))

        assertEquals(blocked(Reason.Source("Sky Sports F1")), verdict)
    }

    @Test
    fun `a strong term is reported before weak terms`() {
        assertEquals(blocked(Reason.StrongTerm("Suzuka")), narrow.check(post("Suzuka: Max on the podium")))
        assertEquals(blocked(Reason.StrongTerm("Suzuka")), broad.check(post("Max wins", "Suzuka")))
    }

    @Test
    fun `a term that is both strong and weak counts as strong`() {
        val matcher = Matcher(BlockerTerms(strong = listOf("Max"), weak = listOf("Max", "podium")))

        assertEquals(blocked(Reason.StrongTerm("Max")), matcher.check(post("Max has a new dog")))
    }

    // Lists with unpleasant things in them.

    @Test
    fun `the same term in several spellings is one term, reported in the first spelling`() {
        val terms = BlockerTerms(
            strong = listOf("suzuka", "Suzuka", "SUZUKA"),
            weak = listOf("Japanese GP", "#JapaneseGP", "japanesegp", "Pérez", "perez", "Pérez"),
        )

        assertEquals(blocked(Reason.StrongTerm("suzuka")), Matcher(terms).check(post("Suzuka")))
        // Three spellings of one weak term are found here, which is not "two different weak terms".
        assertEquals(Verdict.Allowed, Matcher(terms).check(post("#JapaneseGP and #japanesegp and Japanese GP")))
        assertEquals(Verdict.Allowed, Matcher(terms).check(post("Perez, PÉREZ, perez")))
        assertEquals(
            blocked(Reason.WeakTermBroad("Japanese GP")),
            Matcher(terms.copy(breadth = Breadth.BROAD)).check(post("#japanesegp")),
        )
        assertEquals(
            blocked(Reason.WeakTerms(listOf("Japanese GP", "Pérez"))),
            Matcher(terms).check(post("Perez at the #JapaneseGP")),
        )
    }

    @Test
    fun `two terms with the same letters are one term`() {
        // "Man U" and "Manu" run together to the same letters, so the matcher
        // cannot tell them apart. Both words below count as the first of them.
        val terms = BlockerTerms(strong = emptyList(), weak = listOf("Man U", "Manu"))

        assertEquals(Verdict.Allowed, Matcher(terms).check(post("Manu scores for Man U")))
        assertEquals(
            blocked(Reason.WeakTermBroad("Man U")),
            Matcher(terms.copy(breadth = Breadth.BROAD)).check(post("Manu scores")),
        )
    }

    @Test
    fun `a weak term inside another weak term makes two`() {
        // Arguably one mention, but they are two entries of the list and both are found.
        val matcher = Matcher(BlockerTerms(strong = emptyList(), weak = listOf("Max", "Max Verstappen")))

        assertEquals(
            blocked(Reason.WeakTerms(listOf("Max", "Max Verstappen"))),
            matcher.check(post("Max Verstappen has a new dog")),
        )
        assertEquals(Verdict.Allowed, matcher.check(post("Max has a new dog")))
    }

    @Test
    fun `terms with nothing to match are ignored`() {
        // The last one is an accent with no letter to sit on.
        val nothing = listOf("", " ", "\t\n", "!!!", "#", "'", "🏁", "\u0301")
        val matcher = Matcher(BlockerTerms(nothing, weak = nothing, sources = nothing, breadth = Breadth.BROAD))

        assertEquals(Verdict.Allowed, matcher.check(post("", " ", "!!! # ' 🏁", from = "")))
        assertEquals(Verdict.Allowed, matcher.check(post("Max on the podium at Suzuka", from = "FORMULA 1")))
    }

    @Test
    fun `empty lists allow everything`() {
        val matcher = Matcher(BlockerTerms(strong = emptyList(), breadth = Breadth.BROAD))

        assertEquals(Verdict.Allowed, matcher.check(post("Max on the podium at Suzuka", from = "FORMULA 1")))
    }

    @Test
    fun `a candidate with nothing in it is allowed`() {
        assertEquals(Verdict.Allowed, broad.check(Candidate(texts = emptyList())))
        assertEquals(Verdict.Allowed, broad.check(Candidate(texts = emptyList(), sources = emptyList())))
        assertEquals(Verdict.Allowed, broad.check(Candidate(texts = listOf("", "  "), sources = listOf("", "?"))))
        assertEquals(Verdict.Allowed, broad.check(post("🏁🏆", "...", "1 2 3")))
    }

    @Test
    fun `a term is found at the end of a very long text`() {
        val filler = "lorem ipsum dolor sit amet ".repeat(40_000) // 200,000 words

        assertEquals(Verdict.Allowed, narrow.check(post(filler)))
        assertEquals(blocked(Reason.StrongTerm("Suzuka")), narrow.check(post(filler + "Suzuka")))
        assertEquals(blocked(Reason.WeakTerms(listOf("Max", "podium"))), narrow.check(post("Max $filler podium")))
    }

    @Test
    fun `a very long word is not a problem`() {
        val candidate = post("a".repeat(200_000), "1".repeat(200_000), "aB1".repeat(50_000))

        assertEquals(Verdict.Allowed, narrow.check(candidate))
    }

    // hasMatchableText, for the screen where terms are typed.

    @Test
    fun `a term with a letter or a digit has something to match`() {
        for (term in listOf("Suzuka", " Max ", "#JapaneseGP", "P1", "7", "C++", "Pérez", "鈴鹿")) {
            assertTrue(term, hasMatchableText(term))
        }
    }

    @Test
    fun `a term without letters and digits has nothing to match`() {
        for (term in listOf("", " ", "\t\n", "!!!", "#", "'", "™", "🏁")) {
            assertFalse("\"$term\"", hasMatchableText(term))
        }
    }

    @Test
    fun `hasMatchableText agrees with the matcher`() {
        // A term that has something to match is found in itself. One that has nothing never is.
        for (term in listOf("Suzuka", "C++", "P1", "鈴鹿", "!!!", "#", "™", "🏁", "")) {
            val verdict = Matcher(BlockerTerms(strong = listOf(term))).check(Candidate(texts = listOf(term)))

            assertEquals("\"$term\"", hasMatchableText(term), verdict is Verdict.Blocked)
        }
    }
}
