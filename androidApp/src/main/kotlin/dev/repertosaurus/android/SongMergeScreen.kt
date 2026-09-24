package dev.repertosaurus.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.repertosaurus.android.theme.DialogActions
import dev.repertosaurus.android.theme.DialogText
import dev.repertosaurus.android.theme.InkDialog
import dev.repertosaurus.android.theme.PrimaryButton
import dev.repertosaurus.android.theme.SecondaryButton
import dev.repertosaurus.core.NoteSpelling
import dev.repertosaurus.data.SongCatalog
import dev.repertosaurus.data.SongChildKey
import dev.repertosaurus.data.SongMerge
import dev.repertosaurus.session.MergeChildRow
import dev.repertosaurus.session.MergeFieldPick
import dev.repertosaurus.session.MergePlan
import dev.repertosaurus.session.MergeSide
import dev.repertosaurus.session.MergeState
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.SongField
import dev.repertosaurus.session.songLabel

/** Stable handles for the instrumented tests and the UI dumps. */
internal object MergeTags {
    const val PICKER: String = "merge-picker"
    const val PICKER_LIST: String = "merge-picker-list"
    const val SEARCH: String = "merge-search"
    const val PREVIEW: String = "merge-preview"
    const val PREVIEW_LIST: String = "merge-preview-list"
    const val BACK: String = "merge-back"
    const val SWAP: String = "merge-swap"
    const val CONFIRM: String = "merge-confirm"
    const val DO_MERGE: String = "merge-do"
    const val CANCEL: String = "merge-cancel"
    fun candidate(songId: String): String = "merge-candidate-$songId"
    fun field(field: SongField, side: MergeSide): String = "merge-field-${field.name}-${side.name}"
    fun child(key: SongMerge.ChildKey): String = "merge-child-${key.table.name}-${key.parentIds.joinToString("-")}"
    fun event(eventId: String): String = "merge-event-$eventId"
}

/** Every event the merge overlay raises. */
internal class SongMergeActions(
    val onQuery: (String) -> Unit,
    val onChoose: (songId: String) -> Unit,
    val onBack: () -> Unit,
    val onSwap: () -> Unit,
    val onPick: (SongField, MergeSide) -> Unit,
    val onToggleChild: (SongMerge.ChildKey) -> Unit,
    val onToggleEvent: (eventId: String) -> Unit,
    val onConfirm: () -> Unit,
)

/**
 * **The merge overlay — repertoire-editing R38**, opened from the song detail's "Merge with…".
 * Its state is [MergeState], its own, apart from the detail's: the picker while there is no plan,
 * the preview once both sides are read. Back closes the preview to the picker, then the picker
 * (E24's overlays ruling); the screen's `BackHandler` calls [SongMergeActions.onBack].
 */
@Composable
internal fun SongMergeScreen(
    state: MergeState,
    songs: List<SongCatalog.SongListEntry>,
    spelling: NoteSpelling,
    actions: SongMergeActions,
) {
    val plan = state.plan
    if (plan == null) {
        MergePicker(state = state, songs = songs, actions = actions)
    } else {
        MergePreview(plan = plan, spelling = spelling, merging = state.merging, error = state.error, actions = actions)
    }
}

// ---- The picker ------------------------------------------------------------------------------

/**
 * The other song, searched and picked from every live song but this one — the Songs list's own
 * search box, row, empty line and matcher ([SongSearchField], [SongListRow], [emptyListLine],
 * `SongSearch`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MergePicker(
    state: MergeState,
    songs: List<SongCatalog.SongListEntry>,
    actions: SongMergeActions,
) {
    val current = remember(songs, state.songId) { songs.firstOrNull { it.id == state.songId } }
    val shown = remember(songs, state.query, state.songId) { state.candidates(songs) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Messages.mergeWith(current?.let { songLabel(it.title, it.artistName) }.orEmpty()), maxLines = 2) },
                navigationIcon = {
                    TextButton(onClick = actions.onBack, modifier = Modifier.testTag(MergeTags.BACK)) { Text("Back") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).testTag(MergeTags.PICKER)) {
            Text(
                Messages.MERGE_PICK_HINT,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            SongSearchField(query = state.query, onQuery = actions.onQuery, modifier = Modifier.testTag(MergeTags.SEARCH))
            StatusLines(message = null, error = state.error)
            if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            HorizontalDivider()
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).testTag(MergeTags.PICKER_LIST),
                contentPadding = PaddingValues(bottom = 48.dp),
            ) {
                items(shown, key = { it.id }) { song ->
                    SongListRow(
                        song = song,
                        onClick = { actions.onChoose(song.id) },
                        enabled = !state.loading,
                        modifier = Modifier.testTag(MergeTags.candidate(song.id)),
                    )
                    HorizontalDivider()
                }
                if (shown.isEmpty()) emptyListLine(state.query, whenEmpty = Messages.MERGE_NO_OTHER_SONGS)
            }
        }
    }
}

// ---- The preview (R33-R35, R38) --------------------------------------------------------------

/**
 * **What the merge will do, before it does it:** which song survives (with a swap), the per-field
 * picks (R34), every child row with a checkbox (R35), every event the merged song has with a
 * checkbox and the counts (R33), the set-list entries that move (R37), and a confirmation that
 * says it cannot be undone (R38). Every sentence is the core's ([Messages]); every value is the
 * plan's. Nothing is written until the confirmation's Merge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MergePreview(
    plan: MergePlan,
    spelling: NoteSpelling,
    merging: Boolean,
    error: String?,
    actions: SongMergeActions,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val fields = remember(plan) { plan.fields }
    val children = remember(plan) { plan.children.groupBy { it.key.table } }
    val errors = remember(plan) { plan.errors }
    val enabled = !merging

    Scaffold(
        modifier = Modifier.testTag(MergeTags.PREVIEW),
        topBar = {
            TopAppBar(
                title = { Text("Merge preview") },
                navigationIcon = {
                    TextButton(onClick = actions.onBack, enabled = enabled, modifier = Modifier.testTag(MergeTags.BACK)) {
                        Text("Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag(MergeTags.PREVIEW_LIST),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "status") {
                Column {
                    StatusLines(message = null, error = error)
                    if (merging) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            survivorItems(plan, enabled, actions)
            fieldItems(plan, fields, spelling, errors, enabled, actions)
            for (table in SongChildKey.entries) {
                val rows = children[table] ?: continue
                childItems(table, rows, enabled, actions)
            }
            eventItems(plan, enabled, actions)
            if (plan.loser.setlistItems > 0L) {
                item(key = "setlist") {
                    Text(Messages.mergeSetlistItems(plan.loser.setlistItems), style = MaterialTheme.typography.bodyMedium)
                }
            }
            item(key = "confirm") {
                PrimaryButton(
                    text = "Merge…",
                    onClick = { confirming = true },
                    enabled = enabled && errors.isEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(MergeTags.CONFIRM),
                )
            }
        }
    }

    if (confirming) {
        InkDialog(onDismiss = { confirming = false }, title = "Merge these two songs?") {
            DialogText(Messages.mergeConfirmation(plan))
            DialogActions(
                confirm = "Merge",
                onConfirm = {
                    confirming = false
                    actions.onConfirm()
                },
                onDismiss = { confirming = false },
                confirmModifier = Modifier.testTag(MergeTags.DO_MERGE),
                dismissModifier = Modifier.testTag(MergeTags.CANCEL),
            )
        }
    }
}

/** Which song survives, which goes, and the swap (R31: the user chooses). */
private fun LazyListScope.survivorItems(plan: MergePlan, enabled: Boolean, actions: SongMergeActions) {
    item(key = "survivor") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                Messages.mergeKeeps(songLabel(plan.survivor.record.title, plan.survivor.record.artistName)),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                Messages.mergeRemoves(songLabel(plan.loser.record.title, plan.loser.record.artistName)),
                style = MaterialTheme.typography.bodyMedium,
            )
            SecondaryButton(
                text = "Swap: keep the other one",
                onClick = actions.onSwap,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag(MergeTags.SWAP),
            )
        }
    }
}

/**
 * R34: one row per field whose values differ, a chip per side. Each side's value is named here, at
 * render time, so the tonal centre follows the user's [spelling] (R40-R42).
 */
private fun LazyListScope.fieldItems(
    plan: MergePlan,
    fields: List<MergeFieldPick>,
    spelling: NoteSpelling,
    errors: Map<SongField, String>,
    enabled: Boolean,
    actions: SongMergeActions,
) {
    item(key = "fields-heading") { SectionHeading(Messages.SECTION_FIELDS) }
    if (fields.isEmpty()) {
        item(key = "fields-same") { Text(Messages.MERGE_FIELDS_SAME, style = MaterialTheme.typography.bodyMedium) }
    }
    items(fields, key = { "field-${it.field.name}" }) { pick ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(pick.field.label, style = MaterialTheme.typography.labelLarge)
            for (side in MergeSide.entries) {
                FilterChip(
                    selected = pick.pick == side,
                    enabled = enabled,
                    onClick = { actions.onPick(pick.field, side) },
                    label = { Text(Messages.mergeFieldValue(pick.field, plan.draft(side), spelling), maxLines = 3) },
                    modifier = Modifier.fillMaxWidth().testTag(MergeTags.field(pick.field, side)),
                )
            }
            errors[pick.field]?.let { text ->
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** R35: one table's rows, each with its checkbox. */
private fun LazyListScope.childItems(
    table: SongChildKey,
    rows: List<MergeChildRow>,
    enabled: Boolean,
    actions: SongMergeActions,
) {
    item(key = "children-${table.name}") { SectionHeading(Messages.songSection(table)) }
    items(rows, key = { MergeTags.child(it.key) }) { row ->
        CheckRow(
            checked = row.kept,
            text = Messages.mergeChild(row),
            enabled = enabled,
            onToggle = { actions.onToggleChild(row.key) },
            modifier = Modifier.testTag(MergeTags.child(row.key)),
        )
    }
}

/** R33: the counts, then every live event of the merged song with its checkbox. */
private fun LazyListScope.eventItems(plan: MergePlan, enabled: Boolean, actions: SongMergeActions) {
    item(key = "events-heading") { SectionHeading(Messages.SECTION_PRACTICE) }
    item(key = "events-counts") { Text(Messages.mergeEventCounts(plan), style = MaterialTheme.typography.bodyMedium) }
    items(plan.loser.events, key = { "event-${it.id}" }) { event ->
        CheckRow(
            checked = plan.eventKept(event.id),
            text = Messages.mergeEvent(event),
            enabled = enabled,
            onToggle = { actions.onToggleEvent(event.id) },
            modifier = Modifier.testTag(MergeTags.event(event.id)),
        )
    }
}

/** A whole-row tap target with its checkbox; the row, not only the box, toggles. */
@Composable
private fun CheckRow(
    checked: Boolean,
    text: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}
