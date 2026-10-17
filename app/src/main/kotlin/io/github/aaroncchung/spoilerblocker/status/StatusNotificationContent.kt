package io.github.aaroncchung.spoilerblocker.status

/**
 * What the status notification says, worked out in plain Kotlin so that it
 * can be unit tested on a PC. The words themselves are in `strings.xml`, and
 * [StatusNotifier] puts the two together.
 */
data class StatusNotificationContent(
    /**
     * Whether notifications that match are being hidden. If they are not, a
     * blocker is on but does nothing, and the notification has to say so.
     */
    val hidingState: HidingState,
    /** How many blockers are on. Never zero. */
    val blockerCount: Int,
    /** The names to show: those of the first few blockers, each cut to a length that fits. */
    val names: List<String>,
) {
    /** How many blockers are on without being named in [names]. */
    val unnamedCount: Int
        get() = blockerCount - names.size
}

/** Whether the notification listener is hiding the notifications that match, and if not, why not. */
enum class HidingState {
    /** The listener is connected, so a notification that matches is hidden. */
    HIDING,

    /** The owner has not given the app notification access. */
    NO_ACCESS,

    /**
     * The app has notification access, but Android has not connected the
     * listener, or has disconnected it. Nothing is hidden until it connects.
     */
    NOT_CONNECTED,
}

/**
 * Works out the [HidingState].
 *
 * Notification access alone is not enough. Android connects the listener
 * only some time after it starts this app's process, and it can disconnect
 * the listener without taking access away.
 */
fun hidingState(notificationAccessGranted: Boolean, listenerConnected: Boolean): HidingState = when {
    // Checked first: in the instant after access is taken away, the
    // listener may not have been told yet.
    !notificationAccessGranted -> HidingState.NO_ACCESS
    !listenerConnected -> HidingState.NOT_CONNECTED
    else -> HidingState.HIDING
}

/**
 * Decides what the status notification says.
 *
 * @param enabledBlockerNames the names of the blockers that are on, in the
 *   order of the blocker list.
 * @return null if no blocker is on, which means no notification.
 */
fun statusNotificationContent(
    enabledBlockerNames: List<String>,
    hidingState: HidingState,
): StatusNotificationContent? {
    if (enabledBlockerNames.isEmpty()) return null
    return StatusNotificationContent(
        hidingState = hidingState,
        blockerCount = enabledBlockerNames.size,
        names = enabledBlockerNames.take(MAX_NAMES).map { name -> shortened(name) },
    )
}

/**
 * True if a warning that the listener is not connected has to wait, because
 * the process started only [millisSinceStart] milliseconds ago.
 *
 * When Android starts this process, after a restart of the phone, an update
 * of the app or after it ended the process to free memory, it connects the
 * listener a moment later.
 * The stored blockers are read in that same moment, and either can finish
 * first. Without the wait the notification would often warn that nothing is
 * being hidden, only to take the warning back straight away.
 *
 * Meanwhile the notification is left as it is, so the wait makes no claim of
 * its own. If the listener has not connected once the wait is over, the
 * warning is shown. Without notification access there is no wait: the
 * listener cannot connect at all.
 */
fun isTooEarlyToWarn(hidingState: HidingState, millisSinceStart: Long): Boolean =
    hidingState == HidingState.NOT_CONNECTED && millisSinceStart < LISTENER_CONNECT_WAIT_MILLIS

/**
 * How long after the process starts [isTooEarlyToWarn] holds a warning back.
 * Far longer than connecting usually takes.
 */
const val LISTENER_CONNECT_WAIT_MILLIS = 10_000L

// A notification shows one line of text until it is expanded, and a few
// lines after that. Three names of this length still fit the expanded form.
private const val MAX_NAMES = 3
private const val MAX_NAME_LENGTH = 40

/** Returns [name], cut off with "…" if it is longer than [MAX_NAME_LENGTH]. */
private fun shortened(name: String): String {
    if (name.length <= MAX_NAME_LENGTH) return name
    // One place is kept for the "…".
    var start = name.take(MAX_NAME_LENGTH - 1)
    // An emoji is stored as two chars. Half of one would show as a broken
    // character, so it goes as a whole.
    if (start.last().isHighSurrogate()) start = start.dropLast(1)
    return start.trimEnd() + "…"
}
