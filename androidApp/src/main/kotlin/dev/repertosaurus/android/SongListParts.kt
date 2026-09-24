package dev.repertosaurus.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.repertosaurus.android.theme.InkTextField
import dev.repertosaurus.android.theme.Tokens
import dev.repertosaurus.data.SongCatalog
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.songLabel

/**
 * **The song lists' shared parts** (style review F17 N1, F27 B3): the search box, the one-line
 * label, the title over the artist, the tappable song row and the empty-list line. The Songs list, the Repertoire toggle list,
 * the Artists list and the merge picker draw these rather than copies of them.
 */

/**
 * A list's search box: one line, a placeholder, and a Clear that appears once something is
 * typed. The filtering itself is the core's (`SongSearch`, `ArtistSearch`), in memory (E36).
 */
@Composable
internal fun SongSearchField(
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search title or artist",
) {
    // VI1, VI4: the house input (style review F21 B5).
    InkTextField(
        value = query,
        onValueChange = onQuery,
        placeholder = placeholder,
        trailingIcon = {
            if (query.isNotEmpty()) {
                TextButton(onClick = { onQuery("") }) { Text("Clear") }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/**
 * **A song's title over its artist**: the title in the song-title role, the artist muted beneath, and
 * **a missing artist omitted**, never worded. A sheet's heading passes its own [titleStyle].
 */
@Composable
internal fun SongTitleArtist(
    title: String,
    artistName: String?,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
) {
    Column(modifier = modifier) {
        Text(title, style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!artistName.isNullOrBlank()) {
            Text(
                artistName,
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.InkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One song in one line, `Title — Artist` — the core's label (E46), two lines at most. */
@Composable
internal fun SongLabelText(title: String, artistName: String?, modifier: Modifier = Modifier) {
    Text(
        text = songLabel(title, artistName),
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * **One tappable song row** (style review F27 B3): the Songs list and the merge picker. A tap
 * opens or picks the song — it never logs practice. [modifier] carries the row's test tag.
 */
@Composable
internal fun SongListRow(
    song: SongCatalog.SongListEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SongLabelText(
        title = song.title,
        artistName = song.artistName,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
    )
}

/**
 * **The one empty-list line** (style review F27 B3), for all four lists: a search that matched
 * nothing says so; an empty list with no search says [whenEmpty], or nothing when it is null.
 * The caller decides *whether* the list is empty and settled; this decides what it says.
 */
internal fun LazyListScope.emptyListLine(query: String, whenEmpty: String?) {
    val text = if (query.isNotEmpty()) Messages.nothingMatches(query) else whenEmpty ?: return
    item(key = "empty") {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(32.dp))
    }
}
