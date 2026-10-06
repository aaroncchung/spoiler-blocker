package io.github.aaroncchung.spoilerblocker.status

/**
 * What the status notification says, worked out in plain Kotlin so that it
 * can be unit tested on a PC. The words themselves are in `strings.xml`, and
 * [StatusNotifier] puts the two together.
 */
data class StatusNotificationContent(
    /**
     * False if the app has no notification access. A blocker is on but
     * nothing is being hidden, and the notification has to say so.
     */
    val isHidingNotifications: Boolean,
    /** How many blockers are on. Never zero. */
    val blockerCount: Int,
    /** The names to show: those of the first few blockers, each cut to a length that fits. */
    val names: List<String>,
) {
    /** How many blockers are on without being named in [names]. */
    val unnamedCount: Int
        get() = blockerCount - names.size
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
    notificationAccessGranted: Boolean,
): StatusNotificationContent? {
    if (enabledBlockerNames.isEmpty()) return null
    return StatusNotificationContent(
        isHidingNotifications = notificationAccessGranted,
        blockerCount = enabledBlockerNames.size,
        names = enabledBlockerNames.take(MAX_NAMES).map { name -> shortened(name) },
    )
}

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
