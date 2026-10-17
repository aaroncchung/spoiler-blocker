package io.github.aaroncchung.spoilerblocker.blocking

import io.github.aaroncchung.spoilerblocker.data.Blocker
import io.github.aaroncchung.spoilerblocker.matcher.BlockerTerms
import io.github.aaroncchung.spoilerblocker.matcher.Candidate
import io.github.aaroncchung.spoilerblocker.matcher.Matcher
import io.github.aaroncchung.spoilerblocker.matcher.Reason
import io.github.aaroncchung.spoilerblocker.matcher.Verdict

/** Which blocker blocked something, and why. */
data class BlockedBy(val blocker: Blocker, val reason: Reason)

/**
 * The blockers that are switched on, ready to check things against.
 *
 * Like the [Matcher]s inside it, it never changes after it is constructed.
 * When the stored blockers change, build a new one from the new list.
 *
 * Nothing in here knows about notifications: it decides about a [Candidate],
 * so the screen reader can use the same class for posts.
 *
 * @param blockers every stored blocker. The ones that are off are left out.
 */
class ActiveBlockers(blockers: List<Blocker>) {

    private class Entry(val blocker: Blocker, val matcher: Matcher)

    private val entries: List<Entry> = blockers
        .filter { blocker -> blocker.enabled }
        .map { blocker -> Entry(blocker, Matcher(blocker.toBlockerTerms())) }

    /** True when no blocker is on, so nothing can be blocked. */
    val isEmpty: Boolean
        get() = entries.isEmpty()

    /**
     * Returns the blocker that blocks [candidate] and its reason, or null if
     * none of them does. If several would block it, the one that comes first
     * in the list is reported.
     */
    fun check(candidate: Candidate): BlockedBy? {
        for (entry in entries) {
            when (val verdict = entry.matcher.check(candidate)) {
                is Verdict.Blocked -> return BlockedBy(entry.blocker, verdict.reason)
                Verdict.Allowed -> Unit
            }
        }
        return null
    }
}

/** The one place where a stored [Blocker] becomes the term lists the matcher needs. */
fun Blocker.toBlockerTerms(): BlockerTerms = BlockerTerms(strong = strongTerms)
