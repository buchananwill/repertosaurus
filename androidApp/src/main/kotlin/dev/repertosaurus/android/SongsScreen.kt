package dev.repertosaurus.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.repertosaurus.session.DetailRequest
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.SongSearch
import dev.repertosaurus.session.SongsState
import dev.repertosaurus.session.songLabel

/** Stable handles for the instrumented tests. */
internal object SongsTags {
    const val LIST: String = "songs-list"
    const val SEARCH: String = "songs-search"
    const val ADD: String = "songs-add"
    const val DISCARD: String = "songs-discard"
    const val KEEP_EDITING: String = "songs-keep-editing"
    fun row(songId: String): String = "songs-row-$songId"
}

/**
 * The Songs route — repertoire-editing R11-R23: every live song, searchable, and one song's
 * every editable field.
 *
 * **The detail is this route's own state, holding the song's id only** (R27), saved across
 * rotation, with its own back handler (E24): back from the detail closes it — asking first when
 * there are unsaved changes (R14) — and back from the list returns to the logger.
 *
 * **R18: the line-up is edited in the one capability editor**, driven through [session]'s
 * capability state exactly as the logger drives it (session 09 ruling). This screen owns the
 * song; it does not own a second capability editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun SongsScreen(viewModel: SongsViewModel, session: SessionViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val merge by viewModel.merge.collectAsState()
    val capabilities by session.capabilities.collectAsState()
    val sessionState by session.state.collectAsState()
    val performers by session.performers.collectAsState()
    val spelling by session.noteSpelling.collectAsState()

    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var confirmingDiscard by rememberSaveable { mutableStateOf(false) }
    var seenAdds by rememberSaveable { mutableLongStateOf(state.addsCommitted) }

    LaunchedEffect(Unit) {
        viewModel.clearMessages()
        viewModel.load()
    }

    // The ViewModel asks; the screen owns the drill-down (R27). R23: an add that resolved to an
    // existing song opens it; a removal closes it.
    LaunchedEffect(state.detailRequest) {
        when (val request = state.detailRequest) {
            is DetailRequest.Open -> {
                adding = false
                detailId = request.songId
            }
            DetailRequest.Close -> detailId = null
            null -> return@LaunchedEffect
        }
        viewModel.consumeDetailRequest()
    }

    val openId = detailId
    LaunchedEffect(openId) {
        if (openId == null) {
            viewModel.closeDetail()
            // The merge overlay belongs to the detail it was opened from (R38).
            viewModel.cancelMerge()
            // The capability editor is the logger's too; it must not reappear there on return.
            if (session.capabilities.value.song != null) session.closeCapabilities()
        } else {
            viewModel.openDetail(openId)
        }
    }

    // E44: the add sheet closes on a **confirmed** add, never on dispatch.
    LaunchedEffect(state.addsCommitted) {
        if (state.addsCommitted != seenAdds) {
            seenAdds = state.addsCommitted
            adding = false
        }
    }

    // F18 N6: the line-up is read again when a capability write **lands**, not when the sheet
    // is dismissed — a dismissal can come before the write it followed has run.
    LaunchedEffect(capabilities.revision) {
        if (openId != null) viewModel.refreshLineUp()
    }

    val detail = state.detail?.takeIf { it.songId == openId }
    val requestClose = {
        when {
            // F18 N4: Close waits for an in-flight save, so the save's result has a detail to land on.
            detail?.saving == true -> Unit
            // R14 and F16 #6: unsaved song fields or unsaved `song_instrument` edits.
            detail?.dirty == true -> confirmingDiscard = true
            else -> detailId = null
        }
    }

    // E24: the detail's own back handler, composed after the route's and so consulted first.
    BackHandler(enabled = openId != null) { requestClose() }

    // E24's overlays ruling: the merge overlay's handler is composed after the detail's, so it is
    // consulted first — back closes the preview to the picker, then the picker, then the detail.
    BackHandler(enabled = openId != null && merge != null) { viewModel.backFromMerge() }

    val openMerge = merge?.takeIf { openId != null }
    if (openMerge != null) {
        SongMergeScreen(
            state = openMerge,
            songs = state.songs,
            spelling = spelling,
            actions = SongMergeActions(
                onQuery = viewModel::setMergeQuery,
                onChoose = viewModel::chooseMergeWith,
                onBack = viewModel::backFromMerge,
                onSwap = viewModel::swapMerge,
                onPick = viewModel::pickMergeField,
                onToggleChild = viewModel::toggleMergeChild,
                onToggleEvent = viewModel::toggleMergeEvent,
                onConfirm = viewModel::confirmMerge,
            ),
        )
    } else if (openId != null) {
        SongDetailScreen(
            detail = detail,
            lists = SongDetailLists(state.artists, state.grooves, state.tags, state.instruments),
            spelling = spelling,
            message = state.message,
            error = state.error,
            actions = SongDetailActions(
                onEdit = viewModel::editDraft,
                onSave = viewModel::save,
                onClose = requestClose,
                onRemove = viewModel::removeSong,
                onToggleTag = viewModel::toggleTag,
                onAddTag = viewModel::addTagNamed,
                onAddInstrument = viewModel::addSongInstrument,
                onEditInstrument = viewModel::editSongInstrument,
                onSaveInstrument = viewModel::saveSongInstrument,
                onRemoveInstrument = viewModel::removeSongInstrument,
                onEditLineUp = { song -> session.openCapabilities(song) },
                onMerge = {
                    viewModel.clearMessages()
                    viewModel.startMerge()
                },
            ),
        )
    } else {
        SongListScreen(
            state = state,
            onQuery = viewModel::setQuery,
            onOpen = { songId ->
                viewModel.clearMessages()
                detailId = songId
            },
            onAdd = {
                viewModel.clearMessages()
                adding = true
            },
            onBack = onBack,
        )
    }

    // R18: the one capability editor, opened on this song through the logger's state holder.
    capabilities.song?.takeIf { it.songId == openId }?.let { song ->
        SongCapabilitySheet(
            song = song,
            lineUp = capabilities.lineUp,
            performers = performers,
            instruments = sessionState.instruments,
            busy = capabilities.busy,
            message = capabilities.message,
            error = capabilities.error,
            addsCommitted = capabilities.addsCommitted,
            onAdd = session::addCapability,
            onUpdate = session::updateCapability,
            onRemove = session::removeCapability,
            onDismiss = session::closeCapabilities,
        )
    }

    if (adding) {
        AddSongSheet(
            initialTitle = state.query,
            artists = state.artists,
            onDismiss = { adding = false },
            onAdd = viewModel::addSong,
            error = state.error,
            busy = state.adding,
        )
    }

    if (confirmingDiscard) {
        val label = detail?.record?.let { songLabel(it.title, it.artistName) }.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text("Discard your changes?") },
            text = { Text("Your edits to $label have not been saved.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDiscard = false
                        detailId = null
                    },
                    modifier = Modifier.testTag(SongsTags.DISCARD),
                ) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmingDiscard = false },
                    modifier = Modifier.testTag(SongsTags.KEEP_EDITING),
                ) {
                    Text("Keep editing")
                }
            },
        )
    }
}

/** R11: every live song, filtered and ordered in memory by `SongSearch` (E36). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongListScreen(
    state: SongsState,
    onQuery: (String) -> Unit,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
) {
    val shown = remember(state.songs, state.query) { SongSearch.search(state.songs, state.query) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Songs") },
                actions = { TextButton(onClick = onBack) { Text("Done") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, modifier = Modifier.testTag(SongsTags.ADD)) {
                Text("Add song")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SongSearchField(query = state.query, onQuery = onQuery, modifier = Modifier.testTag(SongsTags.SEARCH))
            Text(
                if (state.query.isEmpty()) {
                    Messages.songCount(state.songs.size.toLong())
                } else {
                    Messages.shownOf(shown.size, Messages.songCount(state.songs.size.toLong()))
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            StatusLines(message = state.message, error = state.error)
            if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            HorizontalDivider()

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).testTag(SongsTags.LIST),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(shown, key = { it.id }) { song ->
                    // A tap opens the song's detail — it never logs practice.
                    SongListRow(song = song, onClick = { onOpen(song.id) }, modifier = Modifier.testTag(SongsTags.row(song.id)))
                    HorizontalDivider()
                }
                if (shown.isEmpty() && !state.loading) emptyListLine(state.query, whenEmpty = Messages.NO_SONGS_YET)
            }
        }
    }
}
