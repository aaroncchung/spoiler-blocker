package io.github.aaroncchung.spoilerblocker.ui.blockers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.aaroncchung.spoilerblocker.R
import io.github.aaroncchung.spoilerblocker.data.Blocker
import io.github.aaroncchung.spoilerblocker.ui.theme.SpoilerBlockerTheme

/**
 * The start screen: every blocker, each with a switch.
 *
 * @param onOpenEditor called with a blocker's id to edit it, or with null to
 *   create a new one.
 */
@Composable
fun BlockerListScreen(
    onOpenEditor: (blockerId: String?) -> Unit,
    viewModel: BlockerListViewModel = viewModel(factory = BlockerListViewModel.Factory),
) {
    // "WithLifecycle" stops collecting while the app is in the background.
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    BlockerListContent(
        uiState = uiState,
        onOpenEditor = onOpenEditor,
        onEnabledChange = viewModel::setEnabled,
    )
}

/**
 * The screen without its ViewModel. Everything it shows comes in as
 * parameters, which is what lets the previews below draw it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockerListContent(
    uiState: BlockerListUiState,
    onOpenEditor: (blockerId: String?) -> Unit,
    onEnabledChange: (id: String, enabled: Boolean) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onOpenEditor(null) },
                // Material hides this button's text from screen readers, so
                // the icon has to carry the label for them.
                icon = {
                    Icon(
                        painterResource(R.drawable.ic_add),
                        contentDescription = stringResource(R.string.blockers_new),
                    )
                },
                text = { Text(stringResource(R.string.blockers_new)) },
            )
        },
    ) { innerPadding ->
        // innerPadding is the room taken by the top bar and by the system
        // bars. The app draws edge to edge, so content that ignored it would
        // sit underneath them.
        when {
            // Reading the file takes a few milliseconds. Nothing is drawn
            // until it is done.
            uiState.isLoading -> Unit

            uiState.blockers.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.blockers_empty))
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                // Lets the last row scroll clear of the floating button.
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                // The key tells Compose which row is which when the list changes.
                items(uiState.blockers, key = { it.id }) { blocker ->
                    BlockerRow(
                        blocker = blocker,
                        onClick = { onOpenEditor(blocker.id) },
                        onEnabledChange = { enabled -> onEnabledChange(blocker.id, enabled) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BlockerRow(
    blocker: Blocker,
    onClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(
            // Without a label a screen reader only says "double tap to
            // activate", which does not say that the row opens the editor.
            onClickLabel = stringResource(R.string.blockers_row_action),
            onClick = onClick,
        ),
        headlineContent = {
            Text(blocker.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            val summary = if (blocker.strongTerms.isEmpty()) {
                stringResource(R.string.blockers_no_terms)
            } else {
                blocker.strongTerms.joinToString(", ")
            }
            Text(summary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Switch(
                checked = blocker.enabled,
                onCheckedChange = onEnabledChange,
                // A screen reader says this before "switch, on", so that each
                // switch says which blocker it belongs to.
                modifier = Modifier.semantics { contentDescription = blocker.name },
            )
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun BlockerListPreview() {
    SpoilerBlockerTheme {
        BlockerListContent(
            uiState = BlockerListUiState(
                isLoading = false,
                blockers = listOf(
                    Blocker(
                        id = "1",
                        name = "2026 Japanese Grand Prix",
                        strongTerms = listOf("Japanese Grand Prix", "Suzuka", "#JapaneseGP"),
                        enabled = true,
                        createdAtMillis = 0,
                    ),
                    Blocker(
                        id = "2",
                        name = "Season finale",
                        strongTerms = emptyList(),
                        enabled = false,
                        createdAtMillis = 0,
                    ),
                ),
            ),
            onOpenEditor = {},
            onEnabledChange = { _, _ -> },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BlockerListEmptyPreview() {
    SpoilerBlockerTheme {
        BlockerListContent(
            uiState = BlockerListUiState(isLoading = false),
            onOpenEditor = {},
            onEnabledChange = { _, _ -> },
        )
    }
}
