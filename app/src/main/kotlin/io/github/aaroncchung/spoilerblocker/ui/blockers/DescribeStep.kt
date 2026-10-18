package io.github.aaroncchung.spoilerblocker.ui.blockers

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.aaroncchung.spoilerblocker.R
import io.github.aaroncchung.spoilerblocker.expansion.FailureKind
import io.github.aaroncchung.spoilerblocker.matcher.Breadth
import io.github.aaroncchung.spoilerblocker.ui.theme.SpoilerBlockerTheme

/**
 * The first step of a new blocker: the owner describes the topic, chooses a
 * breadth, and then either has the lists suggested or goes on to type them.
 * See "Creating a blocker" in docs/ARCHITECTURE.md.
 */
@Composable
internal fun DescribeStep(
    description: String,
    breadth: Breadth,
    suggestion: SuggestionState,
    canSuggest: Boolean,
    onDescriptionChange: (String) -> Unit,
    onBreadthChange: (Breadth) -> Unit,
    onSuggest: () -> Unit,
    onCancel: () -> Unit,
    onTypeMyself: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The description and the breadth are what the call was made with, so
    // they stay as they are until it has ended.
    val isWorking = suggestion == SuggestionState.Working

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.describe_intro), style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isWorking,
            label = { Text(stringResource(R.string.describe_field)) },
            // Under the field, where it stays in view. A placeholder would
            // only appear once the field has been tapped.
            supportingText = { Text(stringResource(R.string.describe_example)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        BreadthChoice(breadth = breadth, onBreadthChange = onBreadthChange, enabled = !isWorking)

        when (suggestion) {
            SuggestionState.Idle -> {
                Text(
                    stringResource(R.string.describe_what_is_sent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Actions(
                    suggestLabel = R.string.describe_suggest,
                    canSuggest = canSuggest,
                    onSuggest = onSuggest,
                    onTypeMyself = onTypeMyself,
                )
            }

            SuggestionState.Working -> {
                // With no progress value given, the bar just keeps moving.
                // There is nothing to measure: the reply comes all at once.
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.describe_working), style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.describe_cancel))
                }
            }

            is SuggestionState.Failed -> {
                Text(
                    stringResource(failureSentence(suggestion.kind)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                // The cause as the expansion module reports it. It is in
                // English and written for a developer, so it is small print.
                Text(
                    stringResource(R.string.describe_failure_detail, suggestion.detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Actions(
                    // "Try again" is not offered when asking the same thing
                    // again cannot work, for example while the key is
                    // missing. Changing the description brings "Suggest
                    // terms" back.
                    suggestLabel = if (suggestion.kind.canRetry) R.string.describe_try_again else null,
                    canSuggest = canSuggest,
                    onSuggest = onSuggest,
                    onTypeMyself = onTypeMyself,
                )
            }
        }
    }
}

/**
 * The two ways on from the description.
 *
 * @param suggestLabel what the button that asks for terms says, or null to
 *   leave that button out.
 */
@Composable
private fun Actions(
    @StringRes suggestLabel: Int?,
    canSuggest: Boolean,
    onSuggest: () -> Unit,
    onTypeMyself: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (suggestLabel != null) {
            Button(onClick = onSuggest, enabled = canSuggest) {
                Text(stringResource(suggestLabel))
            }
        }
        TextButton(onClick = onTypeMyself) {
            Text(stringResource(R.string.describe_type_myself))
        }
    }
}

/**
 * The sentence the screen shows for each way that asking for terms can fail.
 *
 * There is no `else` branch on purpose. When the expansion module gains a
 * kind of failure, this stops compiling until the new kind has a sentence.
 */
@StringRes
internal fun failureSentence(kind: FailureKind): Int = when (kind) {
    FailureKind.MISSING_API_KEY -> R.string.suggest_failed_missing_key
    FailureKind.NO_NETWORK -> R.string.suggest_failed_no_network
    FailureKind.TIMEOUT -> R.string.suggest_failed_timeout
    FailureKind.AUTH -> R.string.suggest_failed_auth
    FailureKind.RATE_LIMITED -> R.string.suggest_failed_rate_limited
    FailureKind.SERVER_ERROR -> R.string.suggest_failed_server_error
    FailureKind.HTTP_OTHER -> R.string.suggest_failed_http_other
    FailureKind.REFUSED -> R.string.suggest_failed_refused
    FailureKind.BAD_REPLY -> R.string.suggest_failed_bad_reply
    FailureKind.NOTHING_FOUND -> R.string.suggest_failed_nothing_found
}

/** What the three previews below share. */
@Composable
private fun DescribeStepPreview(suggestion: SuggestionState) {
    SpoilerBlockerTheme {
        DescribeStep(
            description = "2026 Japanese Grand Prix",
            breadth = Breadth.NARROW,
            suggestion = suggestion,
            canSuggest = suggestion != SuggestionState.Working,
            onDescriptionChange = {},
            onBreadthChange = {},
            onSuggest = {},
            onCancel = {},
            onTypeMyself = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DescribeStepIdlePreview() = DescribeStepPreview(SuggestionState.Idle)

@Preview(showBackground = true)
@Composable
private fun DescribeStepWorkingPreview() = DescribeStepPreview(SuggestionState.Working)

@Preview(showBackground = true)
@Composable
private fun DescribeStepFailedPreview() = DescribeStepPreview(
    SuggestionState.Failed(FailureKind.NO_NETWORK, "Could not reach the Claude API: no route to host"),
)
