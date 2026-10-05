package io.github.aaroncchung.spoilerblocker.probe

import kotlin.math.abs

/**
 * E5: decides whether the probe's box can be seen in a screenshot.
 *
 * The box is one flat colour, so the test is simply how many of the pixels
 * where the box should be have that colour.
 */
object BoxPixelCheck {

    enum class Verdict { BOX_PRESENT, BOX_ABSENT, UNCLEAR }

    /**
     * How far each of red, green and blue may be from the box colour and still
     * count. Screenshots can come back in a wider colour space than the box
     * was drawn in, which shifts the values a little.
     */
    const val DEFAULT_TOLERANCE = 24

    /** The fraction (0.0 to 1.0) of [pixels] that have the colour [boxColour]. Pixels are ARGB. */
    fun matchFraction(pixels: IntArray, boxColour: Int, tolerance: Int = DEFAULT_TOLERANCE): Double {
        if (pixels.isEmpty()) return 0.0
        val matches = pixels.count { pixel ->
            abs(red(pixel) - red(boxColour)) <= tolerance &&
                abs(green(pixel) - green(boxColour)) <= tolerance &&
                abs(blue(pixel) - blue(boxColour)) <= tolerance
        }
        return matches.toDouble() / pixels.size
    }

    /**
     * Nearly all pixels matching means the box is in the picture; nearly none
     * means it is not. Anything in between needs a person to look at the image.
     */
    fun verdict(fraction: Double): Verdict = when {
        fraction >= 0.9 -> Verdict.BOX_PRESENT
        fraction <= 0.1 -> Verdict.BOX_ABSENT
        else -> Verdict.UNCLEAR
    }

    private fun red(colour: Int) = (colour shr 16) and 0xFF
    private fun green(colour: Int) = (colour shr 8) and 0xFF
    private fun blue(colour: Int) = colour and 0xFF
}
