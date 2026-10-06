package io.github.aaroncchung.spoilerblocker.probe

import org.junit.Assert.assertEquals
import org.junit.Test

class LagStatsTest {
    private val youtube = "com.example.video"
    private val instagram = "com.example.photos"

    @Test
    fun percentileUsesTheNearestRank() {
        val oneToTen = (1L..10L).toList()

        assertEquals(5, LagStats.percentile(oneToTen, 50))
        assertEquals(9, LagStats.percentile(oneToTen, 90))
        assertEquals(10, LagStats.percentile(oneToTen, 100))
        assertEquals(1, LagStats.percentile(oneToTen, 1))
    }

    @Test
    fun percentileDoesNotNeedSortedInput() {
        assertEquals(30, LagStats.percentile(listOf(50L, 10L, 30L), 50))
        assertEquals(50, LagStats.percentile(listOf(50L, 10L, 30L), 90))
    }

    @Test
    fun percentileOfOneValueIsThatValue() {
        assertEquals(7, LagStats.percentile(listOf(7L), 50))
        assertEquals(7, LagStats.percentile(listOf(7L), 90))
    }

    @Test
    fun spreadIsMedianThenNinetiethPercentileThenMax() {
        assertEquals("5/9/10", LagStats.spread((1L..10L).toList()))
        assertEquals("-", LagStats.spread(emptyList()))
    }

    @Test
    fun summarisesEachMechanismAndTriggerSeparately() {
        val samples = listOf(
            LagSample(youtube, "sc-display", "VIEW_SCROLLED", received = 2, boundsRead = 4, submitted = 5, frame = null, committed = 12),
            LagSample(youtube, "sc-display", "VIEW_SCROLLED", received = 4, boundsRead = 8, submitted = 9, frame = null, committed = 30),
            LagSample(youtube, "sc-display", "VIEW_SCROLLED", received = 6, boundsRead = 9, submitted = 9, frame = null, committed = 20),
            LagSample(youtube, "wm-move", "poll", received = 0, boundsRead = 3, submitted = 3, frame = 4, committed = null),
        )

        assertEquals(
            listOf(
                "app=com.example.video mech=sc-display by=VIEW_SCROLLED n=3 recv=4/6/6 bounds=8/9/9 submit=9/9/9" +
                    " frame=- commit=20/30/30 commit-n=3 (median/p90/max ms after the event)",
                "app=com.example.video mech=wm-move by=poll n=1 recv=0/0/0 bounds=3/3/3 submit=3/3/3" +
                    " frame=4/4/4 commit=- commit-n=0 (median/p90/max ms after the event)",
            ),
            LagStats.summarise(samples),
        )
    }

    @Test
    fun keepsTwoAppsApart() {
        val samples = listOf(
            LagSample(youtube, "wm-canvas", "poll", received = 0, boundsRead = 2, submitted = 2, frame = 0, committed = 10),
            LagSample(instagram, "wm-canvas", "poll", received = 0, boundsRead = 40, submitted = 40, frame = 0, committed = 90),
        )

        val lines = LagStats.summarise(samples)

        assertEquals(2, lines.size)
        assertEquals(
            "app=com.example.video mech=wm-canvas by=poll n=1 recv=0/0/0 bounds=2/2/2 submit=2/2/2" +
                " frame=0/0/0 commit=10/10/10 commit-n=1 (median/p90/max ms after the event)",
            lines[0],
        )
        assertEquals(
            "app=com.example.photos mech=wm-canvas by=poll n=1 recv=0/0/0 bounds=40/40/40 submit=40/40/40" +
                " frame=0/0/0 commit=90/90/90 commit-n=1 (median/p90/max ms after the event)",
            lines[1],
        )
    }

    @Test
    fun countsOnlyTheCommitsThatArrived() {
        // Three updates, but the commit report came back for one of them only.
        val samples = listOf(
            LagSample(youtube, "wm-canvas", "VIEW_SCROLLED", received = 1, boundsRead = 2, submitted = 3, frame = 4, committed = 25),
            LagSample(youtube, "wm-canvas", "VIEW_SCROLLED", received = 1, boundsRead = 2, submitted = 3, frame = 4, committed = null),
            LagSample(youtube, "wm-canvas", "VIEW_SCROLLED", received = 1, boundsRead = 2, submitted = 3, frame = 4, committed = null),
        )

        val line = LagStats.summarise(samples).single()

        assertEquals(true, line.contains(" n=3 "))
        assertEquals(true, line.contains(" commit=25/25/25 commit-n=1 "))
    }

    @Test
    fun saysSoWhenUpdatesWereLeftOut() {
        val samples = listOf(
            LagSample(youtube, "wm-move", "poll", received = 0, boundsRead = 3, submitted = 3, frame = 0, committed = null),
        )

        val lines = LagStats.summarise(samples, dropped = 120)

        assertEquals(2, lines.size)
        assertEquals(
            "CAPPED: 120 later box updates are not in these figures. Reset the summary between clips.",
            lines[1],
        )
    }

    @Test
    fun saysSoWhenThereIsNothingToSummarise() {
        assertEquals(listOf("no box updates recorded yet"), LagStats.summarise(emptyList()))
    }

    @Test
    fun intervalsAreTheGapsBetweenNeighbours() {
        assertEquals(listOf(100L, 110L, 95L), LagStats.intervals(listOf(1000L, 1100L, 1210L, 1305L), longestCounted = 1000))
    }

    @Test
    fun intervalsLeaveOutThePausesBetweenGestures() {
        // Two scrolls, four seconds apart.
        val times = listOf(1000L, 1100L, 1200L, 5200L, 5300L)

        assertEquals(listOf(100L, 100L, 100L), LagStats.intervals(times, longestCounted = 1000))
    }

    @Test
    fun intervalsOfFewerThanTwoTimesAreEmpty() {
        assertEquals(emptyList<Long>(), LagStats.intervals(emptyList(), longestCounted = 1000))
        assertEquals(emptyList<Long>(), LagStats.intervals(listOf(5L), longestCounted = 1000))
    }
}
