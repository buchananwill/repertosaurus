package dev.repertosaurus.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.repertosaurus.android.theme.DialogActions
import dev.repertosaurus.android.theme.DialogText
import dev.repertosaurus.android.theme.InkDialog
import dev.repertosaurus.android.theme.InkTextField
import dev.repertosaurus.android.theme.MutedLine
import dev.repertosaurus.android.theme.RouteHeader
import dev.repertosaurus.android.theme.RowRule
import dev.repertosaurus.android.theme.RuledItem
import dev.repertosaurus.android.theme.TextAction
import dev.repertosaurus.data.SongCatalog
import dev.repertosaurus.session.ArtistSearch
import dev.repertosaurus.session.Messages

/** Stable handles for the instrumented tests. */
internal object ArtistTags {
    const val LIST: String = "artists-list"
    const val SEARCH: String = "artists-search"
    const val CONFIRM_REMOVE: String = "artists-confirm-remove"
    const val SAVE_RENAME: String = "artists-save-rename"
    fun rename(artistId: String): String = "artists-rename-$artistId"
    fun remove(artistId: String): String = "artists-remove-$artistId"
}

/**
 * The Artists route — repertoire-editing R24, R25, R25a.
 *
 * Every live artist with its live song count; rename and sort name; remove, which the core
 * refuses with the count (R25) or with an explanation for *Unknown Artist* (R25a). The rename
 * and the removal confirmation are dialogs inside the route (E24): back closes them first. They
 * hold the artist's **id** and resolve the row from the live list on every composition, so a
 * write landing underneath cannot leave a dialog editing a snapshot (E45's lesson).
 */
@Composable
public fun ArtistsScreen(viewModel: ArtistsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var renamingId by rememberSaveable { mutableStateOf<String?>(null) }
    var removingId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.clearMessages()
        viewModel.load()
    }

    val renaming = renamingId?.let { id -> state.artists.firstOrNull { it.id == id } }
    val removing = removingId?.let { id -> state.artists.firstOrNull { it.id == id } }
    // F16 #8: in memory over the list already held (E36), in the list's own order.
    val shown = remember(state.artists, state.query) { ArtistSearch.search(state.artists, state.query) }

    Column(modifier = Modifier.fillMaxSize()) {
        RouteHeader(title = Messages.DRAWER_ARTISTS, onDone = onBack)
        MutedLine(
            "Renaming keeps every song attached. An artist with songs cannot be removed.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        SongSearchField(
            query = state.query,
            onQuery = viewModel::setQuery,
            placeholder = "Search artists",
            modifier = Modifier.testTag(ArtistTags.SEARCH),
        )
        if (state.query.isNotEmpty()) {
            CountLine(Messages.shownOf(shown.size, Messages.artistCount(state.artists.size.toLong())))
        }
        StatusLines(message = state.message, error = state.error)
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        RowRule(modifier = Modifier.padding(top = 4.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth().testTag(ArtistTags.LIST),
            contentPadding = PaddingValues(bottom = 48.dp),
        ) {
            items(shown, key = { it.id }) { artist ->
                RuledItem {
                    ArtistRow(
                        artist = artist,
                        enabled = !state.busy,
                        onRename = { renamingId = artist.id },
                        onRemove = { removingId = artist.id },
                    )
                }
            }
            // With no search typed, an empty list says nothing: the count line is enough.
            if (shown.isEmpty() && !state.busy) emptyListLine(state.query, whenEmpty = null)
        }
    }

    renaming?.let { artist ->
        RenameArtistDialog(
            artist = artist,
            onDismiss = { renamingId = null },
            onRename = { name, sortName ->
                renamingId = null
                viewModel.rename(artist.id, name, sortName)
            },
        )
    }

    removing?.let { artist ->
        InkDialog(onDismiss = { removingId = null }, title = "Remove ${artist.name}?") {
            DialogText("It stops being offered. An artist with songs is refused, and the screen says how many.")
            DialogActions(
                confirm = "Remove",
                onConfirm = {
                    removingId = null
                    viewModel.remove(artist.id, artist.name)
                },
                onDismiss = { removingId = null },
                confirmModifier = Modifier.testTag(ArtistTags.CONFIRM_REMOVE),
            )
        }
    }
}

@Composable
private fun ArtistRow(
    artist: SongCatalog.ArtistEntry,
    enabled: Boolean,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(artist.name, style = MaterialTheme.typography.titleMedium)
            if (artist.sortName != artist.name) MutedLine("Sorted as ${artist.sortName}")
            MutedLine(Messages.songCount(artist.liveSongs))
        }
        TextAction("Rename", onClick = onRename, enabled = enabled, modifier = Modifier.testTag(ArtistTags.rename(artist.id)))
        TextAction("Remove", onClick = onRemove, enabled = enabled, modifier = Modifier.testTag(ArtistTags.remove(artist.id)))
    }
}

/** R24: name and sort name. A blank sort name is derived from the name (decision 21). */
@Composable
private fun RenameArtistDialog(
    artist: SongCatalog.ArtistEntry,
    onDismiss: () -> Unit,
    onRename: (name: String, sortName: String) -> Unit,
) {
    var name by remember(artist.id) { mutableStateOf(artist.name) }
    var sortName by remember(artist.id) { mutableStateOf(artist.sortName) }
    InkDialog(onDismiss = onDismiss, title = "Rename ${artist.name}") {
        InkTextField(
            value = name,
            onValueChange = { name = it },
            label = "Name",
            isError = name.isBlank(),
            supportingText = if (name.isBlank()) Messages.ARTIST_NEEDS_NAME else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        InkTextField(
            value = sortName,
            onValueChange = { sortName = it },
            label = "Sort name",
            supportingText = "Leave blank to derive it from the name.",
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        // R22: renaming never re-derives the id, so every song stays attached.
        MutedLine("Every song by this artist stays attached to it.")
        DialogActions(
            confirm = "Save",
            onConfirm = { onRename(name, sortName) },
            onDismiss = onDismiss,
            confirmEnabled = name.isNotBlank(),
            confirmModifier = Modifier.testTag(ArtistTags.SAVE_RENAME),
        )
    }
}
