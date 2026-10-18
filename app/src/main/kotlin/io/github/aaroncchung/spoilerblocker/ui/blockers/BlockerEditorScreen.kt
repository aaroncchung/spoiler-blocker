package io.github.aaroncchung.spoilerblocker.ui.blockers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.aaroncchung.spoilerblocker.R
import io.github.aaroncchung.spoilerblocker.matcher.Breadth
import io.github.aaroncchung.spoilerblocker.ui.theme.SpoilerBlockerTheme

/**
 * How many chips a list shows until "Show all" is pressed. A suggested list
 * can have 80 terms, and three such lists would bury the fields between them.
 */
private const val CHIPS_SHOWN_AT_FIRST = 12

/**
 * Creates a blocker, or edits or deletes a stored one.
 *
 * @param blockerId the blocker to edit, or null to create a new one.
 * @param onClose called to leave the screen: on Back, and after a save or a
 *   delete.
 */
@Composable
fun BlockerEditorScreen(
    blockerId: String?,
    onClose: () -> Unit,
    viewModel: BlockerEditorViewModel = viewModel(factory = BlockerEditorViewModel.factory(blockerId)),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Runs again whenever isFinished changes. Saving and deleting take a
    // moment, so the screen closes when the ViewModel says they are done.
    LaunchedEffect(uiState.isFinished) {
        if (uiState.isFinished) onClose()
    }

    BlockerEditorContent(
        uiState = uiState,
        onNameChange = viewModel::onNameChange,
        onEnabledChange = viewModel::onEnabledChange,
        onBreadthChange = viewModel::onBreadthChange,
        onTermDraftChange = viewModel::onTermDraftChange,
        onAddTerm = viewModel::addTerm,
        onRemoveTerm = viewModel::removeTerm,
        onMoveTerm = viewModel::moveTerm,
        onSave = viewModel::save,
        onDelete = viewModel::delete,
        onBack = onClose,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockerEditorContent(
    uiState: BlockerEditorUiState,
    onNameChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onBreadthChange: (Breadth) -> Unit,
    onTermDraftChange: (TermList, String) -> Unit,
    onAddTerm: (TermList) -> Unit,
    onRemoveTerm: (TermList, String) -> Unit,
    onMoveTerm: (TermList, String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    // rememberSaveable keeps the dialog open through a screen rotation. Plain
    // remember would forget it.
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = if (uiState.isNew) R.string.editor_title_new else R.string.editor_title_edit
                    Text(stringResource(title))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.editor_back),
                        )
                    }
                },
                actions = {
                    if (!uiState.isNew) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                painterResource(R.drawable.ic_delete),
                                contentDescription = stringResource(R.string.editor_delete),
                            )
                        }
                    }
                    // Save is in the top bar so that the keyboard never covers it.
                    TextButton(onClick = onSave, enabled = uiState.canSave) {
                        Text(stringResource(R.string.editor_save))
                    }
                },
            )
        },
    ) { innerPadding ->
        if (!uiState.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    // Marks the system bars as dealt with, so that imePadding
                    // adds only what the keyboard covers beyond them.
                    .consumeWindowInsets(innerPadding)
                    // Shrinks the form to the space above the keyboard. It
                    // scrolls, so every field can still be reached.
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = uiState.name,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.editor_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                EnabledRow(enabled = uiState.enabled, onEnabledChange = onEnabledChange)
                if (uiState.hidesNothing) {
                    Text(
                        stringResource(R.string.editor_terms_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BreadthChoice(breadth = uiState.breadth, onBreadthChange = onBreadthChange)
                for (list in TermList.entries) {
                    // key ties what each editor remembers, such as "Show
                    // all", to its list and not to its place in the loop.
                    key(list) {
                        TermListEditor(
                            list = list,
                            state = uiState.list(list),
                            breadth = uiState.breadth,
                            onDraftChange = { draft -> onTermDraftChange(list, draft) },
                            onAdd = { onAddTerm(list) },
                            onRemove = { term -> onRemoveTerm(list, term) },
                            onMove = { term -> onMoveTerm(list, term) },
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.editor_delete_title)) },
            text = { Text(stringResource(R.string.editor_delete_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                ) {
                    Text(stringResource(R.string.editor_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.editor_delete_cancel))
                }
            },
        )
    }
}

@Composable
private fun EnabledRow(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The whole row is the switch, which makes it easy to hit and
            // lets a screen reader say "On, Blocking, switch" as one thing.
            .toggleable(value = enabled, role = Role.Switch, onValueChange = onEnabledChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.editor_enabled),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        // null because the row handles the tap.
        Switch(checked = enabled, onCheckedChange = null)
    }
}

/**
 * One of the three lists: what it does, a field to add to it, and its chips.
 *
 * @param breadth decides what the weak terms' line says, because the rule
 *   for weak terms depends on it.
 */
@Composable
private fun TermListEditor(
    list: TermList,
    state: TermListState,
    breadth: Breadth,
    onDraftChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onMove: (String) -> Unit,
) {
    val title = when (list) {
        TermList.STRONG -> R.string.editor_strong_title
        TermList.WEAK -> R.string.editor_weak_title
        TermList.SOURCES -> R.string.editor_sources_title
    }
    val helper = when (list) {
        TermList.STRONG -> R.string.editor_strong_helper
        TermList.WEAK -> when (breadth) {
            Breadth.NARROW -> R.string.editor_weak_helper_narrow
            Breadth.BROAD -> R.string.editor_weak_helper_broad
        }
        TermList.SOURCES -> R.string.editor_sources_helper
    }
    val placeholder = when (list) {
        TermList.STRONG, TermList.WEAK -> R.string.editor_term_field
        TermList.SOURCES -> R.string.editor_source_field
    }
    // Where "Move" in a chip's menu sends a term. Null for a source, which
    // has nowhere to go.
    val moveLabel = when (list) {
        TermList.STRONG -> R.string.editor_term_to_weak
        TermList.WEAK -> R.string.editor_term_to_strong
        TermList.SOURCES -> null
    }

    var showAll by rememberSaveable { mutableStateOf(false) }
    // The term whose menu is open, if any.
    var menuTerm by remember { mutableStateOf<String?>(null) }
    // Marks where the "not added" line is, so that the form can scroll to it.
    val refusalPlace = remember { BringIntoViewRequester() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title, state.terms.size), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(helper),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                // A placeholder, not a label like the name field has: a label
                // adds room above the box, which would push the Add button
                // off centre.
                placeholder = { Text(stringResource(placeholder)) },
                isError = state.isDraftRefused,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                // Done would normally close the keyboard. Adding the term
                // instead keeps it open for the next one.
                keyboardActions = KeyboardActions(onDone = { onAdd() }),
            )
            Button(onClick = onAdd, enabled = state.draft.isNotBlank()) {
                Text(stringResource(R.string.editor_term_add))
            }
        }
        if (state.isDraftRefused) {
            // Under the row and not inside the field, for the same reason as
            // the placeholder: the field must keep its height.
            Text(
                stringResource(R.string.editor_term_refused),
                modifier = Modifier.bringIntoViewRequester(refusalPlace),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            // The keyboard is usually open at this point, with the field
            // sitting right on top of it, so this line would be out of
            // sight. This scrolls the form just far enough to show it. The
            // effect runs once, when the line appears.
            LaunchedEffect(Unit) { refusalPlace.bringIntoView() }
        }

        val shownTerms = if (showAll) state.terms else state.terms.take(CHIPS_SHOWN_AT_FIRST)
        // A row that wraps onto the next line when it runs out of width.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (term in shownTerms) {
                // A Box so that the menu opens next to its own chip.
                Box {
                    // A tap opens a menu and does not remove the term
                    // itself. A chip is a small target in a long list, and
                    // a term removed by a stray tap would not be missed
                    // until something slipped through.
                    InputChip(
                        selected = false,
                        onClick = { menuTerm = term },
                        label = { Text(term, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                    if (menuTerm == term) {
                        DropdownMenu(expanded = true, onDismissRequest = { menuTerm = null }) {
                            if (moveLabel != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(moveLabel)) },
                                    onClick = {
                                        menuTerm = null
                                        onMove(term)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_term_remove)) },
                                onClick = {
                                    menuTerm = null
                                    onRemove(term)
                                },
                            )
                        }
                    }
                }
            }
        }
        if (state.terms.size > CHIPS_SHOWN_AT_FIRST) {
            TextButton(onClick = { showAll = !showAll }) {
                val label = if (showAll) {
                    stringResource(R.string.editor_terms_show_fewer)
                } else {
                    stringResource(R.string.editor_terms_show_all, state.terms.size)
                }
                Text(label)
            }
        }
    }
}

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun BlockerEditorPreview() {
    SpoilerBlockerTheme {
        BlockerEditorContent(
            uiState = BlockerEditorUiState(
                isNew = false,
                isLoading = false,
                name = "2026 Japanese Grand Prix",
                strong = TermListState(
                    terms = listOf("Japanese Grand Prix", "Suzuka", "#JapaneseGP"),
                    draft = "Honda",
                ),
                weak = TermListState(terms = listOf("Max", "podium", "P1"), draft = "!!!", isDraftRefused = true),
                sources = TermListState(terms = listOf("FORMULA 1", "Sky Sports F1")),
            ),
            onNameChange = {},
            onEnabledChange = {},
            onBreadthChange = {},
            onTermDraftChange = { _, _ -> },
            onAddTerm = {},
            onRemoveTerm = { _, _ -> },
            onMoveTerm = { _, _ -> },
            onSave = {},
            onDelete = {},
            onBack = {},
        )
    }
}
