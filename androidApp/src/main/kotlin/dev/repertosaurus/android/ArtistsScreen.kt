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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Artists") },
                actions = { TextButton(onClick = onBack) { Text("Done") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                "Renaming keeps every song attached. An artist with songs cannot be removed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SongSearchField(
                query = state.query,
                onQuery = viewModel::setQuery,
                placeholder = "Search artists",
                modifier = Modifier.testTag(ArtistTags.SEARCH),
            )
            if (state.query.isNotEmpty()) {
                Text(
                    Messages.shownOf(shown.size, Messages.artistCount(state.artists.size.toLong())),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            StatusLines(message = state.message, error = state.error)
            if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            HorizontalDivider()

            LazyColumn(
                modifier = Modifier.fillMaxWidth().testTag(ArtistTags.LIST),
                contentPadding = PaddingValues(bottom = 48.dp),
            ) {
                items(shown, key = { it.id }) { artist ->
                    ArtistRow(
                        artist = artist,
                        enabled = !state.busy,
                        onRename = { renamingId = artist.id },
                        onRemove = { removingId = artist.id },
                    )
                    HorizontalDivider()
                }
                // With no search typed, an empty list says nothing: the count line is enough.
                if (shown.isEmpty() && !state.busy) emptyListLine(state.query, whenEmpty = null)
            }
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
        AlertDialog(
            onDismissRequest = { removingId = null },
            title = { Text("Remove ${artist.name}?") },
            text = {
                Text(
                    "It stops being offered. An artist with songs is refused, and the screen " +
                        "says how many.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        removingId = null
                        viewModel.remove(artist.id, artist.name)
                    },
                    modifier = Modifier.testTag(ArtistTags.CONFIRM_REMOVE),
                ) {
                    Text("Remove")
                }
            },
            dismissButton = { TextButton(onClick = { removingId = null }) { Text("Cancel") } },
        )
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
            if (artist.sortName != artist.name) {
                Text(
                    "Sorted as ${artist.sortName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                Messages.songCount(artist.liveSongs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onRename,
            enabled = enabled,
            modifier = Modifier.testTag(ArtistTags.rename(artist.id)),
        ) {
            Text("Rename")
        }
        TextButton(
            onClick = onRemove,
            enabled = enabled,
            modifier = Modifier.testTag(ArtistTags.remove(artist.id)),
        ) {
            Text("Remove")
        }
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename ${artist.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    isError = name.isBlank(),
                    supportingText = { if (name.isBlank()) Text(Messages.ARTIST_NEEDS_NAME) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = sortName,
                    onValueChange = { sortName = it },
                    label = { Text("Sort name") },
                    supportingText = { Text("Leave blank to derive it from the name.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                Text(
                    // R22: renaming never re-derives the id, so every song stays attached.
                    "Every song by this artist stays attached to it.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(name, sortName) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag(ArtistTags.SAVE_RENAME),
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
