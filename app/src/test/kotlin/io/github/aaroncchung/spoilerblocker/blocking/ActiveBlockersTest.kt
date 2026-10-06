package io.github.aaroncchung.spoilerblocker.blocking

import io.github.aaroncchung.spoilerblocker.data.Blocker
import io.github.aaroncchung.spoilerblocker.matcher.BlockerTerms
import io.github.aaroncchung.spoilerblocker.matcher.Breadth
import io.github.aaroncchung.spoilerblocker.matcher.Candidate
import io.github.aaroncchung.spoilerblocker.matcher.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How one term is found in a text is the matcher's business and is tested in
 * `MatcherTest`. These tests are about choosing between blockers.
 */
class ActiveBlockersTest {

    private val race = Blocker(
        id = "race",
        name = "2026 Japanese Grand Prix",
        strongTerms = listOf("Japanese Grand Prix", "Suzuka"),
        enabled = true,
        createdAtMillis = 1_000,
    )
    private val finale = Blocker(
        id = "finale",
        name = "Season finale",
        strongTerms = listOf("finale", "Suzuka"),
        enabled = true,
        createdAtMillis = 2_000,
    )

    private fun candidate(vararg texts: String) = Candidate(texts.toList())

    @Test
    fun `something that matches no blocker is not blocked`() {
        val blockers = ActiveBlockers(listOf(race, finale))

        assertNull(blockers.check(candidate("Ten easy weeknight dinners")))
    }

    @Test
    fun `the blocker that matched is reported with the term that was found`() {
        val blockers = ActiveBlockers(listOf(race, finale))

        assertEquals(
            BlockedBy(race, Reason.StrongTerm("Japanese Grand Prix")),
            blockers.check(candidate("The Japanese Grand Prix starts at six")),
        )
        assertEquals(
            BlockedBy(finale, Reason.StrongTerm("finale")),
            blockers.check(candidate("Nobody saw that finale coming")),
        )
    }

    @Test
    fun `when several blockers match the first in the list is reported`() {
        // Both blockers have the term "Suzuka".
        val suzuka = candidate("Rain at Suzuka")

        assertEquals(
            BlockedBy(race, Reason.StrongTerm("Suzuka")),
            ActiveBlockers(listOf(race, finale)).check(suzuka),
        )
        assertEquals(
            BlockedBy(finale, Reason.StrongTerm("Suzuka")),
            ActiveBlockers(listOf(finale, race)).check(suzuka),
        )
    }

    @Test
    fun `a blocker that is off is ignored`() {
        val blockers = ActiveBlockers(listOf(race.copy(enabled = false), finale))

        // Only the race blocker has this term, and it is off.
        assertNull(blockers.check(candidate("The Japanese Grand Prix starts at six")))
        // Both have this one. The race blocker comes first but is off.
        assertEquals(
            BlockedBy(finale, Reason.StrongTerm("Suzuka")),
            blockers.check(candidate("Rain at Suzuka")),
        )
    }

    @Test
    fun `with no blocker on nothing is blocked`() {
        val allOff = ActiveBlockers(listOf(race.copy(enabled = false), finale.copy(enabled = false)))
        val none = ActiveBlockers(emptyList())

        assertTrue(allOff.isEmpty)
        assertNull(allOff.check(candidate("Rain at Suzuka")))
        assertTrue(none.isEmpty)
        assertNull(none.check(candidate("Rain at Suzuka")))
    }

    @Test
    fun `isEmpty is false as soon as one blocker is on`() {
        assertFalse(ActiveBlockers(listOf(race.copy(enabled = false), finale)).isEmpty)
    }

    @Test
    fun `a blocker without terms blocks nothing`() {
        val blockers = ActiveBlockers(listOf(race.copy(strongTerms = emptyList())))

        assertFalse(blockers.isEmpty)
        assertNull(blockers.check(candidate("Rain at Suzuka")))
    }

    @Test
    fun `a new list gives new answers and the old object keeps its own`() {
        val before = ActiveBlockers(listOf(race))
        // What the listener does when the stored blockers change: the race
        // blocker lost a term and was renamed, and another one was switched on.
        val edited = race.copy(name = "Japanese GP", strongTerms = listOf("Japanese Grand Prix"))
        val after = ActiveBlockers(listOf(edited, finale))

        val suzuka = candidate("Rain at Suzuka")
        val grandPrix = candidate("The Japanese Grand Prix starts at six")

        assertEquals(BlockedBy(race, Reason.StrongTerm("Suzuka")), before.check(suzuka))
        assertEquals(BlockedBy(finale, Reason.StrongTerm("Suzuka")), after.check(suzuka))
        assertEquals(BlockedBy(edited, Reason.StrongTerm("Japanese Grand Prix")), after.check(grandPrix))
    }

    @Test
    fun `the lists and the breadth of a blocker are handed to the matcher as they are`() {
        val blocker = race.copy(
            weakTerms = listOf("Max", "podium"),
            sources = listOf("FORMULA 1"),
            breadth = Breadth.BROAD,
        )

        assertEquals(
            BlockerTerms(
                strong = listOf("Japanese Grand Prix", "Suzuka"),
                weak = listOf("Max", "podium"),
                sources = listOf("FORMULA 1"),
                breadth = Breadth.BROAD,
            ),
            blocker.toBlockerTerms(),
        )
    }

    @Test
    fun `a blocker stored without the newer fields is strong terms only and narrow`() {
        // race is built the way a version 1 file is read: the fields that
        // version 2 added are left at their defaults.
        assertEquals(
            BlockerTerms(
                strong = listOf("Japanese Grand Prix", "Suzuka"),
                weak = emptyList(),
                sources = emptyList(),
                breadth = Breadth.NARROW,
            ),
            race.toBlockerTerms(),
        )
    }

    // The rules themselves are tested in the matcher. This checks that a
    // stored blocker's weak terms, sources and breadth reach it.
    @Test
    fun `weak terms, sources and breadth of a stored blocker all take effect`() {
        val narrow = race.copy(weakTerms = listOf("Max", "podium"), sources = listOf("FORMULA 1"))
        val broad = narrow.copy(breadth = Breadth.BROAD)
        val oneWeakTerm = candidate("Max on his plans for next year")
        val twoWeakTerms = candidate("Max on the podium again")
        val fromTheSource = Candidate(texts = listOf("What a finish!"), sources = listOf("FORMULA 1"))

        assertNull(ActiveBlockers(listOf(narrow)).check(oneWeakTerm))
        assertEquals(
            BlockedBy(broad, Reason.WeakTermBroad("Max")),
            ActiveBlockers(listOf(broad)).check(oneWeakTerm),
        )
        assertEquals(
            BlockedBy(narrow, Reason.WeakTerms(listOf("Max", "podium"))),
            ActiveBlockers(listOf(narrow)).check(twoWeakTerms),
        )
        assertEquals(
            BlockedBy(narrow, Reason.Source("FORMULA 1")),
            ActiveBlockers(listOf(narrow)).check(fromTheSource),
        )
    }
}
