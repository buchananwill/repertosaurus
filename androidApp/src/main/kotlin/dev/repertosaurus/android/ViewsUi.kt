package dev.repertosaurus.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.repertosaurus.core.normalise
import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.session.InstrumentChip
import dev.repertosaurus.session.PerformerSuggestions
import dev.repertosaurus.session.SessionOrder
import dev.repertosaurus.session.SessionView
import dev.repertosaurus.session.ViewFilter
import dev.repertosaurus.session.ViewSummary

/**
 * The View switcher and the View editor — views V13, V17, V19-V24.
 *
 * A View is a saved pairing of *which songs are eligible* with *which instrument the session
 * is about*. Those two were the same control, the instrument chip, and this file is where
 * they stop being: the chip row on the Session screen keeps its one meaning (V13 — the
 * practice instrument, which is both the log target and the staleness scope) and the
 * capability filter lives here, in the editor, as a separate field of the View.
 *
 * The switcher is the fast path and is built to be two taps: the top bar names the active
 * View, tapping it opens this sheet, tapping a row switches. Nothing here is on the
 * one-tap log path, and nothing here may get in its way.
 */

/** The `remember` key for a View being created, which has no id to key on yet. */
private const val NEW_VIEW_KEY = "new-view"

/**
 * The eligibility half of a View in words.
 *
 * The **rule** — which parts appear, in what order, joined how — is [ViewSummary.summarise] in
 * the shared core, where it is tested on the JVM. What is here is the lookup: resolving an id
 * against the lists this screen happens to hold, at render time and never stored (V24).
 */
internal fun filterSummary(
    filter: ViewFilter,
    instruments: List<InstrumentChip>,
    performers: List<RepertosaurusRepository.Performer>,
): String = ViewSummary.summarise(
    filter = filter,
    performerName = { id -> performers.firstOrNull { it.id == id }?.name },
    instrumentName = { id -> instruments.firstOrNull { it.id == id }?.label?.lowercase() },
)

/**
 * **E47, E42: does this View's filter name a performer or an instrument that is gone?**
 *
 * The **rule** is [ViewSummary.namesRemovedRow] in the shared core, beside the summary it
 * belongs with. What is here — exactly as in [filterSummary] above — is the lookup: resolving
 * an id against the lists this screen happens to hold. The composable branches on the answer
 * and decides nothing, because an empty list that claims the repertoire is empty when it holds
 * 479 songs is a rule about honesty, not a layout choice.
 */
internal fun filterNamesRemovedRow(
    filter: ViewFilter,
    instruments: List<InstrumentChip>,
    performers: List<RepertosaurusRepository.Performer>,
): Boolean = ViewSummary.namesRemovedRow(
    filter = filter,
    performerName = { id -> performers.firstOrNull { it.id == id }?.name },
    instrumentName = { id -> instruments.firstOrNull { it.id == id }?.label },
)

/** A chip's display name, or a plain marker when the row behind the id has been removed. */
internal fun instrumentLabel(instrumentId: String?, instruments: List<InstrumentChip>): String =
    instruments.firstOrNull { it.id == instrumentId }?.label ?: "No instrument"

/** What the switcher shows under a View's name: the two halves it pairs, in that order. */
internal fun viewSummary(
    view: SessionView,
    instruments: List<InstrumentChip>,
    performers: List<RepertosaurusRepository.Performer>,
): String = instrumentLabel(view.practiceInstrumentId, instruments) +
    ViewSummary.SEPARATOR + filterSummary(view.filter, instruments, performers)

/**
 * The switcher. One row per saved View, tapped to switch — V22's full reload, filter,
 * instrument and direction together.
 *
 * With no saved Views this is not a blocker: V21 says a fresh install behaves exactly as the
 * app does today, so the sheet explains the state it is already in and offers to make the
 * first View, rather than demanding one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ViewSwitcherSheet(
    views: List<SessionView>,
    activeViewId: String?,
    homeViewId: String?,
    instruments: List<InstrumentChip>,
    performers: List<RepertosaurusRepository.Performer>,
    onSwitch: (SessionView) -> Unit,
    onSetHome: (String) -> Unit,
    onEdit: (SessionView) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Views", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = if (views.isEmpty()) {
                        "No views yet. You are seeing every song, on the instrument you " +
                            "last practised, sorted the way you left it."
                    } else {
                        "A view pairs who plays what with the instrument the session is about."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (views.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(views, key = { it.id }) { view ->
                        ViewSwitcherRow(
                            view = view,
                            summary = viewSummary(view, instruments, performers),
                            active = view.id == activeViewId,
                            home = view.id == homeViewId,
                            onSwitch = { onSwitch(view) },
                            onSetHome = { onSetHome(view.id) },
                            onEdit = { onEdit(view) },
                        )
                        HorizontalDivider()
                    }
                }
            }

            Button(
                onClick = onCreate,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(56.dp),
            ) {
                Text(if (views.isEmpty()) "Make the first view" else "New view")
            }
        }
    }
}

/**
 * One switchable View. The whole row switches; Home and Edit are separate targets on the
 * right, both away from where a thumb reaching down the list lands.
 */
@Composable
private fun ViewSwitcherRow(
    view: SessionView,
    summary: String,
    active: Boolean,
    home: Boolean,
    onSwitch: () -> Unit,
    onSetHome: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onSwitch)
            .padding(start = 24.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (home) "${view.name} · home" else view.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!home) {
            TextButton(onClick = onSetHome) { Text("Home") }
        }
        TextButton(onClick = onEdit) { Text("Edit") }
    }
}

/**
 * Create or edit a View.
 *
 * [editing] is null for a new one. There is no separate "is this a create" flag because
 * [SessionView.saved] already is one: a View built with [SessionView.UNSAVED_ID] has no row
 * behind it, and that is precisely the distinction the caller needs.
 *
 * The two instrument controls are deliberately far apart and separately labelled. The upper
 * one is the *capability* filter — which songs are eligible — and the lower one is V13's
 * practice instrument, which the session logs to and measures staleness on. Conflating them
 * is the bug this whole feature exists to fix, so the copy states each one's job.
 *
 * The performer field follows the house type-ahead (decisions 16, 17) and matches through
 * the shared `normalise`, so `will` finds `Will`. It differs from the artist field in one
 * way that matters: **a View stores an id (V24), so a name that is not in `performer` cannot
 * be filtered on and this field never creates a row.**
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ViewEditorSheet(
    editing: SessionView?,
    instruments: List<InstrumentChip>,
    performers: List<RepertosaurusRepository.Performer>,
    defaultPracticeInstrumentId: String?,
    defaultOrder: SessionOrder,
    onCommit: (SessionView) -> Unit,
    onDelete: (SessionView) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val key = editing?.id ?: NEW_VIEW_KEY

    var name by remember(key) { mutableStateOf(editing?.name.orEmpty()) }
    // The picked performer is held as an id and the field shows that row's current name, so
    // a performer list that arrives after the sheet opens still fills the field in. `typed`
    // is null until the user edits, at which point what they typed rules.
    var performerId by remember(key) { mutableStateOf(editing?.filter?.performerId) }
    var typed by remember(key) { mutableStateOf<String?>(null) }
    var filterInstrumentId by remember(key) { mutableStateOf(editing?.filter?.instrumentId) }
    var leadOnly by remember(key) { mutableStateOf(editing?.filter?.leadOnly ?: false) }
    var practiceInstrumentId by remember(key) {
        mutableStateOf(editing?.practiceInstrumentId ?: defaultPracticeInstrumentId)
    }
    var order by remember(key) { mutableStateOf(editing?.order ?: defaultOrder) }

    val pickedName = performers.firstOrNull { it.id == performerId }?.name.orEmpty()
    val performerText = typed ?: pickedName
    // E46: **one matcher for this picker.** This branch was written here, and again in the
    // capability sheet, with two private constants that disagreed on the search cap for the
    // same field — eight names in one sheet, six in the other.
    val suggestions = remember(performerText, performers) {
        PerformerSuggestions.search(performerText, performers)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = if (editing == null) "New view" else "Edit view",
                style = MaterialTheme.typography.headlineSmall,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                placeholder = { Text("Classical piano") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel(
                title = "Which songs",
                detail = "Leave all three alone and the view shows the whole repertoire.",
            )

            OutlinedTextField(
                value = performerText,
                onValueChange = { entered ->
                    typed = entered
                    // A View stores an id, never a name (V24), so the id follows only when
                    // what was typed *is* an existing performer. Otherwise there is no
                    // constraint, and the supporting text says so.
                    performerId = performers.firstOrNull { normalise(it.name) == normalise(entered) }?.id
                },
                label = { Text("Performer") },
                supportingText = {
                    Text(
                        when {
                            // E42, E47: this sheet is the way out of a View filtering on a
                            // performer who has been removed, so it has to say so. The id is
                            // still set and resolves to nothing, which used to render as
                            // "Songs  is on." — a sentence with a hole in it, on the one
                            // screen whose job is to explain the problem.
                            performerId != null && pickedName.isEmpty() ->
                                "That performer has been removed. Pick one below, or clear it."
                            performerId != null -> "Songs $pickedName is on."
                            performerText.isBlank() -> "Anyone. Leave blank for no filter."
                            else -> "No performer by that name. Pick one below, or leave it blank."
                        },
                    )
                },
                trailingIcon = {
                    // `performerId != null` with an empty field is the removed-performer case:
                    // the filter is still set and Clear is the only way to drop it.
                    if (performerText.isNotEmpty() || performerId != null) {
                        TextButton(
                            onClick = {
                                performerId = null
                                typed = ""
                            },
                        ) {
                            Text("Clear")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            if (suggestions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (match in suggestions) {
                        FilterChip(
                            selected = performerId == match.id,
                            onClick = {
                                performerId = match.id
                                typed = null
                            },
                            label = { Text(match.name) },
                            modifier = Modifier.height(44.dp),
                        )
                    }
                }
            }

            Text(
                "Playing",
                style = MaterialTheme.typography.labelLarge,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = filterInstrumentId == null,
                    onClick = { filterInstrumentId = null },
                    label = { Text("Anything") },
                    modifier = Modifier.height(44.dp),
                )
                for (chip in instruments) {
                    FilterChip(
                        selected = filterInstrumentId == chip.id,
                        onClick = { filterInstrumentId = chip.id },
                        label = { Text(chip.label) },
                        modifier = Modifier.height(44.dp),
                    )
                }
            }

            // V6: `is_lead` is scoped to its instrument — lead vocal on a vocal row, lead
            // guitar on a guitar row — so this reads against whatever is selected above.
            FilterChip(
                selected = leadOnly,
                onClick = { leadOnly = !leadOnly },
                label = { Text("Lead only") },
                modifier = Modifier.height(44.dp),
            )

            SectionLabel(
                title = "Practising",
                detail = "The instrument this view logs to, and the one staleness is " +
                    "measured on. A song can pass the filter above and still have never " +
                    "been touched on this one — that is the point.",
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (chip in instruments) {
                    FilterChip(
                        selected = practiceInstrumentId == chip.id,
                        onClick = { practiceInstrumentId = chip.id },
                        label = { Text(chip.label) },
                        modifier = Modifier.height(44.dp),
                    )
                }
            }

            SectionLabel(title = "Starting from", detail = null)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in SessionOrder.entries) {
                    FilterChip(
                        selected = order == option,
                        onClick = { order = option },
                        label = {
                            Text(
                                text = when (option) {
                                    SessionOrder.COLDEST_FIRST -> "Coldest first"
                                    SessionOrder.HOTTEST_FIRST -> "Hottest first"
                                },
                            )
                        },
                        modifier = Modifier.height(44.dp),
                    )
                }
            }

            val practice = practiceInstrumentId
            Button(
                onClick = {
                    practice?.let { instrumentId ->
                        onCommit(
                            SessionView(
                                id = editing?.id ?: SessionView.UNSAVED_ID,
                                name = name.trim(),
                                filter = ViewFilter(
                                    performerId = performerId,
                                    instrumentId = filterInstrumentId,
                                    leadOnly = leadOnly,
                                ),
                                practiceInstrumentId = instrumentId,
                                order = order,
                                position = editing?.position ?: 0L,
                                notes = editing?.notes,
                            ),
                        )
                    }
                },
                enabled = name.isNotBlank() && practice != null,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text("Save view")
            }

            if (editing != null && editing.saved) {
                // V23: a tombstone, not a DELETE — every practice event logged under this
                // View is untouched, because a View was never what they were attached to.
                TextButton(
                    onClick = { onDelete(editing) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Delete this view")
                }
            }
        }
    }
}

/** A form section: what it decides, and why it is not the other instrument control. */
@Composable
private fun SectionLabel(title: String, detail: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
