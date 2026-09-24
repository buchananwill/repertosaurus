package dev.repertosaurus.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.repertosaurus.android.theme.DisplayText
import dev.repertosaurus.android.theme.DisplayType
import dev.repertosaurus.android.theme.InkTextField
import dev.repertosaurus.android.theme.PrimaryButton
import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.session.ArtistSuggestions
import dev.repertosaurus.session.Messages

/** Stable handles for the instrumented tests. */
internal object AddSongTags {
    const val SHEET: String = "add-song-sheet"
    const val TITLE: String = "add-song-title"
    const val ARTIST: String = "add-song-artist"
    const val ADD: String = "add-song-add"
}

/**
 * Add a song: a title, and an artist typed into a type-ahead. Nothing else is asked for —
 * decisions 27 and 37 are explicit that no key may be required, and this is not the song
 * editor.
 *
 * **R15: one component, shared by the logger and the Songs route.** It used to be private to
 * `SessionScreen`; the logger's add path is unchanged by the move.
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
 *
 * [error] and [busy] are for a caller that keeps the sheet open until the add is confirmed
 * (E44) — the Songs route. The logger closes it on dispatch, as it always has, and passes
 * neither.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddSongSheet(
    initialTitle: String,
    artists: List<RepertosaurusRepository.Artist>,
    onDismiss: () -> Unit,
    onAdd: (title: String, artistName: String, artistId: String?) -> Unit,
    error: String? = null,
    busy: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState()
    var title by remember { mutableStateOf(initialTitle) }
    var artist by remember { mutableStateOf("") }
    var pickedArtistId by remember { mutableStateOf<String?>(null) }

    val commit = {
        if (title.isNotBlank() && !busy) onAdd(title, artist, pickedArtistId)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(AddSongTags.SHEET)
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DisplayText("Add a song", style = DisplayType.Heading)

            // E43: a refusal stays on the sheet, in the error colour, with what was typed — the
            // routes' own status lines (style review F17 N8), not a third copy of them.
            StatusLines(message = null, error = error)

            InkTextField(
                value = title,
                onValueChange = { title = it },
                label = "Title",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                fieldModifier = Modifier.testTag(AddSongTags.TITLE),
            )

            // F22 B3: the one type-ahead, shared with the song detail's artist and groove.
            LookupTypeAhead(
                label = "Artist",
                text = artist,
                picked = pickedArtistId != null,
                items = artists,
                suggest = { typed, all -> ArtistSuggestions.search(typed, all) },
                name = { it.name },
                hint = { state ->
                    when (state) {
                        TypeAheadHint.Picked -> "Uses the existing ${artist.trim()}."
                        is TypeAheadHint.Exact -> "Uses the existing ${state.match.name}."
                        TypeAheadHint.Blank -> "Leave blank for Unknown Artist."
                        is TypeAheadHint.New -> "Enter adds it. Nothing else to fill in."
                    }
                },
                onType = {
                    artist = it
                    // Typing again abandons a picked suggestion; the name now rules.
                    pickedArtistId = null
                },
                onPick = { match ->
                    artist = match.name
                    pickedArtistId = match.id
                },
                modifier = Modifier.testTag(AddSongTags.ARTIST),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
            )

            PrimaryButton(
                text = Messages.ADD_SONG,
                onClick = commit,
                enabled = title.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth().testTag(AddSongTags.ADD),
            )
        }
    }
}
