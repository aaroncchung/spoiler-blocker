package io.github.aaroncchung.spoilerblocker.suggestions

import io.github.aaroncchung.spoilerblocker.expansion.ExpansionResult
import io.github.aaroncchung.spoilerblocker.expansion.KeywordExpander
import io.github.aaroncchung.spoilerblocker.matcher.Breadth
import io.github.aaroncchung.spoilerblocker.expansion.Breadth as ExpansionBreadth

// This file is where the app meets the expansion module. The rest of the app
// knows the matcher's Breadth and this interface. Of the expansion module it
// sees only what comes back: an ExpansionResult.

/**
 * Suggests the three lists of a blocker from a description.
 *
 * ViewModels ask this interface and not [KeywordExpander] itself, so that a
 * unit test can give them a fake that answers at once and costs nothing.
 * `fun interface` means it has a single function, which lets a test write one
 * as a lambda.
 */
fun interface TermSuggester {
    /**
     * Asks for suggestions. The real one takes a minute or more.
     *
     * It does not throw for a call that failed: that is an
     * [ExpansionResult.Failure], which says what went wrong. Cancelling the
     * coroutine that is waiting abandons the call.
     *
     * [description] and [breadth] are all it is given, on purpose: they and
     * today's date are the only things that may leave the phone (decision 8
     * in docs/ARCHITECTURE.md).
     */
    suspend fun suggest(description: String, breadth: Breadth): ExpansionResult
}

/** The real [TermSuggester]: one call to the Claude API, made by the expansion module. */
class ClaudeTermSuggester(private val expander: KeywordExpander) : TermSuggester {
    override suspend fun suggest(description: String, breadth: Breadth): ExpansionResult =
        expander.expand(description, breadth.toExpansionBreadth())
}

/**
 * The matcher and the expansion module each have a breadth type of their
 * own, because neither module depends on the other. The app uses the
 * matcher's everywhere, and this turns it into the other one.
 *
 * There is no `else` branch on purpose: if a breadth is ever added, this
 * stops compiling until it says what the new one becomes.
 */
internal fun Breadth.toExpansionBreadth(): ExpansionBreadth = when (this) {
    Breadth.NARROW -> ExpansionBreadth.NARROW
    Breadth.BROAD -> ExpansionBreadth.BROAD
}
