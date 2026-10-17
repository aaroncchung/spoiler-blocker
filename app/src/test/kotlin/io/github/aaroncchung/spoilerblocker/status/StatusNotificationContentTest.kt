package io.github.aaroncchung.spoilerblocker.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusNotificationContentTest {

    @Test
    fun `with no blocker on there is no notification`() {
        // Nothing is on, so there is nothing to warn about either.
        for (hidingState in HidingState.entries) {
            assertNull(statusNotificationContent(emptyList(), hidingState))
        }
    }

    @Test
    fun `one blocker is shown by its name`() {
        val content = statusNotificationContent(
            listOf("2026 Japanese Grand Prix"),
            HidingState.HIDING,
        )

        assertEquals(
            StatusNotificationContent(
                hidingState = HidingState.HIDING,
                blockerCount = 1,
                names = listOf("2026 Japanese Grand Prix"),
            ),
            content,
        )
        assertEquals(0, content!!.unnamedCount)
    }

    @Test
    fun `up to three blockers are counted and all named, in the order given`() {
        val content = statusNotificationContent(
            listOf("2026 Japanese Grand Prix", "Season finale", "Cup final"),
            HidingState.HIDING,
        )!!

        assertEquals(3, content.blockerCount)
        assertEquals(listOf("2026 Japanese Grand Prix", "Season finale", "Cup final"), content.names)
        assertEquals(0, content.unnamedCount)
    }

    @Test
    fun `of many blockers the first three are named and the rest counted`() {
        val names = List(12) { index -> "Blocker ${index + 1}" }

        val content = statusNotificationContent(names, HidingState.HIDING)!!

        assertEquals(12, content.blockerCount)
        assertEquals(listOf("Blocker 1", "Blocker 2", "Blocker 3"), content.names)
        assertEquals(9, content.unnamedCount)
    }

    @Test
    fun `a name of forty characters is shown whole`() {
        val name = "a".repeat(40)

        val content = statusNotificationContent(listOf(name), HidingState.HIDING)!!

        assertEquals(listOf(name), content.names)
    }

    @Test
    fun `a longer name is cut to forty characters, the last of them an ellipsis`() {
        val name = "a".repeat(41)

        val content = statusNotificationContent(listOf(name), HidingState.HIDING)!!

        assertEquals(listOf("a".repeat(39) + "…"), content.names)
    }

    @Test
    fun `a name is not cut in the middle of an emoji or just after a space`() {
        // The emoji takes the 39th and 40th place, so the cut falls inside it.
        val emoji = "b".repeat(38) + "🏁" + "bbb"
        // The 39th character is a space.
        val space = "c".repeat(38) + " " + "ccc"

        val content = statusNotificationContent(listOf(emoji, space), HidingState.HIDING)!!

        assertEquals(listOf("b".repeat(38) + "…", "c".repeat(38) + "…"), content.names)
    }

    @Test
    fun `each of several long names is cut`() {
        val long = "The one where everything that could happen in a season happens at once"

        val content = statusNotificationContent(
            listOf(long, "Cup final", long, long),
            HidingState.HIDING,
        )!!

        val cut = "The one where everything that could hap…"
        assertEquals(40, cut.length)
        assertEquals(listOf(cut, "Cup final", cut), content.names)
        assertEquals(1, content.unnamedCount)
    }

    @Test
    fun `when nothing is being hidden it says so and still names the blockers`() {
        val names = listOf("2026 Japanese Grand Prix", "Season finale")
        val hiding = statusNotificationContent(names, HidingState.HIDING)!!

        for (hidingState in listOf(HidingState.NO_ACCESS, HidingState.NOT_CONNECTED)) {
            val notHiding = statusNotificationContent(names, hidingState)!!

            assertEquals(hidingState, notHiding.hidingState)
            // The blockers are still named, so that it is clear what is not working.
            assertEquals(hiding.copy(hidingState = hidingState), notHiding)
        }
    }

    @Test
    fun `notifications are only hidden while the listener is connected`() {
        assertEquals(HidingState.HIDING, hidingState(notificationAccessGranted = true, listenerConnected = true))
        // The listener has not connected yet, or Android has disconnected it.
        assertEquals(
            HidingState.NOT_CONNECTED,
            hidingState(notificationAccessGranted = true, listenerConnected = false),
        )
    }

    @Test
    fun `without notification access the reason given is the missing access`() {
        assertEquals(HidingState.NO_ACCESS, hidingState(notificationAccessGranted = false, listenerConnected = false))
        // Access was taken away a moment ago, and the listener has not been told yet.
        assertEquals(HidingState.NO_ACCESS, hidingState(notificationAccessGranted = false, listenerConnected = true))
    }

    @Test
    fun `a missing listener is not warned about in the first seconds after the process starts`() {
        assertEquals(true, isTooEarlyToWarn(HidingState.NOT_CONNECTED, millisSinceStart = 0))
        assertEquals(true, isTooEarlyToWarn(HidingState.NOT_CONNECTED, LISTENER_CONNECT_WAIT_MILLIS - 1))
        assertEquals(false, isTooEarlyToWarn(HidingState.NOT_CONNECTED, LISTENER_CONNECT_WAIT_MILLIS))
        assertEquals(false, isTooEarlyToWarn(HidingState.NOT_CONNECTED, millisSinceStart = 3_600_000))
    }

    @Test
    fun `missing access is warned about at once, and nothing else waits either`() {
        // Without access the listener will not connect, however long the wait.
        assertEquals(false, isTooEarlyToWarn(HidingState.NO_ACCESS, millisSinceStart = 0))
        assertEquals(false, isTooEarlyToWarn(HidingState.HIDING, millisSinceStart = 0))
    }
}
