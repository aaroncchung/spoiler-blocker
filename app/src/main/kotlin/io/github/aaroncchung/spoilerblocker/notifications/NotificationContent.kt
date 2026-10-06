package io.github.aaroncchung.spoilerblocker.notifications

import io.github.aaroncchung.spoilerblocker.blocking.BlockedBy
import io.github.aaroncchung.spoilerblocker.data.HiddenNotification
import io.github.aaroncchung.spoilerblocker.matcher.Candidate
import io.github.aaroncchung.spoilerblocker.matcher.Reason
import java.util.UUID

/**
 * The text of one notification, copied out of Android's objects into plain
 * Kotlin. Everything that decides about a notification works on this, which
 * is what lets it be unit tested on a PC.
 *
 * A notification has many places for text and most notifications use only a
 * few. A part that is missing is an empty string or an empty list.
 */
data class NotificationContent(
    val packageName: String,
    /** The sending app's name as the launcher shows it, or its package name if that could not be read. */
    val appName: String,
    val title: String = "",
    /** The title of the expanded notification, when the app gave a separate one. */
    val bigTitle: String = "",
    val text: String = "",
    /** The longer text of the expanded notification. */
    val bigText: String = "",
    /** Small text in the header, next to the app's name. Often an account name. */
    val subText: String = "",
    /** A line under the expanded notification. Email apps put the account here. */
    val summaryText: String = "",
    /** Small text at the right-hand side on old versions of Android. Apps may still set it. */
    val infoText: String = "",
    /** The name of a group chat. */
    val conversationTitle: String = "",
    /** The lines of a notification that lists several things, such as new emails. */
    val lines: List<String> = emptyList(),
    /** The messages of a chat notification, oldest first. */
    val messages: List<Message> = emptyList(),
    /** Text that old versions of Android scrolled through the status bar. Apps may still set it. */
    val tickerText: String = "",
    /** What an app says its picture shows, for screen readers. */
    val pictureDescription: String = "",
) {
    /** One message in a chat notification. [sender] is empty for the owner's own replies. */
    data class Message(val sender: String, val text: String)

    /** The title the hidden list shows: the one from the expanded notification if there is one. */
    val displayTitle: String
        get() = bigTitle.ifBlank { title }

    /**
     * The text the hidden list shows: what Android would have shown when the
     * notification was expanded. That is the fullest form of the body, then
     * the small print around it.
     *
     * The parts that Android itself no longer shows anywhere are left out
     * ([infoText], [tickerText], [pictureDescription]). They are still
     * matched, so now and then an entry is hidden for a term that its text
     * does not show.
     */
    val displayText: String
        get() {
            val body = when {
                messages.isNotEmpty() -> messages.joinToString("\n") { message ->
                    if (message.sender.isBlank()) message.text else "${message.sender}: ${message.text}"
                }
                lines.isNotEmpty() -> lines.joinToString("\n")
                bigText.isNotBlank() -> bigText
                else -> text
            }
            return listOf(body, subText, summaryText)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString("\n")
        }

    /**
     * The one place where a notification becomes what the matcher looks at:
     * every part, because a spoiler can be in any of them.
     *
     * The app's name is included, as docs/ARCHITECTURE.md describes. For now
     * it is matched like any other text.
     */
    fun toCandidate(): Candidate {
        val texts = buildList {
            add(appName)
            add(title)
            add(bigTitle)
            add(text)
            add(bigText)
            add(subText)
            add(summaryText)
            add(infoText)
            add(conversationTitle)
            addAll(lines)
            for (message in messages) {
                add(message.sender)
                add(message.text)
            }
            add(tickerText)
            add(pictureDescription)
        }
        // The same words are often in several parts: the text is usually
        // also the start of the big text and the newest message.
        return Candidate(texts.filter { it.isNotBlank() }.distinct())
    }

    /**
     * Makes the entry for the hidden list.
     *
     * @param notificationKey Android's key for the notification.
     * @param blockedBy the blocker that caused it to be dismissed, and why.
     * @param hiddenWithGroup true if it was the summary of this
     *   notification's group that matched, not this notification.
     */
    fun toHiddenNotification(
        notificationKey: String,
        blockedBy: BlockedBy,
        hiddenAtMillis: Long,
        hiddenWithGroup: Boolean = false,
    ): HiddenNotification = HiddenNotification(
        id = UUID.randomUUID().toString(),
        hiddenAtMillis = hiddenAtMillis,
        notificationKey = notificationKey,
        packageName = packageName,
        appName = appName,
        title = displayTitle,
        text = displayText,
        blockerId = blockedBy.blocker.id,
        blockerName = blockedBy.blocker.name,
        matchedTerm = matchedTermOf(blockedBy.reason),
        hiddenWithGroup = hiddenWithGroup,
    )
}

/**
 * The term to show for a [Reason] in the hidden list.
 *
 * There is no `else` branch on purpose. [Reason] is a sealed interface, so
 * when the matcher gains another kind of reason this stops compiling, which
 * points at the place that has to say how the new kind is shown and stored.
 */
private fun matchedTermOf(reason: Reason): String = when (reason) {
    is Reason.StrongTerm -> reason.term
}
