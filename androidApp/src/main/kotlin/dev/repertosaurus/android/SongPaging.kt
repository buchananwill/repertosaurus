package dev.repertosaurus.android

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.repertosaurus.android.theme.DisplayText
import dev.repertosaurus.android.theme.DisplayType
import dev.repertosaurus.android.theme.Segment
import dev.repertosaurus.android.theme.SegmentLayout
import dev.repertosaurus.android.theme.SegmentStrip
import dev.repertosaurus.session.PageFilter
import dev.repertosaurus.session.SongLetters
import dev.repertosaurus.session.SongPaging

/**
 * **triage T3-T5a on screen: the search, the filter and the letter strip** that the ratings editor
 * and the Repertoire toggle list both draw over their core `SongPaging`.
 */

/** Stable handles for the paging controls, under a screen's own prefix. */
internal object PagingTags {
    fun search(prefix: String): String = "$prefix-search"
    fun searchHeading(prefix: String): String = "$prefix-search-heading"
    fun letter(prefix: String, letter: Char): String = "$prefix-letter-$letter"
    fun filter(prefix: String, filter: PageFilter): String = "$prefix-filter-${filter.name}"
}

/**
 * The paging controls as a list's leading items, so they scroll away and a page keeps the screen.
 * While a search is typed it overrides the letter under a "Search" heading (T4), and no letter is
 * selected (journal session 11, D59 #8).
 */
internal fun LazyListScope.pagingControls(
    paging: SongPaging,
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
            selected = if (paging.searching) null else paging.letter,
            enabled = letters,
            onSelect = onLetter,
            tagPrefix = tagPrefix,
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

/** A letter cell's narrowest: a display letter at font scale 1.3 still fits (VI8). */
private val MinLetterCell: Dp = 36.dp

/**
 * **T3, visual-identity VI13: the letter strip**, a [SegmentLayout.Grid] that wraps and never scrolls
 * sideways. A letter outside [enabled] is a disabled [Segment]: greyed, and not tappable.
 */
@Composable
private fun LetterStrip(
    selected: Char?,
    enabled: Set<Char>,
    onSelect: (Char) -> Unit,
    tagPrefix: String,
    modifier: Modifier = Modifier,
) {
    SegmentStrip(modifier = modifier, layout = SegmentLayout.Grid(MinLetterCell)) {
        for (letter in SongLetters.STRIP) {
            Segment(
                selected = letter == selected,
                onClick = { onSelect(letter) },
                enabled = letter in enabled,
                modifier = Modifier.testTag(PagingTags.letter(tagPrefix, letter)),
            ) {
                DisplayText(letter.toString(), style = DisplayType.Badge, maxLines = 1)
            }
        }
    }
}
