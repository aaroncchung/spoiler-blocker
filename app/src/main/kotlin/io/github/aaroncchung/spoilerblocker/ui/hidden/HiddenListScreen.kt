package io.github.aaroncchung.spoilerblocker.ui.hidden

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.aaroncchung.spoilerblocker.R
import io.github.aaroncchung.spoilerblocker.data.HiddenNotification
import io.github.aaroncchung.spoilerblocker.ui.theme.SpoilerBlockerTheme

/**
 * The "hidden while blocking" list: every notification that was dismissed
 * because it matched a blocker, newest first.
 *
 * @param onBack called to leave the screen.
 */
@Composable
fun HiddenListScreen(
    onBack: () -> Unit,
    viewModel: HiddenListViewModel = viewModel(factory = HiddenListViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HiddenListContent(
        uiState = uiState,
        onBack = onBack,
        onRowClick = viewModel::toggleRevealed,
        onRevealAll = viewModel::revealAll,
        onConcealAll = viewModel::concealAll,
        onClearAll = viewModel::clearAll,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HiddenListContent(
    uiState: HiddenListUiState,
    onBack: () -> Unit,
    onRowClick: (id: String) -> Unit,
    onRevealAll: () -> Unit,
    onConcealAll: () -> Unit,
    onClearAll: () -> Unit,
) {
    var showClearDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hidden_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.hidden_back),
                        )
                    }
                },
                actions = {
                    if (uiState.rows.isNotEmpty()) {
                        if (uiState.allRevealed) {
                            TextButton(onClick = onConcealAll) {
                                Text(stringResource(R.string.hidden_conceal_all))
                            }
                        } else {
                            TextButton(onClick = onRevealAll) {
                                Text(stringResource(R.string.hidden_reveal_all))
                            }
                        }
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(
                                painterResource(R.drawable.ic_delete),
                                contentDescription = stringResource(R.string.hidden_clear_all),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            uiState.isLoading -> Unit

            uiState.rows.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.hidden_empty))
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(uiState.rows, key = { it.notification.id }) { row ->
                    HiddenNotificationRow(row = row, onClick = { onRowClick(row.notification.id) })
                    HorizontalDivider()
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.hidden_clear_title)) },
            text = { Text(stringResource(R.string.hidden_clear_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        onClearAll()
                    },
                ) {
                    Text(stringResource(R.string.hidden_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.hidden_clear_cancel))
                }
            },
        )
    }
}

/**
 * One hidden notification. Until it is tapped it shows only where and when
 * it came from and which blocker hid it. The title, the text and the term
 * that was found are the spoiler, so they stay out of sight.
 */
@Composable
private fun HiddenNotificationRow(row: HiddenRow, onClick: () -> Unit) {
    val notification = row.notification
    val context = LocalContext.current
    val time = DateUtils.formatDateTime(
        context,
        notification.hiddenAtMillis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                // A screen reader says this after "Double tap to".
                onClickLabel = stringResource(
                    if (row.isRevealed) R.string.hidden_row_conceal else R.string.hidden_row_reveal,
                ),
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.hidden_app_and_time, notification.appName, time),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (row.isRevealed) {
            if (notification.title.isNotBlank()) {
                Text(notification.title, style = MaterialTheme.typography.titleMedium)
            }
            if (notification.text.isNotBlank()) {
                Text(notification.text, style = MaterialTheme.typography.bodyMedium)
            }
            val reason = if (notification.hiddenWithGroup) {
                R.string.hidden_reason_group
            } else {
                R.string.hidden_reason_term
            }
            Text(
                stringResource(reason, notification.blockerName, notification.matchedTerm),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OpenAppButton(notification, modifier = Modifier.align(Alignment.End))
        } else {
            Text(
                stringResource(R.string.hidden_concealed),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.hidden_by, notification.blockerName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Opens the app that sent the notification. This is as close as the list can
 * get to a tap on the notification itself: what that tap would have opened
 * went away with the notification.
 */
@Composable
private fun OpenAppButton(notification: HiddenNotification, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Null if the app has no launcher icon or is no longer installed. Then
    // there is nothing to open and no button. remember keeps the answer, so
    // Android is asked once and not every time the row is drawn.
    val launchIntent = remember(notification.packageName) {
        context.packageManager.getLaunchIntentForPackage(notification.packageName)
    }
    if (launchIntent != null) {
        TextButton(onClick = { context.startActivity(launchIntent) }, modifier = modifier) {
            Text(stringResource(R.string.hidden_open_app, notification.appName))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HiddenListPreview() {
    val message = HiddenNotification(
        id = "1",
        hiddenAtMillis = 1_791_239_695_165,
        notificationKey = "0|com.example.chat|1|null|10001",
        packageName = "com.example.chat",
        appName = "Chat",
        title = "Alex",
        text = "Did you watch Suzuka? What a finish!",
        blockerId = "race",
        blockerName = "2026 Japanese Grand Prix",
        matchedTerm = "Suzuka",
    )
    SpoilerBlockerTheme {
        HiddenListContent(
            uiState = HiddenListUiState(
                isLoading = false,
                rows = listOf(
                    HiddenRow(message, isRevealed = true),
                    HiddenRow(message.copy(id = "2", appName = "News"), isRevealed = false),
                ),
            ),
            onBack = {},
            onRowClick = {},
            onRevealAll = {},
            onConcealAll = {},
            onClearAll = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HiddenListEmptyPreview() {
    SpoilerBlockerTheme {
        HiddenListContent(
            uiState = HiddenListUiState(isLoading = false),
            onBack = {},
            onRowClick = {},
            onRevealAll = {},
            onConcealAll = {},
            onClearAll = {},
        )
    }
}
