package io.github.aaroncchung.spoilerblocker.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class HeartbeatSummaryTest {
    private val minute = 60_000L
    private val hour = 60 * minute

    /** A fixed "time of day" for the first record. Only differences matter in these tests. */
    private val startWall = 1_760_000_000_000L

    /**
     * An E6 record. [up] and [rt] are the uptime and real-time clocks. The
     * time of day moves with the real-time clock unless [wall] says otherwise.
     */
    private fun e6(up: Long, rt: Long, message: String, pid: Int = 100, wall: Long = startWall + rt) =
        LogRecord(wall, up, rt, pid, E6.TAG, message)

    private fun now(up: Long, rt: Long, pid: Int = 100) = e6(up, rt, "", pid)

    private fun RunSummary.a11y() = services.first { it.service == E6.A11Y }
    private fun RunSummary.listener() = services.first { it.service == E6.LISTENER }

    @Test
    fun steadyHeartbeatsHaveNoGaps() {
        val records = listOf(
            e6(0, 0, "process-start boot=7"),
            e6(100, 100, "a11y connected"),
            e6(100, 100, "heartbeat a11y n=1"),
            e6(100 + minute, 100 + minute, "heartbeat a11y n=2"),
            e6(100 + 2 * minute, 100 + 2 * minute, "heartbeat a11y n=3"),
        )

        val summary = HeartbeatSummary.compute(records, now(100 + 2 * minute + 5_000, 100 + 2 * minute + 5_000))

        val run = summary.a11y()
        assertEquals(3, run.heartbeats)
        assertEquals(1, run.connects)
        assertEquals(0, run.disconnects)
        assertEquals(2 * minute, run.observedMillis)
        assertEquals(0, run.asleepMillis)
        assertEquals(minute, run.longestAwakeGapMillis)
        assertTrue(run.gaps.isEmpty())
        assertEquals(5_000L, run.awakeSinceLastBeatMillis)
        assertEquals(1, summary.processStarts)
        assertEquals(0, summary.reboots)
    }

    @Test
    fun deepSleepIsNotAGap() {
        // Two hours pass on the wall, but the processor was only awake for
        // one minute of them: the phone slept and the timer waited.
        val records = listOf(
            e6(0, 0, "heartbeat a11y n=1"),
            e6(minute, 2 * hour, "heartbeat a11y n=2"),
        )

        val run = HeartbeatSummary.compute(records, now(minute, 2 * hour)).a11y()

        assertTrue(run.gaps.isEmpty())
        assertEquals(2 * hour, run.observedMillis)
        assertEquals(2 * hour - minute, run.asleepMillis)
        assertEquals(minute, run.longestAwakeGapMillis)
    }

    @Test
    fun awakeTimeWithoutHeartbeatInTheSameProcessIsAStall() {
        val records = listOf(
            e6(0, 0, "heartbeat a11y n=1"),
            e6(10 * minute, 3 * hour, "heartbeat a11y n=2"),
        )

        val run = HeartbeatSummary.compute(records, now(10 * minute, 3 * hour)).a11y()

        assertEquals(
            listOf(HeartbeatGap(GapKind.STALLED, startWall, 10 * minute, 3 * hour)),
            run.gaps,
        )
        assertEquals(10 * minute, run.longestAwakeGapMillis)
    }

    @Test
    fun aNewProcessBetweenHeartbeatsIsARestart() {
        val records = listOf(
            e6(0, 0, "process-start boot=7", pid = 100),
            e6(0, 0, "heartbeat a11y n=1", pid = 100),
            e6(20 * minute, 40 * minute, "process-start boot=7", pid = 200),
            e6(20 * minute + 500, 40 * minute + 500, "a11y connected", pid = 200),
            e6(20 * minute + 500, 40 * minute + 500, "heartbeat a11y n=1", pid = 200),
        )

        val summary = HeartbeatSummary.compute(records, now(21 * minute, 41 * minute, pid = 200))

        assertEquals(2, summary.processStarts)
        assertEquals(0, summary.reboots)
        assertEquals(
            listOf(HeartbeatGap(GapKind.PROCESS_RESTARTED, startWall, 20 * minute + 500, 40 * minute + 500)),
            summary.a11y().gaps,
        )
    }

    @Test
    fun aDifferentPidAloneAlsoCountsAsARestart() {
        // The process-start line itself could be lost if the process is killed
        // again at once. The pid on the heartbeat still gives it away.
        val records = listOf(
            e6(0, 0, "heartbeat a11y n=1", pid = 100),
            e6(5 * minute, 5 * minute, "heartbeat a11y n=1", pid = 200),
        )

        val run = HeartbeatSummary.compute(records, now(5 * minute, 5 * minute, pid = 200)).a11y()

        assertEquals(GapKind.PROCESS_RESTARTED, run.gaps.single().kind)
    }

    @Test
    fun aDisconnectInALivingProcessIsReportedAsSuch() {
        val records = listOf(
            e6(0, 0, "a11y connected"),
            e6(0, 0, "heartbeat a11y n=1"),
            e6(30_000, 30_000, "a11y disconnected (onUnbind)"),
            e6(30_000, 30_000, "a11y destroyed"),
            e6(4 * minute, 4 * minute, "a11y created"),
            e6(4 * minute, 4 * minute, "a11y connected"),
            e6(4 * minute, 4 * minute, "heartbeat a11y n=1"),
        )

        val run = HeartbeatSummary.compute(records, now(4 * minute, 4 * minute)).a11y()

        assertEquals(2, run.connects)
        assertEquals(1, run.disconnects)
        assertEquals(GapKind.SERVICE_DISCONNECTED, run.gaps.single().kind)
        assertEquals(4 * minute, run.gaps.single().awakeMillis)
    }

    @Test
    fun aRebootIsNotMistakenForAStall() {
        // After a reboot both clocks start again from zero, so only the time of day can be compared.
        val records = listOf(
            e6(0, 0, "process-start boot=7", pid = 100),
            e6(5 * hour, 9 * hour, "heartbeat a11y n=1", pid = 100),
            e6(1_000, 1_000, "process-start boot=8", pid = 150, wall = startWall + 10 * hour),
            e6(2_000, 2_000, "heartbeat a11y n=1", pid = 150, wall = startWall + 10 * hour + 1_000),
        )

        val summary = HeartbeatSummary.compute(records, now(3_000, 3_000, pid = 150))

        assertEquals(1, summary.reboots)
        val gap = summary.a11y().gaps.single()
        assertEquals(GapKind.REBOOTED, gap.kind)
        assertNull(gap.awakeMillis)
        assertEquals(hour + 1_000, gap.realMillis)
        // The stretch before the reboot must not count as the longest awake gap.
        assertEquals(0, summary.a11y().longestAwakeGapMillis)
        assertEquals(1_000L, summary.a11y().awakeSinceLastBeatMillis)
    }

    @Test
    fun theTwoServicesAreJudgedSeparately() {
        val records = listOf(
            e6(0, 0, "heartbeat a11y n=1"),
            e6(0, 0, "heartbeat nls n=1"),
            e6(minute, minute, "heartbeat a11y n=2"),
            e6(2 * minute, 2 * minute, "heartbeat a11y n=3"),
            e6(2 * minute, 2 * minute, "nls disconnected (onListenerDisconnected)"),
            e6(3 * minute, 3 * minute, "heartbeat a11y n=4"),
            e6(3 * minute, 3 * minute, "nls connected"),
            e6(3 * minute, 3 * minute, "heartbeat nls n=1"),
        )

        val summary = HeartbeatSummary.compute(records, now(3 * minute, 3 * minute))

        assertTrue(summary.a11y().gaps.isEmpty())
        assertEquals(4, summary.a11y().heartbeats)
        assertEquals(GapKind.SERVICE_DISCONNECTED, summary.listener().gaps.single().kind)
        assertEquals(2, summary.listener().heartbeats)
    }

    @Test
    fun onlyTheNewestRunIsCounted() {
        val records = listOf(
            e6(0, 0, "process-start boot=7"),
            e6(0, 0, "heartbeat a11y n=1"),
            e6(30 * minute, 30 * minute, "heartbeat a11y n=2"),
            e6(31 * minute, 31 * minute, "run-start battery=unrestricted bucket=active label=night-1"),
            e6(31 * minute, 31 * minute, "heartbeat a11y n=3"),
            e6(32 * minute, 32 * minute, "heartbeat a11y n=4"),
        )

        val summary = HeartbeatSummary.compute(records, now(32 * minute, 32 * minute))

        assertEquals(startWall + 31 * minute, summary.runStartWallMillis)
        assertEquals("battery=unrestricted bucket=active label=night-1", summary.runLabel)
        assertEquals(0, summary.processStarts)
        assertEquals(2, summary.a11y().heartbeats)
        assertTrue(summary.a11y().gaps.isEmpty())

        val text = HeartbeatSummary.describe(summary, ZoneId.of("UTC"))
        assertEquals("Run started 2025-10-09 09:24:20 battery=unrestricted bucket=active label=night-1", text[0])
        assertEquals(
            "Process started 0 time(s) since then (more than 0 means it died and came back). Reboots: 0.",
            text[1],
        )
        assertEquals(
            "a11y: 2 heartbeats over 1 min 00 s, of which 0 s in deep sleep. " +
                "Reconnected 0 time(s), disconnected 0 time(s).",
            text[2],
        )
    }

    @Test
    fun withoutARunMarkerTheWholeLogIsDescribed() {
        val records = listOf(
            e6(0, 0, "process-start boot=7"),
            e6(100, 100, "a11y connected"),
            e6(100, 100, "heartbeat a11y n=1"),
            e6(100 + minute, 100 + 2 * hour, "heartbeat a11y n=2"),
        )

        val summary = HeartbeatSummary.compute(records, now(100 + minute + 20_000, 100 + 2 * hour + 20_000))
        val text = HeartbeatSummary.describe(summary, ZoneId.of("UTC"))

        assertEquals(
            listOf(
                "Process started 1 time(s) in this log (more than 1 means it died and came back). Reboots: 0.",
                "a11y: 2 heartbeats over 2 h 00 min, of which 1 h 59 min in deep sleep. " +
                    "Connected 1 time(s), disconnected 0 time(s).",
                "a11y: longest awake time between heartbeats: 1 min 00 s (about 1 min is normal).",
                "a11y: no gaps.",
                "a11y: last heartbeat 20 s of awake time ago.",
                "nls: no heartbeat yet. Connected 0 time(s).",
            ),
            text,
        )
    }

    @Test
    fun linesFromOtherExperimentsAreIgnored() {
        val records = listOf(
            e6(0, 0, "heartbeat a11y n=1"),
            LogRecord(startWall, 10, 10, 100, "E2", "heartbeat a11y n=99"),
            LogRecord(startWall, 20, 20, 100, "APP", "a11y connected"),
            e6(minute, minute, "screen off"),
            e6(minute, minute, "heartbeat a11y n=2"),
        )

        val run = HeartbeatSummary.compute(records, now(minute, minute)).a11y()

        assertEquals(2, run.heartbeats)
        assertEquals(0, run.connects)
    }

    @Test
    fun aServiceThatStoppedForGoodIsCalledNotRunning() {
        val records = listOf(
            e6(0, 0, "heartbeat a11y n=1"),
            e6(minute, minute, "heartbeat a11y n=2"),
        )

        // Half an hour of awake time later, still nothing.
        val summary = HeartbeatSummary.compute(records, now(31 * minute, 5 * hour))
        val text = HeartbeatSummary.describe(summary, ZoneId.of("UTC")).joinToString("\n")

        assertEquals(30 * minute, summary.a11y().awakeSinceLastBeatMillis)
        assertTrue(text, text.contains("a11y: NOT RUNNING. No heartbeat for 30 min 00 s of awake time"))
        // The listener never wrote a heartbeat at all. After half an hour that is not "pending".
        assertTrue(
            text,
            text.contains("nls: NOT RUNNING. No heartbeat at all in the 31 min 00 s since the run began. Connected 0 time(s)."),
        )
    }

    @Test
    fun aServiceWithNoHeartbeatIsOnlyPendingForAMoment() {
        val records = listOf(
            e6(0, 0, "run-start battery=optimised bucket=active label=night-a"),
            e6(1_000, 1_000, "heartbeat a11y n=7"),
        )

        val early = HeartbeatSummary.describe(HeartbeatSummary.compute(records, now(60_000, 60_000)), ZoneId.of("UTC"))
        val late = HeartbeatSummary.describe(HeartbeatSummary.compute(records, now(91_000, 91_000)), ZoneId.of("UTC"))

        assertEquals("nls: no heartbeat yet. Connected 0 time(s).", early.last())
        assertEquals(
            "nls: NOT RUNNING. No heartbeat at all in the 1 min 31 s since the run began. Connected 0 time(s).",
            late.last(),
        )
    }

    @Test
    fun theAgeOfARunIsAwakeTimeNotSleepTime() {
        // Eight hours on the wall, but the phone was awake for one minute of them.
        val records = listOf(e6(0, 0, "run-start battery=optimised bucket=active label=night-a"))

        val summary = HeartbeatSummary.compute(records, now(minute, 8 * hour))

        assertEquals(minute, summary.runAgeMillis)
        assertEquals(
            "a11y: no heartbeat yet. Connected 0 time(s).",
            HeartbeatSummary.describe(summary, ZoneId.of("UTC"))[2],
        )
    }

    @Test
    fun theAgeOfARunAcrossARebootUsesTheTimeOfDay() {
        val records = listOf(
            e6(5 * hour, 9 * hour, "process-start boot=7", pid = 100),
            e6(1_000, 1_000, "process-start boot=8", pid = 150, wall = startWall + 10 * hour),
        )

        // "Now" is two seconds after the second boot's first line.
        val nowAfterReboot = LogRecord(startWall + 10 * hour + 2_000, 3_000, 3_000, 150, E6.TAG, "")
        val summary = HeartbeatSummary.compute(records, nowAfterReboot)

        assertEquals(hour + 2_000, summary.runAgeMillis)
    }

    @Test
    fun anEmptyLogHasNoAge() {
        assertNull(HeartbeatSummary.compute(emptyList(), now(1_000, 1_000)).runAgeMillis)
    }

    @Test
    fun reportsEveryChangeOfStandbyBucketAndBatterySetting() {
        val records = listOf(
            e6(0, 0, "heartbeat a11y n=1 screen=off doze=false a11y-setting=on nls-setting=on battery=optimised bucket=active"),
            e6(0, 0, "heartbeat nls n=1 screen=off doze=false a11y-setting=on nls-setting=on battery=optimised bucket=active"),
            e6(minute, minute, "heartbeat a11y n=2 screen=off doze=true a11y-setting=on nls-setting=on battery=optimised bucket=rare"),
            e6(minute, minute, "heartbeat nls n=2 screen=off doze=true a11y-setting=on nls-setting=on battery=optimised bucket=rare"),
            e6(2 * minute, 2 * minute, "heartbeat a11y n=3 screen=off doze=true a11y-setting=on nls-setting=on battery=optimised bucket=restricted"),
            e6(3 * minute, 3 * minute, "heartbeat a11y n=4 screen=on doze=false a11y-setting=on nls-setting=on battery=optimised bucket=active"),
        )

        val summary = HeartbeatSummary.compute(records, now(3 * minute, 3 * minute))
        val text = HeartbeatSummary.describe(summary, ZoneId.of("UTC"))

        assertEquals(listOf("active", "rare", "restricted", "active"), summary.standbyBuckets)
        assertEquals(listOf("optimised"), summary.batteryModes)
        assertEquals("Standby bucket during the run: active, then rare, then restricted, then active.", text[1])
        assertEquals("Battery setting during the run: optimised.", text[2])
    }

    @Test
    fun saysWhatAStallCanAndCannotMean() {
        val records = listOf(
            e6(0, 0, "heartbeat a11y n=1"),
            e6(10 * minute, 10 * minute, "heartbeat a11y n=2"),
        )

        val text = HeartbeatSummary.describe(
            HeartbeatSummary.compute(records, now(10 * minute, 10 * minute)),
            ZoneId.of("UTC"),
        ).joinToString(" | ")

        assertTrue(
            text,
            text.contains(
                "a11y: GAP from 2025-10-09 08:53:20: the process stayed alive but did not run " +
                    "(a freezer, or a blocked main thread; the log cannot say which); " +
                    "10 min 00 s awake, 10 min 00 s in real time.",
            ),
        )
    }

    @Test
    fun describesGapsAndEarlierExits() {
        val records = listOf(
            e6(0, 0, "process-start boot=7", pid = 100),
            e6(0, 0, "heartbeat a11y n=1", pid = 100),
            e6(20 * minute, 40 * minute, "process-start boot=7", pid = 200),
            e6(20 * minute, 40 * minute, "exit-info at=2025-10-09T09:10:00 reason=LOW_MEMORY importance=125 description=-", pid = 200),
            e6(20 * minute, 40 * minute, "heartbeat a11y n=1", pid = 200),
        )

        val summary = HeartbeatSummary.compute(records, now(20 * minute + 10_000, 40 * minute + 10_000, pid = 200))
        val text = HeartbeatSummary.describe(summary, ZoneId.of("UTC")).joinToString("\n")

        assertTrue(
            text,
            text.contains(
                "a11y: GAP from 2025-10-09 08:53:20: the app's process died and was started again; " +
                    "20 min 00 s awake, 40 min 00 s in real time.",
            ),
        )
        assertTrue(text, text.contains("Earlier process exit: at=2025-10-09T09:10:00 reason=LOW_MEMORY"))
        assertTrue(text, text.contains("a11y: last heartbeat 10 s of awake time ago."))
    }

    @Test
    fun formatsDurations() {
        assertEquals("45 s", HeartbeatSummary.duration(45_999))
        assertEquals("1 min 05 s", HeartbeatSummary.duration(65_000))
        assertEquals("9 h 02 min", HeartbeatSummary.duration(9 * hour + 2 * minute + 30_000))
    }
}
