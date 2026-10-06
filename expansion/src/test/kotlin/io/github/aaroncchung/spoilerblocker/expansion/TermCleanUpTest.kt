package io.github.aaroncchung.spoilerblocker.expansion

import org.junit.Assert.assertEquals
import org.junit.Test

class TermCleanUpTest {

    private fun terms(
        strong: List<String> = emptyList(),
        weak: List<String> = emptyList(),
        sources: List<String> = emptyList(),
    ) = ExpandedTerms(strong, weak, sources)

    @Test
    fun `trims terms and tidies the spaces inside them`() {
        val cleaned = cleanUp(terms(strong = listOf("  Suzuka ", "Japanese   Grand\nPrix")))

        assertEquals(listOf("Suzuka", "Japanese Grand Prix"), cleaned.strong)
    }

    @Test
    fun `drops blank terms from every list`() {
        val cleaned = cleanUp(
            terms(
                strong = listOf("", "Suzuka", "   "),
                weak = listOf("\t", "podium"),
                sources = listOf(" ", "FORMULA 1"),
            ),
        )

        assertEquals(terms(listOf("Suzuka"), listOf("podium"), listOf("FORMULA 1")), cleaned)
    }

    @Test
    fun `drops repeats that differ only in capitals and keeps the first`() {
        val cleaned = cleanUp(
            terms(
                strong = listOf("Suzuka", "SUZUKA", "Japanese GP", "suzuka "),
                sources = listOf("FORMULA 1", "Formula 1"),
            ),
        )

        assertEquals(listOf("Suzuka", "Japanese GP"), cleaned.strong)
        assertEquals(listOf("FORMULA 1"), cleaned.sources)
    }

    @Test
    fun `drops from weak anything that is already strong`() {
        val cleaned = cleanUp(
            terms(
                strong = listOf("Suzuka", "Verstappen"),
                weak = listOf("Max", "SUZUKA", "podium", " verstappen"),
            ),
        )

        assertEquals(listOf("Max", "podium"), cleaned.weak)
        assertEquals(listOf("Suzuka", "Verstappen"), cleaned.strong)
    }

    @Test
    fun `a source may have the same name as a term`() {
        val cleaned = cleanUp(terms(strong = listOf("Formula 1"), sources = listOf("FORMULA 1")))

        assertEquals(listOf("FORMULA 1"), cleaned.sources)
    }

    @Test
    fun `keeps the order the model gave`() {
        val cleaned = cleanUp(terms(weak = listOf("podium", "Max", "P1", "pole")))

        assertEquals(listOf("podium", "Max", "P1", "pole"), cleaned.weak)
    }

    @Test
    fun `cuts each list to its limit and keeps the first entries`() {
        val tooMany = (1..200).map { "term $it" }

        val cleaned = cleanUp(terms(strong = tooMany, weak = tooMany.map { "weak $it" }, sources = tooMany))

        assertEquals(tooMany.take(MAX_STRONG_TERMS), cleaned.strong)
        assertEquals(tooMany.take(MAX_WEAK_TERMS).map { "weak $it" }, cleaned.weak)
        assertEquals(tooMany.take(MAX_SOURCES), cleaned.sources)
    }

    @Test
    fun `repeats and blanks do not use up the limit`() {
        val padded = listOf("", "Suzuka", "suzuka") + (1..MAX_STRONG_TERMS).map { "term $it" }

        val cleaned = cleanUp(terms(strong = padded))

        assertEquals(MAX_STRONG_TERMS, cleaned.strong.size)
        assertEquals("Suzuka", cleaned.strong.first())
        assertEquals("term ${MAX_STRONG_TERMS - 1}", cleaned.strong.last())
    }
}
