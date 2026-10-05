package io.github.aaroncchung.spoilerblocker.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class LogRecordTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun formatsAsOneReadableLine() {
        // 1 760 000 000 123 ms after 1970 is 2025-10-09 08:53:20.123 UTC.
        val record = LogRecord(1_760_000_000_123, 5_000, 9_000, 4321, "E6", "heartbeat a11y n=3")

        assertEquals(
            "2025-10-09 08:53:20.123 up=5000 rt=9000 pid=4321 E6 heartbeat a11y n=3",
            record.format(utc),
        )
    }

    @Test
    fun parsesBackWhatItFormatted() {
        val record = LogRecord(1_760_000_000_123, 5_000, 9_000, 4321, "E2", "#7 mech=wm-move by=VIEW_SCROLLED evt=4990")

        assertEquals(record, LogRecord.parse(record.format(utc), utc))
    }

    @Test
    fun keepsAnEmptyMessage() {
        val record = LogRecord(1_760_000_000_000, 1, 2, 3, "APP", "")

        assertEquals(record, LogRecord.parse(record.format(utc), utc))
    }

    @Test
    fun usesTheGivenTimeZone() {
        val record = LogRecord(1_760_000_000_123, 5_000, 9_000, 4321, "E6", "x")
        val tokyo = ZoneId.of("Asia/Tokyo")

        // Tokyo is nine hours ahead of UTC.
        assertEquals("2025-10-09 17:53:20.123 up=5000 rt=9000 pid=4321 E6 x", record.format(tokyo))
        assertEquals(record, LogRecord.parse(record.format(tokyo), tokyo))
    }

    @Test
    fun rejectsLinesItDidNotWrite() {
        assertNull(LogRecord.parse("", utc))
        assertNull(LogRecord.parse("not a log line", utc))
        assertNull(LogRecord.parse("2025-10-09 08:53:20.123 up=abc rt=9000 pid=4321 E6 x", utc))
        // A date that looks right but does not exist.
        assertNull(LogRecord.parse("2025-13-45 08:53:20.123 up=1 rt=2 pid=3 E6 x", utc))
    }
}
