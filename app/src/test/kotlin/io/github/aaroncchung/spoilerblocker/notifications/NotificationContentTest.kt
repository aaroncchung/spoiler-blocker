package io.github.aaroncchung.spoilerblocker.notifications

import io.github.aaroncchung.spoilerblocker.blocking.ActiveBlockers
import io.github.aaroncchung.spoilerblocker.blocking.BlockedBy
import io.github.aaroncchung.spoilerblocker.data.Blocker
import io.github.aaroncchung.spoilerblocker.matcher.Candidate
import io.github.aaroncchung.spoilerblocker.matcher.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hand-written notifications. None of the text comes from a real phone. */
class NotificationContentTest {

    private val race = Blocker(
        id = "race",
        name = "2026 Japanese Grand Prix",
        strongTerms = listOf("Suzuka"),
        enabled = true,
        createdAtMillis = 1_000,
    )

    /** A notification with nothing in it but where it came from. */
    private val empty = NotificationContent(packageName = "com.example.chat", appName = "Chat")

    @Test
    fun `the candidate holds every part of the notification`() {
        val content = NotificationContent(
            packageName = "com.example.chat",
            appName = "Chat",
            title = "Alex",
            bigTitle = "Alex and Sam",
            text = "See you there",
            bigText = "See you there, and bring the tickets",
            subText = "Work account",
            summaryText = "2 new messages",
            infoText = "12",
            conversationTitle = "Race weekend",
            lines = listOf("First line", "Second line"),
            messages = listOf(
                NotificationContent.Message(sender = "Sam", text = "Who is driving?"),
                NotificationContent.Message(sender = "", text = "I am"),
            ),
            tickerText = "Alex: See you there",
            pictureDescription = "A car park",
            otherTexts = listOf("Reply", "Mark as read", "New message"),
        )

        assertEquals(
            Candidate(
                listOf(
                    "Chat",
                    "Alex",
                    "Alex and Sam",
                    "See you there",
                    "See you there, and bring the tickets",
                    "Work account",
                    "2 new messages",
                    "12",
                    "Race weekend",
                    "First line",
                    "Second line",
                    "Sam",
                    "Who is driving?",
                    "I am",
                    "Alex: See you there",
                    "A car park",
                    "Reply",
                    "Mark as read",
                    "New message",
                ),
            ),
            content.toCandidate(),
        )
    }

    @Test
    fun `the package name is not part of the candidate`() {
        assertEquals(Candidate(listOf("Chat")), empty.toCandidate())
    }

    @Test
    fun `parts that are blank are left out and a repeated text is there once`() {
        val content = empty.copy(
            title = "Alex",
            bigTitle = "Alex",
            text = "See you there",
            bigText = "   ",
            lines = listOf("", "See you there"),
            tickerText = "See you there",
        )

        assertEquals(Candidate(listOf("Chat", "Alex", "See you there")), content.toCandidate())
    }

    @Test
    fun `a term is found whichever part it is in`() {
        val blockers = ActiveBlockers(listOf(race))
        val spoiler = "Rain at Suzuka"
        val everyPart = listOf(
            empty.copy(appName = spoiler),
            empty.copy(title = spoiler),
            empty.copy(bigTitle = spoiler),
            empty.copy(text = spoiler),
            empty.copy(bigText = spoiler),
            empty.copy(subText = spoiler),
            empty.copy(summaryText = spoiler),
            empty.copy(infoText = spoiler),
            empty.copy(conversationTitle = spoiler),
            empty.copy(lines = listOf("Nothing here", spoiler)),
            empty.copy(messages = listOf(NotificationContent.Message(sender = spoiler, text = "Hello"))),
            empty.copy(messages = listOf(NotificationContent.Message(sender = "Sam", text = spoiler))),
            empty.copy(tickerText = spoiler),
            empty.copy(pictureDescription = spoiler),
            // For example a button, or the text for a locked screen.
            empty.copy(otherTexts = listOf("Reply", spoiler)),
        )

        for (content in everyPart) {
            assertNotNull("Not found in $content", blockers.check(content.toCandidate()))
        }
        assertNull(blockers.check(empty.copy(title = "Alex", text = "See you there").toCandidate()))
    }

    @Test
    fun `the title shown is the expanded one when there is one`() {
        assertEquals("Alex", empty.copy(title = "Alex").displayTitle)
        assertEquals("Alex and Sam", empty.copy(title = "Alex", bigTitle = "Alex and Sam").displayTitle)
        assertEquals("", empty.displayTitle)
    }

    @Test
    fun `the text shown is the fullest form the notification has`() {
        val plain = empty.copy(text = "See you there")
        val withBigText = plain.copy(bigText = "See you there, and bring the tickets")
        val withLines = withBigText.copy(lines = listOf("First line", "Second line"))
        val withMessages = withLines.copy(
            messages = listOf(
                NotificationContent.Message(sender = "Sam", text = "Who is driving?"),
                NotificationContent.Message(sender = "", text = "I am"),
            ),
        )

        assertEquals("See you there", plain.displayText)
        assertEquals("See you there, and bring the tickets", withBigText.displayText)
        assertEquals("First line\nSecond line", withLines.displayText)
        // The owner's own reply has no sender.
        assertEquals("Sam: Who is driving?\nI am", withMessages.displayText)
        assertEquals("", empty.displayText)
    }

    @Test
    fun `the text shown ends with the small print`() {
        val content = empty.copy(
            text = "See you there",
            subText = "Work account",
            summaryText = "2 new messages",
            // Android no longer shows these three, and neither does the list.
            infoText = "12",
            tickerText = "Alex: See you there",
            pictureDescription = "A car park",
            // Matched only: buttons, the text for a locked screen and so on.
            otherTexts = listOf("Reply", "New message"),
        )

        assertEquals("See you there\nWork account\n2 new messages", content.displayText)
        assertEquals("Work account", empty.copy(subText = "Work account").displayText)
        // Email apps often put the account in both places.
        assertEquals(
            "See you there\nWork account",
            empty.copy(text = "See you there", subText = "Work account", summaryText = "Work account").displayText,
        )
    }

    @Test
    fun `a text that is missing is empty`() {
        assertEquals("", textOf(null))
        assertEquals("Alex", textOf("Alex"))
        // Android often hands text over as something other than a String.
        assertEquals("Alex", textOf(StringBuilder("Alex")))
    }

    @Test
    fun `a null in a list of texts is left out`() {
        // What Notification.InboxStyle().addLine(null) leaves in the array.
        val lines: Array<CharSequence?> = arrayOf("First line", null, "Second line")

        assertEquals(listOf("First line", "Second line"), textsOf(lines))
        assertEquals(emptyList<String>(), textsOf(arrayOf<CharSequence?>(null)))
        assertEquals(emptyList<String>(), textsOf(null))
    }

    @Test
    fun `a part that cannot be read is empty and the other parts are still read`() {
        val content = NotificationContent(
            packageName = "com.example.chat",
            appName = "Chat",
            title = readPart("") { "Alex" },
            text = readPart("") { throw IllegalStateException("This part is broken.") },
            lines = readPart(emptyList()) { throw NullPointerException() },
            bigText = readPart("") { "Rain at Suzuka" },
        )

        assertEquals(empty.copy(title = "Alex", bigText = "Rain at Suzuka"), content)
        assertNotNull(ActiveBlockers(listOf(race)).check(content.toCandidate()))
    }

    @Test
    fun `a notification with only parts that are not shown has nothing to show`() {
        assertTrue(empty.hasNothingToShow)
        assertTrue(empty.copy(tickerText = "Alex: See you there", infoText = "12").hasNothingToShow)
        assertTrue(empty.copy(otherTexts = listOf("Reply", "New message")).hasNothingToShow)

        assertFalse(empty.copy(title = "Alex").hasNothingToShow)
        assertFalse(empty.copy(bigTitle = "Alex and Sam").hasNothingToShow)
        assertFalse(empty.copy(text = "See you there").hasNothingToShow)
        assertFalse(empty.copy(subText = "Work account").hasNothingToShow)
        assertFalse(empty.copy(lines = listOf("First line")).hasNothingToShow)
    }

    @Test
    fun `the hidden list entry says what was hidden, by which blocker and why`() {
        val content = empty.copy(
            title = "Alex",
            text = "Rain at Suzuka",
            bigText = "Rain at Suzuka, and the start is delayed",
        )

        val entry = content.toHiddenNotification(
            notificationKey = "0|com.example.chat|7|null|10001",
            blockedBy = BlockedBy(race, Reason.StrongTerm("Suzuka")),
            hiddenAtMillis = 5_000,
        )

        assertEquals(5_000, entry.hiddenAtMillis)
        assertEquals("0|com.example.chat|7|null|10001", entry.notificationKey)
        assertEquals("com.example.chat", entry.packageName)
        assertEquals("Chat", entry.appName)
        assertEquals("Alex", entry.title)
        assertEquals("Rain at Suzuka, and the start is delayed", entry.text)
        assertEquals("race", entry.blockerId)
        assertEquals("2026 Japanese Grand Prix", entry.blockerName)
        assertEquals("Suzuka", entry.matchedTerm)
        assertFalse(entry.hiddenWithGroup)
    }

    @Test
    fun `an entry can say that it was hidden with its group`() {
        val entry = empty.copy(title = "Alex").toHiddenNotification(
            notificationKey = "key",
            blockedBy = BlockedBy(race, Reason.StrongTerm("Suzuka")),
            hiddenAtMillis = 5_000,
            hiddenWithGroup = true,
        )

        assertTrue(entry.hiddenWithGroup)
    }

    @Test
    fun `every entry gets its own id`() {
        val blockedBy = BlockedBy(race, Reason.StrongTerm("Suzuka"))

        val first = empty.toHiddenNotification("key", blockedBy, hiddenAtMillis = 5_000)
        val second = empty.toHiddenNotification("key", blockedBy, hiddenAtMillis = 5_000)

        assertTrue(first.id.isNotBlank())
        assertNotEquals(first.id, second.id)
    }
}
