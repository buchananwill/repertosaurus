package dev.repertosaurus.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.repertosaurus.android.theme.DisplayText
import dev.repertosaurus.android.theme.DisplayType
import dev.repertosaurus.android.theme.InkHeader
import dev.repertosaurus.android.theme.LetterStrip
import dev.repertosaurus.android.theme.RowRule
import dev.repertosaurus.android.theme.SecondaryButton
import dev.repertosaurus.android.theme.Segment
import dev.repertosaurus.android.theme.SegmentStrip
import dev.repertosaurus.android.theme.Tokens
import dev.repertosaurus.core.RatingKind
import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.PageFilter
import dev.repertosaurus.session.RatingsEditor
import dev.repertosaurus.session.RatingsEditorState
import dev.repertosaurus.session.RatingsSource
import dev.repertosaurus.session.SongLetters

/** Stable handles for the instrumented tests. */
internal object RatingsEditorTags {
    const val SCREEN: String = "ratings-editor"
    const val TITLE: String = "ratings-title"
    const val LIST: String = "ratings-list"
    const val DONE: String = "ratings-done"
    const val SONGS: String = "ratings-songs"

    /** The paging controls' prefix: `ratings-search`, `ratings-letter-D`, `ratings-filter-UNSET`. */
    const val PAGING: String = "ratings"

    fun row(songId: String): String = "ratings-row-$songId"

    /** T2, journal session 11 F8 N7: a `tagPrefix` unique per song and kind. */
    fun control(songId: String, kind: RatingKind): String = "ratings-$songId-${kind.name.lowercase()}"
}

/** Stable handles for the paging controls, under a screen's own prefix. */
internal object PagingTags {
    fun search(prefix: String): String = "$prefix-search"
    fun searchHeading(prefix: String): String = "$prefix-search-heading"
    fun letters(prefix: String): String = "$prefix-letter"
    fun letter(prefix: String, letter: Char): String = "${letters(prefix)}-$letter"
    fun filter(prefix: String, filter: PageFilter): String = "$prefix-filter-${filter.name}"
}

/** T5: the editor's filter words, in the order the strip shows them. */
private val RATING_FILTERS = listOf(PageFilter.ALL to "All", PageFilter.UNSET to "Unrated", PageFilter.SET to "Rated")

/**
 * **The ratings editor** — triage T1-T5. One screen for both entry points: the Repertoire route's
 * role (with [onSongs], which returns to that role's toggle list) and the View menu's "Rate these
 * songs". The title names the part.
 *
 * **Never on the tap path** (vision, Triage): nothing here logs practice, and nothing on the logger
 * leads here but the View menu. Back is the caller's (E24).
 */
@Composable
public fun RatingsEditorScreen(
    viewModel: RatingsEditorViewModel,
    onDone: () -> Unit,
    onSongs: (() -> Unit)? = null,
) {
    val editor by viewModel.state.collectAsState()
    val open = editor ?: return
    Column(modifier = Modifier.fillMaxSize().testTag(RatingsEditorTags.SCREEN)) {
        RatingsHeader(title = open.target.title, onDone = onDone, onSongs = onSongs)
        StatusLines(message = null, error = open.error)
        if (open.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        RatingsList(
            editor = open,
            onQuery = viewModel::setQuery,
            onLetter = viewModel::setLetter,
            onFilter = viewModel::setFilter,
            onRate = viewModel::rate,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

/** visual-identity VI9, VI15's pattern: the indigo bar, the part as the screen title. */
@Composable
private fun RatingsHeader(title: String, onDone: () -> Unit, onSongs: (() -> Unit)?) {
    InkHeader(
        navigation = { DisplayText("Ratings", style = DisplayType.Subline) },
        actions = {
            if (onSongs != null) {
                SecondaryButton(text = "Songs", onClick = onSongs, modifier = Modifier.testTag(RatingsEditorTags.SONGS))
            }
            SecondaryButton(text = "Done", onClick = onDone, modifier = Modifier.testTag(RatingsEditorTags.DONE))
        },
        title = {
            DisplayText(
                title,
                style = DisplayType.ScreenTitle,
                maxLines = 2,
                modifier = Modifier.testTag(RatingsEditorTags.TITLE),
            )
        },
    )
}

@Composable
private fun RatingsList(
    editor: RatingsEditor,
    onQuery: (String) -> Unit,
    onLetter: (Char) -> Unit,
    onFilter: (PageFilter) -> Unit,
    onRate: (songId: String, kind: RatingKind, level: RatingLevel?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // T3: greyed letters follow the ratings as they change; the page itself does not (RatingsEditor).
    val letters = remember(editor.songs, editor.ratings, editor.paging.filter) { editor.paging.letters(editor.paged) }
    val visible = remember(editor.songs, editor.order) { editor.visible() }
    LazyColumn(
        modifier = modifier.testTag(RatingsEditorTags.LIST),
        contentPadding = PaddingValues(bottom = 48.dp),
    ) {
        pagingControls(
            paging = editor.paging,
            letters = letters,
            filters = RATING_FILTERS,
            onQuery = onQuery,
            onLetter = onLetter,
            onFilter = onFilter,
            tagPrefix = RatingsEditorTags.PAGING,
        )
        items(visible, key = { it.songId }) { song ->
            val ratings = editor.ratingsOf(song.songId)
            RatingRow(
                songId = song.songId,
                title = song.title,
                artistName = song.artistName,
                priority = ratings.priority,
                confidence = ratings.confidence,
                onRate = onRate,
            )
        }
        if (visible.isEmpty() && !editor.loading) {
            item(key = "empty") {
                Text(
                    text = when {
                        editor.paging.searching -> Messages.nothingMatches(editor.paging.query)
                        editor.songs.isNotEmpty() -> NOTHING_UNDER_THE_FILTER
                        editor.target.source == RatingsSource.EnabledParts -> NO_ENABLED_PARTS
                        else -> EMPTY_VIEW
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(32.dp),
                )
            }
        }
    }
}

/**
 * T2: the song's title and artist over two labelled [RatingSegmentedControl]s at the full 0-3 range.
 * A tap writes (RS9); there is no Save.
 */
@Composable
private fun RatingRow(
    songId: String,
    title: String,
    artistName: String?,
    priority: RatingLevel?,
    confidence: RatingLevel?,
    onRate: (songId: String, kind: RatingKind, level: RatingLevel?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(RatingsEditorTags.row(songId))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!artistName.isNullOrBlank()) {
            Text(
                artistName,
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.InkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LabelledRating("Priority", songId, RatingKind.PRIORITY, priority, onRate)
        LabelledRating("Confidence", songId, RatingKind.CONFIDENCE, confidence, onRate)
    }
    RowRule()
}

@Composable
private fun LabelledRating(
    label: String,
    songId: String,
    kind: RatingKind,
    value: RatingLevel?,
    onRate: (songId: String, kind: RatingKind, level: RatingLevel?) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
    RatingSegmentedControl(
        value = value,
        onValueChange = { onRate(songId, kind, it) },
        tagPrefix = RatingsEditorTags.control(songId, kind),
    )
}

/**
 * **triage T3-T5a: the search, the filter and the letter strip**, as the leading items of a song
 * list, so they scroll away and a 400-song page keeps the screen. Both the ratings editor and the
 * Repertoire toggle list draw these. While a search is typed, it overrides the letter under a
 * "Search" heading (T4) and no letter shows as selected.
 */
internal fun LazyListScope.pagingControls(
    paging: RatingsEditorState,
    letters: Set<Char>,
    filters: List<Pair<PageFilter, String>>,
    onQuery: (String) -> Unit,
    onLetter: (Char) -> Unit,
    onFilter: (PageFilter) -> Unit,
    tagPrefix: String,
) {
    item(key = "paging-search") {
        SongSearchField(query = paging.query, onQuery = onQuery, modifier = Modifier.testTag(PagingTags.search(tagPrefix)))
    }
    item(key = "paging-filter") {
        SegmentStrip(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            for ((filter, label) in filters) {
                Segment(
                    selected = paging.filter == filter,
                    onClick = { onFilter(filter) },
                    modifier = Modifier.testTag(PagingTags.filter(tagPrefix, filter)),
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                }
            }
        }
    }
    item(key = "paging-letters") {
        LetterStrip(
            letters = SongLetters.STRIP,
            selected = if (paging.searching) null else paging.letter,
            enabled = letters,
            onSelect = onLetter,
            tagPrefix = PagingTags.letters(tagPrefix),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
    if (paging.searching) {
        item(key = "paging-search-heading") {
            DisplayText(
                "Search",
                style = DisplayType.Heading,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag(PagingTags.searchHeading(tagPrefix)),
            )
        }
    }
}

/** T5: a page the filter has emptied, on either list. */
internal const val NOTHING_UNDER_THE_FILTER = "No songs here under this filter."
private const val NO_ENABLED_PARTS = "This part is on for no songs yet. Turn some on under Songs."
private const val EMPTY_VIEW = "This view has no songs."
