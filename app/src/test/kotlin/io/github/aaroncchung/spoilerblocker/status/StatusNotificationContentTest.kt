package io.github.aaroncchung.spoilerblocker.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusNotificationContentTest {

    @Test
    fun `with no blocker on there is no notification`() {
        assertNull(statusNotificationContent(emptyList(), notificationAccessGranted = true))
        // Nothing is on, so there is nothing to warn about either.
        assertNull(statusNotificationContent(emptyList(), notificationAccessGranted = false))
    }

    @Test
    fun `one blocker is shown by its name`() {
        val content = statusNotificationContent(
            listOf("2026 Japanese Grand Prix"),
            notificationAccessGranted = true,
        )

        assertEquals(
            StatusNotificationContent(
                isHidingNotifications = true,
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
            notificationAccessGranted = true,
        )!!

        assertEquals(3, content.blockerCount)
        assertEquals(listOf("2026 Japanese Grand Prix", "Season finale", "Cup final"), content.names)
        assertEquals(0, content.unnamedCount)
    }

    @Test
    fun `of many blockers the first three are named and the rest counted`() {
        val names = List(12) { index -> "Blocker ${index + 1}" }

        val content = statusNotificationContent(names, notificationAccessGranted = true)!!

        assertEquals(12, content.blockerCount)
        assertEquals(listOf("Blocker 1", "Blocker 2", "Blocker 3"), content.names)
        assertEquals(9, content.unnamedCount)
    }

    @Test
    fun `a name of forty characters is shown whole`() {
        val name = "a".repeat(40)

        val content = statusNotificationContent(listOf(name), notificationAccessGranted = true)!!

        assertEquals(listOf(name), content.names)
    }

    @Test
    fun `a longer name is cut to forty characters, the last of them an ellipsis`() {
        val name = "a".repeat(41)

        val content = statusNotificationContent(listOf(name), notificationAccessGranted = true)!!

        assertEquals(listOf("a".repeat(39) + "…"), content.names)
    }

    @Test
    fun `a name is not cut in the middle of an emoji or just after a space`() {
        // The emoji takes the 39th and 40th place, so the cut falls inside it.
        val emoji = "b".repeat(38) + "🏁" + "bbb"
        // The 39th character is a space.
        val space = "c".repeat(38) + " " + "ccc"

        val content = statusNotificationContent(listOf(emoji, space), notificationAccessGranted = true)!!

        assertEquals(listOf("b".repeat(38) + "…", "c".repeat(38) + "…"), content.names)
    }

    @Test
    fun `each of several long names is cut`() {
        val long = "The one where everything that could happen in a season happens at once"

        val content = statusNotificationContent(
            listOf(long, "Cup final", long, long),
            notificationAccessGranted = true,
        )!!

        val cut = "The one where everything that could hap…"
        assertEquals(40, cut.length)
        assertEquals(listOf(cut, "Cup final", cut), content.names)
        assertEquals(1, content.unnamedCount)
    }

    @Test
    fun `without notification access it says that nothing is being hidden`() {
        val names = listOf("2026 Japanese Grand Prix", "Season finale")

        val withAccess = statusNotificationContent(names, notificationAccessGranted = true)!!
        val without = statusNotificationContent(names, notificationAccessGranted = false)!!

        assertEquals(true, withAccess.isHidingNotifications)
        assertEquals(false, without.isHidingNotifications)
        // The blockers are still named, so that it is clear what is not working.
        assertEquals(withAccess.copy(isHidingNotifications = false), without)
    }
}
