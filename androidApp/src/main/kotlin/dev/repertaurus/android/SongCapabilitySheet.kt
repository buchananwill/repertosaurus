package dev.repertaurus.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.repertaurus.core.NearMatches
import dev.repertaurus.data.RepertaurusRepository
import dev.repertaurus.session.InstrumentChip
import dev.repertaurus.session.PerformerLineUp
import dev.repertaurus.session.PerformerSuggestions
import dev.repertaurus.session.SessionRow
import dev.repertaurus.session.SongCapability
import dev.repertaurus.session.VocalRange

/** Stable handles for the instrumented tests, which have to find controls that are disabled. */
internal object CapabilityTags {
    const val SHEET: String = "capability-sheet"
    const val ADD: String = "capability-add"
    const val SAVE: String = "capability-save"
    const val REMOVE: String = "capability-remove"
    const val ERROR: String = "capability-error"
    const val MESSAGE: String = "capability-message"
}

/**
 * The song-capability editor — editing E1-E11. Who does what on one song.
 *
 * **E1 and E11 are the rules this file exists under, and they are the ones most likely to be
 * broken by a later change: nothing in here logs practice.** No row is tappable-to-log, no
 * gesture reaches [SessionViewModel.log], and the only writes this screen can reach are
 * `song_performer` ones through [SessionViewModel.addCapability],
 * [SessionViewModel.updateCapability] and [SessionViewModel.removeCapability]. "Coralie can
 * sing this" is a fact about the repertoire, true whether or not anyone practised today — and
 * this screen is now one long-press away from the tap that *is* a practice event, which is
 * exactly why the separation is stated here and pinned by a test that **composes this sheet**
 * and drives every control on it (`SongCapabilitySheetTest`).
 *
 * The shape is E10's: **grouped by performer**, so the song reads as a line-up. Coralie with a
 * `vocal` row and a `backing vocal` row is one entry with two chips, not two entries.
 *
 * **E45 governs everything this sheet writes.** The dialog holds an **id**, never a captured
 * row, and every control is dead while a write is in flight. Both halves matter and neither is
 * sufficient alone: the dialog used to edit a snapshot taken when the chip was tapped, and a
 * write plus its reload takes hundreds of milliseconds on a 479-song database, so re-tapping
 * the still-stale chip and saving wrote the pre-edit values back over the user's edit in
 * silence.
 *
 * **E43: success and failure are separate parameters rendered differently.** They used to share
 * one field in the accent colour, so a refused write appeared exactly where a confirmation had
 * appeared a moment before and read as one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SongCapabilitySheet(
    song: SessionRow,
    lineUp: List<PerformerLineUp>,
    performers: List<RepertaurusRepository.Performer>,
    instruments: List<InstrumentChip>,
    busy: Boolean,
    message: String?,
    error: String?,
    addsCommitted: Long,
    onAdd: (performerName: String, instrumentName: String) -> Unit,
    onUpdate: (SongCapability) -> Unit,
    onRemove: (SongCapability) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // E24: the detail *inside* this sheet is the sheet's own state and closes first. It is an
    // AlertDialog rather than an inline expansion precisely so back lands on it: one press
    // closes the capability, a second closes the sheet, and the second leaves the user on the
    // logger. There is no third level anywhere in this arc.
    //
    // **E45: an id, not a row.** Holding the `SongCapability` by value froze it at the moment
    // the chip was tapped, and `remember(capability.id)` could never refresh it because the id
    // is the one thing that does not change. The row is resolved out of the live line-up below,
    // on every composition, so the dialog reads current state by construction rather than by a
    // key that has to be remembered to be right. It also means a row removed underneath the
    // dialog closes it, instead of leaving an editor open on something that is gone.
    var editingId by remember(song.songId) { mutableStateOf<String?>(null) }
    val editing = editingId?.let { id ->
        lineUp.firstNotNullOfOrNull { entry -> entry.capabilities.firstOrNull { it.id == id } }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(CapabilityTags.SHEET)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Who plays this", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.artistName ?: "unknown artist",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // E1, said on the screen and not only in the code: the user has arrived here
                // from the sheet whose other half logs practice.
                Text(
                    text = "The line-up, not a practice log. Nothing on this sheet records " +
                        "that you played today.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // E43: two channels, two colours. A refused write must not be able to look like a
            // confirmation, and the error is the one that gets the loud colour and stays put.
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(CapabilityTags.ERROR),
                )
            }

            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag(CapabilityTags.MESSAGE),
                )
            }

            HorizontalDivider()

            if (lineUp.isEmpty()) {
                Text(
                    text = "Nobody is recorded on this song yet.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                for (entry in lineUp) {
                    // The list reorders on every add and every revive — the comparator ranks
                    // performers who lead anywhere first (E35) — and this is a plain scrolling
                    // Column, so without a key each entry's identity is its **position**. A
                    // scrolled chip row would then be shown against a different person.
                    key(entry.performerId) {
                        PerformerEntry(
                            entry = entry,
                            enabled = !busy,
                            onEdit = { editingId = it.id },
                        )
                    }
                }
            }

            HorizontalDivider()

            AddCapability(
                songId = song.songId,
                performers = performers,
                instruments = instruments,
                enabled = !busy,
                addsCommitted = addsCommitted,
                onAdd = onAdd,
            )
        }
    }

    editing?.let { capability ->
        CapabilityDialog(
            capability = capability,
            busy = busy,
            onDismiss = { editingId = null },
            onSave = { edited ->
                editingId = null
                onUpdate(edited)
            },
            onRemove = {
                editingId = null
                onRemove(capability)
            },
        )
    }
}

/**
 * E10: **one entry per performer**, whatever they hold. Charlotte on `vocal`, `backing vocal`
 * and `keys` is this one block with three chips, and Coralie on `vocal` and `backing vocal` is
 * one block with two — not two blocks, because "who plays this" is a line-up and not a list of
 * pairs.
 *
 * The chips are the individual `(song, performer, instrument)` rows of E3, and tapping one
 * opens that row. **A chip is an edit target and never a log target** (E11).
 *
 * [enabled] is E45: while a write is in flight these chips would open a dialog on a row the
 * reload is about to replace, and saving it would write the pre-edit values back.
 */
@Composable
private fun PerformerEntry(
    entry: PerformerLineUp,
    enabled: Boolean,
    onEdit: (SongCapability) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // E46: the heading is the core's — `Coralie · leads` — separator included.
        Text(entry.heading, style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (capability in entry.capabilities) {
                FilterChip(
                    selected = capability.isLead,
                    enabled = enabled,
                    onClick = { onEdit(capability) },
                    // E46: `Backing Vocal · lead · high`, assembled in the shared core. This
                    // used to be built here beside a character-for-character copy of the
                    // core's title-case helper.
                    label = { Text(capability.label) },
                    modifier = Modifier.height(44.dp),
                )
            }
        }
    }
}

/**
 * One capability row's three editable facts (E4, E7, E8).
 *
 * The three keys are deliberately absent: changing the performer or the instrument is a
 * *different* row with a different derived id, not an edit, so it is done by adding the other
 * row and removing this one.
 *
 * **E8 is honoured by not offering the field**: the range control appears only where
 * [SongCapability.rangeApplies] is true, which the core decides on the instrument's derived
 * **id** and not its name. `CapabilityCoordinator.update` throws rather than silently dropping
 * a range on a guitar row, so a UI that offered it everywhere would be a crash waiting for a
 * user.
 *
 * [capability] arrives resolved from the live line-up on every composition (E45). The three
 * local fields are keyed on its id and are the user's in-progress edit; the row underneath them
 * is current, so Save copies onto today's values rather than onto a snapshot.
 */
@Composable
private fun CapabilityDialog(
    capability: SongCapability,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (SongCapability) -> Unit,
    onRemove: () -> Unit,
) {
    var isLead by remember(capability.id) { mutableStateOf(capability.isLead) }
    var range by remember(capability.id) { mutableStateOf(capability.vocalRange) }
    var notes by remember(capability.id) { mutableStateOf(capability.notes.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        // E46: `Coralie · Backing Vocal`, from the core.
        title = { Text(capability.heading) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // E4: offered on every instrument and defaulting off. `backing vocal` gets no
                // special case — a featured backing vocalist is a real thing.
                FilterChip(
                    selected = isLead,
                    enabled = !busy,
                    onClick = { isLead = !isLead },
                    label = { Text(capability.leadLabel) },
                    modifier = Modifier.height(44.dp),
                )

                if (capability.rangeApplies) {
                    Text("Range", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = range == null,
                            enabled = !busy,
                            onClick = { range = null },
                            label = { Text("Unset") },
                            modifier = Modifier.height(44.dp),
                        )
                        for (option in VocalRange.entries) {
                            FilterChip(
                                selected = range == option,
                                enabled = !busy,
                                onClick = { range = option },
                                label = { Text(option.label) },
                                modifier = Modifier.height(44.dp),
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    enabled = !busy,
                    label = { Text("Notes (optional)") },
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    modifier = Modifier.fillMaxWidth(),
                )

                // E7: the removal is a tombstone, and E6's revive path depends on it still
                // being there — re-adding this pair brings this row back rather than making a
                // second one.
                TextButton(
                    onClick = onRemove,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag(CapabilityTags.REMOVE),
                ) {
                    Text("Remove from the line-up")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    onSave(
                        capability.copy(
                            isLead = isLead,
                            vocalRange = range,
                            notes = notes.trim().takeIf { it.isNotEmpty() },
                        ),
                    )
                },
                modifier = Modifier.testTag(CapabilityTags.SAVE),
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * E5: **both fields are type-aheads that create on enter.** A performer or an instrument the
 * user names and that does not already exist is created rather than blocking the save, with
 * near-matches surfaced *while typing* so `Ukelele` meets `Ukulele` before a second row is
 * committed — those two normalise differently, derive different ids, and nothing downstream
 * would ever merge them.
 *
 * E6 lives below this, in the repository: a pair that already exists as a tombstone is
 * revived, never inserted twice.
 *
 * **E44: the fields clear on confirmed success and never on dispatch.** They used to blank the
 * instant the button was pressed, so a failed write left two empty boxes, a message that read
 * like success, and no record of what the user had typed. [addsCommitted] counts adds the
 * ViewModel has seen land, which is the only fact this composable cannot work out for itself —
 * the practice-log path gets the same thing right by rolling its optimistic tap back on
 * failure, and this is the equivalent it had no version of.
 */
@Composable
private fun AddCapability(
    songId: String,
    performers: List<RepertaurusRepository.Performer>,
    instruments: List<InstrumentChip>,
    enabled: Boolean,
    addsCommitted: Long,
    onAdd: (performerName: String, instrumentName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed on the song: a half-typed name must not follow the editor onto a different one.
    var performer by remember(songId) { mutableStateOf("") }
    var instrument by remember(songId) { mutableStateOf("") }

    // E44. Keyed on the counter, so it runs exactly when an add is confirmed and never when
    // one is merely dispatched. A refused write leaves both names where the user can see what
    // they typed and try again.
    LaunchedEffect(addsCommitted) {
        if (addsCommitted > 0L) {
            performer = ""
            instrument = ""
        }
    }

    // E46: one matcher for this picker and the View editor's, and one limit for browsing and
    // for searching. There used to be a copy of the rule in each sheet and two constants
    // disagreeing about the same field.
    val performerMatches = remember(performer, performers) {
        PerformerSuggestions.search(performer, performers)
    }
    // The instruments row is the same rule and the same cap. It was unbounded, on a comment
    // reading "Five seeded instruments" — an assumption that expired in this very arc, which
    // is the one that made instruments user-extensible from the drawer.
    val instrumentMatches = remember(instrument, instruments) {
        NearMatches.browseOrSearch(instrument, instruments) { it.name }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Add someone", style = MaterialTheme.typography.titleSmall)

        OutlinedTextField(
            value = performer,
            onValueChange = { performer = it },
            enabled = enabled,
            label = { Text("Performer") },
            supportingText = { Text("A name that is not here yet is created.") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        SuggestionRow(
            // A performer's name is theirs as they typed it; only instrument names, which are
            // stored lower case by decision 5, get the core's title case.
            labels = performerMatches.map { it.name },
            selected = performer,
            enabled = enabled,
            onPick = { performer = it },
        )

        OutlinedTextField(
            value = instrument,
            onValueChange = { instrument = it },
            enabled = enabled,
            label = { Text("On") },
            supportingText = { Text("One row per instrument. Add a second for a second part.") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        SuggestionRow(
            labels = instrumentMatches.map { it.label },
            selected = instrument,
            enabled = enabled,
            onPick = { instrument = it },
        )

        Button(
            // E44: the names stay until the write is confirmed. E45: dead while one is in
            // flight, so a double press cannot queue a second add behind the first.
            onClick = { onAdd(performer.trim(), instrument.trim()) },
            enabled = enabled && performer.isNotBlank() && instrument.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag(CapabilityTags.ADD),
        ) {
            Text("Add to the line-up")
        }
    }
}

/** The type-ahead's near-matches, as chips. Picking one fills the field it belongs to. */
@Composable
private fun SuggestionRow(
    labels: List<String>,
    selected: String,
    enabled: Boolean,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (labels.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (label in labels) {
            FilterChip(
                selected = label.equals(selected, ignoreCase = true),
                enabled = enabled,
                onClick = { onPick(label) },
                label = { Text(label) },
                modifier = Modifier.height(44.dp),
            )
        }
    }
}
