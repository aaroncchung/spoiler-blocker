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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests the list's ViewModel against a real repository on a temporary file.
 * `BlockerEditorViewModelTest` explains the test dispatcher.
 *
 * The ViewModel reads the repository only while something is collecting its
 * state, as the screen does. Here `first { ... }` is that collector: it waits
 * for the first state that passes the check.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlockerListViewModelTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val race = Blocker(
        id = "race",
        name = "2026 Japanese Grand Prix",
        strongTerms = listOf("Japanese Grand Prix", "Suzuka"),
        enabled = true,
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

    @Test
    fun `the list is loading until the stored blockers arrive`() = runTest {
        val repository = newRepository()
        repository.save(race)
        val viewModel = BlockerListViewModel(repository)

        assertEquals(BlockerListUiState(isLoading = true, blockers = emptyList()), viewModel.uiState.value)

        val loaded = viewModel.uiState.first { !it.isLoading }
        assertEquals(listOf(race), loaded.blockers)
    }

    @Test
    fun `setEnabled is stored and shows in the list`() = runTest {
        val repository = newRepository()
        repository.save(race)
        val viewModel = BlockerListViewModel(repository)

        viewModel.setEnabled(race.id, false)
        advanceUntilIdle()

        val switchedOff = listOf(race.copy(enabled = false))
        assertEquals(switchedOff, repository.blockers.first())
        assertEquals(switchedOff, viewModel.uiState.first { !it.isLoading }.blockers)
    }
}
