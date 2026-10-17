package io.github.aaroncchung.spoilerblocker.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UnreadableCopyTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun fileInFolder(name: String, text: String): File =
        File(temporaryFolder.root, name).apply { writeText(text) }

    @Test
    fun `the first copy is named after the file`() {
        val file = fileInFolder("blockers.json", "unreadable")

        val copy = keepUnreadableCopy(file)

        assertEquals(File(temporaryFolder.root, "blockers.json.unreadable"), copy)
        assertEquals("unreadable", copy.readText())
        assertEquals("unreadable", file.readText())
    }

    @Test
    fun `a later copy takes the first free name and leaves the earlier ones alone`() {
        val file = fileInFolder("blockers.json", "third")
        fileInFolder("blockers.json.unreadable", "first")
        fileInFolder("blockers.json.unreadable.2", "second")
        // A gap is filled before a higher number is used.
        fileInFolder("blockers.json.unreadable.4", "fourth")

        val copy = keepUnreadableCopy(file)

        assertEquals(File(temporaryFolder.root, "blockers.json.unreadable.3"), copy)
        assertEquals("third", copy.readText())
        assertEquals("first", File(temporaryFolder.root, "blockers.json.unreadable").readText())
        assertEquals("second", File(temporaryFolder.root, "blockers.json.unreadable.2").readText())
        assertEquals("fourth", File(temporaryFolder.root, "blockers.json.unreadable.4").readText())
    }
}
