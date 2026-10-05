package io.github.aaroncchung.spoilerblocker.ui.blockers

import io.github.aaroncchung.spoilerblocker.data.Blocker
import io.github.aaroncchung.spoilerblocker.data.BlockerRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests the editor's ViewModel against a real repository on a temporary file.
 *
 * A ViewModel launches its work on the main thread, which a unit test on a PC
 * does not have, so a test dispatcher stands in for it. That dispatcher runs
 * nothing by itself: `advanceUntilIdle()` runs everything launched so far.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlockerEditorViewModelTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val race = Blocker(
        id = "race",
        name = "2026 Japanese Grand Prix",
        strongTerms = listOf("Japanese Grand Prix", "Suzuka"),
        enabled = false,
        createdAtMillis = 1_000,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.newRepository() =
        BlockerRepository({ File(temporaryFolder.root, "blockers.json") }, this)

    /** A ViewModel for a new blocker with [terms] already added. */
    private fun TestScope.newBlockerViewModel(vararg terms: String): BlockerEditorViewModel {
        val viewModel = BlockerEditorViewModel(newRepository(), blockerId = null)
        for (term in terms) {
            viewModel.onTermDraftChange(term)
            viewModel.addTerm()
        }
        return viewModel
    }

    @Test
    fun `a new blocker starts switched on, with no name and no terms`() = runTest {
        val state = newBlockerViewModel().uiState.value

        assertTrue(state.isNew)
        assertFalse(state.isLoading)
        assertTrue(state.enabled)
        assertEquals("", state.name)
        assertEquals(emptyList<String>(), state.terms)
    }

    @Test
    fun `adding a term trims it and empties the field`() = runTest {
        val viewModel = newBlockerViewModel()

        viewModel.onTermDraftChange("  Japanese Grand Prix ")
        viewModel.addTerm()

        assertEquals(listOf("Japanese Grand Prix"), viewModel.uiState.value.terms)
        assertEquals("", viewModel.uiState.value.termDraft)
    }

    @Test
    fun `terms are kept in the order they were added`() = runTest {
        val viewModel = newBlockerViewModel("Suzuka", "Japanese Grand Prix", "#JapaneseGP")

        assertEquals(
            listOf("Suzuka", "Japanese Grand Prix", "#JapaneseGP"),
            viewModel.uiState.value.terms,
        )
    }

    @Test
    fun `a blank term is not added`() = runTest {
        val viewModel = newBlockerViewModel("Suzuka")

        viewModel.onTermDraftChange("   ")
        viewModel.addTerm()
        viewModel.addTerm()

        assertEquals(listOf("Suzuka"), viewModel.uiState.value.terms)
        assertEquals("", viewModel.uiState.value.termDraft)
    }

    @Test
    fun `a term already in the list is not added again, whatever its capitals`() = runTest {
        val viewModel = newBlockerViewModel("Suzuka")

        viewModel.onTermDraftChange("Suzuka")
        viewModel.addTerm()
        viewModel.onTermDraftChange(" SUZUKA ")
        viewModel.addTerm()

        assertEquals(listOf("Suzuka"), viewModel.uiState.value.terms)
        assertEquals("", viewModel.uiState.value.termDraft)
    }

    @Test
    fun `removing a term leaves the others`() = runTest {
        val viewModel = newBlockerViewModel("Suzuka", "Japanese Grand Prix", "#JapaneseGP")

        viewModel.removeTerm("Japanese Grand Prix")

        assertEquals(listOf("Suzuka", "#JapaneseGP"), viewModel.uiState.value.terms)
    }

    @Test
    fun `save is only allowed once the name is not blank`() = runTest {
        val viewModel = newBlockerViewModel("Suzuka")
        assertFalse(viewModel.uiState.value.canSave)

        viewModel.onNameChange("   ")
        assertFalse(viewModel.uiState.value.canSave)

        viewModel.onNameChange("Race")
        assertTrue(viewModel.uiState.value.canSave)
    }

    @Test
    fun `save with a blank name stores nothing`() = runTest {
        val repository = newRepository()
        val viewModel = BlockerEditorViewModel(repository, blockerId = null)
        viewModel.onNameChange("   ")

        viewModel.save()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isFinished)
        assertEquals(emptyList<Blocker>(), repository.blockers.first())
    }

    @Test
    fun `nothing is stored until save`() = runTest {
        val repository = newRepository()
        val viewModel = BlockerEditorViewModel(repository, blockerId = null)

        viewModel.onNameChange("Race")
        viewModel.onTermDraftChange("Suzuka")
        viewModel.addTerm()
        advanceUntilIdle()

        assertEquals(emptyList<Blocker>(), repository.blockers.first())
    }

    @Test
    fun `saving a new blocker stores it and finishes`() = runTest {
        val repository = newRepository()
        val viewModel = BlockerEditorViewModel(repository, blockerId = null)
        val before = System.currentTimeMillis()

        viewModel.onNameChange("  2026 Japanese Grand Prix ")
        viewModel.onTermDraftChange("Suzuka")
        viewModel.addTerm()
        viewModel.save()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isFinished)
        val saved = repository.blockers.first().single()
        assertEquals("2026 Japanese Grand Prix", saved.name)
        assertEquals(listOf("Suzuka"), saved.strongTerms)
        assertTrue(saved.enabled)
        assertTrue(saved.id.isNotBlank())
        assertTrue(saved.createdAtMillis in before..System.currentTimeMillis())
    }

    @Test
    fun `each new blocker gets its own id`() = runTest {
        val repository = newRepository()

        for (name in listOf("First", "Second")) {
            val viewModel = BlockerEditorViewModel(repository, blockerId = null)
            viewModel.onNameChange(name)
            viewModel.save()
            advanceUntilIdle()
        }

        assertEquals(listOf("First", "Second"), repository.blockers.first().map { it.name })
    }

    @Test
    fun `pressing save twice stores one blocker`() = runTest {
        val repository = newRepository()
        val viewModel = BlockerEditorViewModel(repository, blockerId = null)
        viewModel.onNameChange("Race")

        viewModel.save()
        viewModel.save()
        advanceUntilIdle()

        assertEquals(1, repository.blockers.first().size)
    }

    @Test
    fun `a new blocker can be switched off before it is saved`() = runTest {
        val repository = newRepository()
        val viewModel = BlockerEditorViewModel(repository, blockerId = null)
        viewModel.onNameChange("Race")

        viewModel.onEnabledChange(false)
        viewModel.save()
        advanceUntilIdle()

        assertFalse(repository.blockers.first().single().enabled)
    }

    @Test
    fun `a blocker with no terms can be saved`() = runTest {
        val repository = newRepository()
        val viewModel = BlockerEditorViewModel(repository, blockerId = null)
        viewModel.onNameChange("Race")

        viewModel.save()
        advanceUntilIdle()

        assertEquals(emptyList<String>(), repository.blockers.first().single().strongTerms)
    }

    @Test
    fun `save adds a term that was typed but not added`() = runTest {
        val repository = newRepository()
        val viewModel = BlockerEditorViewModel(repository, blockerId = null)
        viewModel.onNameChange("Race")
        viewModel.onTermDraftChange("Suzuka")
        viewModel.addTerm()

        viewModel.onTermDraftChange(" Honda ")
        viewModel.save()
        advanceUntilIdle()

        assertEquals(listOf("Suzuka", "Honda"), repository.blockers.first().single().strongTerms)
    }

    @Test
    fun `an existing blocker is loaded into the form`() = runTest {
        val repository = newRepository()
        repository.save(race)

        val viewModel = BlockerEditorViewModel(repository, blockerId = race.id)
        assertTrue(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.canSave)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isNew)
        assertFalse(state.isLoading)
        assertEquals(race.name, state.name)
        assertEquals(race.strongTerms, state.terms)
        assertEquals(race.enabled, state.enabled)
        assertTrue(state.canSave)
    }

    @Test
    fun `saving an existing blocker replaces it and keeps its id and creation time`() = runTest {
        val repository = newRepository()
        repository.save(race)
        val viewModel = BlockerEditorViewModel(repository, blockerId = race.id)
        advanceUntilIdle()

        viewModel.onNameChange("Japanese GP")
        viewModel.removeTerm("Suzuka")
        viewModel.onEnabledChange(true)
        viewModel.save()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isFinished)
        val expected = race.copy(
            name = "Japanese GP",
            strongTerms = listOf("Japanese Grand Prix"),
            enabled = true,
        )
        assertEquals(listOf(expected), repository.blockers.first())
    }

    @Test
    fun `delete removes the blocker and finishes`() = runTest {
        val repository = newRepository()
        val other = race.copy(id = "other", name = "Season finale")
        repository.save(race)
        repository.save(other)
        val viewModel = BlockerEditorViewModel(repository, blockerId = race.id)
        advanceUntilIdle()

        viewModel.delete()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isFinished)
        assertEquals(listOf(other), repository.blockers.first())
    }

    @Test
    fun `save after delete does not bring the blocker back`() = runTest {
        val repository = newRepository()
        repository.save(race)
        val viewModel = BlockerEditorViewModel(repository, blockerId = race.id)
        advanceUntilIdle()

        viewModel.delete()
        advanceUntilIdle()
        viewModel.save()
        advanceUntilIdle()

        assertEquals(emptyList<Blocker>(), repository.blockers.first())
    }

    @Test
    fun `opening a blocker that no longer exists finishes straight away`() = runTest {
        val viewModel = BlockerEditorViewModel(newRepository(), blockerId = "deleted")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isFinished)
    }
}
