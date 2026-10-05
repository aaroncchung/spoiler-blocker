package io.github.aaroncchung.spoilerblocker.probe

import io.github.aaroncchung.spoilerblocker.probe.BoxPixelCheck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class BoxPixelCheckTest {
    private val magenta = 0xFFFF00FF.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun aRegionFullOfTheBoxColourMatchesCompletely() {
        val pixels = IntArray(100) { magenta }

        assertEquals(1.0, BoxPixelCheck.matchFraction(pixels, magenta), 0.0)
    }

    @Test
    fun aRegionOfOtherColoursDoesNotMatch() {
        val pixels = IntArray(100) { white }

        assertEquals(0.0, BoxPixelCheck.matchFraction(pixels, magenta), 0.0)
    }

    @Test
    fun countsTheMatchingShare() {
        val pixels = IntArray(100) { index -> if (index < 25) magenta else white }

        assertEquals(0.25, BoxPixelCheck.matchFraction(pixels, magenta), 0.0)
    }

    @Test
    fun allowsSmallColourShiftsButNotLargeOnes() {
        // Red 255 -> 240 and blue 255 -> 235 are within the tolerance of 24.
        val slightlyOff = 0xFFF000EB.toInt()
        // Green 0 -> 40 is not.
        val tooGreen = 0xFFFF28FF.toInt()

        assertEquals(1.0, BoxPixelCheck.matchFraction(intArrayOf(slightlyOff), magenta), 0.0)
        assertEquals(0.0, BoxPixelCheck.matchFraction(intArrayOf(tooGreen), magenta), 0.0)
    }

    @Test
    fun toleranceIsInclusiveAtItsEdge() {
        val green = 0xFF00FF00.toInt()
        val exactlyAtEdge = 0xFF00E700.toInt() // green 255 - 24 = 231 = 0xE7
        val justPastEdge = 0xFF00E600.toInt()

        assertEquals(1.0, BoxPixelCheck.matchFraction(intArrayOf(exactlyAtEdge), green), 0.0)
        assertEquals(0.0, BoxPixelCheck.matchFraction(intArrayOf(justPastEdge), green), 0.0)
    }

    @Test
    fun ignoresTransparency() {
        val seeThroughMagenta = 0x00FF00FF

        assertEquals(1.0, BoxPixelCheck.matchFraction(intArrayOf(seeThroughMagenta), magenta), 0.0)
    }

    @Test
    fun anEmptyRegionMatchesNothing() {
        assertEquals(0.0, BoxPixelCheck.matchFraction(IntArray(0), magenta), 0.0)
    }

    @Test
    fun verdictNeedsAClearMajorityEitherWay() {
        assertEquals(Verdict.BOX_PRESENT, BoxPixelCheck.verdict(1.0))
        assertEquals(Verdict.BOX_PRESENT, BoxPixelCheck.verdict(0.9))
        assertEquals(Verdict.UNCLEAR, BoxPixelCheck.verdict(0.89))
        assertEquals(Verdict.UNCLEAR, BoxPixelCheck.verdict(0.5))
        assertEquals(Verdict.UNCLEAR, BoxPixelCheck.verdict(0.11))
        assertEquals(Verdict.BOX_ABSENT, BoxPixelCheck.verdict(0.1))
        assertEquals(Verdict.BOX_ABSENT, BoxPixelCheck.verdict(0.0))
    }
}
