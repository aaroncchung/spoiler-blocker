package io.github.aaroncchung.spoilerblocker.probe

import kotlin.math.ceil

/**
 * E2: the timing of one box update. Every time is in milliseconds after the
 * moment the update was caused. For an accessibility event that moment is
 * when the app sent the event; for a poll it is when the probe's frame began.
 * The two starting points differ, so the two kinds must not be compared.
 *
 * [app] is the package in front, so that YouTube and Instagram are kept apart.
 * [frame] and [committed] are null when the mechanism cannot report them, or
 * when the report did not arrive in time.
 */
data class LagSample(
    val app: String,
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

    /** "median/90th percentile/max" of [values], or "-" if there are none. */
    fun spread(values: List<Long>): String =
        if (values.isEmpty()) "-" else "${percentile(values, 50)}/${percentile(values, 90)}/${values.max()}"

    /**
     * One line per app, mechanism and trigger.
     *
     * `n` counts the box updates in the group. `commit-n` counts those whose
     * commit time arrived; the commit figures are about those only.
     * [dropped] is how many updates came after the store was full and are
     * therefore missing from every figure.
     */
    fun summarise(samples: List<LagSample>, dropped: Int = 0): List<String> {
        if (samples.isEmpty()) return listOf("no box updates recorded yet")
        val lines = samples
            .groupBy { Triple(it.app, it.mechanism, it.trigger) }
            .map { (key, group) ->
                val (app, mechanism, trigger) = key
                val committed = group.mapNotNull { it.committed }
                "app=$app mech=$mechanism by=$trigger n=${group.size}" +
                    " recv=${spread(group.map { it.received })}" +
                    " bounds=${spread(group.map { it.boundsRead })}" +
                    " submit=${spread(group.map { it.submitted })}" +
                    " frame=${spread(group.mapNotNull { it.frame })}" +
                    " commit=${spread(committed)} commit-n=${committed.size}" +
                    " (median/p90/max ms after the event)"
            }
        return if (dropped > 0) {
            lines + "CAPPED: $dropped later box updates are not in these figures. Reset the summary between clips."
        } else {
            lines
        }
    }

    /**
     * The gaps between neighbouring [times], which must be in order. Gaps
     * longer than [longestCounted] are left out: they are the pauses between
     * two gestures, not the rhythm within one.
     */
    fun intervals(times: List<Long>, longestCounted: Long): List<Long> =
        times.zipWithNext { earlier, later -> later - earlier }.filter { it in 0..longestCounted }
}
