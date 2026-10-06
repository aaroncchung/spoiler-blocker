package io.github.aaroncchung.spoilerblocker.probe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DismissTimeLimitTest {
    private val probe = "io.github.aaroncchung.spoilerblocker.probe"

    @Test
    fun appliesOnlyToAnotherAppWhileDismissingIsOn() {
        assertTrue(DismissTimeLimit.applies(enabled = true, targetPackage = "com.example.chat", ownPackage = probe))

        // The probe's own test notifications may be dismissed for as long as it likes.
        assertFalse(DismissTimeLimit.applies(enabled = true, targetPackage = probe, ownPackage = probe))
        // Nothing is dismissed while the switch is off, or with no package given.
        assertFalse(DismissTimeLimit.applies(enabled = false, targetPackage = "com.example.chat", ownPackage = probe))
        assertFalse(DismissTimeLimit.applies(enabled = true, targetPackage = "", ownPackage = probe))
    }

    @Test
    fun isDueFromTheMomentTheLimitIsReached() {
        assertFalse(DismissTimeLimit.isDue(expiresAt = 10_000, now = 9_999))
        assertTrue(DismissTimeLimit.isDue(expiresAt = 10_000, now = 10_000))
        assertTrue(DismissTimeLimit.isDue(expiresAt = 10_000, now = 99_000))
    }

    @Test
    fun isNeverDueWhenNoLimitIsRunning() {
        assertFalse(DismissTimeLimit.isDue(expiresAt = 0, now = 99_000))
    }

    @Test
    fun theLimitIsAQuarterOfAnHour() {
        assertTrue(DismissTimeLimit.LIMIT_MS == 15 * 60 * 1000L)
    }
}
