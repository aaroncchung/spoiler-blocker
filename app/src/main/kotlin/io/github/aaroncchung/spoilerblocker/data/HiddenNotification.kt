package io.github.aaroncchung.spoilerblocker.data

import kotlinx.serialization.Serializable

/**
 * A notification that was dismissed because it matched a blocker, kept so
 * that the owner can see afterwards what was hidden (decision 13 in
 * docs/ARCHITECTURE.md).
 *
 * [title] and [text] are the spoiler itself. They are stored in the app's
 * private storage by [HiddenNotificationRepository] and go nowhere else: not
 * into the log and not off the phone.
 *
 * As with [Blocker], adding a field changes the stored format. The field
 * then needs a default value, and `CURRENT_VERSION` in
 * `HiddenNotificationRepository.kt` has to be raised.
 * `HiddenNotificationRepositoryTest` pins the format and fails until both
 * are done.
 */
@Serializable
data class HiddenNotification(
    /** A random UUID. The list on screen uses it to tell rows apart. */
    val id: String,
    /** When it was dismissed: milliseconds since 1970, as `System.currentTimeMillis()` gives. */
    val hiddenAtMillis: Long,
    /**
     * Android's own name for the notification: the user, package, tag and id
     * in one string. An app that updates a notification posts it again under
     * the same key, which is how a repeat is recognised.
     */
    val notificationKey: String,
    val packageName: String,
    /** The sending app's name at the time. The app may be uninstalled later. */
    val appName: String,
    val title: String,
    val text: String,
    val blockerId: String,
    /** The blocker's name at the time. The blocker may be renamed or deleted later. */
    val blockerName: String,
    /** The term of the blocker that was found. */
    val matchedTerm: String,
    /**
     * True if this notification did not match by itself. It belonged to a
     * group whose summary matched, and Android dismisses the whole group
     * along with its summary.
     */
    val hiddenWithGroup: Boolean = false,
)
