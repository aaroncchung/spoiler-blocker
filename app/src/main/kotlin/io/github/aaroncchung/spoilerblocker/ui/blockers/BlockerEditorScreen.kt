package io.github.aaroncchung.spoilerblocker.ui.blockers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
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
import androidx.compose.runtime.mutableStateOf
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
import io.github.aaroncchung.spoilerblocker.ui.theme.SpoilerBlockerTheme

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
        onTermDraftChange = viewModel::onTermDraftChange,
        onAddTerm = viewModel::addTerm,
        onRemoveTerm = viewModel::removeTerm,
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
    onTermDraftChange: (String) -> Unit,
    onAddTerm: () -> Unit,
    onRemoveTerm: (String) -> Unit,
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
                TermsEditor(
                    terms = uiState.terms,
                    termDraft = uiState.termDraft,
                    onTermDraftChange = onTermDraftChange,
                    onAddTerm = onAddTerm,
                    onRemoveTerm = onRemoveTerm,
                )
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
            // lets a screen reader say "On, switch" as one thing.
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

@Composable
private fun TermsEditor(
    terms: List<String>,
    termDraft: String,
    onTermDraftChange: (String) -> Unit,
    onAddTerm: () -> Unit,
    onRemoveTerm: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.editor_terms_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.editor_terms_helper),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = termDraft,
                onValueChange = onTermDraftChange,
                modifier = Modifier.weight(1f),
                // A placeholder, not a label like the name field has: a label
                // adds room above the box, which would push the Add button
                // off centre.
                placeholder = { Text(stringResource(R.string.editor_term_field)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                // Done would normally close the keyboard. Adding the term
                // instead keeps it open for the next one.
                keyboardActions = KeyboardActions(onDone = { onAddTerm() }),
            )
            Button(onClick = onAddTerm, enabled = termDraft.isNotBlank()) {
                Text(stringResource(R.string.editor_term_add))
            }
        }
        if (terms.isEmpty()) {
            Text(
                stringResource(R.string.editor_terms_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // A row that wraps onto the next line when it runs out of width.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (term in terms) {
                    InputChip(
                        selected = false,
                        onClick = { onRemoveTerm(term) },
                        label = { Text(term, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = {
                            Icon(
                                painterResource(R.drawable.ic_close),
                                contentDescription = stringResource(R.string.editor_term_remove, term),
                                modifier = Modifier.size(InputChipDefaults.IconSize),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BlockerEditorPreview() {
    SpoilerBlockerTheme {
        BlockerEditorContent(
            uiState = BlockerEditorUiState(
                isNew = false,
                isLoading = false,
                name = "2026 Japanese Grand Prix",
                terms = listOf("Japanese Grand Prix", "Suzuka", "#JapaneseGP"),
                termDraft = "Honda",
            ),
            onNameChange = {},
            onEnabledChange = {},
            onTermDraftChange = {},
            onAddTerm = {},
            onRemoveTerm = {},
            onSave = {},
            onDelete = {},
            onBack = {},
        )
    }
}
