package io.github.aaroncchung.spoilerblocker.probe

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The words used in E6 log lines. The code that writes the lines and the code
 * that reads them back both use these constants, so they cannot drift apart.
 */
object E6 {
    const val TAG = "E6"

    /** The accessibility service. */
    const val A11Y = "a11y"

    /** The notification listener service ("nls"). */
    const val LISTENER = "nls"

    const val PROCESS_START = "process-start"
    const val RUN_START = "run-start"
    const val HEARTBEAT = "heartbeat"
    const val CONNECTED = "connected"
    const val DISCONNECTED = "disconnected"
    const val EXIT_INFO = "exit-info"
}

/** Why two heartbeats of one service were further apart than they should be. */
enum class GapKind(val label: String) {
    REBOOTED("the phone was rebooted"),
    PROCESS_RESTARTED("the app's process died and was started again"),
    SERVICE_DISCONNECTED("Android disconnected the service but the process stayed alive"),
    STALLED("the process stayed alive but did not run (frozen or blocked)"),
}

/**
 * A stretch with no heartbeat.
 *
 * [awakeMillis] is how long the processor was awake during the gap. It is null
 * after a reboot, because the uptime clock restarts and cannot be compared.
 */
data class HeartbeatGap(
    val kind: GapKind,
    val startWallMillis: Long,
    val awakeMillis: Long?,
    val realMillis: Long,
)

/** What the log says about one service during one run. */
data class ServiceRun(
    val service: String,
    val heartbeats: Int,
    val connects: Int,
    val disconnects: Int,
    val firstBeatWallMillis: Long?,
    val lastBeatWallMillis: Long?,
    /** Real time from the first heartbeat to the last. */
    val observedMillis: Long,
    /** The part of [observedMillis] the phone spent in deep sleep. */
    val asleepMillis: Long,
    /** The most awake time that passed between two heartbeats. About 60 s when all is well. */
    val longestAwakeGapMillis: Long,
    val gaps: List<HeartbeatGap>,
    /** Awake time from the last heartbeat until now. Null if unknown (no heartbeat, or a reboot since). */
    val awakeSinceLastBeatMillis: Long?,
)

/** The E6 summary shown in the activity. */
data class RunSummary(
    val runStartWallMillis: Long?,
    val runLabel: String?,
    val processStarts: Int,
    val reboots: Int,
    val services: List<ServiceRun>,
    val exitInfo: List<String>,
)

/**
 * Turns the E6 lines of the log into a summary.
 *
 * Each service writes a heartbeat line once a minute. The timer behind it
 * counts awake time only, so a phone in deep sleep writes no heartbeats and
 * that is not a failure. A failure is awake time passing with no heartbeat.
 * This is why every gap is measured on the uptime clock, and why the real-time
 * clock is only used to say how long the phone slept.
 */
object HeartbeatSummary {
    const val INTERVAL_MS = 60_000L

    /** More awake time than this between two heartbeats is reported as a gap. */
    const val GAP_THRESHOLD_MS = 90_000L

    private class Tracker(val service: String) {
        var heartbeats = 0
        var connects = 0
        var disconnects = 0
        var firstBeat: LogRecord? = null
        var lastBeat: LogRecord? = null
        var observed = 0L
        var asleep = 0L
        var longestAwakeGap = 0L
        val gaps = mutableListOf<HeartbeatGap>()

        // What has happened since the last heartbeat. It explains the next gap.
        var rebooted = false
        var processStarted = false
        var disconnected = false

        fun onHeartbeat(record: LogRecord) {
            heartbeats++
            val previous = lastBeat
            if (previous == null) {
                firstBeat = record
            } else if (rebooted || record.realtimeMillis < previous.realtimeMillis) {
                // Only the time of day can be compared across a reboot.
                val real = record.wallMillis - previous.wallMillis
                observed += real
                gaps += HeartbeatGap(GapKind.REBOOTED, previous.wallMillis, null, real)
            } else {
                val awake = record.uptimeMillis - previous.uptimeMillis
                val real = record.realtimeMillis - previous.realtimeMillis
                observed += real
                asleep += (real - awake).coerceAtLeast(0)
                longestAwakeGap = maxOf(longestAwakeGap, awake)
                if (awake > GAP_THRESHOLD_MS) {
                    val kind = when {
                        processStarted || record.pid != previous.pid -> GapKind.PROCESS_RESTARTED
                        disconnected -> GapKind.SERVICE_DISCONNECTED
                        else -> GapKind.STALLED
                    }
                    gaps += HeartbeatGap(kind, previous.wallMillis, awake, real)
                }
            }
            lastBeat = record
            rebooted = false
            processStarted = false
            disconnected = false
        }

        fun finish(now: LogRecord): ServiceRun {
            val last = lastBeat
            val awakeSinceLast = if (last == null || rebooted || now.realtimeMillis < last.realtimeMillis) {
                null
            } else {
                now.uptimeMillis - last.uptimeMillis
            }
            return ServiceRun(
                service = service,
                heartbeats = heartbeats,
                connects = connects,
                disconnects = disconnects,
                firstBeatWallMillis = firstBeat?.wallMillis,
                lastBeatWallMillis = last?.wallMillis,
                observedMillis = observed,
                asleepMillis = asleep,
                longestAwakeGapMillis = longestAwakeGap,
                gaps = gaps.toList(),
                awakeSinceLastBeatMillis = awakeSinceLast,
            )
        }
    }

    /**
     * Summarises the run that is in progress: every E6 record after the last
     * "run-start" marker, or all of them if there is no marker.
     *
     * [now] carries the three clocks at the moment of asking. Only its clock
     * fields are used.
     */
    fun compute(records: List<LogRecord>, now: LogRecord): RunSummary {
        val e6 = records.filter { it.tag == E6.TAG }
        val runStartIndex = e6.indexOfLast { it.message.startsWith(E6.RUN_START) }
        val run = if (runStartIndex >= 0) e6.subList(runStartIndex, e6.size) else e6
        val runStart = if (runStartIndex >= 0) e6[runStartIndex] else null

        val trackers = listOf(Tracker(E6.A11Y), Tracker(E6.LISTENER))
        var processStarts = 0
        var reboots = 0
        var lastBoot: String? = null
        val exitInfo = mutableListOf<String>()

        for (record in run) {
            val words = record.message.split(' ')
            when (words[0]) {
                E6.PROCESS_START -> {
                    processStarts++
                    val boot = words.firstOrNull { it.startsWith("boot=") }
                    val rebooted = lastBoot != null && boot != null && boot != lastBoot
                    if (rebooted) reboots++
                    if (boot != null) lastBoot = boot
                    for (tracker in trackers) {
                        tracker.processStarted = true
                        if (rebooted) tracker.rebooted = true
                    }
                }
                E6.HEARTBEAT -> trackers.firstOrNull { it.service == words.getOrNull(1) }?.onHeartbeat(record)
                E6.EXIT_INFO -> exitInfo += record.message.removePrefix(E6.EXIT_INFO).trim()
                else -> {
                    // Lifecycle lines look like "a11y connected" or "nls disconnected (...)".
                    val tracker = trackers.firstOrNull { it.service == words[0] } ?: continue
                    when (words.getOrNull(1)) {
                        E6.CONNECTED -> tracker.connects++
                        E6.DISCONNECTED -> {
                            tracker.disconnects++
                            tracker.disconnected = true
                        }
                    }
                }
            }
        }

        return RunSummary(
            runStartWallMillis = runStart?.wallMillis,
            runLabel = runStart?.message?.removePrefix(E6.RUN_START)?.trim()?.ifEmpty { null },
            processStarts = processStarts,
            reboots = reboots,
            services = trackers.map { it.finish(now) },
            exitInfo = exitInfo,
        )
    }

    /** The summary as lines of plain text, for the screen and for the log. */
    fun describe(summary: RunSummary, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        val lines = mutableListOf<String>()
        if (summary.runStartWallMillis != null) {
            lines += "Run started ${wallTime(summary.runStartWallMillis, zone)} ${summary.runLabel.orEmpty()}".trim()
            // The process was already running when the run was marked, so every start since is a restart.
            lines += "Process started ${summary.processStarts} time(s) since then (more than 0 means it died and came back). " +
                "Reboots: ${summary.reboots}."
        } else {
            lines += "Process started ${summary.processStarts} time(s) in this log (more than 1 means it died and came back). " +
                "Reboots: ${summary.reboots}."
        }
        for (run in summary.services) {
            val name = run.service
            if (run.heartbeats == 0) {
                lines += "$name: no heartbeat yet. Connected ${run.connects} time(s)."
                continue
            }
            // After a run-start marker the first connection is not in the run, so only reconnections are counted.
            val connections = if (summary.runStartWallMillis != null) "Reconnected" else "Connected"
            lines += "$name: ${run.heartbeats} heartbeats over ${duration(run.observedMillis)}, " +
                "of which ${duration(run.asleepMillis)} in deep sleep. " +
                "$connections ${run.connects} time(s), disconnected ${run.disconnects} time(s)."
            lines += "$name: longest awake time between heartbeats: ${duration(run.longestAwakeGapMillis)} (about 1 min is normal)."
            if (run.gaps.isEmpty()) {
                lines += "$name: no gaps."
            }
            for (gap in run.gaps) {
                val awake = if (gap.awakeMillis != null) "${duration(gap.awakeMillis)} awake, " else ""
                lines += "$name: GAP from ${wallTime(gap.startWallMillis, zone)}: ${gap.kind.label}; " +
                    "$awake${duration(gap.realMillis)} in real time."
            }
            val sinceLast = run.awakeSinceLastBeatMillis
            lines += when {
                sinceLast == null -> "$name: last heartbeat was before a reboot."
                sinceLast > GAP_THRESHOLD_MS ->
                    "$name: NOT RUNNING. No heartbeat for ${duration(sinceLast)} of awake time " +
                        "(last at ${wallTime(run.lastBeatWallMillis ?: 0, zone)})."
                else -> "$name: last heartbeat ${duration(sinceLast)} of awake time ago."
            }
        }
        for (exit in summary.exitInfo) {
            lines += "Earlier process exit: $exit"
        }
        return lines
    }

    /** "9 h 02 min", "1 min 05 s" or "45 s". */
    fun duration(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        // Locale.ROOT keeps the digits plain whatever language the phone is set to.
        return when {
            hours > 0 -> String.format(Locale.ROOT, "%d h %02d min", hours, minutes)
            minutes > 0 -> String.format(Locale.ROOT, "%d min %02d s", minutes, seconds)
            else -> "$seconds s"
        }
    }

    private val WALL_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private fun wallTime(millis: Long, zone: ZoneId): String =
        WALL_TIME.format(Instant.ofEpochMilli(millis).atZone(zone))
}
