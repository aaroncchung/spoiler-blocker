package io.github.aaroncchung.spoilerblocker.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RateTestTest {

    private fun pair(nominal: Long, measured: Long, firstFailed: Boolean = false, secondFailed: Boolean) =
        RatePair(nominal, measured, firstFailed, secondFailed)

    @Test
    fun reportsMeasuredGapsNotTheOnesAskedFor() {
        val pairs = listOf(
            pair(nominal = 300, measured = 304, secondFailed = true),
            pair(nominal = 320, measured = 322, secondFailed = true),
            // Asked for 340 ms, but the phone was busy and it became 371 ms.
            pair(nominal = 340, measured = 371, secondFailed = false),
            pair(nominal = 400, measured = 402, secondFailed = false),
        )

        assertEquals(
            "shortest measured gap that worked=371ms longest measured gap that was refused=322ms " +
                "valid pairs=4 invalid pairs=0 call time median=14ms max=59ms n=3",
            RateTest.summarise(pairs, callTimes = listOf(9, 14, 59)),
        )
    }

    @Test
    fun aPairWhoseFirstShotFailedSaysNothing() {
        val pairs = listOf(
            pair(nominal = 200, measured = 203, secondFailed = true),
            // The first shot was refused, so nothing started the timer. That
            // the second one then worked after only 101 ms proves nothing.
            pair(nominal = 100, measured = 101, firstFailed = true, secondFailed = false),
            pair(nominal = 400, measured = 404, secondFailed = false),
        )

        val line = RateTest.summarise(pairs, callTimes = listOf(12))

        assertTrue(line, line.startsWith("shortest measured gap that worked=404ms longest measured gap that was refused=203ms "))
        assertTrue(line, line.contains("valid pairs=2 invalid pairs=1"))
        assertTrue(line, !line.contains("INCONSISTENT"))
    }

    @Test
    fun flagsResultsThatContradictEachOther() {
        val pairs = listOf(
            pair(nominal = 300, measured = 310, secondFailed = false),
            pair(nominal = 340, measured = 345, secondFailed = true),
        )

        val line = RateTest.summarise(pairs, callTimes = listOf(10, 11))

        assertTrue(line, line.endsWith("INCONSISTENT: a gap was refused that is no shorter than one that worked. Run the test again."))
    }

    @Test
    fun copesWithNothingWorkingAndNothingMeasured() {
        val pairs = listOf(pair(nominal = 0, measured = 1, secondFailed = true))

        assertEquals(
            "shortest measured gap that worked=none longest measured gap that was refused=1ms " +
                "valid pairs=1 invalid pairs=0 call time median=- max=- n=0",
            RateTest.summarise(pairs, callTimes = emptyList()),
        )
        assertEquals(
            "shortest measured gap that worked=none longest measured gap that was refused=none " +
                "valid pairs=0 invalid pairs=0 call time median=- max=- n=0",
            RateTest.summarise(emptyList(), callTimes = emptyList()),
        )
    }
}
