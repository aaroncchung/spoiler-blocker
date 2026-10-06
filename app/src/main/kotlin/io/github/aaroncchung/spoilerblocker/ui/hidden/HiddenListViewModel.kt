package io.github.aaroncchung.spoilerblocker.ui.hidden

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.aaroncchung.spoilerblocker.appContainer
import io.github.aaroncchung.spoilerblocker.data.HiddenNotification
import io.github.aaroncchung.spoilerblocker.data.HiddenNotificationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One row of the hidden list. Its title and text are only shown while [isRevealed] is true. */
data class HiddenRow(
    val notification: HiddenNotification,
    val isRevealed: Boolean,
)

/** What the hidden list screen shows. */
data class HiddenListUiState(
    /** True until the stored list has been read. */
    val isLoading: Boolean = true,
    /** Newest first. */
    val rows: List<HiddenRow> = emptyList(),
) {
    val allRevealed: Boolean
        get() = rows.isNotEmpty() && rows.all { it.isRevealed }
}

/**
 * Holds the state of the hidden list screen.
 *
 * What the list holds is the very thing the owner did not want to see, and
 * the owner may open it while a blocker is still on, only to find out whether
 * a friend wrote. So every row starts concealed and is revealed one at a
 * time, by a tap.
 *
 * Which rows are revealed is kept here and nowhere else. It is gone when the
 * screen is left, so the list is concealed again the next time.
 */
class HiddenListViewModel(private val repository: HiddenNotificationRepository) : ViewModel() {

    /** The ids of the revealed rows. */
    private val revealedIds = MutableStateFlow(emptySet<String>())

    // combine makes a new state whenever either of the two changes.
    val uiState: StateFlow<HiddenListUiState> =
        combine(repository.hiddenNotifications, revealedIds) { notifications, revealed ->
            HiddenListUiState(
                isLoading = false,
                rows = notifications.map { HiddenRow(it, isRevealed = it.id in revealed) },
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HiddenListUiState(),
        )

    /** Reveals the row of the notification with this [id], or conceals it again. */
    fun toggleRevealed(id: String) {
        revealedIds.update { revealed -> if (id in revealed) revealed - id else revealed + id }
    }

    /**
     * Reveals every row that is on screen now. A notification hidden after
     * this still arrives concealed.
     */
    fun revealAll() {
        revealedIds.value = uiState.value.rows.map { it.notification.id }.toSet()
    }

    fun concealAll() {
        revealedIds.value = emptySet()
    }

    /** Empties the stored list. */
    fun clearAll() {
        viewModelScope.launch {
            repository.clear()
            revealedIds.value = emptySet()
        }
    }

    companion object {
        /** Creates the ViewModel with the app's one hidden list. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { HiddenListViewModel(appContainer.hiddenNotificationRepository) }
        }
    }
}
