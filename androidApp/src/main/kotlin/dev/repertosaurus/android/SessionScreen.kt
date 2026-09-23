package dev.repertosaurus.android

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.repertosaurus.core.Timestamps
import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.session.InstrumentChip
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.SessionOrder
import dev.repertosaurus.session.SessionRow
import dev.repertosaurus.session.SessionState
import dev.repertosaurus.session.SessionView
import dev.repertosaurus.session.ViewFilter
import dev.repertosaurus.session.identity
import kotlinx.datetime.LocalDate

/** Stable handles for the instrumented tests and for on-device inspection. */
internal object SessionTags {
    /** E47's way out of a View whose filter names a row that is gone. */
    const val EDIT_BROKEN_VIEW: String = "session-edit-broken-view"
}

/**
 * The Session screen — the interaction the whole product exists for.
 *
 * Layout follows the target device and the posture: a Galaxy S20 on a music stand, one
 * hand, mid-practice. The list fills the bottom two thirds where the thumb lands, because
 * tapping a row is the only thing that happens often. Everything that is not the tap path
 * lives in the drawer, out of the way — except Export, which stays in the top bar because
 * it is the only backup the user has until phase 2 sync exists.
 *
 * No state is owned here. Everything comes from [SessionViewModel], and the rules it
 * enforces live in the shared core.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun SessionScreen(
    viewModel: SessionViewModel,
    onOpenDrawer: () -> Unit,
    onExport: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val transfer by viewModel.transfer.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var feelFor by remember { mutableStateOf<SessionRow?>(null) }
    var addingSong by remember { mutableStateOf(false) }
    var addSongTitle by remember { mutableStateOf("") }
    val artists by viewModel.artists.collectAsState()

    // The Views surface. The switcher is a sheet rather than a screen because switching is
    // a two-tap operation the user makes mid-practice; the editor is a second sheet, opened
    // only after the switcher has closed, so the two never stack.
    val performers by viewModel.performers.collectAsState()
    val homeViewId by viewModel.homeViewId.collectAsState()
    var switching by remember { mutableStateOf(false) }
    var editorOpen by remember { mutableStateOf(false) }
    var editorTarget by remember { mutableStateOf<SessionView?>(null) }
    var deletingView by remember { mutableStateOf<SessionView?>(null) }

    // The capability editor's state (E1-E11). It is the ViewModel's rather than this
    // composable's because the line-up is a database read and the edits are database writes;
    // what is local here is only which sheet is open.
    val capabilities by viewModel.capabilities.collectAsState()

    val undo = state.undo
    LaunchedEffect(undo?.tapId) {
        if (undo != null) {
            val result = snackbarHostState.showSnackbar(
                message = "Logged ${undo.songTitle}" + (undo.feel?.let { " · feel $it" } ?: ""),
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undo(undo.tapId)
            } else {
                viewModel.dismissUndo()
            }
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(transfer.message) {
        transfer.message?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
            viewModel.clearTransferMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    addSongTitle = state.query
                    viewModel.loadArtists()
                    addingSong = true
                },
            ) {
                Text("Add song")
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    // The active View names the screen, and tapping it switches. Two taps
                    // from anywhere to any other View, which is what "now I'm switching to
                    // classical piano" has to cost.
                    ActiveViewTitle(
                        view = state.view,
                        instruments = state.instruments,
                        performers = performers,
                        onClick = { switching = true },
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onOpenDrawer) { Text("Menu") }
                },
                actions = {
                    // Export is the only backup there is until phase 2 sync exists, so it
                    // stays on the surface as well as being the first item in the drawer.
                    TextButton(onClick = onExport) { Text("Export") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // The practice instrument (V13): what a tap logs to, and what staleness is
            // measured against. It is **not** the capability filter — that is a field of
            // the View, set in the editor and summarised in the title above. Merging the
            // two back into one control is the bug Views exist to fix.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (chip in state.instruments) {
                    FilterChip(
                        selected = chip.id == state.selectedInstrumentId,
                        onClick = { viewModel.selectInstrument(chip.id) },
                        label = { Text(chip.label) },
                        modifier = Modifier.height(44.dp),
                    )
                }
            }

            // The sort toggle sits up here, above the search field and two rows clear of
            // the first tap target. Coldest first is the default and stays it; a mis-tap
            // that reorders the list mid-session is worse than useless, so nothing that
            // reorders is within reach of a thumb aiming at a song.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Sort",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (option in SessionOrder.entries) {
                    FilterChip(
                        selected = state.order == option,
                        onClick = { viewModel.setOrder(option) },
                        label = {
                            Text(
                                text = when (option) {
                                    SessionOrder.COLDEST_FIRST -> "Coldest first"
                                    SessionOrder.HOTTEST_FIRST -> "Hottest first"
                                },
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                        modifier = Modifier.height(40.dp),
                    )
                }
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("Search title or artist") },
                singleLine = true,
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        TextButton(onClick = { viewModel.setQuery("") }) { Text("Clear") }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )

            if (state.loading || transfer.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            SessionList(
                state = state,
                // E47: the empty list has three causes, not two, and the third one is the
                // only one that must not be described as an empty repertoire.
                filterNamesRemovedRow = filterNamesRemovedRow(
                    filter = state.view?.filter ?: ViewFilter.NONE,
                    instruments = state.instruments,
                    performers = performers,
                ),
                onTap = viewModel::log,
                onLongPress = { row -> feelFor = row },
                onAddSong = { title ->
                    addSongTitle = title
                    viewModel.loadArtists()
                    addingSong = true
                },
                // E42: there is no sensible automatic repair — nothing can stand in for the
                // performer the user meant — so the way out is the View editor, opened on the
                // View that is broken.
                // The active View, saved or forked — the forked one carries the same broken
                // filter (V13a), so opening the editor on it is what puts the problem in front
                // of the user rather than a blank form.
                onEditView = {
                    editorTarget = state.view
                    editorOpen = true
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }

    feelFor?.let { row ->
        FeelSheet(
            row = row,
            onDismiss = { feelFor = null },
            onLog = { feel, note, loggedOn ->
                viewModel.log(row.songId, feel, note, loggedOn)
                feelFor = null
            },
            // E2: the route into the capability editor, added to the sheet that already
            // exists rather than in front of it. The feel chips are untouched and stay one
            // long-press away; this row sits below the Log button, so nothing that was on
            // the rating path has moved or grown a step.
            onEditLineUp = {
                feelFor = null
                viewModel.openCapabilities(row.identity())
            },
        )
    }

    // E1: the capability editor. It writes `song_performer` and nothing else — there is no
    // call to `viewModel.log` anywhere inside it, and the sheet it was opened from has
    // already closed, so the two never stack.
    capabilities.song?.let { song ->
        SongCapabilitySheet(
            song = song,
            lineUp = capabilities.lineUp,
            performers = performers,
            instruments = state.instruments,
            busy = capabilities.busy,
            // E43: two channels, never one. A refused write renders in the error colour and
            // cannot be mistaken for the confirmation that stood in the same place.
            message = capabilities.message,
            error = capabilities.error,
            addsCommitted = capabilities.addsCommitted,
            onAdd = viewModel::addCapability,
            onUpdate = viewModel::updateCapability,
            onRemove = viewModel::removeCapability,
            onDismiss = viewModel::closeCapabilities,
        )
    }

    if (addingSong) {
        AddSongSheet(
            initialTitle = addSongTitle,
            artists = artists,
            onDismiss = { addingSong = false },
            onAdd = { title, artistName, artistId ->
                viewModel.addSong(title, artistName, artistId)
                addingSong = false
            },
        )
    }

    if (switching) {
        ViewSwitcherSheet(
            views = state.views,
            activeViewId = state.view?.id,
            homeViewId = homeViewId,
            instruments = state.instruments,
            performers = performers,
            onSwitch = { view ->
                switching = false
                viewModel.switchView(view)
            },
            onSetHome = viewModel::setHomeView,
            onEdit = { view ->
                switching = false
                editorTarget = view
                editorOpen = true
            },
            onCreate = {
                switching = false
                editorTarget = null
                editorOpen = true
            },
            onDismiss = { switching = false },
        )
    }

    if (editorOpen) {
        ViewEditorSheet(
            editing = editorTarget,
            instruments = state.instruments,
            performers = performers,
            // A new View starts where the user already is: same instrument, same
            // direction. V21's unsaved state is the natural draft of the first View.
            defaultPracticeInstrumentId = state.selectedInstrumentId,
            defaultOrder = state.order,
            onCommit = { view ->
                editorOpen = false
                viewModel.commitView(view)
            },
            onDelete = { view ->
                editorOpen = false
                deletingView = view
            },
            onDismiss = { editorOpen = false },
        )
    }

    deletingView?.let { view ->
        AlertDialog(
            onDismissRequest = { deletingView = null },
            title = { Text("Delete ${view.name}?") },
            text = {
                Text(
                    "The view goes; nothing you have practised does. Practice is logged " +
                        "against a song and an instrument, never against a view.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteView(view.id)
                        deletingView = null
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingView = null }) { Text("Cancel") }
            },
        )
    }

    transfer.pending?.let { preview ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelImport() },
            title = { Text("Replace everything on this phone?") },
            text = {
                Text(
                    "The file you picked holds ${preview.songs} songs and " +
                        "${preview.practiceEvents} practice events.\n\n" +
                        "Importing deletes the database on this phone, including any " +
                        "practice logged since your last export. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmImport() }) { Text("Replace") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelImport() }) { Text("Cancel") }
            },
        )
    }
}

/**
 * The top bar's title: which View is on screen, and what it lets through.
 *
 * The second line is the *filter* (V11) and not the practice instrument — that is the chip
 * row directly beneath, and the two are separate on purpose (V13). With no saved Views this
 * reads "All songs / Every song", which is exactly what the app does today (V21).
 *
 * V13a adds a third case: a chip tap forks a saved View into an unsaved one that **keeps the
 * filter**. Calling that "All songs" over a filtered list would be a plain falsehood, so it
 * reads "Not saved". That wording is an assumption, not a recorded decision.
 */
@Composable
private fun ActiveViewTitle(
    view: SessionView?,
    instruments: List<InstrumentChip>,
    performers: List<RepertosaurusRepository.Performer>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.clickable(onClick = onClick)) {
        Text(
            text = when {
                view == null -> "All songs"
                view.saved -> view.name
                view.filter.unfiltered -> "All songs"
                else -> "Not saved"
            } + " ▾",
            // Two lines inside a 64dp top bar: titleMedium over labelSmall fits at every
            // system font scale, where titleLarge clips the second line at the large ones.
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = filterSummary(view?.filter ?: ViewFilter.NONE, instruments, performers),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Coldest first, never-practised leading, with everything logged in this session moved
 * below a divider — "the row shows it is logged and moves out of the way". A logged row
 * stays tappable: a second pass at the same song on the same day is a second session and
 * the UI must not treat it as a mistake (decision 47).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionList(
    state: SessionState,
    filterNamesRemovedRow: Boolean,
    onTap: (String) -> Unit,
    onLongPress: (SessionRow) -> Unit,
    onAddSong: (String) -> Unit,
    onEditView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        items(state.pending, key = { "pending:${it.songId}" }) { row ->
            SongRow(
                row = row,
                loggedCount = 0,
                onTap = { onTap(row.songId) },
                onLongPress = { onLongPress(row) },
                modifier = Modifier.animateItemPlacement(),
            )
            HorizontalDivider()
        }

        if (state.logged.isNotEmpty()) {
            item(key = "logged-header") {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "Logged this session · ${state.logged.size}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "Tap again for a second pass.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.logged, key = { "logged:${it.row.songId}" }) { logged ->
                SongRow(
                    row = logged.row,
                    loggedCount = logged.countThisSession,
                    onTap = { onTap(logged.row.songId) },
                    onLongPress = { onLongPress(logged.row) },
                    modifier = Modifier.animateItemPlacement(),
                )
                HorizontalDivider()
            }
        }

        if (!state.loading && state.pending.isEmpty() && state.logged.isEmpty()) {
            item(key = "empty") {
                // **E47: an empty list must never claim the repertoire is empty when it is
                // not.** There are three causes and they are not interchangeable:
                //
                // 1. a search that matched nothing — offer to add what was typed;
                // 2. a View whose filter names a performer or an instrument that has been
                //    removed (E42) — say so, and offer the editor;
                // 3. genuinely no songs — offer Import.
                //
                // Cause 2 used to render as cause 3, so a phone holding 479 songs told the
                // user they had none and pointed them at Import, which is the one destructive
                // path in the app. The predicate is the shared core's; this only branches.
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = when {
                            state.query.isNotEmpty() -> Messages.nothingMatches(state.query)
                            filterNamesRemovedRow ->
                                "This view filters on someone — or something — that has " +
                                    "been removed, so no song can match it. Your songs are " +
                                    "all still here. Edit the view to point it at a " +
                                    "performer or an instrument that still exists."
                            else -> "No songs yet. Use Import to load your database file, " +
                                "or add one by hand."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    // Searching for a song that turns out not to be there is exactly the
                    // moment you want to add it, with the title already typed.
                    if (state.query.isNotEmpty()) {
                        Button(onClick = { onAddSong(state.query) }) {
                            Text("Add \"${state.query}\"")
                        }
                    } else if (filterNamesRemovedRow) {
                        // E42: no automatic repair is honest here, so the way out is explicit.
                        Button(
                            onClick = onEditView,
                            modifier = Modifier.testTag(SessionTags.EDIT_BROKEN_VIEW),
                        ) {
                            Text("Edit this view")
                        }
                    }
                }
            }
        }
    }
}

/**
 * One row. Tap logs it — no dialog, no confirmation, no wait on the database. Long-press
 * opens the feel sheet (decision 46); it is an addition to the tap path, never the only way
 * to log.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    row: SessionRow,
    loggedCount: Int,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.artistName ?: "unknown artist",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StalenessBadge(row = row, loggedCount = loggedCount)
    }
}

/** Days since the last live practice on the selected instrument, "never" when there is none. */
@Composable
private fun StalenessBadge(row: SessionRow, loggedCount: Int) {
    val days = row.daysSince
    val scheme = MaterialTheme.colorScheme
    val container = when {
        loggedCount > 0 -> scheme.primary
        days == null -> scheme.errorContainer
        days >= 30L -> scheme.tertiaryContainer
        else -> scheme.surfaceVariant
    }
    val content = when {
        loggedCount > 0 -> scheme.onPrimary
        days == null -> scheme.onErrorContainer
        days >= 30L -> scheme.onTertiaryContainer
        else -> scheme.onSurfaceVariant
    }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Text(
            text = when {
                loggedCount > 1 -> "logged ×$loggedCount"
                loggedCount == 1 -> "logged"
                else -> row.badge
            },
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * Long-press: `feel` 1-3 and an optional note (decision 46). Both are optional — the sheet
 * can log with neither, which is exactly what a plain tap does.
 *
 * The numbers carry no words because the spec assigns them no meaning; decision 46 says
 * "1-3, optional" and nothing about which end is good.
 *
 * The date sits here too, and only here: decision 45 makes logging for a past date
 * available but demoted, never on the primary tap path. Three days back is as far as it
 * goes, because a fortnight of real use is meant to tell us whether it is needed at all.
 *
 * **E2: the capability editor is reached from here, and the feel rating keeps its exact
 * cost.** Decision 46 makes long-press *the* rating affordance and a plain tap the log, so
 * this sheet shows the feel chips **and** a row opening the editor — never a chooser in front
 * of them. Everything above [onEditLineUp]'s row is unchanged and in the same place: the
 * rating is still one long-press and one chip, and the new row is appended below the Log
 * button where nothing that was already on the rating path has to move past it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeelSheet(
    row: SessionRow,
    onDismiss: () -> Unit,
    onLog: (Long?, String?, String) -> Unit,
    onEditLineUp: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var feel by remember { mutableStateOf<Long?>(null) }
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (value in 1L..3L) {
                    FilterChip(
                        selected = feel == value,
                        onClick = { feel = if (feel == value) null else value },
                        label = { Text(value.toString()) },
                        modifier = Modifier.weight(1f).height(56.dp),
                    )
                }
            }

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

            // E2's other half, and the only new thing on this sheet. It is below the Log
            // button on purpose: the rating is what a long-press is for, and this must not
            // sit between the user and it.
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
