package io.github.aaroncchung.spoilerblocker.probe

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * One line of the probe log.
 *
 * Three clocks are recorded because they answer different questions:
 * - [wallMillis] is the time of day. It matches a line to a film or to a memory.
 * - [uptimeMillis] counts only while the processor is awake. Android stamps
 *   touches and accessibility events with this clock.
 * - [realtimeMillis] also counts the time the phone spent in deep sleep.
 *
 * E6 compares the last two to tell "the phone was asleep" from "the app was
 * stopped". Both restart from zero when the phone reboots.
 */
data class LogRecord(
    val wallMillis: Long,
    val uptimeMillis: Long,
    val realtimeMillis: Long,
    val pid: Int,
    val tag: String,
    val message: String,
) {
    /** The text written to the log file and to logcat. Always a single line. */
    fun format(zone: ZoneId = ZoneId.systemDefault()): String {
        val wall = WALL_FORMAT.format(Instant.ofEpochMilli(wallMillis).atZone(zone))
        return "$wall up=$uptimeMillis rt=$realtimeMillis pid=$pid $tag $message"
    }

    companion object {
        private val WALL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

        private val LINE = Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) up=(\d+) rt=(\d+) pid=(\d+) (\S+) ?(.*)$""")

        /** Reads back a line written by [format]. Returns null for anything else. */
        fun parse(line: String, zone: ZoneId = ZoneId.systemDefault()): LogRecord? {
            val match = LINE.matchEntire(line) ?: return null
            val (wall, up, rt, pid, tag, message) = match.destructured
            val wallMillis = try {
                LocalDateTime.parse(wall, WALL_FORMAT).atZone(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                return null
            }
            return LogRecord(wallMillis, up.toLong(), rt.toLong(), pid.toInt(), tag, message)
        }
    }
}
