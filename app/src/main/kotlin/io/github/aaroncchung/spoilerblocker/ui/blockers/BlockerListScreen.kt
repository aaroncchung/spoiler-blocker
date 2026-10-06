package io.github.aaroncchung.spoilerblocker.ui.blockers

import android.Manifest
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.aaroncchung.spoilerblocker.R
import io.github.aaroncchung.spoilerblocker.data.Blocker
import io.github.aaroncchung.spoilerblocker.notifications.isNotificationAccessGranted
import io.github.aaroncchung.spoilerblocker.notifications.openNotificationAccessSettings
import io.github.aaroncchung.spoilerblocker.notifications.requestListenerRebindIfDisconnected
import io.github.aaroncchung.spoilerblocker.status.PostPermissionRequest
import io.github.aaroncchung.spoilerblocker.status.PostPermissionStep
import io.github.aaroncchung.spoilerblocker.status.canPostNotifications
import io.github.aaroncchung.spoilerblocker.status.openAppNotificationSettings
import io.github.aaroncchung.spoilerblocker.status.postPermissionStep
import io.github.aaroncchung.spoilerblocker.status.wasPostPermissionRefused
import io.github.aaroncchung.spoilerblocker.ui.theme.SpoilerBlockerTheme

/**
 * The start screen: every blocker, each with a switch.
 *
 * @param onOpenEditor called with a blocker's id to edit it, or with null to
 *   create a new one.
 * @param onOpenHiddenList called to show the "hidden while blocking" list.
 */
@Composable
fun BlockerListScreen(
    onOpenEditor: (blockerId: String?) -> Unit,
    onOpenHiddenList: () -> Unit,
    viewModel: BlockerListViewModel = viewModel(factory = BlockerListViewModel.Factory),
) {
    // "WithLifecycle" stops collecting while the app is in the background.
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Notification access is given and taken away in the system settings, and
    // Android does not tell the app when it changes. So it is checked each
    // time the app comes to the front, which includes coming back from the
    // settings screen that the card's button opens.
    // The same goes for the permission to post notifications.
    val context = LocalContext.current
    // The screen is only ever shown by MainActivity, so there is an activity.
    val activity = checkNotNull(LocalActivity.current)
    var hasNotificationAccess by remember { mutableStateOf(isNotificationAccessGranted(context)) }
    var mayPost by remember { mutableStateOf(canPostNotifications(context)) }
    var refusedToPost by remember { mutableStateOf(wasPostPermissionRefused(activity)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasNotificationAccess = isNotificationAccessGranted(context)
        mayPost = canPostNotifications(context)
        refusedToPost = wasPostPermissionRefused(activity)
        requestListenerRebindIfDisconnected(context)
    }

    val anyBlockerOn = uiState.blockers.any { it.enabled }

    // rememberSaveable and not remember, so that turning the phone while
    // Android's dialog is up does not make the app ask a second time.
    var permissionRequest by rememberSaveable { mutableStateOf(PostPermissionRequest.NOT_ASKED) }
    // The launcher shows Android's dialog and calls back with the answer.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        mayPost = granted
        permissionRequest = PostPermissionRequest.ANSWERED
    }
    val permissionStep = postPermissionStep(
        blockerOn = anyBlockerOn,
        canPost = mayPost,
        refusedBefore = refusedToPost,
        request = permissionRequest,
    )
    // Showing a dialog is not something to do in the middle of drawing the
    // screen. A LaunchedEffect runs afterwards, each time the step changes.
    LaunchedEffect(permissionStep) {
        if (permissionStep == PostPermissionStep.ASK) {
            permissionRequest = PostPermissionRequest.ASKING
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    BlockerListContent(
        uiState = uiState,
        // Without notification access a blocker that is on hides nothing,
        // and nothing else in the app would say so.
        showNotificationAccessCard = !hasNotificationAccess && anyBlockerOn,
        showPostNotificationsCard = permissionStep == PostPermissionStep.SHOW_CARD,
        onOpenEditor = onOpenEditor,
        onOpenHiddenList = onOpenHiddenList,
        onOpenNotificationAccess = { openNotificationAccessSettings(context) },
        onOpenNotificationSettings = { openAppNotificationSettings(context) },
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
    showNotificationAccessCard: Boolean,
    showPostNotificationsCard: Boolean,
    onOpenEditor: (blockerId: String?) -> Unit,
    onOpenHiddenList: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onEnabledChange: (id: String, enabled: Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = onOpenHiddenList) {
                        val label = if (uiState.hiddenCount == 0) {
                            stringResource(R.string.blockers_hidden)
                        } else {
                            stringResource(R.string.blockers_hidden_count, uiState.hiddenCount)
                        }
                        Text(label)
                    }
                },
            )
        },
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
                if (showNotificationAccessCard) {
                    item(key = "notification access") {
                        SetupCard(
                            title = stringResource(R.string.access_card_title),
                            text = stringResource(R.string.access_card_text),
                            buttonText = stringResource(R.string.access_card_button),
                            onButtonClick = onOpenNotificationAccess,
                            // The colours Material keeps for "something is
                            // wrong": with this missing, nothing is hidden.
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        )
                    }
                }
                if (showPostNotificationsCard) {
                    item(key = "post notifications") {
                        // In a card's usual colours: blocking works without
                        // this, only the reminder is missing.
                        SetupCard(
                            title = stringResource(R.string.post_card_title),
                            text = stringResource(R.string.post_card_text),
                            buttonText = stringResource(R.string.post_card_button),
                            onButtonClick = onOpenNotificationSettings,
                        )
                    }
                }
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

/**
 * A card above the blockers that says what the app is not allowed to do, with
 * a button that opens the system settings screen where the owner can allow it.
 */
@Composable
private fun SetupCard(
    title: String,
    text: String,
    buttonText: String,
    onButtonClick: () -> Unit,
    colors: CardColors = CardDefaults.cardColors(),
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = colors,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onButtonClick) {
                Text(buttonText)
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
            // The start of the lists, strong terms first, cut off at the end
            // of the line. It is there to recognise the blocker by.
            val everything = blocker.strongTerms + blocker.weakTerms + blocker.sources
            val summary = if (everything.isEmpty()) {
                stringResource(R.string.blockers_no_terms)
            } else {
                everything.joinToString(", ")
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
                        weakTerms = listOf("Max", "podium"),
                        sources = listOf("FORMULA 1"),
                    ),
                    Blocker(
                        id = "2",
                        name = "Season finale",
                        strongTerms = emptyList(),
                        enabled = false,
                        createdAtMillis = 0,
                    ),
                ),
                hiddenCount = 3,
            ),
            showNotificationAccessCard = true,
            showPostNotificationsCard = true,
            onOpenEditor = {},
            onOpenHiddenList = {},
            onOpenNotificationAccess = {},
            onOpenNotificationSettings = {},
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
            showNotificationAccessCard = false,
            showPostNotificationsCard = false,
            onOpenEditor = {},
            onOpenHiddenList = {},
            onOpenNotificationAccess = {},
            onOpenNotificationSettings = {},
            onEnabledChange = { _, _ -> },
        )
    }
}
