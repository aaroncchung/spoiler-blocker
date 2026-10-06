package io.github.aaroncchung.spoilerblocker.probe

/**
 * One pair of screenshots from the E5 rate test: a first shot that starts
 * Android's timer, and a second one [measuredGap] milliseconds later.
 *
 * [nominalGap] is the gap that was asked for. The measured one is what counts:
 * the phone may have been busy and stretched it.
 */
data class RatePair(
    val nominalGap: Long,
    val measuredGap: Long,
    val firstFailed: Boolean,
    val secondFailed: Boolean,
)

/** E5: turns the pairs of the rate test into one line. */
object RateTest {

    /**
     * A pair only says something if its first shot worked. If the first shot
     * was itself refused, the timer it was meant to start is unknown, and the
     * pair is counted as invalid.
     *
     * [callTimes] are how long the successful screenshots took, in milliseconds.
     */
    fun summarise(pairs: List<RatePair>, callTimes: List<Long>): String {
        val valid = pairs.filter { !it.firstFailed }
        val worked = valid.filter { !it.secondFailed }.map { it.measuredGap }
        val refused = valid.filter { it.secondFailed }.map { it.measuredGap }
        val shortestWorked = worked.minOrNull()
        val longestRefused = refused.maxOrNull()

        var line = "shortest measured gap that worked=${shortestWorked?.let { "${it}ms" } ?: "none"} " +
            "longest measured gap that was refused=${longestRefused?.let { "${it}ms" } ?: "none"} " +
            "valid pairs=${valid.size} invalid pairs=${pairs.size - valid.size} " +
            "call time median=${if (callTimes.isEmpty()) "-" else "${LagStats.percentile(callTimes, 50)}ms"} " +
            "max=${callTimes.maxOrNull()?.let { "${it}ms" } ?: "-"} n=${callTimes.size}"
        if (shortestWorked != null && longestRefused != null && shortestWorked <= longestRefused) {
            line += " INCONSISTENT: a gap was refused that is no shorter than one that worked. Run the test again."
        }
        return line
    }
}
