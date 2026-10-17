package io.github.aaroncchung.spoilerblocker.ui.blockers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.aaroncchung.spoilerblocker.appContainer
import io.github.aaroncchung.spoilerblocker.data.Blocker
import io.github.aaroncchung.spoilerblocker.data.BlockerRepository
import io.github.aaroncchung.spoilerblocker.data.HiddenNotificationRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the blocker list screen shows. */
data class BlockerListUiState(
    /**
     * True until both the stored blockers and the hidden list have been read.
     * The screen shows nothing meanwhile, so "No blockers yet." does not
     * flash by on every launch.
     */
    val isLoading: Boolean = true,
    val blockers: List<Blocker> = emptyList(),
    /** How many notifications are in the hidden list. 0 if the list cannot be read. */
    val hiddenCount: Int = 0,
)

/** Holds the state of the blocker list screen. It outlives a screen rotation. */
class BlockerListViewModel(
    private val repository: BlockerRepository,
    hiddenNotificationRepository: HiddenNotificationRepository,
) : ViewModel() {

    // combine makes a new state whenever either of the two lists changes.
    //
    // If either list fails, combine fails as a whole, the screen never gets
    // its blockers, and the exception crashes the app. For the blockers that
    // is on purpose: see CURRENT_VERSION in BlockerRepository.kt. The hidden
    // list only gives the count on a button, which must not cost the owner
    // the switches that turn the blockers off. So a hidden list that cannot
    // be read counts as empty here, and the button shows no count. catch is
    // on the hidden list alone, so a failure of the blockers still gets
    // through. Opening the hidden list screen still crashes the app on such
    // a file, so the failure is not lost.
    val uiState: StateFlow<BlockerListUiState> =
        combine(
            repository.blockers,
            hiddenNotificationRepository.hiddenNotifications.catch { emit(emptyList()) },
        ) { blockers, hiddenNotifications ->
            BlockerListUiState(
                isLoading = false,
                blockers = blockers,
                hiddenCount = hiddenNotifications.size,
            )
        }.stateIn(
            scope = viewModelScope,
            // Stops watching the repositories five seconds after the screen
            // stops watching this. The delay covers a screen rotation, when
            // the screen goes away and comes straight back.
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BlockerListUiState(),
        )

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(id, enabled) }
    }

    companion object {
        /** Creates the ViewModel with the app's repositories. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                BlockerListViewModel(
                    appContainer.blockerRepository,
                    appContainer.hiddenNotificationRepository,
                )
            }
        }
    }
}
