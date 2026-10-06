package io.github.aaroncchung.spoilerblocker.expansion

// The longest each list may be. The prompt tells the model these numbers, and
// cleanUp() enforces them. They are generous because a racing season has many
// names, and small enough that the review screen stays readable.
internal const val MAX_STRONG_TERMS = 80
internal const val MAX_WEAK_TERMS = 80
internal const val MAX_SOURCES = 60

private val WHITESPACE = Regex("\\s+")

/**
 * Tidies the lists the model returned. The prompt already asks for all of
 * this; the code makes sure of it, whatever the model sent.
 */
internal fun cleanUp(terms: ExpandedTerms): ExpandedTerms {
    val strong = tidy(terms.strong).take(MAX_STRONG_TERMS)

    // A strong term already blocks by itself, so the same term in the weak
    // list would do nothing except clutter the review screen.
    val strongInLowerCase = strong.map { it.lowercase() }.toSet()
    val weak = tidy(terms.weak)
        .filter { it.lowercase() !in strongInLowerCase }
        .take(MAX_WEAK_TERMS)

    val sources = tidy(terms.sources).take(MAX_SOURCES)
    return ExpandedTerms(strong, weak, sources)
}

/**
 * Trims each term, drops blank ones and drops repeats. Two terms that differ
 * only in capitals count as repeats, because matching ignores case. The first
 * one is kept, so the model's order (most important first) survives.
 */
private fun tidy(terms: List<String>): List<String> =
    terms
        // A line break or a double space inside a term becomes one space.
        .map { it.replace(WHITESPACE, " ").trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
