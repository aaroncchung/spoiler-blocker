package io.github.aaroncchung.spoilerblocker.expansion

import io.github.aaroncchung.spoilerblocker.matcher.hasMatchableText
import io.github.aaroncchung.spoilerblocker.matcher.matchingKey

// The longest each list may be. The prompt tells the model these numbers, and
// cleanUp() enforces them. They are generous because a racing season has many
// names, and small enough that the review screen stays readable.
internal const val MAX_STRONG_TERMS = 80
internal const val MAX_WEAK_TERMS = 80
internal const val MAX_SOURCES = 60

private val WHITESPACE = Regex("\\s+")

/**
 * Tidies the lists the model returned, so that every entry left does
 * something. The prompt asks for most of this already; the code makes sure
 * of all of it, whatever the model sent:
 *
 * - No entry without a letter or a digit, such as "🏁" or "!!!". The matcher
 *   would skip it.
 * - No entry twice in the same list. Two entries are the same when the
 *   matcher treats them as one term: "Japanese GP", "#JapaneseGP" and
 *   "japanese-gp".
 * - No weak term that is the same as a strong one.
 * - No list longer than its limit.
 *
 * Sources are looked for in other text than terms are (the names of
 * channels, accounts and senders), so a source may have the same name as a
 * term.
 */
internal fun cleanUp(terms: ExpandedTerms): ExpandedTerms {
    val strong = tidy(terms.strong).take(MAX_STRONG_TERMS)

    // A strong term already blocks by itself, so the same term in the weak
    // list would do nothing except clutter the review screen.
    val strongKeys = strong.map { matchingKey(it) }.toSet()
    val weak = tidy(terms.weak)
        .filter { matchingKey(it) !in strongKeys }
        .take(MAX_WEAK_TERMS)

    val sources = tidy(terms.sources).take(MAX_SOURCES)
    return ExpandedTerms(strong, weak, sources)
}

/**
 * Trims each entry, drops the ones that have nothing to match and drops
 * repeats. Of entries that are the same, the first is kept, so the model's
 * order (most important first) survives.
 */
private fun tidy(terms: List<String>): List<String> =
    terms
        // A line break or a double space inside a term becomes one space.
        .map { it.replace(WHITESPACE, " ").trim() }
        .filter { hasMatchableText(it) }
        .distinctBy { matchingKey(it) }
