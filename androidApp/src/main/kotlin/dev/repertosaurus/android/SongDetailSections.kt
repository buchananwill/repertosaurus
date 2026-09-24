package dev.repertosaurus.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.repertosaurus.android.theme.DialogText
import dev.repertosaurus.android.theme.DisplayText
import dev.repertosaurus.android.theme.DisplayType
import dev.repertosaurus.android.theme.InkChip
import dev.repertosaurus.android.theme.InkDialog
import dev.repertosaurus.android.theme.InkTextField
import dev.repertosaurus.android.theme.MutedLine
import dev.repertosaurus.android.theme.SecondaryButton
import dev.repertosaurus.android.theme.StackedActions
import dev.repertosaurus.android.theme.Tokens
import dev.repertosaurus.core.NearMatches
import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.data.SongCatalog
import dev.repertosaurus.data.SongChildKey
import dev.repertosaurus.session.InstrumentChip
import dev.repertosaurus.session.InstrumentEdit
import dev.repertosaurus.session.LookupItem
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.SongDetail
import dev.repertosaurus.session.TimedHistory
import dev.repertosaurus.session.titleCase

// ---- Tags (R17) -----------------------------------------------------------------------------

@Composable
internal fun TagsSection(
    detail: SongDetail,
    allTags: List<LookupItem>,
    enabled: Boolean,
    actions: SongDetailActions,
) {
    var typed by rememberSaveable(detail.songId) { mutableStateOf("") }
    val held = remember(detail.tags) { detail.tags.mapTo(mutableSetOf()) { it.tagId } }

    // E44: the field clears when the typed tag is **on the song**, never on dispatch.
    LaunchedEffect(detail.tags) {
        if (NearMatches.exact(typed, detail.tags) { it.tagName } != null) typed = ""
    }

    SectionHeading(Messages.songSection(SongChildKey.TAG))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (tag in allTags) {
            InkChip(
                label = tag.name,
                checked = tag.id in held,
                enabled = enabled,
                onCheckedChange = { actions.onToggleTag(tag.id) },
                modifier = Modifier.testTag(SongDetailTags.tag(tag.id)),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        InkTextField(
            value = typed,
            onValueChange = { typed = it },
            enabled = enabled,
            label = "New tag",
            modifier = Modifier.weight(1f),
            fieldModifier = Modifier.testTag(SongDetailTags.NEW_TAG),
        )
        SecondaryButton(text = "Add", onClick = { actions.onAddTag(typed) }, enabled = enabled && typed.isNotBlank())
    }
}

// ---- song_instrument (R19) ------------------------------------------------------------------

@Composable
internal fun InstrumentsSection(
    detail: SongDetail,
    instruments: List<InstrumentChip>,
    enabled: Boolean,
    actions: SongDetailActions,
) {
    val rows = detail.instruments
    SectionHeading(Messages.songSection(SongChildKey.INSTRUMENT))
    MutedLine("Difficulty, patch and notes for this song on one instrument.")
    for (row in rows) {
        key(row.id) {
            SongInstrumentRow(
                row = row,
                edit = detail.instrumentEdit(row),
                enabled = enabled,
                onEdit = { edit -> actions.onEditInstrument(row.id, edit) },
                onSave = { actions.onSaveInstrument(row.id) },
                onRemove = { actions.onRemoveInstrument(row.id) },
            )
        }
    }
    val present = remember(rows) { rows.mapTo(mutableSetOf()) { it.instrumentId } }
    val addable = instruments.filterNot { it.id in present }
    if (addable.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (instrument in addable) {
                InkChip(
                    label = "+ ${instrument.label}",
                    enabled = enabled,
                    onClick = { actions.onAddInstrument(instrument.id) },
                )
            }
        }
    }
}

/**
 * One `song_instrument` row, edited in place. [edit] is the user's in-progress edit, **held in
 * the detail** (F16 #6) so the discard prompt can see it; a write that changes the stored row
 * underneath resets it to what is stored.
 */
@Composable
private fun SongInstrumentRow(
    row: SongCatalog.SongInstrument,
    edit: InstrumentEdit,
    enabled: Boolean,
    onEdit: (InstrumentEdit) -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
) {
    val changed = edit != InstrumentEdit.of(row)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // E46: the core's title case, never a copy of it.
        Text(titleCase(row.instrumentName), style = MaterialTheme.typography.titleSmall)
        // R13 / decision 42: 1-5, picked — the same picker as the key signature (F17 B4).
        Picker(
            label = "Difficulty",
            selected = edit.difficulty,
            choices = DIFFICULTY_CHOICES,
            choiceLabel = { it.toString() },
            enabled = enabled,
            onPick = { value -> onEdit(edit.copy(difficulty = value)) },
        )
        InkTextField(
            value = edit.patch,
            onValueChange = { onEdit(edit.copy(patch = it)) },
            enabled = enabled,
            label = "Patch",
            fieldModifier = Modifier.testTag(SongDetailTags.instrumentPatch(row.id)),
        )
        InkTextField(
            value = edit.notes,
            onValueChange = { onEdit(edit.copy(notes = it)) },
            enabled = enabled,
            label = "Notes",
            singleLine = false,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                text = "Save ${titleCase(row.instrumentName)}",
                onClick = onSave,
                enabled = enabled && changed,
                modifier = Modifier.testTag(SongDetailTags.instrumentSave(row.id)),
            )
            SecondaryButton(text = Messages.REMOVE, onClick = onRemove, enabled = enabled)
        }
    }
}

/** Decision 42's range as the picker's values. */
private val DIFFICULTY_CHOICES: List<Long> = SongCatalog.DIFFICULTY_RANGE.toList()

// ---- Line-up (R18) and practice (R20) --------------------------------------------------------

/**
 * **One heading convention for the song's sections** (style review F27 N2), shared by the detail
 * and the merge preview, which lists the same sections under the same words ([Messages.songSection]).
 * Display type, as the scorecards' sections are (VI6).
 */
@Composable
internal fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    DisplayText(text, style = DisplayType.Badge, color = Tokens.Ink, modifier = modifier.padding(top = 8.dp))
}

@Composable
internal fun LineUpSection(detail: SongDetail, enabled: Boolean, onEdit: () -> Unit) {
    SectionHeading(Messages.songSection(SongChildKey.PERFORMER))
    if (detail.lineUp.isEmpty()) {
        Text("Nobody is recorded on this song yet.", style = MaterialTheme.typography.bodyMedium)
    }
    for (entry in detail.lineUp) {
        key(entry.performerId) {
            Column {
                // E46: the heading and each chip's label are the core's.
                Text(entry.heading, style = MaterialTheme.typography.titleSmall)
                for (capability in entry.capabilities) {
                    SubLine(capability.label)
                }
            }
        }
    }
    SecondaryButton(
        text = "Edit who plays this",
        onClick = onEdit,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag(SongDetailTags.EDIT_LINE_UP),
    )
}

/** R20: read-only, in decision 18's chip order (the core's read applied it), worded by the core. */
@Composable
internal fun PracticeSection(practice: List<RepertosaurusRepository.PracticeSummary>, timed: TimedHistory?) {
    SectionHeading(Messages.SECTION_PRACTICE)
    if (practice.isEmpty()) {
        Text("Never practised.", style = MaterialTheme.typography.bodyMedium)
    }
    for (summary in practice) {
        Text(text = Messages.practiceLine(summary), style = MaterialTheme.typography.bodyMedium)
    }
    timed?.let { TimedLines(it) }
}

/** SC19. */
@Composable
private fun TimedLines(timed: TimedHistory) {
    Text(
        text = Messages.timedTotalLine(timed.totalSeconds),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp).testTag(SongDetailTags.TIMED_TOTAL),
    )
    for (event in timed.latest) {
        key(event.id) {
            SubLine(Messages.timedEventLine(event), Modifier.testTag(SongDetailTags.timedEvent(event.id)))
        }
    }
    if (timed.olderCount > 0L) {
        SubLine(Messages.timedMore(timed.olderCount), Modifier.testTag(SongDetailTags.TIMED_MORE))
    }
}

/** An indented [MutedLine]. */
@Composable
private fun SubLine(text: String, modifier: Modifier = Modifier) {
    MutedLine(text, modifier = modifier.padding(start = 12.dp))
}

/**
 * R38: "Merge with…" — the way into a merge. Dead while the detail has unsaved changes: the merge
 * re-reads this song, so the draft would be lost without the discard prompt ever asking.
 */
@Composable
internal fun MergeSection(dirty: Boolean, enabled: Boolean, onMerge: () -> Unit) {
    SecondaryButton(
        text = "Merge with…",
        onClick = onMerge,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag(SongDetailTags.MERGE),
    )
    if (dirty) MutedLine(Messages.MERGE_NEEDS_SAVE)
}

/** R21: a soft delete behind a confirmation that says the history is kept and hidden. */
@Composable
internal fun RemoveSection(label: String, enabled: Boolean, onRemove: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    SecondaryButton(
        text = "Remove this song",
        onClick = { confirming = true },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag(SongDetailTags.REMOVE),
    )
    if (confirming) {
        InkDialog(onDismiss = { confirming = false }, title = "Remove $label?") {
            DialogText(
                "It leaves every list. Its practice history is kept, and hidden with it. " +
                    "Adding the same title and artist again restores it.",
            )
            StackedActions(
                primary = Messages.REMOVE,
                onPrimary = {
                    confirming = false
                    onRemove()
                },
                onSecondary = { confirming = false },
                primaryModifier = Modifier.testTag(SongDetailTags.CONFIRM_REMOVE),
            )
        }
    }
}
