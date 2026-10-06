package io.github.aaroncchung.spoilerblocker.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchMatcherTest {
    private val scrolled = TouchMatcher.VIEW_SCROLLED
    private val changed = TouchMatcher.CONTENT_CHANGED

    @Test
    fun aTouchOnAListAtRestIsPairedWithItsFirstScrollEvent() {
        val matcher = TouchMatcher()

        val touch = matcher.onTouch("outside-touch", downTime = 1000, received = 1003)
        val event = matcher.onEvent(scrolled, eventTime = 1165, received = 1172)

        assertEquals("touch #1 src=outside-touch down=1000 recv=+3ms", touch.line)
        assertTrue(touch.isNewTouch)
        assertEquals(
            "touch #1: first VIEW_SCROLLED event was sent +165ms and received +172ms after touch-down; " +
                "the outside-touch signal reached the probe 169ms before that EVENT did. The content moves " +
                "up to 100ms before its event is sent, so this is not the time to spare for covering.",
            event,
        )
    }

    @Test
    fun eachKindOfEventIsPairedOnce() {
        val matcher = TouchMatcher()
        matcher.onTouch("outside-touch", downTime = 1000, received = 1003)

        val firstChange = matcher.onEvent(changed, eventTime = 1060, received = 1062)
        val firstScroll = matcher.onEvent(scrolled, eventTime = 1165, received = 1172)

        assertTrue(firstChange!!.startsWith("touch #1: first CONTENT_CHANGED event was sent +60ms"))
        assertTrue(firstScroll!!.startsWith("touch #1: first VIEW_SCROLLED event was sent +165ms"))
        assertNull(matcher.onEvent(scrolled, eventTime = 1265, received = 1270))
        assertNull(matcher.onEvent(changed, eventTime = 1270, received = 1275))
    }

    @Test
    fun aTouchOnAMovingListIsNotPaired() {
        val matcher = TouchMatcher()
        // The list is flinging: a scroll event every 100 ms.
        matcher.onEvent(scrolled, eventTime = 795, received = 800)
        matcher.onEvent(scrolled, eventTime = 895, received = 900)

        val touch = matcher.onTouch("outside-touch", downTime = 1000, received = 1002)
        // The fling's last event was still waiting to be sent when the finger landed.
        val leftOver = matcher.onEvent(scrolled, eventTime = 1010, received = 1014)
        val later = matcher.onEvent(scrolled, eventTime = 1110, received = 1114)

        assertEquals(
            "touch #1 src=outside-touch down=1000 recv=+2ms " +
                "(touch during scroll, not matched: a scroll event arrived in the 300ms before it)",
            touch.line,
        )
        assertTrue(touch.isNewTouch)
        assertNull(leftOver)
        assertNull(later)
    }

    @Test
    fun aListThatStoppedLongEnoughAgoCountsAsAtRest() {
        val matcher = TouchMatcher()
        matcher.onEvent(scrolled, eventTime = 690, received = 699)

        // 301 ms of quiet before the finger lands.
        val touch = matcher.onTouch("outside-touch", downTime = 1000, received = 1001)
        val event = matcher.onEvent(scrolled, eventTime = 1150, received = 1155)

        assertEquals("touch #1 src=outside-touch down=1000 recv=+1ms", touch.line)
        assertTrue(event!!.startsWith("touch #1: first VIEW_SCROLLED event was sent +150ms"))
    }

    @Test
    fun exactlyTheQuietTimeIsStillCountedAsMoving() {
        val matcher = TouchMatcher()
        matcher.onEvent(scrolled, eventTime = 695, received = 700)

        val touch = matcher.onTouch("outside-touch", downTime = 1000, received = 1001)

        assertTrue(touch.line.contains("touch during scroll, not matched"))
    }

    @Test
    fun contentChangesAloneDoNotMakeAListCountAsMoving() {
        // A playing video changes content all the time without scrolling.
        val matcher = TouchMatcher()
        matcher.onEvent(changed, eventTime = 950, received = 955)

        val touch = matcher.onTouch("outside-touch", downTime = 1000, received = 1001)

        assertEquals("touch #1 src=outside-touch down=1000 recv=+1ms", touch.line)
    }

    @Test
    fun theNextTouchIsJudgedAfresh() {
        val matcher = TouchMatcher()
        matcher.onEvent(scrolled, eventTime = 895, received = 900)
        matcher.onTouch("outside-touch", downTime = 1000, received = 1002)
        matcher.onEvent(scrolled, eventTime = 1010, received = 1014)

        // Two seconds later the list is at rest and a new finger lands.
        val second = matcher.onTouch("outside-touch", downTime = 3000, received = 3004)
        val event = matcher.onEvent(scrolled, eventTime = 3200, received = 3206)

        assertEquals("touch #2 src=outside-touch down=3000 recv=+4ms", second.line)
        assertTrue(event!!.startsWith("touch #2: first VIEW_SCROLLED event was sent +200ms and received +206ms"))
        assertTrue(event.contains("signal reached the probe 202ms before that EVENT did"))
    }

    @Test
    fun aSecondMechanismReportingTheSameFingerIsNotANewTouch() {
        val matcher = TouchMatcher()
        matcher.onTouch("outside-touch", downTime = 1000, received = 1003)

        val again = matcher.onTouch("motion-event", downTime = 1000, received = 1006)

        assertEquals("touch #1 src=motion-event down=1000 recv=+6ms", again.line)
        assertFalse(again.isNewTouch)
    }

    @Test
    fun eventsFromBeforeTheTouchOrLongAfterItAreNotPaired() {
        val matcher = TouchMatcher()
        matcher.onTouch("outside-touch", downTime = 1000, received = 1003)

        // Sent before the finger went down, delivered after.
        assertNull(matcher.onEvent(changed, eventTime = 990, received = 1004))
        // More than five seconds later.
        assertNull(matcher.onEvent(scrolled, eventTime = 6001, received = 6005))
    }

    @Test
    fun withoutATouchNothingIsPaired() {
        assertNull(TouchMatcher().onEvent(scrolled, eventTime = 100, received = 105))
    }
}
