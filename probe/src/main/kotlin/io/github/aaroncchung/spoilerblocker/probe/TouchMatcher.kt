package io.github.aaroncchung.spoilerblocker.probe

/**
 * E3: decides what to log about each touch and about the first scroll event
 * that follows it. It uses no Android classes, so the rules can be unit
 * tested. All times are uptime milliseconds.
 *
 * Two traps are avoided here:
 *
 * - A finger that lands on a list which is still moving would be paired with
 *   an event left over from the earlier movement. Such a touch is logged as
 *   "touch during scroll" and is not paired with anything.
 * - The figure logged is how far the touch signal was ahead of the scroll
 *   *event*. An app sends that event up to 100 ms after the content moved,
 *   so the figure is not the time there was to cover the content.
 */
class TouchMatcher {

    /** The line to log for a touch report, and whether it is a finger not reported before. */
    data class TouchReport(val line: String, val isNewTouch: Boolean)

    private class Touch(
        val number: Int,
        val source: String,
        val downTime: Long,
        val received: Long,
        val duringScroll: Boolean,
    ) {
        /** The kinds of event already paired with this touch, so each is logged once. */
        val matched = HashSet<String>()
    }

    private var count = 0
    private var last: Touch? = null
    private var lastScrollEventReceived: Long? = null

    /**
     * A mechanism called [source] reports a finger that went down at
     * [downTime]; the probe heard of it at [received].
     */
    fun onTouch(source: String, downTime: Long, received: Long): TouchReport {
        val previous = last
        if (previous != null && previous.downTime == downTime) {
            // A second mechanism reporting the same finger.
            return TouchReport(
                "touch #${previous.number} src=$source down=$downTime recv=+${received - downTime}ms",
                isNewTouch = false,
            )
        }
        val lastScroll = lastScrollEventReceived
        val duringScroll = lastScroll != null && lastScroll >= downTime - QUIET_BEFORE_TOUCH_MS
        count++
        last = Touch(count, source, downTime, received, duringScroll)
        var line = "touch #$count src=$source down=$downTime recv=+${received - downTime}ms"
        if (duringScroll) {
            line += " (touch during scroll, not matched: a scroll event arrived in the " +
                "${QUIET_BEFORE_TOUCH_MS}ms before it)"
        }
        return TouchReport(line, isNewTouch = true)
    }

    /**
     * An accessibility event of [kind] was sent by the app at [eventTime] and
     * reached the probe at [received]. Returns a line to log if this is the
     * first event of its kind after a touch that can be paired, else null.
     */
    fun onEvent(kind: String, eventTime: Long, received: Long): String? {
        val line = pair(kind, eventTime, received)
        if (kind == VIEW_SCROLLED) lastScrollEventReceived = received
        return line
    }

    private fun pair(kind: String, eventTime: Long, received: Long): String? {
        val touch = last ?: return null
        if (touch.duringScroll) return null
        if (eventTime < touch.downTime || eventTime - touch.downTime > PAIRING_WINDOW_MS) return null
        if (!touch.matched.add(kind)) return null
        return "touch #${touch.number}: first $kind event was sent +${eventTime - touch.downTime}ms and received " +
            "+${received - touch.downTime}ms after touch-down; the ${touch.source} signal reached the probe " +
            "${received - touch.received}ms before that EVENT did. The content moves up to 100ms before " +
            "its event is sent, so this is not the time to spare for covering."
    }

    companion object {
        const val VIEW_SCROLLED = "VIEW_SCROLLED"
        const val CONTENT_CHANGED = "CONTENT_CHANGED"

        /** A list counts as still moving if a scroll event arrived this recently before the finger landed. */
        const val QUIET_BEFORE_TOUCH_MS = 300L

        /** An event later than this after a touch is not about that touch. */
        const val PAIRING_WINDOW_MS = 5_000L
    }
}
