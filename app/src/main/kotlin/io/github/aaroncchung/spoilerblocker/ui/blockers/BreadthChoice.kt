package io.github.aaroncchung.spoilerblocker.ui.blockers

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.aaroncchung.spoilerblocker.R
import io.github.aaroncchung.spoilerblocker.matcher.Breadth

/**
 * The choice between a narrow and a broad blocker (decision 5 in
 * docs/ARCHITECTURE.md).
 *
 * @param enabled false while the choice cannot be changed, which is while
 *   terms are being asked for with it.
 */
@Composable
fun BreadthChoice(
    breadth: Breadth,
    onBreadthChange: (Breadth) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier = modifier) {
        Text(stringResource(R.string.breadth_title), style = MaterialTheme.typography.titleMedium)
        // selectableGroup tells a screen reader that the two rows are one
        // choice, so it can say "1 of 2".
        Column(modifier = Modifier.selectableGroup()) {
            BreadthOption(
                title = R.string.breadth_narrow,
                explanation = R.string.breadth_narrow_explanation,
                selected = breadth == Breadth.NARROW,
                enabled = enabled,
                onClick = { onBreadthChange(Breadth.NARROW) },
            )
            BreadthOption(
                title = R.string.breadth_broad,
                explanation = R.string.breadth_broad_explanation,
                selected = breadth == Breadth.BROAD,
                enabled = enabled,
                onClick = { onBreadthChange(Breadth.BROAD) },
            )
        }
    }
}

@Composable
private fun BreadthOption(
    @StringRes title: Int,
    @StringRes explanation: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The whole row is the radio button, as the whole "Blocking" row
            // is the switch.
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // null because the row handles the tap.
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(explanation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
