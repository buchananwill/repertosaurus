package dev.repertosaurus.session

import dev.repertosaurus.core.RatingKind
import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.data.PartRatings

/**
 * **The ratings editor's shared core** (triage T1-T5a, T9): the page a title files under, the
 * paging, search and filter state both song lists use, whose part the View entry rates, and the
 * editor's own list. Pure; the ViewModel, its queue and its scope stay in `androidApp` (E29).
 */

/**
 * **triage T3: the letter a song files under, and the strip's pages.** This is the one letter
 * rule. It is not [normalise], which only ever drops "The ": "A Day in the Life" files under D.
 */
public object SongLetters {

    /** T3: digits and symbols. */
    public const val OTHER: Char = '#'

    /** T3: the strip, in order. */
    public val STRIP: List<Char> = listOf(OTHER) + ('A'..'Z')

    private val ARTICLES = listOf("the ", "an ", "a ")

    /**
     * T3: the first character after a leading "The ", "A " or "An ", in upper case; [OTHER] when
     * that is not A-Z. A title that is only an article keeps it: "A" files under A.
     */
    public fun of(title: String): Char {
        val trimmed = title.trim()
        val article = ARTICLES.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
        val rest = article?.let { trimmed.substring(it.length).trimStart() }?.takeIf { it.isNotEmpty() } ?: trimmed
        val first = rest.firstOrNull()?.uppercaseChar() ?: return OTHER
        return if (first in 'A'..'Z') first else OTHER
    }
}

/**
 * **triage T5, T5a: the three-way filter.** The words are each list's: the editor's All / Unrated /
 * Rated, the toggle list's All / On / Off. [SET] is rated there and on here.
 */
public enum class PageFilter {
    ALL,
    SET,
    UNSET,
    ;

    public fun admits(song: PagedSong): Boolean = when (this) {
        ALL -> true
        SET -> song.set
        UNSET -> !song.set
    }
}

/** One song as the paging sees it. [set] is T5's flag: rated in the editor, held on the toggle list. */
public data class PagedSong(val songId: String, val title: String, val artistName: String?, val set: Boolean)

/**
 * **triage T3-T5a: the letter, the search and the filter**, written once for both lists. Screen state,
 * never a preference (T5). The songs are always the caller's, in the caller's order.
 *
 * [letter] is null until a page is first fixed ([pinned]), which opens on the first letter with songs.
 */
public data class RatingsEditorState(
    val letter: Char? = null,
    val query: String = "",
    val filter: PageFilter = PageFilter.ALL,
) {
    /** T4: anything typed overrides the letter. */
    public val searching: Boolean get() = query.isNotBlank()

    /** T3: the letters with a song under the filter. Every other letter is greyed. */
    public fun letters(songs: List<PagedSong>): Set<Char> =
        songs.filter { filter.admits(it) }.mapTo(HashSet()) { SongLetters.of(it.title) }

    /**
     * T3: the letter open — the chosen one while it has songs, otherwise the first that has. Null
     * only when nothing passes the filter.
     */
    public fun openLetter(songs: List<PagedSong>): Char? {
        val live = letters(songs)
        return letter?.takeIf { it in live } ?: SongLetters.STRIP.firstOrNull { it in live }
    }

    /** This state with [openLetter] written into it, so a later change cannot move the page by itself. */
    public fun pinned(songs: List<PagedSong>): RatingsEditorState = copy(letter = openLetter(songs))

    /**
     * T3-T5: the songs shown, in the order given. A search shows its matches across every letter
     * (T4), through [SongSearch], the Songs route's own matcher; the filter applies either way.
     */
    public fun visible(songs: List<PagedSong>): List<PagedSong> {
        val admitted = songs.filter { filter.admits(it) }
        if (searching) return admitted.filter { SongSearch.matches(query, it.title, it.artistName) }
        val open = openLetter(songs) ?: return emptyList()
        return admitted.filter { SongLetters.of(it.title) == open }
    }

    /** T3. A letter tapped during a search ends the search: the letter is what was asked for. */
    public fun withLetter(letter: Char): RatingsEditorState = copy(letter = letter, query = "")

    /** T4: clearing the search returns to [letter], which a search never changes. */
    public fun withQuery(query: String): RatingsEditorState = copy(query = query)

    public fun withFilter(filter: PageFilter): RatingsEditorState = copy(filter = filter)
}

/**
 * **triage T9: whose part the View's ratings are**, resolved in T9's order: the View's filter
 * performer, then the owner performer (T10), then none. Nothing is guessed.
 *
 * A performer that is not live resolves to none (T10: soft-deleting the owner). A filter that names a
 * removed performer resolves to none too, rather than falling through to the owner: the View names
 * someone, and it is not the owner.
 */
public object RatingsPerformer {

    public fun resolve(filterPerformerId: String?, ownerPerformerId: String?, livePerformerIds: Set<String>): String? =
        when {
            filterPerformerId != null -> filterPerformerId.takeIf { it in livePerformerIds }
            ownerPerformerId != null -> ownerPerformerId.takeIf { it in livePerformerIds }
            else -> null
        }

    /** T9: the one line under the View menu's "Rate these songs" when no performer resolves. */
    public const val NONE_REASON: String = "Choose who you are in Settings to rate these songs"
}

/** One song the editor lists. */
public data class RatingsSong(val songId: String, val title: String, val artistName: String?)

/** triage T1: where the editor's songs come from. */
public sealed interface RatingsSource {
    /** T1, the Repertoire route: the songs the part is enabled on, read when the editor opens. */
    public data object EnabledParts : RatingsSource

    /** T1, the View menu: the active View's pool, as the logger holds it. */
    public data class ViewPool(val songs: List<RatingsSong>) : RatingsSource
}

/** triage T1: one editor for one `(performer, instrument)` and one song source. */
public data class RatingsTarget(
    val performerId: String,
    val instrumentId: String,
    val performerName: String,
    val instrumentLabel: String,
    val source: RatingsSource,
) {
    /** T1: the title names the part, "Will · guitar". */
    val title: String get() = performerName + ViewSummary.SEPARATOR + instrumentLabel.lowercase()
}

/**
 * **The open editor** (triage T1-T5). [songs] are in title order (T4); [order] is the page shown, as
 * song ids, **fixed when it loads and when the letter, search or filter changes, never by a rating**
 * (repertoire-editing R7's rule): rating the last unrated song on an Unrated page does not take the
 * row out from under the thumb before its second rating is set.
 */
public data class RatingsEditor(
    val target: RatingsTarget,
    /** Which open editor a result belongs to, so a result for a closed one never lands. */
    val ticket: Long,
    val songs: List<RatingsSong> = emptyList(),
    val ratings: Map<String, PartRatings> = emptyMap(),
    val paging: RatingsEditorState = RatingsEditorState(),
    val order: List<String> = emptyList(),
    val loading: Boolean = true,
    /** E43: a failed read or write, on the error channel. */
    val error: String? = null,
) {
    /** Every song as the paging sees it, rated or not. */
    public val paged: List<PagedSong>
        get() = songs.map { PagedSong(it.songId, it.title, it.artistName, set = isRated(it.songId)) }

    /** T5: Unrated means neither rating is set. */
    public fun isRated(songId: String): Boolean =
        ratingsOf(songId).let { it.priority != null || it.confidence != null }

    public fun ratingsOf(songId: String): PartRatings = ratings[songId] ?: PartRatings.UNRATED

    public fun levelOf(songId: String, kind: RatingKind): RatingLevel? = ratingsOf(songId).let {
        when (kind) {
            RatingKind.PRIORITY -> it.priority
            RatingKind.CONFIDENCE -> it.confidence
        }
    }

    /** The page as displayed: [order], resolved against [songs]. */
    public fun visible(): List<RatingsSong> {
        val byId = songs.associateBy { it.songId }
        return order.mapNotNull { byId[it] }
    }

    /** A read landed: the songs in title order (T4), their ratings, and the page fixed. */
    public fun loaded(songs: List<RatingsSong>, ratings: Map<String, PartRatings>): RatingsEditor =
        copy(songs = songs.sortedWith(TITLE_ORDER), ratings = ratings, loading = false).refixed()

    public fun withLetter(letter: Char): RatingsEditor = copy(paging = paging.withLetter(letter)).refixed()

    public fun withQuery(query: String): RatingsEditor = copy(paging = paging.withQuery(query)).refixed()

    public fun withFilter(filter: PageFilter): RatingsEditor = copy(paging = paging.withFilter(filter)).refixed()

    /**
     * RS9, T2: one rating shown at [level] — a tap's optimistic half, and a write's landing (the level
     * written, or the one before it when the write failed). **The page is not re-fixed.**
     */
    public fun withLevel(songId: String, kind: RatingKind, level: RatingLevel?): RatingsEditor {
        val was = ratingsOf(songId)
        val now = when (kind) {
            RatingKind.PRIORITY -> was.copy(priority = level)
            RatingKind.CONFIDENCE -> was.copy(confidence = level)
        }
        return copy(ratings = ratings + (songId to now))
    }

    /** The only place the page is computed: on load, and on a letter, search or filter change. */
    private fun refixed(): RatingsEditor {
        val all = paged
        val pinned = paging.pinned(all)
        return copy(paging = pinned, order = pinned.visible(all).map { it.songId })
    }

    public companion object {
        /** T4's title order: [SongSearch.byTitle], N2's base order. */
        public val TITLE_ORDER: Comparator<RatingsSong> =
            SongSearch.byTitle({ it.title }, { it.artistName }, { it.songId })
    }
}
