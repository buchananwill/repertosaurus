package dev.repertaurus.android

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.repertaurus.core.Timestamps
import dev.repertaurus.core.normalise
import dev.repertaurus.data.RepertaurusRepository
import dev.repertaurus.session.ArtistSuggestions
import dev.repertaurus.session.SessionOrder
import dev.repertaurus.session.SessionRow
import dev.repertaurus.session.SessionState
import kotlinx.datetime.LocalDate

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
                title = { Text("Repertaurus") },
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
                for (option in SessionOrder.values()) {
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
                onTap = viewModel::log,
                onLongPress = { row -> feelFor = row },
                onAddSong = { title ->
                    addSongTitle = title
                    viewModel.loadArtists()
                    addingSong = true
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
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionList(
    state: SessionState,
    onTap: (String) -> Unit,
    onLongPress: (SessionRow) -> Unit,
    onAddSong: (String) -> Unit,
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
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = if (state.query.isNotEmpty()) {
                            "Nothing matches \"${state.query}\"."
                        } else {
                            "No songs yet. Use Import to load your database file, " +
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
 * Add a song: a title, and an artist typed into a type-ahead. Nothing else is asked for —
 * decisions 27 and 37 are explicit that no key may be required, and this is not the song
 * editor.
 *
 * The artist field is the substance of this sheet. Decisions 16 and 17 call for a
 * type-ahead that creates on enter and surfaces near-matches *while typing*, with no admin
 * screen anywhere, and matching runs through the shared `normalise` — so `Fratellis`
 * surfaces `The Fratellis` and `AC DC` surfaces `AC/DC` before a second row can be made.
 * Even if the suggestion goes unnoticed, the derived id resolves to the same existing row,
 * because that id *is* `UUIDv5(namespace, normalise(name))`.
 *
 * Leaving the artist blank uses the seeded `Unknown Artist` (decision 28a). A musician
 * mid-practice must never be blocked from logging by an unsettled attribution.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSongSheet(
    initialTitle: String,
    artists: List<RepertaurusRepository.Artist>,
    onDismiss: () -> Unit,
    onAdd: (title: String, artistName: String, artistId: String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var title by remember { mutableStateOf(initialTitle) }
    var artist by remember { mutableStateOf("") }
    var pickedArtistId by remember { mutableStateOf<String?>(null) }

    val suggestions = remember(artist, artists) { ArtistSuggestions.search(artist, artists) }
    val exact = remember(artist, suggestions) {
        val typed = normalise(artist)
        suggestions.firstOrNull { normalise(it.name) == typed && typed.isNotEmpty() }
    }
    val commit = {
        if (title.isNotBlank()) onAdd(title, artist, pickedArtistId)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add a song", style = MaterialTheme.typography.headlineSmall)

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = artist,
                onValueChange = {
                    artist = it
                    // Typing again abandons a picked suggestion; the name now rules.
                    pickedArtistId = null
                },
                label = { Text("Artist") },
                supportingText = {
                    Text(
                        when {
                            exact != null -> "Uses the existing ${exact.name}."
                            artist.isBlank() -> "Leave blank for Unknown Artist."
                            else -> "Enter adds it. Nothing else to fill in."
                        },
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier.fillMaxWidth(),
            )

            if (suggestions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (match in suggestions) {
                        FilterChip(
                            selected = pickedArtistId == match.id,
                            onClick = {
                                artist = match.name
                                pickedArtistId = match.id
                            },
                            label = { Text(match.name) },
                            modifier = Modifier.height(44.dp),
                        )
                    }
                }
            }

            Button(
                onClick = commit,
                enabled = title.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text("Add song")
            }
        }
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeelSheet(
    row: SessionRow,
    onDismiss: () -> Unit,
    onLog: (Long?, String?, String) -> Unit,
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
        }
    }
}
