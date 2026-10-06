package io.github.aaroncchung.spoilerblocker.eval

import io.github.aaroncchung.spoilerblocker.matcher.BlockerTerms
import io.github.aaroncchung.spoilerblocker.matcher.Candidate
import io.github.aaroncchung.spoilerblocker.matcher.Matcher
import io.github.aaroncchung.spoilerblocker.matcher.Reason
import io.github.aaroncchung.spoilerblocker.matcher.Verdict
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** What a person decided about one example: is it about the blocker's topic? */
@Serializable
enum class Label {
    @SerialName("related")
    RELATED,

    @SerialName("unrelated")
    UNRELATED,
}

/**
 * One labelled title, caption or notification. This is also the shape of one
 * line of the examples file, see [parseExamples].
 */
@Serializable
data class Example(
    val label: Label,
    val texts: List<String>,
    val sources: List<String> = emptyList(),
    /** Anything the person labelling wanted to remember. The matcher never sees it. */
    val note: String? = null,
    /** Where the example is in its file, to find it again. @Transient: it is not read from the JSON. */
    @Transient val line: Int = 0,
)

/** An unrelated example that was blocked, with the rule that blocked it. */
data class OverBlock(val example: Example, val reason: Reason)

/** The result of running the matcher over a set of examples. */
data class Report(
    val relatedCount: Int,
    val unrelatedCount: Int,
    /** Related examples that were allowed. These are the costly mistakes: a spoiler gets through. */
    val misses: List<Example>,
    /** Unrelated examples that were blocked. */
    val overBlocks: List<OverBlock>,
) {
    /** The share of related examples that were missed, from 0.0 to 1.0. Null if there were none. */
    val missRate: Double?
        get() = if (relatedCount == 0) null else misses.size.toDouble() / relatedCount

    /** The share of unrelated examples that were blocked, from 0.0 to 1.0. Null if there were none. */
    val overBlockRate: Double?
        get() = if (unrelatedCount == 0) null else overBlocks.size.toDouble() / unrelatedCount
}

/** Checks every example against a blocker with these [terms] and collects the mistakes, in the order given. */
fun evaluate(terms: BlockerTerms, examples: List<Example>): Report {
    val matcher = Matcher(terms)
    val misses = mutableListOf<Example>()
    val overBlocks = mutableListOf<OverBlock>()

    for (example in examples) {
        val verdict = matcher.check(Candidate(texts = example.texts, sources = example.sources))
        when (example.label) {
            Label.RELATED -> if (verdict is Verdict.Allowed) misses += example
            Label.UNRELATED -> if (verdict is Verdict.Blocked) overBlocks += OverBlock(example, verdict.reason)
        }
    }

    return Report(
        relatedCount = examples.count { it.label == Label.RELATED },
        unrelatedCount = examples.count { it.label == Label.UNRELATED },
        misses = misses,
        overBlocks = overBlocks,
    )
}
