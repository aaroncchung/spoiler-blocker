package io.github.aaroncchung.spoilerblocker.data

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `the file records its format version`() = runTest {
        val repository = BlockerRepository({ file }, this)

        repository.save(race)

        assertTrue(file.readText().contains("\"version\":1"))
    }

    @Test
    fun `fields added by a later version are ignored`() = runTest {
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
                  "weakTerms": ["podium"],
                  "enabled": true,
                  "createdAtMillis": 1000
                }
              ]
            }
            """.trimIndent(),
        )

        val repository = BlockerRepository({ file }, this)

        assertEquals(listOf(race), repository.blockers.first())
    }

    @Test
    fun `a file that cannot be read is replaced with an empty list`() = runTest {
        file.writeText("this is not JSON")

        val repository = BlockerRepository({ file }, this)

        assertEquals(emptyList<Blocker>(), repository.blockers.first())
        // The repository still works afterwards.
        repository.save(race)
        assertEquals(listOf(race), repository.blockers.first())
    }
}
