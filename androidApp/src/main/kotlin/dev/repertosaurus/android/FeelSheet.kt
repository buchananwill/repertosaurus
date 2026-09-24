package dev.repertosaurus.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import dev.repertosaurus.android.theme.ActionPair
import dev.repertosaurus.android.theme.InkChip
import dev.repertosaurus.android.theme.InkTextField
import dev.repertosaurus.android.theme.MutedLine
import dev.repertosaurus.android.theme.RowRule
import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.core.Timestamps
import dev.repertosaurus.session.Messages
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
    /** timer TM2: a timer is running, so the button switches it here. */
    timerRunning: Boolean,
    /** timer TM1: the timer, on this row's song. Never the plain tap's. */
    onStartTimer: () -> Unit,
) {
    // Fully expanded: half open, Log and the timer sit below the fold.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
            SongTitleArtist(title = row.title, artistName = row.artistName, titleStyle = MaterialTheme.typography.headlineSmall)

            Text("Feel", style = MaterialTheme.typography.labelLarge)
            RatingSegmentedControl(
                value = feel,
                onValueChange = { feel = it },
                tagPrefix = FeelSheetTags.FEEL,
            )

            InkTextField(
                value = note,
                onValueChange = { note = it },
                label = "Note (optional)",
                singleLine = false,
                minLines = 2,
            )

            Text("Date", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Short labels: four chips share the width of a 360dp phone.
                val labels = listOf("Today", "1d ago", "2d ago", "3d ago")
                for ((offset, label) in labels.withIndex()) {
                    InkChip(
                        label = label,
                        selected = daysAgo == offset,
                        onSelect = { daysAgo = offset },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // visual-identity VI12: the sheet's one primary action. timer TM1, TM2: the timer beside it.
            ActionPair(
                primary = Messages.LOG_IT,
                onPrimary = { onLog(feel, note, loggedOn) },
                secondary = if (timerRunning) Messages.TIMER_SWITCH else Messages.TIMER_START,
                onSecondary = onStartTimer,
            )

            // E2's other half. It is below the Log button on purpose: the rating is what a
            // long-press is for, and this must not sit between the user and it.
            RowRule()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clickable(onClick = onEditLineUp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Who plays this", style = MaterialTheme.typography.titleMedium)
                    // E1 in the one place a user could confuse the two.
                    MutedLine("Edit the line-up. Recording it never logs practice.")
                }
                Text("Edit", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
