package io.github.aaroncchung.spoilerblocker.ui.blockers

import io.github.aaroncchung.spoilerblocker.data.Blocker
import io.github.aaroncchung.spoilerblocker.data.BlockerRepository
import io.github.aaroncchung.spoilerblocker.data.HiddenNotification
import io.github.aaroncchung.spoilerblocker.data.HiddenNotificationRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    private fun TestScope.newHiddenRepository() =
        HiddenNotificationRepository({ File(temporaryFolder.root, "hidden_notifications.json") }, this)

    private fun hidden(id: String) = HiddenNotification(
        id = id,
        hiddenAtMillis = 1_000,
        notificationKey = "0|com.example.chat|$id|null|10001",
        packageName = "com.example.chat",
        appName = "Chat",
        title = "Alex",
        text = "Message $id",
        blockerId = race.id,
        blockerName = race.name,
        matchedTerm = "Suzuka",
    )

    @Test
    fun `the list is loading until the stored blockers arrive`() = runTest {
        val repository = newRepository()
        repository.save(race)
        val viewModel = BlockerListViewModel(repository, newHiddenRepository())

        assertEquals(BlockerListUiState(isLoading = true, blockers = emptyList()), viewModel.uiState.value)

        val loaded = viewModel.uiState.first { !it.isLoading }
        assertEquals(listOf(race), loaded.blockers)
    }

    @Test
    fun `setEnabled is stored and shows in the list`() = runTest {
        val repository = newRepository()
        repository.save(race)
        val viewModel = BlockerListViewModel(repository, newHiddenRepository())

        viewModel.setEnabled(race.id, false)
        advanceUntilIdle()

        val switchedOff = listOf(race.copy(enabled = false))
        assertEquals(switchedOff, repository.blockers.first())
        assertEquals(switchedOff, viewModel.uiState.first { !it.isLoading }.blockers)
    }

    @Test
    fun `the state counts the hidden notifications`() = runTest {
        val hiddenRepository = newHiddenRepository()
        val viewModel = BlockerListViewModel(newRepository(), hiddenRepository)
        // This collector stays for the whole test, as the screen's does, so
        // that the state follows the repository. backgroundScope stops it
        // when the test ends.
        backgroundScope.launch { viewModel.uiState.collect { } }
        advanceUntilIdle()
        assertEquals(BlockerListUiState(isLoading = false, hiddenCount = 0), viewModel.uiState.value)

        hiddenRepository.add(hidden("1"))
        hiddenRepository.add(hidden("2"))
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.hiddenCount)
    }
}
