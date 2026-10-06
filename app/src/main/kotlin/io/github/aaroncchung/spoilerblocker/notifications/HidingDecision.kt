package io.github.aaroncchung.spoilerblocker.notifications

import io.github.aaroncchung.spoilerblocker.blocking.ActiveBlockers
import io.github.aaroncchung.spoilerblocker.blocking.BlockedBy
import io.github.aaroncchung.spoilerblocker.data.HiddenNotification

// What the listener does about a notification is decided here, on plain
// Kotlin data, so that the rules for groups can be unit tested on a PC. The
// rules themselves are described in the class comment of
// SpoilerNotificationListener.

/** One notification, as far as the deciding code needs to know it. */
data class ShownNotification(
    /** Android's key for it. */
    val key: String,
    val content: NotificationContent,
    val isGroupSummary: Boolean = false,
)

/** What [decideAbout] says about a notification that is to be dismissed. */
data class Hiding(
    /** The blocker that the notification itself matched, and why. */
    val blockedBy: BlockedBy,
    /**
     * The entries for the hidden list, in the order to add them. For a
     * summary these include its children: Android dismisses them along with
     * it.
     */
    val entries: List<HiddenNotification>,
)

/**
 * Decides about one notification. Returns null if it is to be left alone.
 *
 * @param children the other notifications in the group of [notification]
 *   that Android will dismiss with it. This is only asked for when
 *   [notification] is a summary that matches, because the answer costs a
 *   call to Android.
 */
fun ActiveBlockers.decideAbout(
    notification: ShownNotification,
    hiddenAtMillis: Long,
    children: () -> List<ShownNotification>,
): Hiding? {
    val blockedBy = check(notification.content.toCandidate()) ?: return null

    val entries = buildList {
        // When an app has several loose notifications showing, Android puts
        // them under a summary of its own making, which has no text. It can
        // still match, through the app's name. It is dismissed like any
        // other, but there is nothing in it to list: its children are.
        if (!(notification.isGroupSummary && notification.content.hasNothingToShow)) {
            add(notification.content.toHiddenNotification(notification.key, blockedBy, hiddenAtMillis))
        }
        if (notification.isGroupSummary) {
            for (child in children()) {
                add(entryForGroupChild(child, summaryBlockedBy = blockedBy, hiddenAtMillis))
            }
        }
    }
    return Hiding(blockedBy, entries)
}

/**
 * The entry for a child that is dismissed because the summary of its group
 * matched. A child that matches by itself is listed with its own reason.
 */
fun ActiveBlockers.entryForGroupChild(
    child: ShownNotification,
    summaryBlockedBy: BlockedBy,
    hiddenAtMillis: Long,
): HiddenNotification {
    val ownBlockedBy = check(child.content.toCandidate())
    return child.content.toHiddenNotification(
        notificationKey = child.key,
        blockedBy = ownBlockedBy ?: summaryBlockedBy,
        hiddenAtMillis = hiddenAtMillis,
        hiddenWithGroup = ownBlockedBy == null,
    )
}

/**
 * Remembers the groups whose summary the listener has just dismissed, and
 * which of their notifications are in the hidden list already.
 *
 * The listener lists a group's children and then dismisses the summary.
 * Android dismisses whatever children the group has at that moment, and that
 * can include one that was posted in between. Android reports each child it
 * dismissed, with "its summary was dismissed" as the cause, but it does not
 * say who dismissed the summary: it says the same when the owner swipes a
 * group away. This class is how the listener tells its own groups apart, and
 * how each child gets one entry and no more.
 */
class DismissedGroups {

    private class Group(val blockedBy: BlockedBy, val dismissedAtMillis: Long, val listedKeys: MutableSet<String>)

    /** By group key. */
    private val groups = HashMap<String, Group>()

    /**
     * Notes that the summary of [groupKey] was dismissed because of
     * [blockedBy], and that the notifications in [listedKeys] were put in
     * the hidden list with it.
     *
     * @param nowMillis any clock that only moves forward. The same one must
     *   be used for every call.
     */
    fun remember(groupKey: String, blockedBy: BlockedBy, listedKeys: Collection<String>, nowMillis: Long) {
        forgetOld(nowMillis)
        // Android sometimes hands the same summary over twice in a row. The
        // second time its children have gone already, so what was listed the
        // first time must be kept.
        val listedBefore = groups[groupKey]?.listedKeys.orEmpty()
        groups[groupKey] = Group(blockedBy, nowMillis, (listedBefore + listedKeys).toMutableSet())
    }

    /**
     * Notes that the notification [key] of [groupKey] was put in the hidden
     * list for a reason of its own. Does nothing if the group is not one
     * that is remembered.
     */
    fun markListed(groupKey: String, key: String) {
        groups[groupKey]?.listedKeys?.add(key)
    }

    /**
     * To be asked when Android reports that the child [key] went because the
     * summary of [groupKey] was dismissed. Returns the blocker that the
     * summary matched if the child still needs an entry, and from then on
     * counts the child as listed. Returns null if it needs none: the summary
     * was not dismissed by the listener, or the child is listed already.
     */
    fun blockerForUnlistedChild(groupKey: String, key: String, nowMillis: Long): BlockedBy? {
        forgetOld(nowMillis)
        val group = groups[groupKey] ?: return null
        // add returns false if the key was in the set already.
        return if (group.listedKeys.add(key)) group.blockedBy else null
    }

    private fun forgetOld(nowMillis: Long) {
        groups.values.removeAll { group -> nowMillis - group.dismissedAtMillis > REMEMBER_MILLIS }
    }

    companion object {
        /**
         * How long a dismissed group is remembered. Android reports the
         * children within milliseconds, so this is generous. It must not be
         * much longer: the app may post the same group again, and when the
         * owner then swipes that away, nothing was hidden.
         */
        const val REMEMBER_MILLIS = 10_000L
    }
}
