package dev.repertosaurus.android

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.repertosaurus.android.theme.RouteHeader
import dev.repertosaurus.android.theme.RuledItem
import dev.repertosaurus.android.theme.SecondaryButton
import dev.repertosaurus.core.RatingKind
import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.PageFilter
import dev.repertosaurus.session.RatingsEditor
import dev.repertosaurus.session.RatingsSource

/** Stable handles for the instrumented tests. */
internal object RatingsEditorTags {
    const val SCREEN: String = "ratings-editor"
    const val TITLE: String = "ratings-title"
    const val LIST: String = "ratings-list"
    const val DONE: String = "ratings-done"
    const val SONGS: String = "ratings-songs"

    /** The paging controls' prefix (`PagingTags`). */
    const val PAGING: String = "ratings"

    fun row(songId: String): String = "ratings-row-$songId"

    /** T2, journal session 11 F8 N7: a `tagPrefix` unique per song and kind. */
    fun control(songId: String, kind: RatingKind): String = "ratings-$songId-${kind.name.lowercase()}"
}

/** T5: the editor's filter words, in strip order. */
private val RATING_FILTERS = listOf(PageFilter.ALL to "All", PageFilter.UNSET to "Unrated", PageFilter.SET to "Rated")

/** A rating row's side padding, which the controls' width is measured inside (F22 B2). */
private val RowInset = 16.dp

/**
 * **The ratings editor** (triage T1-T5), for both entry points. [onSongs], the Repertoire role's
 * way back to its toggle list, is null from the View menu. Back is the caller's (E24).
 */
@Composable
public fun RatingsEditorScreen(
    viewModel: RatingsEditorViewModel,
    onDone: () -> Unit,
    onSongs: (() -> Unit)?,
) {
    val editor by viewModel.state.collectAsState()
    val open = editor ?: return
    Column(modifier = Modifier.fillMaxSize().testTag(RatingsEditorTags.SCREEN)) {
        RolePaneHeader(
            pane = Messages.RATINGS_PANE,
            title = open.target.title,
            onDone = onDone,
            other = Messages.DRAWER_SONGS,
            onOther = onSongs,
            titleModifier = Modifier.testTag(RatingsEditorTags.TITLE),
            doneModifier = Modifier.testTag(RatingsEditorTags.DONE),
            otherModifier = Modifier.testTag(RatingsEditorTags.SONGS),
        )
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

/**
 * Triage T1: the header of a role's two panes, the toggle list and this editor. [pane] names the one shown,
 * the part is the title, and [onOther], when there is another pane (the View menu's editor has none), leads
 * to it by its name, [other].
 */
@Composable
internal fun RolePaneHeader(
    pane: String,
    title: String,
    onDone: () -> Unit,
    other: String,
    onOther: (() -> Unit)?,
    titleModifier: Modifier = Modifier,
    doneModifier: Modifier = Modifier,
    otherModifier: Modifier = Modifier,
) {
    RouteHeader(
        title = title,
        kicker = pane,
        onDone = onDone,
        doneModifier = doneModifier,
        titleModifier = titleModifier,
        actions = {
            if (onOther != null) SecondaryButton(text = other, onClick = onOther, modifier = otherModifier)
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
    val filter = editor.page.paging.filter
    // T3's greyed letters follow the ratings, which only matter under a filter (F22 N1).
    val ratingsKey = if (filter == PageFilter.ALL) null else editor.ratings
    val letters = remember(editor.songs, filter, ratingsKey) { editor.page.paging.letters(editor.paged()) }
    val visible = remember(editor.songs, editor.page.order) { editor.visible() }
    BoxWithConstraints(modifier = modifier) {
        // F22 B2: one label-fits decision for every control in the list.
        val labelsFit = rememberRatingLabelsFit(maxWidth - RowInset * 2)
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(RatingsEditorTags.LIST),
            contentPadding = PaddingValues(bottom = 48.dp),
        ) {
            pagingControls(
                paging = editor.page.paging,
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
                    labelsFit = labelsFit,
                    onRate = onRate,
                )
            }
            if (visible.isEmpty() && !editor.loading) {
                item(key = "empty") {
                    Text(emptyLine(editor), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(32.dp))
                }
            }
        }
    }
}

private fun emptyLine(editor: RatingsEditor): String = when {
    editor.page.paging.searching -> Messages.nothingMatches(editor.page.paging.query)
    editor.songs.isNotEmpty() -> Messages.NOTHING_UNDER_THE_FILTER
    editor.target.source == RatingsSource.EnabledParts -> Messages.RATINGS_NO_ENABLED_PARTS
    else -> Messages.RATINGS_EMPTY_VIEW
}

/** T2: the song over its Priority and Confidence controls, at the full 0-3 range. A tap writes. */
@Composable
private fun RatingRow(
    songId: String,
    title: String,
    artistName: String?,
    priority: RatingLevel?,
    confidence: RatingLevel?,
    labelsFit: Boolean,
    onRate: (songId: String, kind: RatingKind, level: RatingLevel?) -> Unit,
) {
    RuledItem(modifier = Modifier.fillMaxWidth().testTag(RatingsEditorTags.row(songId))) {
        Column(modifier = Modifier.padding(horizontal = RowInset, vertical = 12.dp)) {
            SongTitleArtist(title = title, artistName = artistName)
            LabelledRating("Priority", songId, RatingKind.PRIORITY, priority, labelsFit, onRate, Modifier.padding(top = 8.dp))
            LabelledRating("Confidence", songId, RatingKind.CONFIDENCE, confidence, labelsFit, onRate, Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun LabelledRating(
    label: String,
    songId: String,
    kind: RatingKind,
    value: RatingLevel?,
    labelsFit: Boolean,
    onRate: (songId: String, kind: RatingKind, level: RatingLevel?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        RatingSegmentedControl(
            value = value,
            onValueChange = { onRate(songId, kind, it) },
            labelsFit = labelsFit,
            tagPrefix = RatingsEditorTags.control(songId, kind),
        )
    }
}
