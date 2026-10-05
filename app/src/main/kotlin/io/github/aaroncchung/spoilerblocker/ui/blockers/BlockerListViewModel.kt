package io.github.aaroncchung.spoilerblocker.ui.blockers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.aaroncchung.spoilerblocker.appContainer
import io.github.aaroncchung.spoilerblocker.data.Blocker
import io.github.aaroncchung.spoilerblocker.data.BlockerRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the blocker list screen shows. */
data class BlockerListUiState(
    /**
     * True until the stored blockers have been read. The screen shows nothing
     * meanwhile, so "No blockers yet." does not flash by on every launch.
     */
    val isLoading: Boolean = true,
    val blockers: List<Blocker> = emptyList(),
)

/** Holds the state of the blocker list screen. It outlives a screen rotation. */
class BlockerListViewModel(private val repository: BlockerRepository) : ViewModel() {

    val uiState: StateFlow<BlockerListUiState> = repository.blockers
        .map { blockers -> BlockerListUiState(isLoading = false, blockers = blockers) }
        .stateIn(
            scope = viewModelScope,
            // Stops watching the repository five seconds after the screen
            // stops watching this. The delay covers a screen rotation, when
            // the screen goes away and comes straight back.
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BlockerListUiState(),
        )

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(id, enabled) }
    }

    companion object {
        /** Creates the ViewModel with the app's one repository. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { BlockerListViewModel(appContainer.blockerRepository) }
        }
    }
}
