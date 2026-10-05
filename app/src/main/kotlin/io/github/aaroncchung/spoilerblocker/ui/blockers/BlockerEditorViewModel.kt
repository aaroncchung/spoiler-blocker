package io.github.aaroncchung.spoilerblocker.ui.blockers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.aaroncchung.spoilerblocker.appContainer
import io.github.aaroncchung.spoilerblocker.data.Blocker
import io.github.aaroncchung.spoilerblocker.data.BlockerRepository
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the editor screen shows. It is a draft: nothing is stored until Save,
 * and leaving the screen any other way throws it away.
 */
data class BlockerEditorUiState(
    /** True when creating a blocker, false when editing a stored one. */
    val isNew: Boolean,
    /** True while a stored blocker is being read. The form is hidden meanwhile. */
    val isLoading: Boolean,
    val name: String = "",
    /**
     * On by default, so that saving a new blocker starts it. If it started
     * off, forgetting the switch would mean a spoiler.
     */
    val enabled: Boolean = true,
    val terms: List<String> = emptyList(),
    /** The text in the term field that has not been added to [terms] yet. */
    val termDraft: String = "",
    /** True once the blocker is saved or deleted. The screen closes when it sees this. */
    val isFinished: Boolean = false,
) {
    // The screen can still be tapped while it fades out. Without the
    // isFinished check, Save pressed just after Delete would bring the
    // blocker back.
    val canSave: Boolean
        get() = !isLoading && !isFinished && name.isNotBlank()
}

/**
 * Holds the draft behind the editor screen.
 *
 * @param blockerId the blocker to edit, or null to create a new one.
 */
class BlockerEditorViewModel(
    private val repository: BlockerRepository,
    blockerId: String?,
) : ViewModel() {

    // A new blocker gets its id here, once, so that pressing Save twice
    // stores one blocker and not two.
    private val id: String = blockerId ?: UUID.randomUUID().toString()

    /** The stored blocker being edited. Stays null for a new one. */
    private var original: Blocker? = null

    // The usual pair: only this class can change the state, everyone else can
    // only read it.
    private val _uiState = MutableStateFlow(
        BlockerEditorUiState(isNew = blockerId == null, isLoading = blockerId != null),
    )
    val uiState: StateFlow<BlockerEditorUiState> = _uiState.asStateFlow()

    init {
        if (blockerId != null) {
            viewModelScope.launch { load() }
        }
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
            it.copy(
                isLoading = false,
                name = blocker.name,
                enabled = blocker.enabled,
                terms = blocker.strongTerms,
            )
        }
    }

    fun onNameChange(name: String) {
        _uiState.update { it.copy(name = name) }
    }

    fun onEnabledChange(enabled: Boolean) {
        _uiState.update { it.copy(enabled = enabled) }
    }

    fun onTermDraftChange(termDraft: String) {
        _uiState.update { it.copy(termDraft = termDraft) }
    }

    /** Moves the text in the term field into the list of terms. */
    fun addTerm() {
        _uiState.update { it.copy(terms = it.terms.withTerm(it.termDraft), termDraft = "") }
    }

    fun removeTerm(term: String) {
        _uiState.update { it.copy(terms = it.terms - term) }
    }

    fun save() {
        val state = _uiState.value
        if (!state.canSave) return

        val name = state.name.trim()
        // A term that was typed but never added is added now. Dropping it
        // without a word would leave a gap in the blocker.
        val terms = state.terms.withTerm(state.termDraft)
        // copy() keeps whatever else the stored blocker holds, such as its
        // creation time.
        val blocker = original?.copy(name = name, strongTerms = terms, enabled = state.enabled)
            ?: Blocker(
                id = id,
                name = name,
                strongTerms = terms,
                enabled = state.enabled,
                createdAtMillis = System.currentTimeMillis(),
            )

        viewModelScope.launch {
            repository.save(blocker)
            _uiState.update { it.copy(isFinished = true) }
        }
    }

    fun delete() {
        viewModelScope.launch {
            repository.delete(id)
            _uiState.update { it.copy(isFinished = true) }
        }
    }

    companion object {
        /** Creates the ViewModel with the app's one repository. */
        fun factory(blockerId: String?): ViewModelProvider.Factory = viewModelFactory {
            initializer { BlockerEditorViewModel(appContainer.blockerRepository, blockerId) }
        }
    }
}

/**
 * Returns the list with [typed] added at the end, trimmed. Text that is blank,
 * or already in the list in any mix of capitals, is not added.
 */
private fun List<String>.withTerm(typed: String): List<String> {
    val term = typed.trim()
    val isDuplicate = any { it.equals(term, ignoreCase = true) }
    return if (term.isEmpty() || isDuplicate) this else this + term
}
