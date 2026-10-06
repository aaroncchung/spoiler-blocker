package io.github.aaroncchung.spoilerblocker.notifications

import io.github.aaroncchung.spoilerblocker.blocking.ActiveBlockers
import io.github.aaroncchung.spoilerblocker.blocking.BlockedBy
import io.github.aaroncchung.spoilerblocker.data.Blocker
import io.github.aaroncchung.spoilerblocker.data.HiddenNotification
import io.github.aaroncchung.spoilerblocker.matcher.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rules for what is dismissed and what is listed, above all for groups.
 * Hand-written notifications. None of the text comes from a real phone.
 */
class HidingDecisionTest {

    private val race = Blocker(
        id = "race",
        name = "2026 Japanese Grand Prix",
        strongTerms = listOf("Suzuka"),
        enabled = true,
        createdAtMillis = 1_000,
    )
    private val finale = Blocker(
        id = "finale",
        name = "Season finale",
        strongTerms = listOf("finale"),
        enabled = true,
        createdAtMillis = 2_000,
    )

    /** A blocker whose term is the name of the app that sends the test notifications. */
    private val chatApp = Blocker(
        id = "chat",
        name = "Everything from Chat",
        strongTerms = listOf("Chat"),
        enabled = true,
        createdAtMillis = 3_000,
    )

    private val blockers = ActiveBlockers(listOf(race, finale))
    private val bySuzuka = BlockedBy(race, Reason.StrongTerm("Suzuka"))

    private fun notification(key: String, title: String, text: String, isGroupSummary: Boolean = false) =
        ShownNotification(
            key = key,
            content = NotificationContent(packageName = "com.example.chat", appName = "Chat", title = title, text = text),
            isGroupSummary = isGroupSummary,
        )

    /** For a notification whose children must not be asked for. */
    private val notAsked: () -> List<ShownNotification> = {
        throw AssertionError("The children were asked for.")
    }

    /** Every entry gets a random id. This takes it out, so that entries can be compared. */
    private fun List<HiddenNotification>.withoutIds() = map { it.copy(id = "") }

    private fun entry(
        notification: ShownNotification,
        blockedBy: BlockedBy,
        hiddenWithGroup: Boolean = false,
    ) = notification.content
        .toHiddenNotification(notification.key, blockedBy, hiddenAtMillis = 5_000, hiddenWithGroup)
        .copy(id = "")

    @Test
    fun `a notification that matches no blocker is left alone`() {
        val harmless = notification("1", "Alex", "Lunch tomorrow?")

        assertNull(blockers.decideAbout(harmless, hiddenAtMillis = 5_000, notAsked))
    }

    @Test
    fun `a notification that matches is listed with the blocker and the term`() {
        val spoiler = notification("1", "Alex", "Rain at Suzuka")

        val hiding = blockers.decideAbout(spoiler, hiddenAtMillis = 5_000, notAsked)!!

        assertEquals(bySuzuka, hiding.blockedBy)
        assertEquals(listOf(entry(spoiler, bySuzuka)), hiding.entries.withoutIds())
    }

    @Test
    fun `a child that matches goes alone`() {
        // Seen from here a child is like any notification that is not a
        // summary: its group is not asked about and not touched.
        val child = notification("1", "Alex", "Rain at Suzuka", isGroupSummary = false)

        val hiding = blockers.decideAbout(child, hiddenAtMillis = 5_000, notAsked)!!

        assertEquals(listOf("1"), hiding.entries.map { it.notificationKey })
    }

    @Test
    fun `a summary that does not match is left alone, whatever its children say`() {
        val summary = notification("summary", "2 new messages", "Alex, Sam", isGroupSummary = true)

        // A child that matches is dismissed when it is handled by itself.
        assertNull(blockers.decideAbout(summary, hiddenAtMillis = 5_000, notAsked))
    }

    @Test
    fun `a summary that matches takes every child into the list`() {
        val summary = notification("summary", "3 new messages", "Jo: what a race at Suzuka", isGroupSummary = true)
        val harmless = notification("1", "Alex", "Lunch tomorrow?")
        val spoiler = notification("2", "Sam", "Suzuka was wild")
        val otherTopic = notification("3", "Kim", "Nobody saw that finale coming")

        val hiding = blockers.decideAbout(summary, hiddenAtMillis = 5_000) { listOf(harmless, spoiler, otherTopic) }!!

        assertEquals(bySuzuka, hiding.blockedBy)
        assertEquals(
            listOf(
                entry(summary, bySuzuka),
                // Did not match by itself: listed for the summary's reason.
                entry(harmless, bySuzuka, hiddenWithGroup = true),
                // These two match by themselves and keep their own reason.
                entry(spoiler, bySuzuka),
                entry(otherTopic, BlockedBy(finale, Reason.StrongTerm("finale"))),
            ),
            hiding.entries.withoutIds(),
        )
    }

    @Test
    fun `a summary that matches and has no children is listed alone`() {
        val summary = notification("summary", "1 new message", "Jo: what a race at Suzuka", isGroupSummary = true)

        val hiding = blockers.decideAbout(summary, hiddenAtMillis = 5_000) { emptyList() }!!

        assertEquals(listOf(entry(summary, bySuzuka)), hiding.entries.withoutIds())
    }

    @Test
    fun `a summary without text is dismissed but only its children are listed`() {
        // The summary Android makes for an app's loose notifications. It
        // matches through the app's name and has nothing to show.
        val everythingFromChat = ActiveBlockers(listOf(chatApp))
        val byAppName = BlockedBy(chatApp, Reason.StrongTerm("Chat"))
        val summary = notification("summary", title = "", text = "", isGroupSummary = true)
        val first = notification("1", "Alex", "Lunch tomorrow?")
        val second = notification("2", "Sam", "Running late")

        val hiding = everythingFromChat.decideAbout(summary, hiddenAtMillis = 5_000) { listOf(first, second) }!!

        assertEquals(byAppName, hiding.blockedBy)
        // The children match by themselves too, through the same app name.
        assertEquals(
            listOf(entry(first, byAppName), entry(second, byAppName)),
            hiding.entries.withoutIds(),
        )
    }

    @Test
    fun `a notification without text that is not a summary is still listed`() {
        // Something from the app was hidden, and the list should say so.
        val everythingFromChat = ActiveBlockers(listOf(chatApp))
        val blank = notification("1", title = "", text = "")

        val hiding = everythingFromChat.decideAbout(blank, hiddenAtMillis = 5_000, notAsked)!!

        assertEquals(listOf("1"), hiding.entries.map { it.notificationKey })
    }

    @Test
    fun `a child reported later is listed for its own reason if it has one`() {
        val harmless = notification("1", "Alex", "Lunch tomorrow?")
        val otherTopic = notification("2", "Kim", "Nobody saw that finale coming")

        assertEquals(
            entry(harmless, bySuzuka, hiddenWithGroup = true),
            blockers.entryForGroupChild(harmless, summaryBlockedBy = bySuzuka, hiddenAtMillis = 5_000).copy(id = ""),
        )
        assertEquals(
            entry(otherTopic, BlockedBy(finale, Reason.StrongTerm("finale"))),
            blockers.entryForGroupChild(otherTopic, summaryBlockedBy = bySuzuka, hiddenAtMillis = 5_000).copy(id = ""),
        )
    }

    // DismissedGroups: which of the children that Android reports as "gone
    // with its summary" still need an entry.

    @Test
    fun `a child of a group that the listener did not dismiss gets no entry`() {
        val groups = DismissedGroups()
        groups.remember("chat group", bySuzuka, listedKeys = listOf("summary"), nowMillis = 1_000)

        // The owner swiped this other group away.
        assertNull(groups.blockerForUnlistedChild("mail group", "1", nowMillis = 1_010))
    }

    @Test
    fun `a child that was listed with its summary gets no second entry`() {
        val groups = DismissedGroups()
        groups.remember("chat group", bySuzuka, listedKeys = listOf("summary", "1", "2"), nowMillis = 1_000)

        assertNull(groups.blockerForUnlistedChild("chat group", "1", nowMillis = 1_010))
        assertNull(groups.blockerForUnlistedChild("chat group", "2", nowMillis = 1_010))
    }

    @Test
    fun `a child that was not listed gets one entry and no more`() {
        val groups = DismissedGroups()
        groups.remember("chat group", bySuzuka, listedKeys = listOf("summary", "1"), nowMillis = 1_000)

        // Child "2" was posted between listing the group and dismissing it.
        assertEquals(bySuzuka, groups.blockerForUnlistedChild("chat group", "2", nowMillis = 1_010))
        assertNull(groups.blockerForUnlistedChild("chat group", "2", nowMillis = 1_020))
    }

    @Test
    fun `a child that was listed for its own reason gets no second entry`() {
        val groups = DismissedGroups()
        groups.remember("chat group", bySuzuka, listedKeys = listOf("summary"), nowMillis = 1_000)

        groups.markListed("chat group", "2")

        assertNull(groups.blockerForUnlistedChild("chat group", "2", nowMillis = 1_010))
    }

    @Test
    fun `marking a notification of an unknown group does nothing`() {
        val groups = DismissedGroups()

        groups.markListed("chat group", "1")

        assertNull(groups.blockerForUnlistedChild("chat group", "1", nowMillis = 1_010))
    }

    @Test
    fun `a dismissed group is forgotten after a while`() {
        val groups = DismissedGroups()
        val remembered = DismissedGroups.REMEMBER_MILLIS
        groups.remember("chat group", bySuzuka, listedKeys = listOf("summary"), nowMillis = 1_000)

        assertEquals(bySuzuka, groups.blockerForUnlistedChild("chat group", "1", nowMillis = 1_000 + remembered))
        // The app posted the group again and the owner swiped it away.
        assertNull(groups.blockerForUnlistedChild("chat group", "2", nowMillis = 1_001 + remembered))
    }

    @Test
    fun `a summary that is handed over twice keeps what was listed the first time`() {
        // Seen on the emulator: Android reported the same summary twice,
        // three milliseconds apart. The second time its children had gone.
        val groups = DismissedGroups()
        groups.remember("chat group", bySuzuka, listedKeys = listOf("summary", "1", "2"), nowMillis = 1_000)

        groups.remember("chat group", bySuzuka, listedKeys = listOf("summary"), nowMillis = 1_003)

        assertNull(groups.blockerForUnlistedChild("chat group", "1", nowMillis = 1_010))
        assertNull(groups.blockerForUnlistedChild("chat group", "2", nowMillis = 1_010))
        // One that was never listed still gets its entry.
        assertEquals(bySuzuka, groups.blockerForUnlistedChild("chat group", "3", nowMillis = 1_010))
    }

    @Test
    fun `dismissing a group again much later starts afresh`() {
        val groups = DismissedGroups()
        val byFinale = BlockedBy(finale, Reason.StrongTerm("finale"))
        val later = 1_001 + DismissedGroups.REMEMBER_MILLIS
        groups.remember("chat group", bySuzuka, listedKeys = listOf("summary", "1"), nowMillis = 1_000)

        groups.remember("chat group", byFinale, listedKeys = listOf("summary"), nowMillis = later)

        // "1" was listed the first time. This is a new notification under the same key.
        assertEquals(byFinale, groups.blockerForUnlistedChild("chat group", "1", nowMillis = later + 10))
    }

    @Test
    fun `groups are kept apart`() {
        val groups = DismissedGroups()
        val byFinale = BlockedBy(finale, Reason.StrongTerm("finale"))
        groups.remember("chat group", bySuzuka, listedKeys = listOf("1"), nowMillis = 1_000)
        groups.remember("mail group", byFinale, listedKeys = emptyList(), nowMillis = 1_005)

        assertEquals(byFinale, groups.blockerForUnlistedChild("mail group", "1", nowMillis = 1_010))
        assertNull(groups.blockerForUnlistedChild("chat group", "1", nowMillis = 1_010))
        assertEquals(bySuzuka, groups.blockerForUnlistedChild("chat group", "2", nowMillis = 1_010))
    }
}
