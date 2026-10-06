package io.github.aaroncchung.spoilerblocker.ui.blockers

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.aaroncchung.spoilerblocker.R
import io.github.aaroncchung.spoilerblocker.data.Blocker
import io.github.aaroncchung.spoilerblocker.data.BlockerRepository
import io.github.aaroncchung.spoilerblocker.expansion.ExpandedTerms
import io.github.aaroncchung.spoilerblocker.expansion.ExpansionResult
import io.github.aaroncchung.spoilerblocker.expansion.FailureKind
import io.github.aaroncchung.spoilerblocker.matcher.Breadth
import io.github.aaroncchung.spoilerblocker.suggestions.TermSuggester
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
 * Tests how a new blocker is made: the description, the suggested lists and
 * what survives when Android stops the app's process.
 *
 * [FakeTermSuggester] stands in for the Claude API, so no test here uses the
 * network or costs anything. `BlockerEditorViewModelTest` explains the test
 * dispatcher and `advanceUntilIdle()`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewBlockerFlowTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val suggester = FakeTermSuggester()

    /** What the fake answers when a test wants the call to succeed. Written by hand. */
    private val suggestedTerms = ExpandedTerms(
        strong = listOf("Japanese Grand Prix", "Suzuka", "#JapaneseGP"),
        weak = listOf("Max", "podium", "P1"),
        sources = listOf("FORMULA 1", "Sky Sports F1"),
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

    private fun newBlocker(
        repository: BlockerRepository,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ) = BlockerEditorViewModel(repository, suggester, savedStateHandle, blockerId = null)

    /** Types a description, chooses a breadth and presses "Suggest terms". The call is left waiting. */
    private fun TestScope.ask(
        viewModel: BlockerEditorViewModel,
        description: String = "2026 Japanese Grand Prix",
        breadth: Breadth = Breadth.NARROW,
    ) {
        viewModel.onDescriptionChange(description)
        viewModel.onBreadthChange(breadth)
        viewModel.suggestTerms()
        advanceUntilIdle()
    }

    /**
     * What Android does when it stops the app's process and the owner comes
     * back: whatever was put into the saved state is handed to a new one.
     */
    private fun SavedStateHandle.afterProcessDeath() =
        SavedStateHandle(keys().associateWith { key -> get<Any?>(key) })

    // The first step.

    @Test
    fun `a new blocker starts at the description with nothing asked`() = runTest {
        val state = newBlocker(newRepository()).uiState.value

        assertTrue(state.isNew)
        assertFalse(state.isLoading)
        assertEquals(EditorStep.DESCRIBE, state.step)
        assertEquals("", state.description)
        assertEquals(Breadth.NARROW, state.breadth)
        assertEquals(SuggestionState.Idle, state.suggestion)
        assertFalse(state.canSuggest)
        assertFalse(state.canSave)
    }

    @Test
    fun `a blank description cannot be asked about`() = runTest {
        val viewModel = newBlocker(newRepository())

        ask(viewModel, description = "   ")

        assertEquals(SuggestionState.Idle, viewModel.uiState.value.suggestion)
        assertEquals(emptyList<Pair<String, Breadth>>(), suggester.calls)
    }

    @Test
    fun `asking shows that a call is under way`() = runTest {
        val viewModel = newBlocker(newRepository())

        ask(viewModel)

        val state = viewModel.uiState.value
        assertEquals(SuggestionState.Working, state.suggestion)
        assertEquals(EditorStep.DESCRIBE, state.step)
        assertFalse(state.canSuggest)
    }

    // Decision 8: only the description, the breadth and today's date leave
    // the phone. The date is added by the expansion module. This checks that
    // the app hands over the other two and has nowhere to put anything else.
    @Test
    fun `the suggester is given the trimmed description and the breadth`() = runTest {
        val viewModel = newBlocker(newRepository())

        ask(viewModel, description = "  2026 Japanese Grand Prix ", breadth = Breadth.BROAD)

        assertEquals(listOf("2026 Japanese Grand Prix" to Breadth.BROAD), suggester.calls)
    }

    @Test
    fun `asking again while a call is under way does not start a second call`() = runTest {
        val viewModel = newBlocker(newRepository())
        ask(viewModel)

        viewModel.suggestTerms()
        advanceUntilIdle()

        assertEquals(1, suggester.calls.size)
    }

    // Success.

    @Test
    fun `suggested lists fill the form, which is named after the description`() = runTest {
        val viewModel = newBlocker(newRepository())
        ask(viewModel, description = " 2026 Japanese Grand Prix ", breadth = Breadth.BROAD)

        suggester.answer(ExpansionResult.Success(suggestedTerms))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(EditorStep.LISTS, state.step)
        assertEquals(SuggestionState.Idle, state.suggestion)
        assertEquals("2026 Japanese Grand Prix", state.name)
        assertEquals("2026 Japanese Grand Prix", state.description)
        assertEquals(Breadth.BROAD, state.breadth)
        assertEquals(TermListState(suggestedTerms.strong), state.strong)
        assertEquals(TermListState(suggestedTerms.weak), state.weak)
        assertEquals(TermListState(suggestedTerms.sources), state.sources)
        assertTrue(state.enabled)
        assertTrue(state.canSave)
    }

    @Test
    fun `suggested lists are not stored until save, and then with the description and breadth`() = runTest {
        val repository = newRepository()
        val viewModel = newBlocker(repository)
        ask(viewModel, breadth = Breadth.BROAD)
        suggester.answer(ExpansionResult.Success(suggestedTerms))
        advanceUntilIdle()
        // The owner reviews: one term is too common to block by itself, one
        // source is wrong, and the name is shortened.
        viewModel.moveTerm(TermList.STRONG, "Suzuka")
        viewModel.removeTerm(TermList.SOURCES, "Sky Sports F1")
        viewModel.onNameChange("Japanese GP")
        advanceUntilIdle()

        assertEquals(emptyList<Blocker>(), repository.blockers.first())

        viewModel.save()
        advanceUntilIdle()

        val saved = repository.blockers.first().single()
        assertEquals("Japanese GP", saved.name)
        assertEquals("2026 Japanese Grand Prix", saved.description)
        assertEquals(listOf("Japanese Grand Prix", "#JapaneseGP"), saved.strongTerms)
        assertEquals(listOf("Suzuka", "Max", "podium", "P1"), saved.weakTerms)
        assertEquals(listOf("FORMULA 1"), saved.sources)
        assertEquals(Breadth.BROAD, saved.breadth)
        assertTrue(saved.enabled)
    }

    // Cancelling and leaving.

    @Test
    fun `cancel abandons the call and goes back to the description`() = runTest {
        val repository = newRepository()
        val viewModel = newBlocker(repository)
        ask(viewModel)

        viewModel.cancelSuggestion()
        advanceUntilIdle()

        assertEquals(1, suggester.abandonedCalls)
        val state = viewModel.uiState.value
        assertEquals(SuggestionState.Idle, state.suggestion)
        assertEquals(EditorStep.DESCRIBE, state.step)
        assertEquals("2026 Japanese Grand Prix", state.description)
        assertTrue(state.canSuggest)

        // An answer that turns up after the call was abandoned changes nothing.
        suggester.answer(ExpansionResult.Success(suggestedTerms))
        advanceUntilIdle()
        assertEquals(state, viewModel.uiState.value)
        assertEquals(emptyList<Blocker>(), repository.blockers.first())
    }

    @Test
    fun `leaving the screen abandons the call`() = runTest {
        val repository = newRepository()
        // A ViewModelStore is where Android keeps a screen's ViewModels. It
        // clears the store when the screen leaves the back stack, and that
        // is what ends a ViewModel. A rotation does not clear it.
        val store = ViewModelStore()
        val factory = viewModelFactory { initializer { newBlocker(repository) } }
        val viewModel = ViewModelProvider.create(store, factory)[BlockerEditorViewModel::class]
        ask(viewModel)
        assertEquals(0, suggester.abandonedCalls)

        store.clear()
        advanceUntilIdle()

        assertEquals(1, suggester.abandonedCalls)
    }

    // Failures.

    private class FailureCase(val kind: FailureKind, @StringRes val sentence: Int, val tryAgainOffered: Boolean)

    /** Every kind of failure, with the sentence it must show and whether "Try again" goes with it. */
    private val failureCases = listOf(
        FailureCase(FailureKind.MISSING_API_KEY, R.string.suggest_failed_missing_key, tryAgainOffered = false),
        FailureCase(FailureKind.NO_NETWORK, R.string.suggest_failed_no_network, tryAgainOffered = true),
        FailureCase(FailureKind.TIMEOUT, R.string.suggest_failed_timeout, tryAgainOffered = true),
        FailureCase(FailureKind.AUTH, R.string.suggest_failed_auth, tryAgainOffered = false),
        FailureCase(FailureKind.RATE_LIMITED, R.string.suggest_failed_rate_limited, tryAgainOffered = true),
        FailureCase(FailureKind.SERVER_ERROR, R.string.suggest_failed_server_error, tryAgainOffered = true),
        FailureCase(FailureKind.HTTP_OTHER, R.string.suggest_failed_http_other, tryAgainOffered = false),
        FailureCase(FailureKind.REFUSED, R.string.suggest_failed_refused, tryAgainOffered = false),
        FailureCase(FailureKind.BAD_REPLY, R.string.suggest_failed_bad_reply, tryAgainOffered = true),
        FailureCase(FailureKind.NOTHING_FOUND, R.string.suggest_failed_nothing_found, tryAgainOffered = false),
    )

    @Test
    fun `every kind of failure is shown with its own sentence, and stays at the description`() = runTest {
        // If the expansion module gains a kind, it has to be added above.
        assertEquals(FailureKind.entries, failureCases.map { it.kind })
        val repository = newRepository()

        for (case in failureCases) {
            val viewModel = newBlocker(repository)
            ask(viewModel)

            suggester.answer(ExpansionResult.Failure(case.kind, "the detail"))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(SuggestionState.Failed(case.kind, "the detail"), state.suggestion)
            assertEquals("${case.kind}", EditorStep.DESCRIBE, state.step)
            assertTrue("${case.kind}", state.hidesNothing)
            // The two things the screen takes from the kind.
            assertEquals("${case.kind}", case.sentence, failureSentence(case.kind))
            assertEquals("${case.kind}", case.tryAgainOffered, case.kind.canRetry)
        }

        val sentences = failureCases.map { it.sentence }
        assertEquals(sentences.size, sentences.toSet().size)
    }

    @Test
    fun `try again asks the same question again`() = runTest {
        val viewModel = newBlocker(newRepository())
        ask(viewModel)
        suggester.answer(ExpansionResult.Failure(FailureKind.NO_NETWORK, "no route"))
        advanceUntilIdle()

        viewModel.suggestTerms()
        advanceUntilIdle()
        assertEquals(SuggestionState.Working, viewModel.uiState.value.suggestion)
        suggester.answer(ExpansionResult.Success(suggestedTerms))
        advanceUntilIdle()

        assertEquals(
            listOf("2026 Japanese Grand Prix" to Breadth.NARROW, "2026 Japanese Grand Prix" to Breadth.NARROW),
            suggester.calls,
        )
        assertEquals(EditorStep.LISTS, viewModel.uiState.value.step)
        assertEquals(TermListState(suggestedTerms.strong), viewModel.uiState.value.strong)
    }

    @Test
    fun `changing what would be asked takes the failure sentence away`() = runTest {
        val failure = ExpansionResult.Failure(FailureKind.REFUSED, "declined")
        val viewModel = newBlocker(newRepository())
        ask(viewModel)
        suggester.answer(failure)
        advanceUntilIdle()

        viewModel.onDescriptionChange("2026 Japanese Grand Prix, the race")
        assertEquals(SuggestionState.Idle, viewModel.uiState.value.suggestion)

        viewModel.suggestTerms()
        advanceUntilIdle()
        suggester.answer(failure)
        advanceUntilIdle()
        viewModel.onBreadthChange(Breadth.BROAD)
        assertEquals(SuggestionState.Idle, viewModel.uiState.value.suggestion)
    }

    // Typing the terms by hand.

    @Test
    fun `typing the terms myself goes to empty lists and keeps the description`() = runTest {
        val repository = newRepository()
        val viewModel = newBlocker(repository)
        viewModel.onDescriptionChange(" Season finale ")
        viewModel.onBreadthChange(Breadth.BROAD)

        viewModel.typeTermsMyself()

        val state = viewModel.uiState.value
        assertEquals(EditorStep.LISTS, state.step)
        assertEquals("Season finale", state.name)
        assertEquals("Season finale", state.description)
        assertEquals(Breadth.BROAD, state.breadth)
        assertTrue(state.hidesNothing)
        assertEquals(emptyList<Pair<String, Breadth>>(), suggester.calls)

        viewModel.onTermDraftChange(TermList.STRONG, "finale")
        viewModel.save()
        advanceUntilIdle()
        val saved = repository.blockers.first().single()
        assertEquals("Season finale", saved.description)
        assertEquals(listOf("finale"), saved.strongTerms)
        assertEquals(Breadth.BROAD, saved.breadth)
    }

    @Test
    fun `typing the terms myself works with no description and after a failure`() = runTest {
        val repository = newRepository()
        val withoutDescription = newBlocker(repository)
        withoutDescription.typeTermsMyself()
        assertEquals(EditorStep.LISTS, withoutDescription.uiState.value.step)
        assertEquals("", withoutDescription.uiState.value.name)
        assertFalse(withoutDescription.uiState.value.canSave)

        val afterFailure = newBlocker(repository)
        ask(afterFailure)
        suggester.answer(ExpansionResult.Failure(FailureKind.MISSING_API_KEY, "No API key has been set."))
        advanceUntilIdle()
        afterFailure.typeTermsMyself()
        assertEquals(EditorStep.LISTS, afterFailure.uiState.value.step)
        assertEquals(SuggestionState.Idle, afterFailure.uiState.value.suggestion)
        assertEquals("2026 Japanese Grand Prix", afterFailure.uiState.value.name)
    }

    // Going back.

    @Test
    fun `going back asks first only when a new blocker has lists that were never saved`() = runTest {
        val viewModel = newBlocker(newRepository())
        assertFalse(viewModel.uiState.value.backNeedsConfirming)

        ask(viewModel)
        // Back during the call just leaves, which abandons the call.
        assertFalse(viewModel.uiState.value.backNeedsConfirming)

        suggester.answer(ExpansionResult.Success(suggestedTerms))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.backNeedsConfirming)

        for (list in TermList.entries) {
            for (term in viewModel.uiState.value.list(list).terms) {
                viewModel.removeTerm(list, term)
            }
        }
        assertFalse(viewModel.uiState.value.backNeedsConfirming)
    }

    // The saved state.

    @Test
    fun `suggested lists and the edits to them survive the death of the process`() = runTest {
        val repository = newRepository()
        val savedStateHandle = SavedStateHandle()
        val before = newBlocker(repository, savedStateHandle)
        ask(before, breadth = Breadth.BROAD)
        suggester.answer(ExpansionResult.Success(suggestedTerms))
        advanceUntilIdle()
        before.moveTerm(TermList.STRONG, "Suzuka")
        before.onEnabledChange(false)
        before.onTermDraftChange(TermList.SOURCES, "half typed")
        advanceUntilIdle()

        val after = newBlocker(repository, savedStateHandle.afterProcessDeath())
        advanceUntilIdle()

        assertEquals(before.uiState.value, after.uiState.value)
        // Nothing was asked a second time, and nothing has been stored yet.
        assertEquals(1, suggester.calls.size)
        assertEquals(emptyList<Blocker>(), repository.blockers.first())

        after.save()
        advanceUntilIdle()
        val saved = repository.blockers.first().single()
        assertEquals("2026 Japanese Grand Prix", saved.description)
        assertEquals(listOf("Japanese Grand Prix", "#JapaneseGP"), saved.strongTerms)
        assertEquals(listOf("Suzuka", "Max", "podium", "P1"), saved.weakTerms)
        assertEquals(listOf("half typed", "FORMULA 1", "Sky Sports F1"), saved.sources)
        assertEquals(Breadth.BROAD, saved.breadth)
        assertFalse(saved.enabled)
    }

    @Test
    fun `a draft typed by hand survives the death of the process`() = runTest {
        val repository = newRepository()
        val savedStateHandle = SavedStateHandle()
        val before = newBlocker(repository, savedStateHandle)
        before.onDescriptionChange("Season finale")
        before.typeTermsMyself()
        before.onNameChange("Finale")
        before.onTermDraftChange(TermList.STRONG, "finale")
        before.addTerm(TermList.STRONG)
        before.onTermDraftChange(TermList.WEAK, "!!!")
        before.addTerm(TermList.WEAK)
        advanceUntilIdle()

        val after = newBlocker(repository, savedStateHandle.afterProcessDeath())
        advanceUntilIdle()

        assertEquals(before.uiState.value, after.uiState.value)
        assertEquals(listOf("finale"), after.uiState.value.strong.terms)
        assertTrue(after.uiState.value.weak.isDraftRefused)
    }

    @Test
    fun `a description survives the death of the process, and a call that was under way does not`() = runTest {
        val repository = newRepository()
        val savedStateHandle = SavedStateHandle()
        val before = newBlocker(repository, savedStateHandle)
        ask(before, breadth = Breadth.BROAD)
        assertEquals(SuggestionState.Working, before.uiState.value.suggestion)

        val after = newBlocker(repository, savedStateHandle.afterProcessDeath())
        advanceUntilIdle()

        // The call died with the process. The owner is back at the
        // description and can ask again.
        val state = after.uiState.value
        assertEquals(EditorStep.DESCRIBE, state.step)
        assertEquals("2026 Japanese Grand Prix", state.description)
        assertEquals(Breadth.BROAD, state.breadth)
        assertEquals(SuggestionState.Idle, state.suggestion)
        assertTrue(state.canSuggest)
        assertEquals(1, suggester.calls.size)
    }

    @Test
    fun `the edits to a stored blocker survive, and saving still replaces that blocker`() = runTest {
        val stored = Blocker(
            id = "race",
            name = "2026 Japanese Grand Prix",
            strongTerms = listOf("Japanese Grand Prix", "Suzuka"),
            enabled = true,
            createdAtMillis = 1_000,
            description = "The 2026 race at Suzuka",
            weakTerms = listOf("Max"),
        )
        val repository = newRepository()
        repository.save(stored)
        val savedStateHandle = SavedStateHandle()
        val before = BlockerEditorViewModel(repository, suggester, savedStateHandle, stored.id)
        advanceUntilIdle()
        before.onNameChange("Japanese GP")
        before.removeTerm(TermList.STRONG, "Suzuka")
        advanceUntilIdle()

        val after = BlockerEditorViewModel(repository, suggester, savedStateHandle.afterProcessDeath(), stored.id)
        // The stored blocker has to be read again before Save can replace it.
        assertTrue(after.uiState.value.isLoading)
        advanceUntilIdle()

        assertEquals(before.uiState.value, after.uiState.value)
        after.save()
        advanceUntilIdle()
        assertEquals(
            listOf(stored.copy(name = "Japanese GP", strongTerms = listOf("Japanese Grand Prix"))),
            repository.blockers.first(),
        )
    }

    @Test
    fun `a saved draft that cannot be read is ignored`() = runTest {
        val repository = newRepository()
        // Run one ViewModel to learn the name the draft is kept under.
        val savedStateHandle = SavedStateHandle()
        newBlocker(repository, savedStateHandle)
        advanceUntilIdle()
        val key = savedStateHandle.keys().single()

        val viewModel = newBlocker(repository, SavedStateHandle(mapOf(key to """{"step":"GONE"}""")))
        advanceUntilIdle()

        assertEquals(EditorStep.DESCRIBE, viewModel.uiState.value.step)
        assertEquals("", viewModel.uiState.value.description)
    }
}

/**
 * Stands in for the Claude API. A call waits, as the real one does for
 * minutes, until the test answers it with [answer].
 */
private class FakeTermSuggester : TermSuggester {

    /** What each call was given, in order. */
    val calls = mutableListOf<Pair<String, Breadth>>()

    /** How many calls were cancelled while they waited. */
    var abandonedCalls = 0
        private set

    private var waitingCall: CompletableDeferred<ExpansionResult>? = null

    override suspend fun suggest(description: String, breadth: Breadth): ExpansionResult {
        calls += description to breadth
        val call = CompletableDeferred<ExpansionResult>()
        waitingCall = call
        try {
            return call.await()
        } catch (e: CancellationException) {
            // Cancelling a coroutine makes whatever it is waiting in throw
            // this. It must be thrown on, or the coroutine would carry on.
            abandonedCalls++
            throw e
        }
    }

    /** Answers the call that is waiting. */
    fun answer(result: ExpansionResult) {
        checkNotNull(waitingCall) { "No call has been made." }.complete(result)
    }
}
