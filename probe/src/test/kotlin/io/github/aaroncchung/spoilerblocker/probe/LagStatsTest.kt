package io.github.aaroncchung.spoilerblocker.probe

import org.junit.Assert.assertEquals
import org.junit.Test

class LagStatsTest {

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
    fun summarisesEachMechanismAndTriggerSeparately() {
        val samples = listOf(
            LagSample("sc-display", "VIEW_SCROLLED", received = 2, boundsRead = 4, submitted = 5, frame = null, committed = 12),
            LagSample("sc-display", "VIEW_SCROLLED", received = 4, boundsRead = 8, submitted = 9, frame = null, committed = 30),
            LagSample("sc-display", "VIEW_SCROLLED", received = 6, boundsRead = 9, submitted = 9, frame = null, committed = 20),
            LagSample("wm-move", "poll", received = 0, boundsRead = 3, submitted = 3, frame = 4, committed = null),
        )

        assertEquals(
            listOf(
                "mech=sc-display by=VIEW_SCROLLED n=3 recv=4/6/6 bounds=8/9/9 submit=9/9/9 frame=- commit=20/30/30" +
                    " (median/p90/max ms after the event)",
                "mech=wm-move by=poll n=1 recv=0/0/0 bounds=3/3/3 submit=3/3/3 frame=4/4/4 commit=-" +
                    " (median/p90/max ms after the event)",
            ),
            LagStats.summarise(samples),
        )
    }

    @Test
    fun saysSoWhenThereIsNothingToSummarise() {
        assertEquals(listOf("no box updates recorded yet"), LagStats.summarise(emptyList()))
    }
}
