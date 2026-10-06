package io.github.aaroncchung.spoilerblocker.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Runs the real repository against a file in a temporary folder, in the same
 * way as `BlockerRepositoryTest`. None of the text comes from a real phone.
 */
class HiddenNotificationRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val file: File
        get() = File(temporaryFolder.root, "hidden_notifications.json")

    /** Where the repository keeps a file that it could not read. */
    private val unreadableCopy: File
        get() = File(temporaryFolder.root, "hidden_notifications.json.unreadable")

    /** An entry for the notification that Android knows as [key]. */
    private fun hidden(
        id: String,
        key: String = "0|com.example.chat|$id|null|10001",
        title: String = "Alex",
        text: String = "Message $id",
    ) = HiddenNotification(
        id = id,
        hiddenAtMillis = 1_000,
        notificationKey = key,
        packageName = "com.example.chat",
        appName = "Chat",
        title = title,
        text = text,
        blockerId = "race",
        blockerName = "2026 Japanese Grand Prix",
        matchedTerm = "Suzuka",
    )

    /**
     * A whole file in version 1 of the stored format, exactly as the app
     * writes it apart from the line breaks.
     *
     * NEVER EDIT THIS. A phone holds a file like it, and the test that loads
     * it is the proof that this build can still read that file. When the
     * format changes, add a new document beside this one and keep this one.
     */
    private val version1File = """
        {
          "version": 1,
          "notifications": [
            {
              "id": "5f0c2a9e-3b41-4d7a-8c6e-1a2b3c4d5e6f",
              "hiddenAtMillis": 1791243000000,
              "notificationKey": "0|com.example.mail|12|inbox|10002",
              "packageName": "com.example.mail",
              "appName": "Mail",
              "title": "Sam",
              "text": "Lunch on Friday?",
              "blockerId": "0b8f6c1e-5a52-4a0e-9d57-3c1f0e6b7a11",
              "blockerName": "2026 Japanese Grand Prix",
              "matchedTerm": "Suzuka",
              "hiddenWithGroup": true
            },
            {
              "id": "9d3e7b1c-6f24-4a58-b0c1-7e8f9a0b1c2d",
              "hiddenAtMillis": 1791242900000,
              "notificationKey": "0|com.example.chat|7|null|10001",
              "packageName": "com.example.chat",
              "appName": "Chat",
              "title": "Alex",
              "text": "Alex: Did you watch Suzuka?\nAlex: What a finish",
              "blockerId": "0b8f6c1e-5a52-4a0e-9d57-3c1f0e6b7a11",
              "blockerName": "2026 Japanese Grand Prix",
              "matchedTerm": "Suzuka",
              "hiddenWithGroup": false
            }
          ]
        }
    """.trimIndent()

    /** The entries in [version1File], newest first. This is frozen with it. */
    private val version1Notifications = listOf(
        HiddenNotification(
            id = "5f0c2a9e-3b41-4d7a-8c6e-1a2b3c4d5e6f",
            hiddenAtMillis = 1_791_243_000_000,
            notificationKey = "0|com.example.mail|12|inbox|10002",
            packageName = "com.example.mail",
            appName = "Mail",
            title = "Sam",
            text = "Lunch on Friday?",
            blockerId = "0b8f6c1e-5a52-4a0e-9d57-3c1f0e6b7a11",
            blockerName = "2026 Japanese Grand Prix",
            matchedTerm = "Suzuka",
            hiddenWithGroup = true,
        ),
        HiddenNotification(
            id = "9d3e7b1c-6f24-4a58-b0c1-7e8f9a0b1c2d",
            hiddenAtMillis = 1_791_242_900_000,
            notificationKey = "0|com.example.chat|7|null|10001",
            packageName = "com.example.chat",
            appName = "Chat",
            title = "Alex",
            text = "Alex: Did you watch Suzuka?\nAlex: What a finish",
            blockerId = "0b8f6c1e-5a52-4a0e-9d57-3c1f0e6b7a11",
            blockerName = "2026 Japanese Grand Prix",
            matchedTerm = "Suzuka",
            hiddenWithGroup = false,
        ),
    )

    @Test
    fun `starts empty`() = runTest {
        val repository = HiddenNotificationRepository({ file }, this)

        assertEquals(emptyList<HiddenNotification>(), repository.hiddenNotifications.first())
    }

    @Test
    fun `the newest entry comes first`() = runTest {
        val repository = HiddenNotificationRepository({ file }, this)

        repository.add(hidden("1"))
        repository.add(hidden("2"))
        repository.add(hidden("3"))

        assertEquals(
            listOf(hidden("3"), hidden("2"), hidden("1")),
            repository.hiddenNotifications.first(),
        )
    }

    @Test
    fun `entries survive a reload from the same file`() = runTest {
        // The first repository gets a scope of its own that can end mid-test,
        // as in BlockerRepositoryTest.
        val firstJob = Job()
        val firstScope = CoroutineScope(StandardTestDispatcher(testScheduler) + firstJob)
        val first = HiddenNotificationRepository({ file }, firstScope)
        first.add(hidden("1"))
        first.add(hidden("2"))
        firstJob.cancelAndJoin()

        val second = HiddenNotificationRepository({ file }, this)

        assertEquals(listOf(hidden("2"), hidden("1")), second.hiddenNotifications.first())
    }

    @Test
    fun `only the newest entries are kept`() = runTest {
        // A file that is one entry short of full, written directly. Adding
        // them one by one would rewrite the file hundreds of times.
        val max = HiddenNotificationRepository.MAX_ENTRIES
        val stored = (max - 1 downTo 1).map { number -> hidden("$number") }
        file.writeText("""{"version":1,"notifications":${Json.encodeToString(stored)}}""")
        val repository = HiddenNotificationRepository({ file }, this)

        repository.add(hidden("fills the list"))
        assertEquals(max, repository.hiddenNotifications.first().size)

        repository.add(hidden("one too many"))
        repository.add(hidden("two too many"))

        val kept = repository.hiddenNotifications.first()
        assertEquals(max, kept.size)
        assertEquals(hidden("two too many"), kept.first())
        // The two oldest, "1" and "2", made room.
        assertEquals(hidden("3"), kept.last())
    }

    @Test
    fun `a notification posted again with the same content is stored once`() = runTest {
        val repository = HiddenNotificationRepository({ file }, this)
        val first = hidden("1", key = "chat with Alex", text = "Did you watch Suzuka?")
        val again = first.copy(id = "2", hiddenAtMillis = 2_000)
        val andAgain = first.copy(id = "3", hiddenAtMillis = 3_000)

        repository.add(first)
        repository.add(again)
        repository.add(andAgain)

        // The entry that stays is the last one, so the list shows when the
        // notification was last hidden.
        assertEquals(listOf(andAgain), repository.hiddenNotifications.first())
    }

    @Test
    fun `a notification updated with new content is stored again`() = runTest {
        val repository = HiddenNotificationRepository({ file }, this)
        val first = hidden("1", key = "chat with Alex", text = "Did you watch Suzuka?")
        val newText = first.copy(id = "2", text = "Did you watch Suzuka?\nWhat a finish")
        val newTitle = newText.copy(id = "3", title = "Alex (2 messages)")

        repository.add(first)
        repository.add(newText)
        repository.add(newTitle)

        assertEquals(listOf(newTitle, newText, first), repository.hiddenNotifications.first())
    }

    @Test
    fun `the same content in two different notifications is stored twice`() = runTest {
        val repository = HiddenNotificationRepository({ file }, this)
        val fromAlex = hidden("1", key = "chat with Alex", text = "Did you watch Suzuka?")
        val fromSam = fromAlex.copy(id = "2", notificationKey = "chat with Sam")

        repository.add(fromAlex)
        repository.add(fromSam)

        assertEquals(listOf(fromSam, fromAlex), repository.hiddenNotifications.first())
    }

    @Test
    fun `a repeat is recognised when other notifications came in between`() = runTest {
        val repository = HiddenNotificationRepository({ file }, this)
        val fromAlex = hidden("1", key = "chat with Alex", text = "Did you watch Suzuka?")
        val fromSam = hidden("2", key = "chat with Sam", text = "Suzuka was wild")
        val fromAlexAgain = fromAlex.copy(id = "3", hiddenAtMillis = 3_000)

        repository.add(fromAlex)
        repository.add(fromSam)
        repository.add(fromAlexAgain)

        // It replaces the earlier entry and moves to the top.
        assertEquals(listOf(fromAlexAgain, fromSam), repository.hiddenNotifications.first())
    }

    @Test
    fun `content that comes back after a change is stored again`() = runTest {
        val repository = HiddenNotificationRepository({ file }, this)
        val first = hidden("1", key = "scores", text = "Suzuka: lap 1")
        val second = first.copy(id = "2", text = "Suzuka: lap 2")
        val firstAgain = first.copy(id = "3")

        repository.add(first)
        repository.add(second)
        repository.add(firstAgain)

        // Only the newest entry for the notification counts, and that is "lap 2".
        assertEquals(listOf(firstAgain, second, first), repository.hiddenNotifications.first())
    }

    @Test
    fun `several entries added together end up with the last one on top`() = runTest {
        val repository = HiddenNotificationRepository({ file }, this)
        repository.add(hidden("1"))

        // A summary and the two children that went with it.
        repository.addAll(listOf(hidden("summary"), hidden("2"), hidden("3")))

        assertEquals(
            listOf(hidden("3"), hidden("2"), hidden("summary"), hidden("1")),
            repository.hiddenNotifications.first(),
        )
    }

    @Test
    fun `adding nothing writes nothing`() = runTest {
        val repository = HiddenNotificationRepository({ file }, this)

        repository.addAll(emptyList())

        assertFalse(file.exists())
        assertEquals(emptyList<HiddenNotification>(), repository.hiddenNotifications.first())
    }

    @Test
    fun `entries added together follow the same rules as entries added one by one`() = runTest {
        val repository = HiddenNotificationRepository({ file }, this)
        val fromAlex = hidden("1", key = "chat with Alex", text = "Did you watch Suzuka?")
        val fromSam = hidden("2", key = "chat with Sam", text = "Suzuka was wild")
        repository.add(fromAlex)

        // The first repeats a stored entry, the third repeats the second.
        val fromAlexAgain = fromAlex.copy(id = "3", hiddenAtMillis = 3_000)
        val fromSamAgain = fromSam.copy(id = "4", hiddenAtMillis = 4_000)
        repository.addAll(listOf(fromAlexAgain, fromSam, fromSamAgain))

        assertEquals(listOf(fromSamAgain, fromAlexAgain), repository.hiddenNotifications.first())
    }

    @Test
    fun `a notification that keeps changing keeps only its newest entries`() = runTest {
        val repository = HiddenNotificationRepository({ file }, this)
        val perNotification = HiddenNotificationRepository.MAX_ENTRIES_PER_NOTIFICATION
        val fromAlex = hidden("from Alex", key = "chat with Alex")
        val laps = (1..perNotification + 3).map { lap ->
            hidden("lap $lap", key = "live timing", text = "Suzuka: lap $lap")
        }

        repository.add(laps[0])
        repository.add(fromAlex)
        for (lap in laps.drop(1)) {
            repository.add(lap)
        }

        // The newest few laps, newest first, and below them what Alex wrote.
        assertEquals(
            laps.takeLast(perNotification).reversed() + fromAlex,
            repository.hiddenNotifications.first(),
        )
    }

    @Test
    fun `a notification that keeps changing cannot push the others out of the list`() = runTest {
        val repository = HiddenNotificationRepository({ file }, this)
        val fromAlex = hidden("from Alex", key = "chat with Alex")
        repository.add(fromAlex)

        // More updates than the whole list holds, written in one go.
        val laps = (1..HiddenNotificationRepository.MAX_ENTRIES + 100).map { lap ->
            hidden("lap $lap", key = "live timing", text = "Suzuka: lap $lap")
        }
        repository.addAll(laps)

        val kept = repository.hiddenNotifications.first()
        assertEquals(HiddenNotificationRepository.MAX_ENTRIES_PER_NOTIFICATION + 1, kept.size)
        assertEquals(laps.last(), kept.first())
        assertEquals(fromAlex, kept.last())
    }

    @Test
    fun `clear removes every entry`() = runTest {
        val repository = HiddenNotificationRepository({ file }, this)
        repository.add(hidden("1"))
        repository.add(hidden("2"))

        repository.clear()

        assertEquals(emptyList<HiddenNotification>(), repository.hiddenNotifications.first())
        // The list starts afresh: what was cleared is not remembered as a repeat.
        repository.add(hidden("1"))
        assertEquals(listOf(hidden("1")), repository.hiddenNotifications.first())
    }

    @Test
    fun `a version 1 file loads`() = runTest {
        file.writeText(version1File)

        val repository = HiddenNotificationRepository({ file }, this)

        assertEquals(version1Notifications, repository.hiddenNotifications.first())
        assertFalse(unreadableCopy.exists())
    }

    // This fails as soon as the build writes anything else, for example
    // after a field is added to HiddenNotification. That is the moment to
    // raise CURRENT_VERSION, add a document for the new version beside
    // version1File, and point this test at the new one.
    @Test
    fun `this build writes version 1 files`() = runTest {
        val repository = HiddenNotificationRepository({ file }, this)

        // Oldest first, so that the file ends up newest first.
        for (notification in version1Notifications.reversed()) {
            repository.add(notification)
        }

        // Compared as JSON, so that line breaks and spaces do not count.
        assertEquals(Json.parseToJsonElement(version1File), Json.parseToJsonElement(file.readText()))
    }

    @Test
    fun `a change stamps an older file with the version of this build`() = runTest {
        // There was no version before 1, so 0 stands in for "older".
        file.writeText("""{"version":0,"notifications":[]}""")
        val repository = HiddenNotificationRepository({ file }, this)

        repository.add(hidden("1"))

        assertTrue(file.readText().startsWith("""{"version":1,"""))
    }

    @Test
    fun `a field this build does not know is skipped`() = runTest {
        val withUnknownFields = version1File
            .replace(""""version": 1,""", """"version": 1, "somethingNew": true,""")
            .replace(""""appName": "Mail",""", """"appName": "Mail", "channel": "Inbox",""")
        file.writeText(withUnknownFields)

        val repository = HiddenNotificationRepository({ file }, this)

        assertEquals(version1Notifications, repository.hiddenNotifications.first())
        assertFalse(unreadableCopy.exists())
    }

    @Test
    fun `a file from a newer build is refused and left untouched`() = runTest {
        // The newer build has renamed "title", which this build requires.
        val newerFile = """{"version":2,"notifications":[{"id":"1","heading":"Alex"}]}"""
        file.writeText(newerFile)
        val repository = HiddenNotificationRepository({ file }, this)

        try {
            repository.hiddenNotifications.first()
            fail("Reading a newer file should fail.")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("newer build (version 2)"))
        }
        try {
            repository.add(hidden("1"))
            fail("Adding to a newer file should fail.")
        } catch (e: IllegalStateException) {
            // Expected.
        }

        assertEquals(newerFile, file.readText())
        assertFalse(unreadableCopy.exists())
    }

    @Test
    fun `a file that cannot be read is kept as a copy and replaced with an empty list`() = runTest {
        // What a file looks like when writing it was cut short.
        val cutShort = """{"version":1,"notifications":[{"id":"1","hiddenAt"""
        file.writeText(cutShort)

        val repository = HiddenNotificationRepository({ file }, this)

        assertEquals(emptyList<HiddenNotification>(), repository.hiddenNotifications.first())
        assertEquals(cutShort, unreadableCopy.readText())
        // The repository still works afterwards.
        repository.add(hidden("1"))
        assertEquals(listOf(hidden("1")), repository.hiddenNotifications.first())
    }

    @Test
    fun `clear removes the copy of an unreadable file as well`() = runTest {
        // The copy holds the text of notifications, like the list itself.
        file.writeText("""{"version":1,"notifications":[{"id":"1","title":"Did you watch Suz""")
        val repository = HiddenNotificationRepository({ file }, this)
        repository.hiddenNotifications.first()
        assertTrue(unreadableCopy.exists())

        repository.clear()

        assertFalse(unreadableCopy.exists())
        assertEquals(emptyList<HiddenNotification>(), repository.hiddenNotifications.first())
    }

    @Test
    fun `clear works when there is no copy of an unreadable file`() = runTest {
        val repository = HiddenNotificationRepository({ file }, this)
        repository.add(hidden("1"))

        repository.clear()

        assertEquals(emptyList<HiddenNotification>(), repository.hiddenNotifications.first())
    }

    // When a file cannot be read and the empty list that should replace it
    // cannot be written either, the failure reaches whoever asked: the
    // listener, which logs it, or a screen, which crashes with it. Either
    // way it ends up in the log, so it must not carry what the file holds.
    @Test
    fun `the failure for an unreadable file does not quote the file`() = runTest {
        val secret = "Did you watch Suzuka"
        file.writeText("""{"version":1,"notifications":[{"id":"1","title":"$secret""")
        // DataStore writes a new file under this name and then renames it.
        // A folder in its place, and not an empty one, makes that fail.
        val temporaryName = File(temporaryFolder.root, "hidden_notifications.json.tmp")
        temporaryName.mkdir()
        File(temporaryName, "in the way").writeText("")
        val repository = HiddenNotificationRepository({ file }, this)

        val failure = try {
            repository.hiddenNotifications.first()
            null
        } catch (e: IOException) {
            e
        }

        assertTrue("Reading should have failed.", failure != null)
        // stackTraceToString is what a crash puts in the log: the message,
        // the causes and the suppressed exceptions.
        assertFalse(failure!!.stackTraceToString().contains("Suzuka"))
    }
}
