package io.github.aaroncchung.spoilerblocker.probe

import kotlin.math.ceil

/**
 * E2: the timing of one box update. Every field is in milliseconds after the
 * moment the update was caused (an accessibility event, or a polled frame).
 *
 * [frame] and [committed] are null when the mechanism cannot report them.
 */
data class LagSample(
    val mechanism: String,
    val trigger: String,
    val received: Long,
    val boundsRead: Long,
    val submitted: Long,
    val frame: Long?,
    val committed: Long?,
)

/** Puts the E2 timings into a few numbers, so nobody has to average log lines by hand. */
object LagStats {

    /**
     * The value below which [percent] per cent of [values] fall, by the
     * "nearest rank" method. [values] must not be empty.
     */
    fun percentile(values: List<Long>, percent: Int): Long {
        val sorted = values.sorted()
        val rank = ceil(percent / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /** One line per mechanism and trigger, each stage as "median/90th percentile/max". */
    fun summarise(samples: List<LagSample>): List<String> {
        if (samples.isEmpty()) return listOf("no box updates recorded yet")
        return samples
            .groupBy { it.mechanism to it.trigger }
            .map { (key, group) ->
                val (mechanism, trigger) = key
                "mech=$mechanism by=$trigger n=${group.size}" +
                    " recv=${stage(group.map { it.received })}" +
                    " bounds=${stage(group.map { it.boundsRead })}" +
                    " submit=${stage(group.map { it.submitted })}" +
                    " frame=${stage(group.mapNotNull { it.frame })}" +
                    " commit=${stage(group.mapNotNull { it.committed })}" +
                    " (median/p90/max ms after the event)"
            }
    }

    private fun stage(values: List<Long>): String =
        if (values.isEmpty()) "-" else "${percentile(values, 50)}/${percentile(values, 90)}/${values.max()}"
}
