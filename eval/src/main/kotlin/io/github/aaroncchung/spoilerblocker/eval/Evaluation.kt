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

/** What an example is. The app treats the two kinds differently, see [Example.toCandidate]. */
@Serializable
enum class Kind {
    @SerialName("post")
    POST,

    @SerialName("notification")
    NOTIFICATION,
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
    val kind: Kind = Kind.POST,
    /** Anything the person labelling wanted to remember. The matcher never sees it. */
    val note: String? = null,
    /** Where the example is in its file, to find it again. @Transient: it is not read from the JSON. */
    @Transient val line: Int = 0,
) {
    /**
     * What the matcher is given for this example. It is built the way the app
     * builds it, or the counts would not be the app's.
     *
     * A post's sources are its channel or account name, and they are only
     * looked for as sources. A notification's sources are its app name, title
     * and senders, and the app looks for terms in them too: its
     * NotificationContent.toCandidate() puts them in the texts as well. So a
     * WhatsApp message from Max saying "podium!!" holds two weak terms.
     */
    fun toCandidate(): Candidate = when (kind) {
        Kind.POST -> Candidate(texts = texts, sources = sources)
        Kind.NOTIFICATION -> Candidate(texts = (sources + texts).distinct(), sources = sources)
    }
}

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
        val verdict = matcher.check(example.toCandidate())
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
