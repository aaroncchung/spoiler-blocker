package io.github.aaroncchung.spoilerblocker.ui.blockers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.aaroncchung.spoilerblocker.appContainer
import io.github.aaroncchung.spoilerblocker.data.Blocker
import io.github.aaroncchung.spoilerblocker.data.BlockerRepository
import io.github.aaroncchung.spoilerblocker.expansion.ExpansionResult
import io.github.aaroncchung.spoilerblocker.expansion.FailureKind
import io.github.aaroncchung.spoilerblocker.matcher.Breadth
import io.github.aaroncchung.spoilerblocker.matcher.hasMatchableText
import io.github.aaroncchung.spoilerblocker.suggestions.TermSuggester
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The three lists of a blocker. The screen uses this to tell the ViewModel
 * which list a tap was in. "Matching rules" in docs/ARCHITECTURE.md says what
 * each list does.
 */
enum class TermList { STRONG, WEAK, SOURCES }

/** One of the three lists as the editor shows it: its chips and its field. */
@Serializable
data class TermListState(
    /** Newest first, so that a term just added shows up next to the field. */
    val terms: List<String> = emptyList(),
    /** The text in the field that has not been added to [terms] yet. */
    val draft: String = "",
    /**
     * True when Add was pressed and [draft] was refused, because it has no
     * letter or digit and so could never be found. The text stays in the
     * field, and this goes back to false as soon as it is changed.
     */
    val isDraftRefused: Boolean = false,
)

/** The two parts of the editor screen. A stored blocker only ever shows [LISTS]. */
enum class EditorStep {
    /** A new blocker starts here: the owner describes it, and can have the lists suggested. */
    DESCRIBE,

    /** The name, the switch, the breadth and the three lists, with Save. */
    LISTS,
}

/** Where asking for suggested terms has got to. */
sealed interface SuggestionState {
    /** Nothing has been asked, or the last call was cancelled. */
    data object Idle : SuggestionState

    /** A call to the Claude API is under way. It can take minutes. */
    data object Working : SuggestionState

    /**
     * The last call failed.
     *
     * @property kind picks the sentence the screen shows, and says whether
     *   "Try again" is worth offering.
     * @property detail the cause in English, such as the API's own error
     *   text. Shown small, under the sentence.
     */
    data class Failed(val kind: FailureKind, val detail: String) : SuggestionState
}

/**
 * What the editor screen shows. It is a draft: nothing is stored until Save,
 * and leaving the screen any other way throws it away.
 */
data class BlockerEditorUiState(
    /** True when creating a blocker, false when editing a stored one. */
    val isNew: Boolean,
    /** True while a stored blocker is being read. The form is hidden meanwhile. */
    val isLoading: Boolean,
    val step: EditorStep,
    /**
     * What the owner typed in the first step. It is what is sent to the
     * Claude API, and it is stored with the blocker.
     */
    val description: String = "",
    val suggestion: SuggestionState = SuggestionState.Idle,
    val name: String = "",
    /**
     * On by default, so that saving a new blocker starts it. If it started
     * off, forgetting the switch would mean a spoiler.
     */
    val enabled: Boolean = true,
    val breadth: Breadth = Breadth.NARROW,
    val strong: TermListState = TermListState(),
    val weak: TermListState = TermListState(),
    val sources: TermListState = TermListState(),
    /**
     * True from the moment Save or Delete is pressed. Writing the file takes
     * a moment after that, and the screen can still be tapped while it fades
     * out. Without this, Save pressed just after Delete would bring the
     * blocker back.
     */
    val isClosing: Boolean = false,
    /** True once the save or delete is written. The screen closes when it sees this. */
    val isFinished: Boolean = false,
) {
    val canSuggest: Boolean
        get() = step == EditorStep.DESCRIBE && description.isNotBlank() && suggestion != SuggestionState.Working

    val canSave: Boolean
        get() = step == EditorStep.LISTS && !isLoading && !isClosing && name.isNotBlank()

    /** True while all three lists are empty. Such a blocker can be saved, but it hides nothing. */
    val hidesNothing: Boolean
        get() = strong.terms.isEmpty() && weak.terms.isEmpty() && sources.terms.isEmpty()

    /**
     * True when going back would throw away the lists of a blocker that was
     * never saved. Suggested lists cost money and minutes, and Back is one
     * swipe from the edge of the screen, so the screen asks first.
     */
    val backNeedsConfirming: Boolean
        get() = isNew && step == EditorStep.LISTS && !hidesNothing && !isClosing

    fun list(which: TermList): TermListState = when (which) {
        TermList.STRONG -> strong
        TermList.WEAK -> weak
        TermList.SOURCES -> sources
    }
}

/**
 * Holds the draft behind the editor screen.
 *
 * @param termSuggester suggests the lists of a new blocker from its
 *   description.
 * @param savedStateHandle a small store that Android keeps for this screen
 *   when it stops the app's process to free memory, and hands back when the
 *   owner returns. The draft is copied into it, so that suggested lists,
 *   which cost money and minutes, are still there. It is not kept when the
 *   owner leaves the screen or swipes the app away.
 * @param blockerId the blocker to edit, or null to create a new one.
 */
class BlockerEditorViewModel(
    private val repository: BlockerRepository,
    private val termSuggester: TermSuggester,
    private val savedStateHandle: SavedStateHandle,
    blockerId: String?,
) : ViewModel() {

    // A new blocker gets its id here, once, so that pressing Save twice
    // stores one blocker and not two.
    private val id: String = blockerId ?: UUID.randomUUID().toString()

    /** The stored blocker being edited. Stays null for a new one. */
    private var original: Blocker? = null

    /** The call to the Claude API that is under way, if any. Cancelling it abandons the call. */
    private var suggestionJob: Job? = null

    /** The draft from before the process was stopped. Null on an ordinary start. */
    private val restoredDraft: SavedDraft? = savedStateHandle.get<String>(DRAFT_KEY)?.let(::decodeDraft)

    // The usual pair: only this class can change the state, everyone else can
    // only read it.
    private val _uiState = MutableStateFlow(initialState(isNew = blockerId == null))
    val uiState: StateFlow<BlockerEditorUiState> = _uiState.asStateFlow()

    init {
        if (blockerId != null) {
            viewModelScope.launch { load() }
        }
        // Copies the draft into the saved state after every change. collect
        // never returns: it runs this for each new state for as long as the
        // ViewModel lives.
        viewModelScope.launch {
            _uiState.collect { state ->
                // While a stored blocker is still being read there is nothing
                // to keep yet.
                if (!state.isLoading) {
                    savedStateHandle[DRAFT_KEY] = Json.encodeToString(state.toSavedDraft())
                }
            }
        }
    }

    private fun initialState(isNew: Boolean): BlockerEditorUiState {
        val fresh = BlockerEditorUiState(
            isNew = isNew,
            isLoading = !isNew,
            step = if (isNew) EditorStep.DESCRIBE else EditorStep.LISTS,
        )
        return restoredDraft?.putInto(fresh) ?: fresh
    }

    private suspend fun load() {
        // first() takes the current list and stops watching. Later changes
        // must not overwrite what is being typed.
        val blocker = repository.blockers.first().firstOrNull { it.id == id }
        if (blocker == null) {
            // It was deleted in the meantime, so there is nothing to edit.
            _uiState.update { it.copy(isFinished = true) }
            return
        }
        original = blocker
        _uiState.update {
            if (restoredDraft != null) {
                // The form already holds what was being edited when the
                // process was stopped. That is newer than what is stored.
                it.copy(isLoading = false)
            } else {
                it.copy(
                    isLoading = false,
                    name = blocker.name,
                    enabled = blocker.enabled,
                    breadth = blocker.breadth,
                    strong = TermListState(blocker.strongTerms),
                    weak = TermListState(blocker.weakTerms),
                    sources = TermListState(blocker.sources),
                )
            }
        }
    }

    // In the first step, a change to what would be asked also takes away the
    // sentence about a call that failed: it was about the old question.

    fun onDescriptionChange(description: String) {
        _uiState.update { it.copy(description = description, suggestion = it.suggestion.withoutFailure()) }
    }

    fun onBreadthChange(breadth: Breadth) {
        _uiState.update { it.copy(breadth = breadth, suggestion = it.suggestion.withoutFailure()) }
    }

    /**
     * Asks the Claude API to suggest the three lists for the description.
     * When they arrive the screen moves on to the lists, filled in for the
     * owner to check. Nothing is stored: that still takes Save.
     */
    fun suggestTerms() {
        val state = _uiState.value
        if (!state.canSuggest) return
        // Taken now, so that the lists are shown with exactly what they were
        // asked for.
        val description = state.description.trim()
        val breadth = state.breadth
        _uiState.update { it.copy(suggestion = SuggestionState.Working) }

        // viewModelScope lives as long as the screen is on the back stack. A
        // rotation does not end it, so the call carries on. Leaving the
        // screen does, and that abandons the call.
        suggestionJob = viewModelScope.launch {
            when (val result = termSuggester.suggest(description, breadth)) {
                is ExpansionResult.Success -> _uiState.update {
                    it.copy(
                        step = EditorStep.LISTS,
                        suggestion = SuggestionState.Idle,
                        description = description,
                        name = description,
                        breadth = breadth,
                        strong = TermListState(result.terms.strong),
                        weak = TermListState(result.terms.weak),
                        sources = TermListState(result.terms.sources),
                    )
                }

                is ExpansionResult.Failure -> _uiState.update {
                    it.copy(suggestion = SuggestionState.Failed(result.kind, result.message))
                }
            }
        }
    }

    /** Abandons the call that is under way and goes back to the description. */
    fun cancelSuggestion() {
        suggestionJob?.cancel()
        suggestionJob = null
        _uiState.update {
            if (it.suggestion == SuggestionState.Working) it.copy(suggestion = SuggestionState.Idle) else it
        }
    }

    /** Moves on to the lists without asking for suggestions. They start empty. */
    fun typeTermsMyself() {
        cancelSuggestion()
        _uiState.update {
            if (it.step != EditorStep.DESCRIBE) return@update it
            val description = it.description.trim()
            it.copy(
                step = EditorStep.LISTS,
                suggestion = SuggestionState.Idle,
                description = description,
                name = description,
            )
        }
    }

    fun onNameChange(name: String) {
        _uiState.update { it.copy(name = name) }
    }

    fun onEnabledChange(enabled: Boolean) {
        _uiState.update { it.copy(enabled = enabled) }
    }

    fun onTermDraftChange(list: TermList, draft: String) {
        _uiState.update { state ->
            state.withList(list, state.list(list).copy(draft = draft, isDraftRefused = false))
        }
    }

    /** Moves the text in the field of [list] into that list. */
    fun addTerm(list: TermList) {
        _uiState.update { state -> state.withList(list, state.list(list).withDraftAdded()) }
    }

    fun removeTerm(list: TermList, term: String) {
        _uiState.update { state ->
            val listState = state.list(list)
            state.withList(list, listState.copy(terms = listState.terms - term))
        }
    }

    /**
     * Moves [term] out of the list [from] into the other list of terms:
     * strong to weak, or weak to strong. This is how the owner says "this
     * word is too common to block by itself" without typing it again. A
     * source is not a term and stays where it is.
     */
    fun moveTerm(from: TermList, term: String) {
        val to = when (from) {
            TermList.STRONG -> TermList.WEAK
            TermList.WEAK -> TermList.STRONG
            TermList.SOURCES -> return
        }
        _uiState.update { state ->
            val source = state.list(from)
            val target = state.list(to)
            // Nothing to do if the chip went away between the two taps.
            if (term !in source.terms) return@update state
            state
                .withList(from, source.copy(terms = source.terms - term))
                .withList(to, target.copy(terms = target.terms.withTerm(term)))
        }
    }

    fun save() {
        val state = _uiState.value
        if (!state.canSave) return
        // Set here, not in the coroutine below, so that it is already true
        // when the next tap arrives.
        _uiState.update { it.copy(isClosing = true) }

        val name = state.name.trim()
        // A term that was typed but never added is added now. Dropping it
        // without a word would leave a gap in the blocker. Text that could
        // never be found is the exception: leaving it out changes nothing.
        val strongTerms = state.strong.withDraftAdded().terms
        val weakTerms = state.weak.withDraftAdded().terms
        val sources = state.sources.withDraftAdded().terms
        // copy() keeps whatever else the stored blocker holds, such as its
        // creation time and its description.
        val blocker = original?.copy(
            name = name,
            strongTerms = strongTerms,
            enabled = state.enabled,
            weakTerms = weakTerms,
            sources = sources,
            breadth = state.breadth,
        ) ?: Blocker(
            id = id,
            name = name,
            strongTerms = strongTerms,
            enabled = state.enabled,
            createdAtMillis = System.currentTimeMillis(),
            description = state.description,
            weakTerms = weakTerms,
            sources = sources,
            breadth = state.breadth,
        )

        viewModelScope.launch {
            repository.save(blocker)
            _uiState.update { it.copy(isFinished = true) }
        }
    }

    fun delete() {
        if (_uiState.value.isClosing) return
        _uiState.update { it.copy(isClosing = true) }

        viewModelScope.launch {
            repository.delete(id)
            _uiState.update { it.copy(isFinished = true) }
        }
    }

    companion object {
        /** The name the draft is kept under in the saved state. */
        private const val DRAFT_KEY = "draft"

        /** Creates the ViewModel with the app's shared objects. */
        fun factory(blockerId: String?): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                BlockerEditorViewModel(
                    repository = appContainer.blockerRepository,
                    termSuggester = appContainer.termSuggester,
                    // Android hands a factory the means to make the handle
                    // that belongs to the screen the ViewModel is for.
                    savedStateHandle = createSavedStateHandle(),
                    blockerId = blockerId,
                )
            }
        }
    }
}

private fun SuggestionState.withoutFailure(): SuggestionState =
    if (this is SuggestionState.Failed) SuggestionState.Idle else this

private fun BlockerEditorUiState.withList(which: TermList, list: TermListState): BlockerEditorUiState =
    when (which) {
        TermList.STRONG -> copy(strong = list)
        TermList.WEAK -> copy(weak = list)
        TermList.SOURCES -> copy(sources = list)
    }

/**
 * Returns the list with the text in its field added, trimmed. Blank text
 * empties the field and adds nothing. Text that could never be found is
 * refused: it stays in the field, marked, so that the screen can say why.
 */
private fun TermListState.withDraftAdded(): TermListState {
    val term = draft.trim()
    return when {
        term.isEmpty() -> copy(draft = "")
        !hasMatchableText(term) -> copy(isDraftRefused = true)
        else -> copy(terms = terms.withTerm(term), draft = "")
    }
}

/**
 * Returns the list with [term] at the front. A term already in the list in
 * any mix of capitals is not added again.
 */
private fun List<String>.withTerm(term: String): List<String> {
    val isDuplicate = any { it.equals(term, ignoreCase = true) }
    return if (isDuplicate) this else listOf(term) + this
}

/**
 * The part of the screen's state that is the owner's work, and so is worth
 * keeping when Android stops the process. The rest says what the screen is
 * busy with, and starts again from nothing.
 *
 * It goes into the saved state as one piece of JSON text, the same way the
 * blockers go into their file. The saved state is meant for small things:
 * three full lists come to a few kilobytes, which is fine.
 */
@Serializable
private data class SavedDraft(
    val step: EditorStep,
    val description: String,
    val name: String,
    val enabled: Boolean,
    val breadth: Breadth,
    val strong: TermListState,
    val weak: TermListState,
    val sources: TermListState,
) {
    fun putInto(state: BlockerEditorUiState): BlockerEditorUiState = state.copy(
        step = step,
        description = description,
        name = name,
        enabled = enabled,
        breadth = breadth,
        strong = strong,
        weak = weak,
        sources = sources,
    )
}

private fun BlockerEditorUiState.toSavedDraft() = SavedDraft(
    step = step,
    description = description,
    name = name,
    enabled = enabled,
    breadth = breadth,
    strong = strong,
    weak = weak,
    sources = sources,
)

/**
 * Reads a draft back. A draft that cannot be read counts as no draft: it can
 * only be one that an older build of the app saved in another shape.
 */
private fun decodeDraft(text: String): SavedDraft? =
    try {
        Json.decodeFromString<SavedDraft>(text)
    } catch (e: SerializationException) {
        null
    }
