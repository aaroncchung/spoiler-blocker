package io.github.aaroncchung.spoilerblocker.matcher

import java.util.Collections

/** The term lists of one blocker, in the form the matcher needs. */
data class BlockerTerms(val strong: List<String>)

/** One notification or post, reduced to the pieces of text the matcher looks at. */
data class Candidate(val texts: List<String>)

/**
 * What the matcher decided about one [Candidate].
 *
 * A sealed interface has a fixed set of implementations, all listed here. The
 * compiler therefore knows that a `when` over a Verdict which handles
 * [Allowed] and [Blocked] has handled everything.
 */
sealed interface Verdict {
    data object Allowed : Verdict
    data class Blocked(val reason: Reason) : Verdict
}

/**
 * Why something was blocked, so the app can always show it (see "Matching
 * rules" in docs/ARCHITECTURE.md).
 */
sealed interface Reason {
    /** A strong term was found. [term] is the term as the user typed it. */
    data class StrongTerm(val term: String) : Reason
}

/**
 * Decides whether a [Candidate] is about the topic of one blocker.
 *
 * A term matches a text when the words of the term appear in the text, in the
 * same order with nothing between them. Upper and lower case are the same.
 * Only whole words count: "Max" is not found in "maximum". Spaces and
 * punctuation only separate words, so "Japanese Grand Prix" is found in
 * "Japanese-Grand   Prix!".
 *
 * A Matcher never changes after it is constructed, so one instance can be
 * shared and called from several threads at once. The notification listener
 * and the accessibility service will do exactly that. When the user edits a
 * blocker, build a new Matcher.
 */
class Matcher(terms: BlockerTerms) {

    private class PreparedTerm(val asTyped: String, val words: List<String>)

    // The terms are cut into words once, here, and not on every call to check().
    private val strongTerms: List<PreparedTerm> = terms.strong
        .map { term -> PreparedTerm(asTyped = term, words = wordsOf(term)) }
        // A blank term has no words and could never be found, so it is dropped.
        .filter { term -> term.words.isNotEmpty() }

    /**
     * Returns [Verdict.Blocked] if any text of [candidate] contains a strong
     * term. If several terms are found, the reason names the one that comes
     * first in the blocker's list.
     */
    fun check(candidate: Candidate): Verdict {
        // Each text is cut into words separately. That is why a phrase cannot
        // start at the end of a title and finish at the start of a caption.
        val texts: List<List<String>> = candidate.texts.map { text -> wordsOf(text) }

        for (term in strongTerms) {
            if (texts.any { words -> containsPhrase(words, term.words) }) {
                return Verdict.Blocked(Reason.StrongTerm(term.asTyped))
            }
        }
        return Verdict.Allowed
    }
}

/** One or more characters that are neither letters nor digits, in any alphabet. */
private val WORD_SEPARATOR = Regex("""[^\p{L}\p{N}]+""")

/**
 * Cuts [text] into lower-case words. Everything that is not a letter or a
 * digit separates words, including an apostrophe: "Max's" becomes "max" and
 * "s", which is why a possessive does not hide the name in front of it.
 */
private fun wordsOf(text: String): List<String> =
    // A text that starts or ends with a separator gives an empty first or last
    // piece, hence the filter.
    text.lowercase().split(WORD_SEPARATOR).filter { word -> word.isNotEmpty() }

/** True if the words of [phrase] appear in [words] in order, with nothing between them. */
private fun containsPhrase(words: List<String>, phrase: List<String>): Boolean =
    Collections.indexOfSubList(words, phrase) != -1
