package dev.repertosaurus.android

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.repertosaurus.android.theme.MutedLine
import dev.repertosaurus.android.theme.InkFooter
import dev.repertosaurus.android.theme.Motion
import dev.repertosaurus.android.theme.PrimaryButton
import dev.repertosaurus.android.theme.RuledItem
import dev.repertosaurus.android.theme.SecondaryButton
import dev.repertosaurus.android.theme.Segment
import dev.repertosaurus.android.theme.SegmentLabel
import dev.repertosaurus.android.theme.SegmentLayout
import dev.repertosaurus.android.theme.SegmentStrip
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.RatingsTarget
import dev.repertosaurus.session.SessionRow
import dev.repertosaurus.session.SessionState
import dev.repertosaurus.session.SessionView
import dev.repertosaurus.session.SuggestTuning
import dev.repertosaurus.session.ViewFilter
import dev.repertosaurus.session.identity
import dev.repertosaurus.session.part
import dev.repertosaurus.session.triageAvailable
import dev.repertosaurus.session.suggestionCard

/** Stable handles for the instrumented tests and for on-device inspection. */
internal object SessionTags {
    /** E47's way out of a View whose filter names a row that is gone. */
    const val EDIT_BROKEN_VIEW: String = "session-edit-broken-view"

    /** The session list itself, for scrolling it in a test. */
    const val LIST: String = "session-list"

    /** visual-identity VI20: a logged row on its way out, which has no other semantics. */
    fun leaving(songId: String): String = "session-leaving-$songId"
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
    // Suggest SG15: the radar's tuning, a device preference, and where a change to it goes.
    suggestTuning: SuggestTuning,
    onTune: (SuggestTuning) -> Unit,
    onOpenDrawer: () -> Unit,
    onExport: () -> Unit,
    // Triage T10: the device's owner performer. The session state resolves the part from it (T9), for the
    // sort and for the View menu's "Rate these songs", which opens a route, off the tap path.
    ownerPerformerId: String?,
    onRateSongs: (RatingsTarget) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val transfer by viewModel.transfer.collectAsState()
    LaunchedEffect(viewModel, ownerPerformerId) { viewModel.setOwnerPerformer(ownerPerformerId) }
    val snackbarHostState = remember { SnackbarHostState() }
    var feelFor by remember { mutableStateOf<SessionRow?>(null) }

    // visual-identity VI20: rows just logged, drawn in place while they leave.
    val departures = rememberDepartures(state.pending) { viewModel.state.value.pending }
    val logAndDepart = { row: SessionRow, log: () -> Unit -> departures.logAndDepart(row, state.pending, log) }
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
    val suggestions = viewModel.suggestions
    val suggestion by suggestions.deck.collectAsState()

    val undo = state.undo
    LaunchedEffect(undo?.tapId) {
        if (undo != null) {
            val result = snackbarHostState.showSnackbar(
                message = Messages.logged(undo.songTitle, undo.feel),
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
        bottomBar = {
            // visual-identity VI15: the screen's one primary action, full width, below the list so
            // it never covers the last row as a FAB did.
            InkFooter {
                PrimaryButton(
                    text = "Add song",
                    onClick = {
                        addSongTitle = state.query
                        viewModel.loadArtists()
                        addingSong = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        topBar = {
            SessionHeader(
                view = state.view,
                instruments = state.instruments,
                performers = performers,
                songCount = state.pending.size + state.logged.size,
                onOpenDrawer = onOpenDrawer,
                onExport = onExport,
                onSwitchView = { switching = true },
                onSuggest = { suggestions.open(suggestTuning) },
                suggestEnabled = !state.loading,
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // The practice instrument (V13): what a tap logs to, and what staleness is
            // measured against. It is **not** the capability filter — that is a field of
            // the View, set in the editor and summarised in the header above. Merging the
            // two back into one control is the bug Views exist to fix.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                SegmentStrip(layout = SegmentLayout.ContentWidth) {
                    for (chip in state.instruments) {
                        Segment(
                            selected = chip.id == state.selectedInstrumentId,
                            onClick = { viewModel.selectInstrument(chip.id) },
                        ) {
                            SegmentLabel(chip.label, modifier = Modifier.padding(horizontal = 8.dp))
                        }
                    }
                }
            }

            // A mis-tap that reorders the list mid-session is worse than useless, so nothing that
            // reorders is within reach of a thumb aiming at a song.
            SortControl(
                order = state.order,
                triageAvailable = state.triageAvailable,
                onOrder = viewModel::setOrder,
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            SongSearchField(query = state.query, onQuery = viewModel::setQuery)

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
                departures = departures,
                onTap = { row -> logAndDepart(row) { viewModel.log(row.songId) } },
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
                logAndDepart(row) { viewModel.log(row.songId, feel, note, loggedOn) }
                feelFor = null
            },
            onEditLineUp = {
                feelFor = null
                viewModel.openCapabilities(row.identity())
            },
        )
    }

    // Suggest SG2-SG4. "Log it" is the plain tap's log through the same departure, so a visible row
    // leaves the list as it does for a tap; dismissing writes nothing (SG3). SG12's skip window is the holder's.
    // SG11: "Tune" is collapsed on every opening, and kept across a rotation while open.
    var tuneOpen by rememberSaveable(suggestion != null) { mutableStateOf(false) }
    val skipStaged by suggestions.staged.collectAsState()
    suggestion?.let { deck ->
        SuggestSheet(
            card = state.suggestionCard(deck),
            tuning = suggestTuning,
            part = state.part,
            skipStaged = skipStaged != null,
            tuneOpen = tuneOpen,
            onTuneOpen = { tuneOpen = it },
            onLog = { row -> logAndDepart(row) { if (suggestions.take(row.songId)) viewModel.log(row.songId) } },
            onAnother = { suggestions.another(suggestTuning) },
            onUndoSkip = suggestions::undo,
            onTune = { tuning ->
                onTune(tuning)
                suggestions.retune(tuning)
            },
            onDismiss = suggestions::close,
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
            rateThese = remember(state.part, state.rows, state.instruments) {
                rateThese(state.part, state.rows, state.instruments)
            },
            onRate = { target ->
                switching = false
                onRateSongs(target)
            },
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
 * Coldest first, never-practised leading, with everything logged in this session moved
 * below a divider — "the row shows it is logged and moves out of the way". A logged row
 * stays tappable: a second pass at the same song on the same day is a second session and
 * the UI must not treat it as a mistake (decision 47).
 *
 * visual-identity VI20: leaving rows are drawn where they were until their exit has run, and every
 * item moves with the one spring. Foundation 1.6 has `animateItemPlacement` and no item enter or exit
 * animation, which is why the exit is done by hand ([Departures]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionList(
    state: SessionState,
    departures: Departures,
    filterNamesRemovedRow: Boolean,
    onTap: (SessionRow) -> Unit,
    onLongPress: (SessionRow) -> Unit,
    onAddSong: (String) -> Unit,
    onEditView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries by remember(state.pending) { derivedStateOf { departures.entries(state.pending) } }
    val listState = rememberLazyListState()
    ScrollToTopOnNewList(listState, state)
    LazyColumn(
        modifier = modifier.testTag(SessionTags.LIST),
        state = listState,
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        items(entries, key = { it.key }) { entry ->
            RuledItem(modifier = Modifier.animateItemPlacement(Motion.spring())) {
                when (entry) {
                    is ListEntry.Pending -> SongRow(
                        row = entry.row,
                        loggedCount = 0,
                        onTap = { onTap(entry.row) },
                        onLongPress = { onLongPress(entry.row) },
                    )
                    is ListEntry.Departing -> LeavingRow(leaving = entry.leaving, onLeft = departures::left)
                }
            }
        }

        if (state.logged.isNotEmpty()) {
            item(key = "logged-header") {
                Column(modifier = Modifier.animateItemPlacement(Motion.spring()).fillMaxWidth().padding(16.dp)) {
                    Text(
                        "Logged this session · ${state.logged.size}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    MutedLine("Tap again for a second pass.")
                }
            }
            items(state.logged, key = { "logged:${it.row.songId}" }) { logged ->
                RuledItem(modifier = Modifier.animateItemPlacement(Motion.spring())) {
                    SongRow(
                        row = logged.row,
                        loggedCount = logged.countThisSession,
                        onTap = { onTap(logged.row) },
                        onLongPress = { onLongPress(logged.row) },
                    )
                }
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
                    // moment you want to add it, with the title already typed. Secondary, both:
                    // Add song at the foot stays the screen's one primary (VI12).
                    if (state.query.isNotEmpty()) {
                        SecondaryButton(text = "Add \"${state.query}\"", onClick = { onAddSong(state.query) })
                    } else if (filterNamesRemovedRow) {
                        // E42: no automatic repair is honest here, so the way out is explicit.
                        SecondaryButton(
                            text = "Edit this view",
                            onClick = onEditView,
                            modifier = Modifier.testTag(SessionTags.EDIT_BROKEN_VIEW),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Triage T6: a new order starts at its top, and so does another View, which is another list. Only a real
 * change scrolls: the list last shown is saved with the scroll position, so a rotation keeps both.
 */
@Composable
private fun ScrollToTopOnNewList(listState: LazyListState, state: SessionState) {
    val shown = listOf(state.order.name, state.view?.id, state.view?.practiceInstrumentId).joinToString("|")
    var lastShown by rememberSaveable { mutableStateOf(shown) }
    LaunchedEffect(shown) {
        if (shown != lastShown) {
            lastShown = shown
            listState.scrollToItem(0)
        }
    }
}
