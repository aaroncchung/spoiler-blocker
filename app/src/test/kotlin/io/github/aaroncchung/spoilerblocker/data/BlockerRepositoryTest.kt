package io.github.aaroncchung.spoilerblocker.data

import java.io.File
import java.io.IOException
import io.github.aaroncchung.spoilerblocker.matcher.Breadth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
 * Runs the real repository against a file in a temporary folder. DataStore
 * needs nothing from Android for this, so the tests run on a PC.
 *
 * Inside `runTest`, `this` is the test's own coroutine scope. The repository
 * does its file work there, so it is all finished when the test ends.
 */
class BlockerRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val file: File
        get() = File(temporaryFolder.root, "blockers.json")

    /** Where the repository keeps a file that it could not read. */
    private val unreadableCopy: File
        get() = File(temporaryFolder.root, "blockers.json.unreadable")

    private val race = Blocker(
        id = "race",
        name = "2026 Japanese Grand Prix",
        strongTerms = listOf("Japanese Grand Prix", "Suzuka"),
        enabled = true,
        createdAtMillis = 1_000,
    )
    private val finale = Blocker(
        id = "finale",
        name = "Season finale",
        strongTerms = listOf("finale"),
        enabled = false,
        createdAtMillis = 2_000,
        description = "The last episode of the season",
        weakTerms = listOf("twist", "ending"),
        sources = listOf("Example Studios"),
        breadth = Breadth.BROAD,
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
          "blockers": [
            {
              "id": "0b8f6c1e-5a52-4a0e-9d57-3c1f0e6b7a11",
              "name": "2026 Japanese Grand Prix",
              "strongTerms": ["Japanese Grand Prix", "Suzuka", "#JapaneseGP"],
              "enabled": true,
              "createdAtMillis": 1791239695165
            },
            {
              "id": "c4a1d2f3-8e77-4b6a-a0c9-5d2e9f4b6c22",
              "name": "São Paulo Grand Prix",
              "strongTerms": [],
              "enabled": false,
              "createdAtMillis": 1791241067370
            }
          ]
        }
    """.trimIndent()

    /** The blockers in [version1File]. This is frozen with it. */
    private val version1Blockers = listOf(
        Blocker(
            id = "0b8f6c1e-5a52-4a0e-9d57-3c1f0e6b7a11",
            name = "2026 Japanese Grand Prix",
            strongTerms = listOf("Japanese Grand Prix", "Suzuka", "#JapaneseGP"),
            enabled = true,
            createdAtMillis = 1_791_239_695_165,
        ),
        Blocker(
            id = "c4a1d2f3-8e77-4b6a-a0c9-5d2e9f4b6c22",
            name = "São Paulo Grand Prix",
            strongTerms = emptyList(),
            enabled = false,
            createdAtMillis = 1_791_241_067_370,
        ),
    )

    /**
     * A whole file in version 2 of the stored format, which added the
     * description, the weak terms, the sources and the breadth.
     *
     * NEVER EDIT THIS, for the same reason as [version1File].
     */
    private val version2File = """
        {
          "version": 2,
          "blockers": [
            {
              "id": "0b8f6c1e-5a52-4a0e-9d57-3c1f0e6b7a11",
              "name": "2026 Japanese Grand Prix",
              "strongTerms": ["Japanese Grand Prix", "Suzuka", "#JapaneseGP"],
              "enabled": true,
              "createdAtMillis": 1791239695165,
              "description": "2026 Japanese Grand Prix",
              "weakTerms": ["Max", "podium", "P1"],
              "sources": ["FORMULA 1", "Sky Sports F1"],
              "breadth": "NARROW"
            },
            {
              "id": "c4a1d2f3-8e77-4b6a-a0c9-5d2e9f4b6c22",
              "name": "São Paulo Grand Prix",
              "strongTerms": [],
              "enabled": false,
              "createdAtMillis": 1791241067370,
              "description": "",
              "weakTerms": [],
              "sources": [],
              "breadth": "BROAD"
            }
          ]
        }
    """.trimIndent()

    /** The blockers in [version2File]. This is frozen with it. */
    private val version2Blockers = listOf(
        Blocker(
            id = "0b8f6c1e-5a52-4a0e-9d57-3c1f0e6b7a11",
            name = "2026 Japanese Grand Prix",
            strongTerms = listOf("Japanese Grand Prix", "Suzuka", "#JapaneseGP"),
            enabled = true,
            createdAtMillis = 1_791_239_695_165,
            description = "2026 Japanese Grand Prix",
            weakTerms = listOf("Max", "podium", "P1"),
            sources = listOf("FORMULA 1", "Sky Sports F1"),
            breadth = Breadth.NARROW,
        ),
        Blocker(
            id = "c4a1d2f3-8e77-4b6a-a0c9-5d2e9f4b6c22",
            name = "São Paulo Grand Prix",
            strongTerms = emptyList(),
            enabled = false,
            createdAtMillis = 1_791_241_067_370,
            description = "",
            weakTerms = emptyList(),
            sources = emptyList(),
            breadth = Breadth.BROAD,
        ),
    )

    @Test
    fun `starts with no blockers`() = runTest {
        val repository = BlockerRepository({ file }, this)

        assertEquals(emptyList<Blocker>(), repository.blockers.first())
    }

    @Test
    fun `save adds a new blocker after the existing ones`() = runTest {
        val repository = BlockerRepository({ file }, this)

        repository.save(race)
        repository.save(finale)

        assertEquals(listOf(race, finale), repository.blockers.first())
    }

    @Test
    fun `save replaces the blocker with the same id and keeps its place`() = runTest {
        val repository = BlockerRepository({ file }, this)
        repository.save(race)
        repository.save(finale)

        val edited = race.copy(name = "Japanese GP", strongTerms = listOf("Suzuka"))
        repository.save(edited)

        assertEquals(listOf(edited, finale), repository.blockers.first())
    }

    @Test
    fun `delete removes only that blocker`() = runTest {
        val repository = BlockerRepository({ file }, this)
        repository.save(race)
        repository.save(finale)

        repository.delete(race.id)

        assertEquals(listOf(finale), repository.blockers.first())
    }

    @Test
    fun `setEnabled changes only that blocker`() = runTest {
        val repository = BlockerRepository({ file }, this)
        repository.save(race)
        repository.save(finale)

        repository.setEnabled(race.id, false)
        repository.setEnabled(finale.id, true)

        assertEquals(
            listOf(race.copy(enabled = false), finale.copy(enabled = true)),
            repository.blockers.first(),
        )
    }

    @Test
    fun `delete and setEnabled ignore an unknown id`() = runTest {
        val repository = BlockerRepository({ file }, this)
        repository.save(race)

        repository.delete("no such id")
        repository.setEnabled("no such id", false)

        assertEquals(listOf(race), repository.blockers.first())
    }

    @Test
    fun `changes made at the same time are all applied`() = runTest {
        val repository = BlockerRepository({ file }, this)
        repository.save(race)
        repository.save(finale)
        val more = (1..10).map { number -> race.copy(id = "more $number") }

        // launch starts each change without waiting for the one before, so
        // all of them are under way together. coroutineScope returns when
        // every one has finished.
        coroutineScope {
            launch { repository.setEnabled(race.id, false) }
            launch { repository.delete(finale.id) }
            for (blocker in more) {
                launch { repository.save(blocker) }
            }
        }

        assertEquals(listOf(race.copy(enabled = false)) + more, repository.blockers.first())
    }

    @Test
    fun `blockers survive a reload from the same file`() = runTest {
        // DataStore lets go of a file only when its scope ends, so the first
        // repository gets a scope of its own that can end mid-test. This is
        // what happens for real when the app's process is stopped.
        val firstJob = Job()
        val firstScope = CoroutineScope(StandardTestDispatcher(testScheduler) + firstJob)
        val first = BlockerRepository({ file }, firstScope)
        first.save(race)
        first.save(finale)
        first.setEnabled(race.id, false)
        firstJob.cancelAndJoin()

        val second = BlockerRepository({ file }, this)

        assertEquals(listOf(race.copy(enabled = false), finale), second.blockers.first())
    }

    @Test
    fun `a version 1 file still loads`() = runTest {
        file.writeText(version1File)

        val repository = BlockerRepository({ file }, this)

        val blockers = repository.blockers.first()
        assertEquals(version1Blockers, blockers)
        assertFalse(unreadableCopy.exists())
        // What version 1 did not have is filled in with the defaults, so a
        // version 1 blocker behaves as it always did: strong terms only.
        for (blocker in blockers) {
            assertEquals("", blocker.description)
            assertEquals(emptyList<String>(), blocker.weakTerms)
            assertEquals(emptyList<String>(), blocker.sources)
            assertEquals(Breadth.NARROW, blocker.breadth)
        }
    }

    @Test
    fun `a version 2 file loads`() = runTest {
        file.writeText(version2File)

        val repository = BlockerRepository({ file }, this)

        assertEquals(version2Blockers, repository.blockers.first())
        assertFalse(unreadableCopy.exists())
    }

    // This fails as soon as the build writes anything else, for example
    // after a field is added to Blocker. That is the moment to raise
    // CURRENT_VERSION, add a document for the new version beside
    // version2File, and point this test at the new one.
    @Test
    fun `this build writes version 2 files`() = runTest {
        val repository = BlockerRepository({ file }, this)

        for (blocker in version2Blockers) {
            repository.save(blocker)
        }

        // Compared as JSON, so that line breaks and spaces do not count.
        assertEquals(Json.parseToJsonElement(version2File), Json.parseToJsonElement(file.readText()))
    }

    @Test
    fun `a change rewrites a version 1 file as version 2 and keeps its blockers`() = runTest {
        file.writeText(version1File)
        val repository = BlockerRepository({ file }, this)

        repository.save(race)

        assertTrue(file.readText().startsWith("""{"version":2,"""))
        assertEquals(version1Blockers + race, repository.blockers.first())
    }

    // What is guaranteed is only this: an unknown field does not make the
    // file unreadable. The field itself is lost at the next change, which is
    // why a build that adds a field must also raise the version.
    @Test
    fun `a field this build does not know is skipped`() = runTest {
        file.writeText(
            """
            {
              "version": 2,
              "somethingNew": true,
              "blockers": [
                {
                  "id": "race",
                  "name": "2026 Japanese Grand Prix",
                  "strongTerms": ["Japanese Grand Prix", "Suzuka"],
                  "expiresAtMillis": 2000,
                  "enabled": true,
                  "createdAtMillis": 1000
                }
              ]
            }
            """.trimIndent(),
        )

        val repository = BlockerRepository({ file }, this)

        assertEquals(listOf(race), repository.blockers.first())
        assertFalse(unreadableCopy.exists())
    }

    @Test
    fun `a file from a newer build is refused and left untouched`() = runTest {
        assertRefusedAndLeftUntouched(
            """
            {
              "version": 3,
              "blockers": [
                {
                  "id": "race",
                  "name": "2026 Japanese Grand Prix",
                  "strongTerms": ["Japanese Grand Prix", "Suzuka"],
                  "expiresAtMillis": 2000,
                  "enabled": true,
                  "createdAtMillis": 1000
                }
              ]
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a newer file is refused even when this build cannot read the rest of it`() = runTest {
        // Here the newer build has renamed "name", which this build requires.
        assertRefusedAndLeftUntouched(
            """{"version":3,"blockers":[{"id":"race","title":"2026 Japanese Grand Prix"}]}""",
        )
    }

    private suspend fun TestScope.assertRefusedAndLeftUntouched(newerFile: String) {
        file.writeText(newerFile)
        val repository = BlockerRepository({ file }, this)

        try {
            repository.blockers.first()
            fail("Reading a newer file should fail.")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("newer build (version 3)"))
        }
        try {
            repository.setEnabled("race", false)
            fail("Changing a newer file should fail.")
        } catch (e: IllegalStateException) {
            // Expected.
        }

        assertEquals(newerFile, file.readText())
        assertFalse(unreadableCopy.exists())
    }

    @Test
    fun `a file that cannot be read is kept as a copy and replaced with an empty list`() = runTest {
        // What a file looks like when writing it was cut short.
        val cutShort = """{"version":1,"blockers":[{"id":"race","na"""
        file.writeText(cutShort)

        val repository = BlockerRepository({ file }, this)

        assertEquals(emptyList<Blocker>(), repository.blockers.first())
        assertEquals(cutShort, unreadableCopy.readText())
        // The repository still works afterwards.
        repository.save(race)
        assertEquals(listOf(race), repository.blockers.first())
    }

    // When a file cannot be read and the empty list that should replace it
    // cannot be written either, the failure reaches whoever asked, and from
    // there the log. It must not carry what the file holds.
    @Test
    fun `the failure for an unreadable file does not quote the file`() = runTest {
        file.writeText("""{"version":1,"blockers":[{"id":"race","name":"Suzuka weekend""")
        // DataStore writes a new file under this name and then renames it.
        // A folder in its place, and not an empty one, makes that fail.
        val temporaryName = File(temporaryFolder.root, "blockers.json.tmp")
        temporaryName.mkdir()
        File(temporaryName, "in the way").writeText("")
        val repository = BlockerRepository({ file }, this)

        val failure = try {
            repository.blockers.first()
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
