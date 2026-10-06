package io.github.aaroncchung.spoilerblocker.eval

import io.github.aaroncchung.spoilerblocker.matcher.BlockerTerms
import io.github.aaroncchung.spoilerblocker.matcher.Breadth
import io.github.aaroncchung.spoilerblocker.matcher.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class EvaluationTest {

    private val terms = BlockerTerms(
        strong = listOf("Suzuka"),
        weak = listOf("Max", "podium"),
        sources = listOf("FORMULA 1"),
        breadth = Breadth.NARROW,
    )

    private val blockedRelated = Example(Label.RELATED, texts = listOf("Rain at Suzuka"), line = 1)
    private val missedRelated = Example(Label.RELATED, texts = listOf("What a race that was"), line = 2)
    private val allowedUnrelated = Example(Label.UNRELATED, texts = listOf("Ten easy dinners"), line = 3)
    private val blockedUnrelated = Example(Label.UNRELATED, texts = listOf("A weekend in Suzuka"), line = 4)

    @Test
    fun `a related example that is allowed is a miss`() {
        val report = evaluate(terms, listOf(blockedRelated, missedRelated))

        assertEquals(listOf(missedRelated), report.misses)
        assertEquals(emptyList<OverBlock>(), report.overBlocks)
    }

    @Test
    fun `an unrelated example that is blocked is an over-block, with the reason`() {
        val report = evaluate(terms, listOf(allowedUnrelated, blockedUnrelated))

        assertEquals(emptyList<Example>(), report.misses)
        assertEquals(listOf(OverBlock(blockedUnrelated, Reason.StrongTerm("Suzuka"))), report.overBlocks)
    }

    @Test
    fun `counts and rates`() {
        val report = evaluate(
            terms,
            listOf(blockedRelated, missedRelated, allowedUnrelated, blockedUnrelated, allowedUnrelated, allowedUnrelated),
        )

        assertEquals(2, report.relatedCount)
        assertEquals(4, report.unrelatedCount)
        assertEquals(0.5, report.missRate!!, 0.0001)
        assertEquals(0.25, report.overBlockRate!!, 0.0001)
    }

    @Test
    fun `each kind of reason is reported`() {
        val fromSource = Example(Label.UNRELATED, listOf("How a wind tunnel works"), sources = listOf("FORMULA 1"))
        val twoWeak = Example(Label.UNRELATED, listOf("Max made the podium at the swimming gala"))
        val oneWeak = Example(Label.UNRELATED, listOf("Max has a new dog"))

        val narrow = evaluate(terms, listOf(fromSource, twoWeak, oneWeak))
        val broad = evaluate(terms.copy(breadth = Breadth.BROAD), listOf(oneWeak))

        assertEquals(
            listOf(
                OverBlock(fromSource, Reason.Source("FORMULA 1")),
                OverBlock(twoWeak, Reason.WeakTerms(listOf("Max", "podium"))),
            ),
            narrow.overBlocks,
        )
        assertEquals(listOf(OverBlock(oneWeak, Reason.WeakTermBroad("Max"))), broad.overBlocks)
    }

    @Test
    fun `mistakes are listed in the order of the examples`() {
        val first = missedRelated.copy(line = 10)
        val second = missedRelated.copy(texts = listOf("Still thinking about that last lap"), line = 20)

        val report = evaluate(terms, listOf(first, blockedRelated, second))

        assertEquals(listOf(first, second), report.misses)
    }

    @Test
    fun `no examples gives zero counts and no rates`() {
        val report = evaluate(terms, emptyList())

        assertEquals(Report(relatedCount = 0, unrelatedCount = 0, misses = emptyList(), overBlocks = emptyList()), report)
        assertNull(report.missRate)
        assertNull(report.overBlockRate)
    }

    @Test
    fun `the report as text`() {
        val report = evaluate(
            terms,
            listOf(
                blockedRelated,
                missedRelated.copy(note = "No term in it."),
                allowedUnrelated,
                blockedUnrelated,
                Example(Label.UNRELATED, listOf("How a wind\ntunnel works", "x".repeat(200)), listOf("FORMULA 1"), line = 5),
            ),
        )

        assertEquals(
            """
            Examples:    5 (2 related, 3 unrelated)
            Misses:      1 of 2 related examples were allowed (50.0%)
            Over-blocks: 2 of 3 unrelated examples were blocked (66.7%)

            Misses
              line 2: What a race that was
                note: No term in it.

            Over-blocks
              line 4: A weekend in Suzuka
                blocked by the strong term "Suzuka"
              line 5: [FORMULA 1] How a wind tunnel works | ${"x".repeat(59)}...
                blocked by the source "FORMULA 1"

            """.trimIndent(),
            describe(report),
        )
    }

    @Test
    fun `the report as text when nothing went wrong`() {
        val report = evaluate(terms, listOf(blockedRelated, allowedUnrelated, allowedUnrelated))

        assertEquals(
            """
            Examples:    3 (1 related, 2 unrelated)
            Misses:      0 of 1 related examples were allowed (0.0%)
            Over-blocks: 0 of 2 unrelated examples were blocked (0.0%)

            """.trimIndent(),
            describe(report),
        )
    }

    @Test
    fun `a rate with nothing to measure is not reported as zero`() {
        val onlyRelated = evaluate(terms, listOf(blockedRelated))
        val onlyUnrelated = evaluate(terms, listOf(allowedUnrelated))

        assertEquals(
            """
            Examples:    1 (1 related, 0 unrelated)
            Misses:      0 of 1 related examples were allowed (0.0%)
            Over-blocks: not measured, there are no unrelated examples

            """.trimIndent(),
            describe(onlyRelated),
        )
        assertEquals(
            """
            Examples:    1 (0 related, 1 unrelated)
            Misses:      not measured, there are no related examples
            Over-blocks: 0 of 1 unrelated examples were blocked (0.0%)

            """.trimIndent(),
            describe(onlyUnrelated),
        )
    }

    @Test
    fun `no examples at all gives a warning`() {
        val report = evaluate(terms, emptyList())

        assertEquals(
            """
            Warning: there are no examples, so nothing was measured.

            Examples:    0 (0 related, 0 unrelated)
            Misses:      not measured, there are no related examples
            Over-blocks: not measured, there are no unrelated examples

            """.trimIndent(),
            describe(report),
        )
    }

    @Test
    fun `every line of the report ends the same way`() {
        val report = evaluate(terms, listOf(blockedRelated, missedRelated, allowedUnrelated, blockedUnrelated))

        assertFalse(describe(report).contains('\r'))
    }
}
