package io.github.aaroncchung.spoilerblocker.matcher

/**
 * One list of a blocker (its strong terms, its weak terms or its sources),
 * arranged so that the terms can be found in a text quickly.
 *
 * The rule for finding a term:
 *
 * > A term is found in a text when the letters and digits of the term, run
 * > together, are the letters and digits of one or more whole words of the
 * > text that follow each other, run together.
 *
 * "Japanese GP" run together is "japanesegp". The words japanese and gp of
 * "The Japanese G.P.!" and of "#JapaneseGP" run together to the same, and
 * "#japanesegp" is that one word already. So what is inside a term does not
 * matter: spaces, capitals, punctuation. Where it starts and ends does: at
 * the start of a word of the text and at the end of one. That is why "Max" is
 * not found in "maximum", nor "P1" in "P10".
 *
 * The price is that neighbouring words can add up to a term by accident:
 * "therapist" is found in "the rapist". That is accepted, because a miss
 * costs more than an over-block (decision 4 in docs/ARCHITECTURE.md).
 */
internal class TermIndex(termsAsTyped: List<String>) {

    private class Term(val asTyped: String, val position: Int)

    /** Each term under its key: its letters and digits run together, "japanesegp" for "Japanese GP". */
    private val termsByKey: Map<String, Term>

    private val longestKeyLength: Int

    init {
        val byKey = mutableMapOf<String, Term>()
        var longest = 0
        // withIndex() gives each term together with its place in the list.
        for ((position, term) in termsAsTyped.withIndex()) {
            val key = normalise(term).joinToString(separator = "")
            // A term that is blank, or only punctuation or emoji, has an empty
            // key and could never be found.
            if (key.isEmpty()) continue
            // "Max", "MAX" and "max" have the same key and are one term. The
            // first spelling in the list is the one that gets reported.
            if (key !in byKey) byKey[key] = Term(asTyped = term, position = position)
            longest = maxOf(longest, key.length)
        }
        termsByKey = byKey
        longestKeyLength = longest
    }

    /**
     * Returns the terms found in [texts], each as the user typed it and each
     * only once, in the order of the user's list. Every text is a list of
     * words from [normalise].
     */
    fun findIn(texts: List<List<String>>): List<String> {
        val found = mutableSetOf<Term>()
        for (words in texts) {
            for (start in words.indices) {
                // Run the word at `start` together with the words after it,
                // one more each time, and look each result up.
                var runTogether = ""
                for (end in start until words.size) {
                    // Stop before the result gets longer than any key: more
                    // words cannot help.
                    if (runTogether.length + words[end].length > longestKeyLength) break
                    runTogether += words[end]
                    val term = termsByKey[runTogether]
                    if (term != null) found += term
                }
            }
        }
        return found.sortedBy { term -> term.position }.map { term -> term.asTyped }
    }
}
