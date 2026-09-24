package dev.repertosaurus.android

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.repertosaurus.android.theme.DisplayText
import dev.repertosaurus.android.theme.DisplayType
import dev.repertosaurus.android.theme.ErrorLine
import dev.repertosaurus.android.theme.InkChip
import dev.repertosaurus.android.theme.InkHeader
import dev.repertosaurus.android.theme.InkTextField
import dev.repertosaurus.android.theme.PrimaryButton
import dev.repertosaurus.android.theme.RowRule
import dev.repertosaurus.android.theme.SecondaryButton
import dev.repertosaurus.android.theme.TextAction
import dev.repertosaurus.core.Keys
import dev.repertosaurus.core.NoteSpelling
import dev.repertosaurus.data.RepertosaurusRepository
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
import dev.repertosaurus.session.songLabel

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
                SecondaryButton(text = Messages.SAVE, onClick = actions.onSave, enabled = canSave, modifier = Modifier.testTag(SongDetailTags.SAVE))
            },
            // The stored values, not the draft.
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

            PrimaryButton(
                text = if (detail.draftDirty) Messages.SAVE else "No unsaved changes",
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
        fieldModifier = Modifier.testTag(SongDetailTags.field(SongField.ARTIST)),
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
        fieldModifier = Modifier.testTag(SongDetailTags.field(SongField.GROOVE)),
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
internal fun <T> Picker(
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
                InkChip(label = "Unset", selected = selected == null, enabled = enabled, onSelect = { onPick(null) })
            }
            itemsIndexed(choices, key = { index, _ -> index }) { index, choice ->
                InkChip(
                    label = choiceLabel(choice),
                    selected = selected == choice,
                    enabled = enabled,
                    onSelect = { onPick(choice) },
                    modifier = choiceTag?.let { Modifier.testTag(it(index)) } ?: Modifier,
                )
            }
        }
        error?.let { text ->
            ErrorLine(text, style = MaterialTheme.typography.bodySmall)
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
