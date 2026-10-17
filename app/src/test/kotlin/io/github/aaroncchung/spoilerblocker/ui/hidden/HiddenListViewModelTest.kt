package io.github.aaroncchung.spoilerblocker.ui.hidden

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests the hidden list's ViewModel against a real repository on a temporary
 * file. `BlockerEditorViewModelTest` explains the test dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HiddenListViewModelTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun hidden(id: String) = HiddenNotification(
        id = id,
        hiddenAtMillis = 1_000,
        notificationKey = "0|com.example.chat|$id|null|10001",
        packageName = "com.example.chat",
        appName = "Chat",
        title = "Alex",
        text = "Message $id",
        blockerId = "race",
        blockerName = "2026 Japanese Grand Prix",
        matchedTerm = "Suzuka",
    )

    private fun TestScope.newRepository() =
        HiddenNotificationRepository({ File(temporaryFolder.root, "hidden_notifications.json") }, this)

    /**
     * A ViewModel whose state is being collected, as the screen does. The
     * ViewModel reads the repository only while that is so. Everything
     * launched in `backgroundScope` is stopped when the test ends.
     */
    private fun TestScope.newViewModel(repository: HiddenNotificationRepository): HiddenListViewModel {
        val viewModel = HiddenListViewModel(repository)
        backgroundScope.launch { viewModel.uiState.collect { } }
        advanceUntilIdle()
        return viewModel
    }

    /** The ids of the rows whose title and text are showing. */
    private fun HiddenListViewModel.revealedIds(): List<String> =
        uiState.value.rows.filter { it.isRevealed }.map { it.notification.id }

    @Test
    fun `the list is loading until the stored entries arrive`() = runTest {
        val repository = newRepository()
        repository.add(hidden("1"))
        val viewModel = HiddenListViewModel(repository)

        assertEquals(HiddenListUiState(isLoading = true, rows = emptyList()), viewModel.uiState.value)

        val loaded = viewModel.uiState.first { !it.isLoading }
        assertEquals(listOf(HiddenRow(hidden("1"), isRevealed = false)), loaded.rows)
    }

    @Test
    fun `every row starts concealed, newest first`() = runTest {
        val repository = newRepository()
        repository.add(hidden("1"))
        repository.add(hidden("2"))
        val viewModel = newViewModel(repository)

        assertEquals(
            listOf(HiddenRow(hidden("2"), isRevealed = false), HiddenRow(hidden("1"), isRevealed = false)),
            viewModel.uiState.value.rows,
        )
        assertFalse(viewModel.uiState.value.allRevealed)
    }

    @Test
    fun `a tap reveals that row only, and a second tap conceals it again`() = runTest {
        val repository = newRepository()
        repository.add(hidden("1"))
        repository.add(hidden("2"))
        repository.add(hidden("3"))
        val viewModel = newViewModel(repository)

        viewModel.toggleRevealed("2")
        advanceUntilIdle()
        assertEquals(listOf("2"), viewModel.revealedIds())

        viewModel.toggleRevealed("3")
        advanceUntilIdle()
        assertEquals(listOf("3", "2"), viewModel.revealedIds())
        assertFalse(viewModel.uiState.value.allRevealed)

        viewModel.toggleRevealed("2")
        advanceUntilIdle()
        assertEquals(listOf("3"), viewModel.revealedIds())
    }

    @Test
    fun `show all reveals every row`() = runTest {
        val repository = newRepository()
        repository.add(hidden("1"))
        repository.add(hidden("2"))
        val viewModel = newViewModel(repository)

        viewModel.revealAll()
        advanceUntilIdle()

        assertEquals(listOf("2", "1"), viewModel.revealedIds())
        assertTrue(viewModel.uiState.value.allRevealed)
    }

    @Test
    fun `a notification hidden after show all arrives concealed`() = runTest {
        val repository = newRepository()
        repository.add(hidden("1"))
        val viewModel = newViewModel(repository)
        viewModel.revealAll()
        advanceUntilIdle()

        repository.add(hidden("2"))
        advanceUntilIdle()

        assertEquals(
            listOf(HiddenRow(hidden("2"), isRevealed = false), HiddenRow(hidden("1"), isRevealed = true)),
            viewModel.uiState.value.rows,
        )
        assertFalse(viewModel.uiState.value.allRevealed)
    }

    @Test
    fun `hide all conceals every row`() = runTest {
        val repository = newRepository()
        repository.add(hidden("1"))
        repository.add(hidden("2"))
        val viewModel = newViewModel(repository)
        viewModel.revealAll()
        advanceUntilIdle()

        viewModel.concealAll()
        advanceUntilIdle()

        assertEquals(emptyList<String>(), viewModel.revealedIds())
        assertEquals(2, viewModel.uiState.value.rows.size)
    }

    @Test
    fun `revealing is not stored`() = runTest {
        val repository = newRepository()
        repository.add(hidden("1"))
        val viewModel = newViewModel(repository)
        viewModel.revealAll()
        advanceUntilIdle()

        // What the next visit to the screen gets.
        val nextVisit = newViewModel(repository)

        assertEquals(listOf(HiddenRow(hidden("1"), isRevealed = false)), nextVisit.uiState.value.rows)
    }

    @Test
    fun `clear all empties the stored list`() = runTest {
        val repository = newRepository()
        repository.add(hidden("1"))
        repository.add(hidden("2"))
        val viewModel = newViewModel(repository)
        viewModel.toggleRevealed("1")

        viewModel.clearAll()
        advanceUntilIdle()

        assertEquals(emptyList<HiddenNotification>(), repository.hiddenNotifications.first())
        assertEquals(HiddenListUiState(isLoading = false, rows = emptyList()), viewModel.uiState.value)
        assertFalse(viewModel.uiState.value.allRevealed)
    }
}
