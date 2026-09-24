package dev.repertosaurus.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.repertosaurus.android.theme.DialogActions
import dev.repertosaurus.android.theme.DialogText
import dev.repertosaurus.android.theme.DisplayText
import dev.repertosaurus.android.theme.DisplayType
import dev.repertosaurus.android.theme.InkChip
import dev.repertosaurus.android.theme.InkDialog
import dev.repertosaurus.android.theme.InkHeader
import dev.repertosaurus.android.theme.InkTextField
import dev.repertosaurus.android.theme.MutedLine
import dev.repertosaurus.android.theme.PrimaryButton
import dev.repertosaurus.android.theme.RowRule
import dev.repertosaurus.android.theme.SecondaryButton
import dev.repertosaurus.android.theme.TextAction
import dev.repertosaurus.android.theme.Tokens
import dev.repertosaurus.core.Keys
import dev.repertosaurus.core.NoteSpelling
import dev.repertosaurus.core.NearMatches
import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.data.SongCatalog
import dev.repertosaurus.data.SongChildKey
import dev.repertosaurus.session.ArtistSuggestions
import dev.repertosaurus.session.InstrumentChip
import dev.repertosaurus.session.InstrumentEdit
import dev.repertosaurus.session.LookupItem
import dev.repertosaurus.session.LookupSuggestions
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.SongDetail
import dev.repertosaurus.session.SongDraft
import dev.repertosaurus.session.SongField
import dev.repertosaurus.session.SongIdentity
import dev.repertosaurus.session.TimedHistory
import dev.repertosaurus.session.songLabel
import dev.repertosaurus.session.titleCase

/** Stable handles for the instrumented tests. */
internal object SongDetailTags {
    const val DETAIL: String = "song-detail"
    const val SAVE: String = "song-detail-save"
    const val CLOSE: String = "song-detail-close"
    const val REMOVE: String = "song-detail-remove"
    const val CONFIRM_REMOVE: String = "song-detail-confirm-remove"
    const val EDIT_LINE_UP: String = "song-detail-edit-line-up"
    const val MERGE: String = "song-detail-merge"
    const val NEW_TAG: String = "song-detail-new-tag"
    const val TIMED_TOTAL: String = "song-detail-timed-total"
    const val TIMED_MORE: String = "song-detail-timed-more"
    fun timedEvent(eventId: String): String = "song-detail-timed-$eventId"
    fun field(field: SongField): String = "song-detail-field-${field.name}"
    fun tag(tagId: String): String = "song-detail-tag-$tagId"

    /** One chip of a picker row, by its position among the choices ("Unset" is not one). */
    fun choice(field: SongField, index: Int): String = "song-detail-choice-${field.name}-$index"
    fun instrumentPatch(rowId: String): String = "song-detail-instrument-patch-$rowId"
    fun instrumentSave(rowId: String): String = "song-detail-instrument-save-$rowId"
}

/** The in-memory lists the detail's pickers and type-aheads read (E36). */
internal class SongDetailLists(
    val artists: List<RepertosaurusRepository.Artist>,
    val grooves: List<LookupItem>,
    val tags: List<LookupItem>,
    val instruments: List<InstrumentChip>,
)

/** Every event the detail raises. */
internal class SongDetailActions(
    val onEdit: ((SongDraft) -> SongDraft) -> Unit,
    val onSave: () -> Unit,
    val onClose: () -> Unit,
    val onRemove: () -> Unit,
    val onToggleTag: (tagId: String) -> Unit,
    val onAddTag: (name: String) -> Unit,
    val onAddInstrument: (instrumentId: String) -> Unit,
    /** F16 #6: a `song_instrument` field changed — held in the detail, not in the composable. */
    val onEditInstrument: (rowId: String, edit: InstrumentEdit) -> Unit,
    val onSaveInstrument: (rowId: String) -> Unit,
    val onRemoveInstrument: (id: String) -> Unit,
    val onEditLineUp: (SongIdentity) -> Unit,
    /** R38: open the merge picker on this song. */
    val onMerge: () -> Unit,
)

/**
 * One song's every editable field — repertoire-editing R12-R21.
 *
 * - **R12:** every user-meaningful `song` column, labelled by [SongField]. Decade and loop length
 *   carry plain labels and no invented unit (session 09 ruling).
 * - **R13:** key signature and tonal centre are **picked**, labelled by [Keys]; numbers use a
 *   numeric keyboard and blank means null. A picked value is scrolled into view (F16 #9).
 * - **R14:** Save is explicit and writes the whole row. Errors come from [SongDraft.errors], show
 *   inline, and disable Save. Close waits for an in-flight save (F18 N4).
 * - **R16:** artist and groove are create-on-enter type-aheads over the in-memory lists (E36),
 *   through the one [LookupTypeAhead] the add sheet also uses (F22 B3).
 * - **R17-R21:** tags, `song_instrument` rows, the line-up, the practice summary, and removal.
 *
 * Nothing on this screen logs practice.
 */
@Composable
internal fun SongDetailScreen(
    detail: SongDetail?,
    lists: SongDetailLists,
    spelling: NoteSpelling,
    message: String?,
    error: String?,
    actions: SongDetailActions,
) {
    val record = detail?.record
    val busy = detail?.busy ?: true
    val errors = remember(detail?.draft) { detail?.draft?.errors().orEmpty() }
    val canSave = detail != null && detail.draftDirty && errors.isEmpty() && !busy

    Column(modifier = Modifier.fillMaxSize()) {
        InkHeader(
            navigation = {
                SecondaryButton(
                    text = "Close",
                    onClick = actions.onClose,
                    // F18 N4: a save in flight lands on this detail; closing under it would
                    // drop the result the user is waiting for.
                    enabled = detail?.saving != true,
                    modifier = Modifier.testTag(SongDetailTags.CLOSE),
                )
            },
            actions = {
                SecondaryButton(text = "Save", onClick = actions.onSave, enabled = canSave, modifier = Modifier.testTag(SongDetailTags.SAVE))
            },
            // E46: the one song label, over the stored values rather than the draft.
            title = { DisplayText(record?.title.orEmpty(), style = DisplayType.Heading, maxLines = 2) },
            subline = record?.artistName?.let { artist -> { DisplayText(artist, style = DisplayType.Subline, maxLines = 1) } },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(SongDetailTags.DETAIL)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusLines(message = message, error = error)
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (detail == null || record == null) return@Column

            SongFields(
                draft = detail.draft,
                errors = errors,
                enabled = !busy,
                lists = lists,
                spelling = spelling,
                onEdit = actions.onEdit,
            )

            // VI12: the detail's one primary action.
            PrimaryButton(
                text = if (detail.draftDirty) "Save" else "No unsaved changes",
                onClick = actions.onSave,
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )

            SectionRule()
            TagsSection(detail = detail, allTags = lists.tags, enabled = !busy, actions = actions)

            SectionRule()
            InstrumentsSection(
                detail = detail,
                instruments = lists.instruments,
                enabled = !busy,
                actions = actions,
            )

            SectionRule()
            LineUpSection(
                detail = detail,
                enabled = !busy,
                onEdit = { actions.onEditLineUp(SongIdentity(record.id, record.title, record.artistName)) },
            )

            SectionRule()
            PracticeSection(detail.practice, detail.timed)

            SectionRule()
            MergeSection(dirty = detail.dirty, enabled = detail.canMerge, onMerge = actions.onMerge)

            SectionRule()
            RemoveSection(
                label = songLabel(record.title, record.artistName),
                enabled = !busy,
                onRemove = actions.onRemove,
            )
        }
    }
}

/** VI4: the rule between the detail's sections. */
@Composable
private fun SectionRule() {
    RowRule(modifier = Modifier.padding(top = 8.dp))
}

// ---- The song's own columns (R12-R16) -------------------------------------------------------

@Composable
private fun SongFields(
    draft: SongDraft,
    errors: Map<SongField, String>,
    enabled: Boolean,
    lists: SongDetailLists,
    spelling: NoteSpelling,
    onEdit: ((SongDraft) -> SongDraft) -> Unit,
) {
    var showTonalityNote by rememberSaveable { mutableStateOf(false) }

    DraftField(SongField.TITLE, draft.title, errors, enabled) { v -> onEdit { it.copy(title = v) } }

    // R16 / E5: the artist, create-on-enter. Editing the text drops the picked id (package 1
    // ruling), so a typed name is resolved or created on Save; picking a suggestion carries its id.
    LookupTypeAhead(
        label = SongField.ARTIST.label,
        text = draft.artistName,
        picked = draft.artistId != null,
        items = lists.artists,
        suggest = { typed, artists -> ArtistSuggestions.search(typed, artists) },
        name = { it.name },
        hint = { state -> detailHint(state, newNoun = "artist", blank = null) { it.name } },
        onType = { v -> onEdit { it.copy(artistName = v, artistId = null) } },
        onPick = { match -> onEdit { it.copy(artistName = match.name, artistId = match.id) } },
        modifier = Modifier.testTag(SongDetailTags.field(SongField.ARTIST)),
        enabled = enabled,
        error = errors[SongField.ARTIST],
    )

    DraftField(SongField.REFERENCE_RECORDING, draft.referenceRecording, errors, enabled) { v ->
        onEdit { it.copy(referenceRecording = v) }
    }

    // R13: picked from −7..7, labelled by the core.
    Picker(
        label = SongField.KEY_SIGNATURE.label,
        selected = draft.keySignature,
        choices = SongDraft.KEY_SIGNATURE_CHOICES,
        choiceLabel = { Keys.keySignatureLabel(it) },
        enabled = enabled,
        error = errors[SongField.KEY_SIGNATURE],
        choiceTag = { index -> SongDetailTags.choice(SongField.KEY_SIGNATURE, index) },
        onPick = { v -> onEdit { it.copy(keySignature = v) } },
        modifier = Modifier.testTag(SongDetailTags.field(SongField.KEY_SIGNATURE)),
    )

    // R13: the twelve pitch classes, spelled from the picked key signature (decision 40) and the
    // user's note spelling (R40-R42).
    Picker(
        label = SongField.TONAL_CENTRE.label,
        selected = draft.tonalCentre,
        choices = SongDraft.TONAL_CENTRE_CHOICES,
        choiceLabel = { Keys.noteName(it, draft.keySignature, spelling) },
        enabled = enabled,
        error = errors[SongField.TONAL_CENTRE],
        choiceTag = { index -> SongDetailTags.choice(SongField.TONAL_CENTRE, index) },
        onPick = { v -> onEdit { it.copy(tonalCentre = v) } },
        modifier = Modifier.testTag(SongDetailTags.field(SongField.TONAL_CENTRE)),
    )

    // Decision 39: hidden by default in every UI. One tap shows it.
    if (showTonalityNote) {
        DraftField(SongField.TONALITY_NOTE, draft.tonalityNote, errors, enabled, singleLine = false) { v ->
            onEdit { it.copy(tonalityNote = v) }
        }
    } else {
        TextAction(
            text = if (draft.tonalityNote.isBlank()) {
                "Show ${SongField.TONALITY_NOTE.label.lowercase()}"
            } else {
                "Show ${SongField.TONALITY_NOTE.label.lowercase()} (has one)"
            },
            onClick = { showTonalityNote = true },
        )
    }

    DraftField(SongField.TEMPO_BPM, draft.tempoBpm, errors, enabled, numeric = true) { v ->
        onEdit { it.copy(tempoBpm = v) }
    }
    DraftField(SongField.DURATION_SECONDS, draft.durationSeconds, errors, enabled, numeric = true) { v ->
        onEdit { it.copy(durationSeconds = v) }
    }
    DraftField(SongField.DECADE, draft.decade, errors, enabled, numeric = true) { v ->
        onEdit { it.copy(decade = v) }
    }
    DraftField(SongField.LOOP_LENGTH, draft.loopLength, errors, enabled, numeric = true) { v ->
        onEdit { it.copy(loopLength = v) }
    }
    DraftField(SongField.CHORD_COUNT, draft.chordCount, errors, enabled, numeric = true) { v ->
        onEdit { it.copy(chordCount = v) }
    }
    DraftField(SongField.CHORD_PATTERN, draft.chordPattern, errors, enabled) { v ->
        onEdit { it.copy(chordPattern = v) }
    }

    // R16: the groove, create-on-enter over the in-memory groove list. Blank clears it.
    LookupTypeAhead(
        label = SongField.GROOVE.label,
        text = draft.grooveName,
        picked = draft.grooveId != null,
        items = lists.grooves,
        suggest = { typed, grooves -> LookupSuggestions.search(typed, grooves) },
        name = { it.name },
        hint = { state -> detailHint(state, newNoun = "groove", blank = "Leave blank for none.") { it.name } },
        onType = { v -> onEdit { it.copy(grooveName = v, grooveId = null) } },
        onPick = { match -> onEdit { it.copy(grooveName = match.name, grooveId = match.id) } },
        modifier = Modifier.testTag(SongDetailTags.field(SongField.GROOVE)),
        enabled = enabled,
        error = errors[SongField.GROOVE],
    )

    DraftField(SongField.MASHUP_NOTE, draft.mashupNote, errors, enabled, singleLine = false) { v ->
        onEdit { it.copy(mashupNote = v) }
    }
    DraftField(SongField.NOTES, draft.notes, errors, enabled, singleLine = false) { v ->
        onEdit { it.copy(notes = v) }
    }
    DraftField(SongField.CHART_URL, draft.chartUrl, errors, enabled, keyboardType = KeyboardType.Uri) { v ->
        onEdit { it.copy(chartUrl = v) }
    }
}

/** One text column. The error, when there is one, is [SongDraft.errors]' own wording. */
@Composable
private fun DraftField(
    field: SongField,
    value: String,
    errors: Map<SongField, String>,
    enabled: Boolean,
    numeric: Boolean = false,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    val error = errors[field]
    InkTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        label = field.label,
        isError = error != null,
        supportingText = error,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        fieldModifier = Modifier.testTag(SongDetailTags.field(field)),
    )
}

/**
 * R13: a constrained value, **picked, never typed** — the key signature, the tonal centre, and a
 * `song_instrument` row's difficulty (style review F17 B4: one picker, generic over its value).
 * "Unset" is null; none of the three is mandatory (R13, decisions 37, 42).
 *
 * F16 #9: the row is lazy and **scrolls the selected chip into view** when it is not already —
 * on the song's first read, so *G♭ major* is not hidden off the right edge of a row that starts
 * at seven flats, and whenever the selection changes from outside. A chip the user has just
 * tapped is already visible, so the row does not move under their thumb.
 */
@Composable
private fun <T> Picker(
    label: String,
    selected: T?,
    choices: List<T>,
    choiceLabel: (T) -> String,
    enabled: Boolean,
    onPick: (T?) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    choiceTag: ((index: Int) -> String)? = null,
) {
    val listState = rememberLazyListState()
    // Item 0 is "Unset"; a choice's item is its index plus one.
    val selectedItem = selected?.let { choices.indexOf(it) }?.takeIf { it >= 0 }?.plus(1) ?: 0
    LaunchedEffect(selectedItem) {
        val visible = listState.layoutInfo.visibleItemsInfo
        val viewport = listState.layoutInfo.viewportEndOffset
        val shown = visible.firstOrNull { it.index == selectedItem }
        val fullyShown = shown != null && shown.offset >= 0 && shown.offset + shown.size <= viewport
        // One chip of context before the selection, where there is one.
        if (!fullyShown) listState.scrollToItem((selectedItem - 1).coerceAtLeast(0))
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "unset") {
                InkChip(label = "Unset", selected = selected == null, enabled = enabled, onClick = { onPick(null) })
            }
            itemsIndexed(choices, key = { index, _ -> index }) { index, choice ->
                InkChip(
                    label = choiceLabel(choice),
                    selected = selected == choice,
                    enabled = enabled,
                    onClick = { onPick(choice) },
                    modifier = choiceTag?.let { Modifier.testTag(it(index)) } ?: Modifier,
                )
            }
        }
        error?.let { text ->
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * The detail's words for a [LookupTypeAhead] (R16): what Save will do — use the row the name
 * lands on, or add a new one. Nothing is said once a row is picked.
 */
private fun <T> detailHint(state: TypeAheadHint<T>, newNoun: String, blank: String?, name: (T) -> String): String? =
    when (state) {
        TypeAheadHint.Picked -> null
        is TypeAheadHint.Exact -> "Uses the existing ${name(state.match)}."
        is TypeAheadHint.New -> "Saving adds ${state.text} as a new $newNoun."
        TypeAheadHint.Blank -> blank
    }

// ---- Tags (R17) -----------------------------------------------------------------------------

@Composable
private fun TagsSection(
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
                selected = tag.id in held,
                enabled = enabled,
                onClick = { actions.onToggleTag(tag.id) },
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
private fun InstrumentsSection(
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
                    selected = false,
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
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                text = "Save ${titleCase(row.instrumentName)}",
                onClick = onSave,
                enabled = enabled && changed,
                modifier = Modifier.testTag(SongDetailTags.instrumentSave(row.id)),
            )
            SecondaryButton(text = "Remove", onClick = onRemove, enabled = enabled)
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
private fun LineUpSection(detail: SongDetail, enabled: Boolean, onEdit: () -> Unit) {
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
private fun PracticeSection(practice: List<RepertosaurusRepository.PracticeSummary>, timed: TimedHistory?) {
    SectionHeading(Messages.SECTION_PRACTICE)
    if (practice.isEmpty()) {
        Text("Never practised.", style = MaterialTheme.typography.bodyMedium)
    }
    for (summary in practice) {
        Text(text = Messages.practiceLine(summary), style = MaterialTheme.typography.bodyMedium)
    }
    timed?.let { TimedLines(it) }
}

/** SC19, D85 #1: the total over every timed event, then the latest [TimedHistory.SHOWN] and a count of the rest. */
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
    if (timed.olderCount > 0) {
        SubLine(Messages.timedMore(timed.olderCount), Modifier.testTag(SongDetailTags.TIMED_MORE))
    }
}

/** An indented [MutedLine] beneath a heading line (F46 B5, F36 N2). */
@Composable
private fun SubLine(text: String, modifier: Modifier = Modifier) {
    MutedLine(text, modifier = modifier.padding(start = 12.dp))
}

/**
 * R38: "Merge with…" — the way into a merge. Dead while the detail has unsaved changes: the merge
 * re-reads this song, so the draft would be lost without the discard prompt ever asking.
 */
@Composable
private fun MergeSection(dirty: Boolean, enabled: Boolean, onMerge: () -> Unit) {
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
private fun RemoveSection(label: String, enabled: Boolean, onRemove: () -> Unit) {
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
            DialogActions(
                confirm = "Remove",
                onConfirm = {
                    confirming = false
                    onRemove()
                },
                onDismiss = { confirming = false },
                confirmModifier = Modifier.testTag(SongDetailTags.CONFIRM_REMOVE),
            )
        }
    }
}
