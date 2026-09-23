package dev.repertosaurus.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.core.Timestamps
import dev.repertosaurus.session.SessionRow
import kotlinx.datetime.LocalDate

/** Stable handles for the feel sheet. */
internal object FeelSheetTags {
    /** The feel's rating segments, as `feel-<value>` (rating-scale RS9). */
    const val FEEL: String = "feel"
}

/**
 * Long-press: `feel` and an optional note (decision 46). Both are optional — the sheet can log
 * with neither, which is exactly what a plain tap does. The feel speaks the rating scale, all
 * four levels (rating-scale RS3, RS10, RS13): schema 3 widened `practice_event`'s CHECK to 0-3.
 *
 * The date sits here too, and only here: decision 45 makes logging for a past date
 * available but demoted, never on the primary tap path. Three days back is as far as it
 * goes, because a fortnight of real use is meant to tell us whether it is needed at all.
 *
 * **E2: the capability editor is reached from here, and the feel rating keeps its exact
 * cost.** Decision 46 makes long-press *the* rating affordance and a plain tap the log, so
 * this sheet shows the feel levels **and** a row opening the editor — never a chooser in front
 * of them. The rating is one long-press and one tap on a level, and [onEditLineUp]'s row sits
 * below the Log button, where nothing on the rating path has to move past it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeelSheet(
    row: SessionRow,
    onDismiss: () -> Unit,
    onLog: (RatingLevel?, String?, String) -> Unit,
    onEditLineUp: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var feel by remember { mutableStateOf<RatingLevel?>(null) }
    var note by remember { mutableStateOf("") }
    var daysAgo by remember { mutableStateOf(0) }
    val today = remember { LocalDate.parse(Timestamps.today()) }
    val loggedOn = LocalDate.fromEpochDays(today.toEpochDays() - daysAgo).toString()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(row.title, style = MaterialTheme.typography.headlineSmall)
            Text(
                row.artistName ?: "unknown artist",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Feel", style = MaterialTheme.typography.labelLarge)
            RatingSegmentedControl(
                value = feel,
                onValueChange = { feel = it },
                tagPrefix = FeelSheetTags.FEEL,
            )

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (optional)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Date", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Short labels: four chips share the width of a 360dp phone.
                val labels = listOf("Today", "1d ago", "2d ago", "3d ago")
                for ((offset, label) in labels.withIndex()) {
                    FilterChip(
                        selected = daysAgo == offset,
                        onClick = { daysAgo = offset },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f).height(44.dp),
                    )
                }
            }

            Button(
                onClick = { onLog(feel, note, loggedOn) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text("Log it")
            }

            // E2's other half. It is below the Log button on purpose: the rating is what a
            // long-press is for, and this must not sit between the user and it.
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clickable(onClick = onEditLineUp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Who plays this", style = MaterialTheme.typography.titleMedium)
                    Text(
                        // E1 in the one place a user could confuse the two.
                        "Edit the line-up. Recording it never logs practice.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("Edit", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
