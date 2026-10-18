package io.github.aaroncchung.spoilerblocker.ui.blockers

import io.github.aaroncchung.spoilerblocker.data.Blocker
import io.github.aaroncchung.spoilerblocker.data.BlockerRepository
import io.github.aaroncchung.spoilerblocker.matcher.Breadth
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
        description = "The 2026 race at Suzuka",
        weakTerms = listOf("Max", "podium"),
        sources = listOf("FORMULA 1"),
        breadth = Breadth.BROAD,
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

    /** Types [term] into the field of [list] and presses Add. */
    private fun BlockerEditorViewModel.add(list: TermList, term: String) {
        onTermDraftChange(list, term)
        addTerm(list)
    }

    /** A ViewModel for a new blocker with [strongTerms] already added, in this order. */
    private fun TestScope.newBlockerViewModel(vararg strongTerms: String): BlockerEditorViewModel {
        val viewModel = BlockerEditorViewModel(newRepository(), blockerId = null)
        for (term in strongTerms) {
            viewModel.add(TermList.STRONG, term)
        }
        return viewModel
    }

    @Test
    fun `a new blocker starts switched on and narrow, with no name and empty lists`() = runTest {
        val state = newBlockerViewModel().uiState.value

        assertTrue(state.isNew)
        assertFalse(state.isLoading)
        assertTrue(state.enabled)
        assertEquals(Breadth.NARROW, state.breadth)
        assertEquals("", state.name)
        for (list in TermList.entries) {
            assertEquals(TermListState(), state.list(list))
        }
        assertTrue(state.hidesNothing)
    }

    @Test
    fun `adding a term trims it and empties the field`() = runTest {
        val viewModel = newBlockerViewModel()

        viewModel.onTermDraftChange(TermList.STRONG, "  Japanese Grand Prix ")
        viewModel.addTerm(TermList.STRONG)

        assertEquals(TermListState(terms = listOf("Japanese Grand Prix")), viewModel.uiState.value.strong)
    }

    @Test
    fun `each list has its own field and its own terms`() = runTest {
        val viewModel = newBlockerViewModel()

        viewModel.onTermDraftChange(TermList.STRONG, "Suzuka")
        viewModel.onTermDraftChange(TermList.WEAK, "podium")
        viewModel.onTermDraftChange(TermList.SOURCES, "FORMULA 1")
        // Only the weak terms' Add is pressed. The other two fields keep
        // their text.
        viewModel.addTerm(TermList.WEAK)

        val state = viewModel.uiState.value
        assertEquals(TermListState(draft = "Suzuka"), state.strong)
        assertEquals(TermListState(terms = listOf("podium")), state.weak)
        assertEquals(TermListState(draft = "FORMULA 1"), state.sources)
        assertFalse(state.hidesNothing)
    }

    @Test
    fun `the newest term comes first`() = runTest {
        val viewModel = newBlockerViewModel("Suzuka", "Japanese Grand Prix", "#JapaneseGP")

        assertEquals(
            listOf("#JapaneseGP", "Japanese Grand Prix", "Suzuka"),
            viewModel.uiState.value.strong.terms,
        )
    }

    @Test
    fun `a blank term is not added`() = runTest {
        val viewModel = newBlockerViewModel("Suzuka")

        viewModel.onTermDraftChange(TermList.STRONG, "   ")
        viewModel.addTerm(TermList.STRONG)
        viewModel.addTerm(TermList.STRONG)

        assertEquals(TermListState(terms = listOf("Suzuka")), viewModel.uiState.value.strong)
    }

    @Test
    fun `a term already in the list is not added again, whatever its capitals`() = runTest {
        val viewModel = newBlockerViewModel("Suzuka")

        viewModel.add(TermList.STRONG, "Suzuka")
        viewModel.add(TermList.STRONG, " SUZUKA ")

        assertEquals(TermListState(terms = listOf("Suzuka")), viewModel.uiState.value.strong)
    }

    @Test
    fun `text with no letter or digit is refused and stays in the field`() = runTest {
        val viewModel = newBlockerViewModel("Suzuka")

        for (list in TermList.entries) {
            viewModel.add(list, " !!! ")

            val listState = viewModel.uiState.value.list(list)
            assertTrue("$list", listState.isDraftRefused)
            assertEquals(" !!! ", listState.draft)
            assertFalse("$list", "!!!" in listState.terms)
        }
        assertEquals(listOf("Suzuka"), viewModel.uiState.value.strong.terms)
    }

    @Test
    fun `changing the field takes the refusal away`() = runTest {
        val viewModel = newBlockerViewModel()
        viewModel.add(TermList.WEAK, "?")
        assertTrue(viewModel.uiState.value.weak.isDraftRefused)

        viewModel.onTermDraftChange(TermList.WEAK, "?1")
        assertFalse(viewModel.uiState.value.weak.isDraftRefused)

        // One digit is enough for it to be found.
        viewModel.addTerm(TermList.WEAK)
        assertEquals(TermListState(terms = listOf("?1")), viewModel.uiState.value.weak)
    }

    @Test
    fun `removing a term leaves the others`() = runTest {
        val viewModel = newBlockerViewModel("Suzuka", "Japanese Grand Prix", "#JapaneseGP")
        viewModel.add(TermList.WEAK, "Suzuka")

        viewModel.removeTerm(TermList.STRONG, "Japanese Grand Prix")
        viewModel.removeTerm(TermList.STRONG, "Suzuka")

        assertEquals(listOf("#JapaneseGP"), viewModel.uiState.value.strong.terms)
        // The same word in another list is another chip.
        assertEquals(listOf("Suzuka"), viewModel.uiState.value.weak.terms)
    }

    @Test
    fun `a term moves from strong to weak and back`() = runTest {
        val viewModel = newBlockerViewModel("Suzuka", "Max")
        viewModel.add(TermList.WEAK, "podium")

        viewModel.moveTerm(TermList.STRONG, "Max")
        assertEquals(listOf("Suzuka"), viewModel.uiState.value.strong.terms)
        assertEquals(listOf("Max", "podium"), viewModel.uiState.value.weak.terms)

        viewModel.moveTerm(TermList.WEAK, "podium")
        assertEquals(listOf("podium", "Suzuka"), viewModel.uiState.value.strong.terms)
        assertEquals(listOf("Max"), viewModel.uiState.value.weak.terms)
    }

    @Test
    fun `a term moved to a list that has it already is not there twice`() = runTest {
        val viewModel = newBlockerViewModel("Suzuka", "Max")
        viewModel.add(TermList.WEAK, "max")

        viewModel.moveTerm(TermList.STRONG, "Max")

        assertEquals(listOf("Suzuka"), viewModel.uiState.value.strong.terms)
        assertEquals(listOf("max"), viewModel.uiState.value.weak.terms)
    }

    @Test
    fun `a source cannot be moved, and neither can a term that is not in the list`() = runTest {
        val viewModel = newBlockerViewModel("Suzuka")
        viewModel.add(TermList.SOURCES, "FORMULA 1")
        val before = viewModel.uiState.value

        viewModel.moveTerm(TermList.SOURCES, "FORMULA 1")
        viewModel.moveTerm(TermList.WEAK, "Suzuka")

        assertEquals(before, viewModel.uiState.value)
    }

    @Test
    fun `the breadth can be changed`() = runTest {
        val viewModel = newBlockerViewModel()

        viewModel.onBreadthChange(Breadth.BROAD)

        assertEquals(Breadth.BROAD, viewModel.uiState.value.breadth)
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
        viewModel.add(TermList.STRONG, "Suzuka")
        viewModel.add(TermList.WEAK, "podium")
        viewModel.add(TermList.SOURCES, "FORMULA 1")
        viewModel.onBreadthChange(Breadth.BROAD)
        advanceUntilIdle()

        assertEquals(emptyList<Blocker>(), repository.blockers.first())
    }

    @Test
    fun `saving a new blocker stores its lists and breadth and finishes`() = runTest {
        val repository = newRepository()
        val viewModel = BlockerEditorViewModel(repository, blockerId = null)
        val before = System.currentTimeMillis()

        viewModel.onNameChange("  2026 Japanese Grand Prix ")
        viewModel.add(TermList.STRONG, "Suzuka")
        viewModel.add(TermList.WEAK, "Max")
        viewModel.add(TermList.WEAK, "podium")
        viewModel.add(TermList.SOURCES, "FORMULA 1")
        viewModel.onBreadthChange(Breadth.BROAD)
        viewModel.save()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isFinished)
        val saved = repository.blockers.first().single()
        assertEquals("2026 Japanese Grand Prix", saved.name)
        assertEquals(listOf("Suzuka"), saved.strongTerms)
        assertEquals(listOf("podium", "Max"), saved.weakTerms)
        assertEquals(listOf("FORMULA 1"), saved.sources)
        assertEquals(Breadth.BROAD, saved.breadth)
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
    fun `a blocker with nothing in its lists can be saved`() = runTest {
        val repository = newRepository()
        val viewModel = BlockerEditorViewModel(repository, blockerId = null)
        viewModel.onNameChange("Race")

        viewModel.save()
        advanceUntilIdle()

        val saved = repository.blockers.first().single()
        assertEquals(emptyList<String>(), saved.strongTerms)
        assertEquals(emptyList<String>(), saved.weakTerms)
        assertEquals(emptyList<String>(), saved.sources)
        assertEquals(Breadth.NARROW, saved.breadth)
    }

    @Test
    fun `save adds what was typed into each field but not added`() = runTest {
        val repository = newRepository()
        val viewModel = BlockerEditorViewModel(repository, blockerId = null)
        viewModel.onNameChange("Race")
        viewModel.add(TermList.STRONG, "Suzuka")

        viewModel.onTermDraftChange(TermList.STRONG, " Honda ")
        viewModel.onTermDraftChange(TermList.WEAK, "podium")
        viewModel.onTermDraftChange(TermList.SOURCES, "FORMULA 1")
        viewModel.save()
        advanceUntilIdle()

        val saved = repository.blockers.first().single()
        assertEquals(listOf("Honda", "Suzuka"), saved.strongTerms)
        assertEquals(listOf("podium"), saved.weakTerms)
        assertEquals(listOf("FORMULA 1"), saved.sources)
    }

    @Test
    fun `save leaves out text in a field that could never be found`() = runTest {
        val repository = newRepository()
        val viewModel = BlockerEditorViewModel(repository, blockerId = null)
        viewModel.onNameChange("Race")
        viewModel.add(TermList.STRONG, "Suzuka")

        viewModel.onTermDraftChange(TermList.STRONG, "!!!")
        viewModel.onTermDraftChange(TermList.SOURCES, "...")
        viewModel.save()
        advanceUntilIdle()

        val saved = repository.blockers.first().single()
        assertEquals(listOf("Suzuka"), saved.strongTerms)
        assertEquals(emptyList<String>(), saved.sources)
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
        assertEquals(TermListState(race.strongTerms), state.strong)
        assertEquals(TermListState(race.weakTerms), state.weak)
        assertEquals(TermListState(race.sources), state.sources)
        assertEquals(race.breadth, state.breadth)
        assertEquals(race.enabled, state.enabled)
        assertTrue(state.canSave)
    }

    @Test
    fun `saving an existing blocker unchanged stores it exactly as it was`() = runTest {
        val repository = newRepository()
        repository.save(race)
        val viewModel = BlockerEditorViewModel(repository, blockerId = race.id)
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()

        assertEquals(listOf(race), repository.blockers.first())
    }

    @Test
    fun `saving an existing blocker replaces it and keeps its id, creation time and description`() = runTest {
        val repository = newRepository()
        repository.save(race)
        val viewModel = BlockerEditorViewModel(repository, blockerId = race.id)
        advanceUntilIdle()

        viewModel.onNameChange("Japanese GP")
        viewModel.removeTerm(TermList.STRONG, "Suzuka")
        viewModel.moveTerm(TermList.WEAK, "Max")
        viewModel.removeTerm(TermList.SOURCES, "FORMULA 1")
        viewModel.onBreadthChange(Breadth.NARROW)
        viewModel.onEnabledChange(true)
        viewModel.save()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isFinished)
        // copy() changes only what is named, so the id, the creation time and
        // the description are those of the stored blocker.
        val expected = race.copy(
            name = "Japanese GP",
            strongTerms = listOf("Max", "Japanese Grand Prix"),
            weakTerms = listOf("podium"),
            sources = emptyList(),
            breadth = Breadth.NARROW,
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
    fun `save pressed straight after delete does not bring the blocker back`() = runTest {
        val repository = newRepository()
        repository.save(race)
        val viewModel = BlockerEditorViewModel(repository, blockerId = race.id)
        advanceUntilIdle()

        // No advanceUntilIdle() between the two: Save arrives while the
        // delete has been asked for but is not written yet.
        viewModel.delete()
        assertFalse(viewModel.uiState.value.canSave)
        viewModel.save()
        advanceUntilIdle()

        assertEquals(emptyList<Blocker>(), repository.blockers.first())
    }

    @Test
    fun `delete pressed straight after save is ignored`() = runTest {
        val repository = newRepository()
        repository.save(race)
        val viewModel = BlockerEditorViewModel(repository, blockerId = race.id)
        advanceUntilIdle()

        viewModel.onNameChange("Japanese GP")
        viewModel.save()
        viewModel.delete()
        advanceUntilIdle()

        assertEquals(listOf(race.copy(name = "Japanese GP")), repository.blockers.first())
    }

    @Test
    fun `a later change in storage does not overwrite what is being typed`() = runTest {
        val repository = newRepository()
        repository.save(race)
        val viewModel = BlockerEditorViewModel(repository, blockerId = race.id)
        advanceUntilIdle()
        viewModel.onNameChange("Typed name")
        viewModel.removeTerm(TermList.STRONG, "Suzuka")
        viewModel.onEnabledChange(true)

        // Something else changes the stored blocker while the editor is open.
        repository.save(race.copy(name = "Stored name", strongTerms = listOf("stored")))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Typed name", state.name)
        assertEquals(listOf("Japanese Grand Prix"), state.strong.terms)
        assertTrue(state.enabled)
    }

    @Test
    fun `opening a blocker that no longer exists finishes straight away`() = runTest {
        val viewModel = BlockerEditorViewModel(newRepository(), blockerId = "deleted")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isFinished)
    }
}
