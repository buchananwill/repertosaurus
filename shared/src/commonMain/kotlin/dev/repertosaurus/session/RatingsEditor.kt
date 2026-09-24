package dev.repertosaurus.session

import dev.repertosaurus.core.RatingKind
import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.data.PartRatings

/** One song the ratings editor lists. */
public data class RatingsSong(val songId: String, val title: String, val artistName: String?)

/** triage T1: where the editor's songs come from. */
public sealed interface RatingsSource {
    /** T1, the Repertoire route: the songs the part is enabled on, read when the editor opens. */
    public data object EnabledParts : RatingsSource

    /** T1, the View menu: the active View's pool as the logger holds it (journal session 11, D59 #10). */
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
    /** T1: "Will · guitar". */
    val title: String get() = performerName + ViewSummary.SEPARATOR + instrumentLabel.lowercase()
}

/** One of a song's two ratings. */
public data class RatingKey(val songId: String, val kind: RatingKind)

/**
 * **The open ratings editor** (triage T1-T5). [songs] are in title order (T4); [page] is fixed as
 * [Page] says, so a rating never takes its row off the page.
 *
 * **safety review F20 B1: a tap shows at once, and only that key's latest tap decides what it
 * shows.** [ratings] is what is displayed; [stored] is what the writes so far have confirmed. Each
 * tap takes the next [lastTap] and marks its key with it in [taps]. A landing that is not its key's
 * latest changes only [stored]; the latest one shows what it wrote, or, when it failed, [stored].
 */
public data class RatingsEditor(
    val target: RatingsTarget,
    /** Which open editor a result belongs to, so a result for a closed one never lands. */
    val ticket: Long,
    val songs: List<RatingsSong> = emptyList(),
    val ratings: Map<String, PartRatings> = emptyMap(),
    val stored: Map<String, PartRatings> = emptyMap(),
    val taps: Map<RatingKey, Long> = emptyMap(),
    val lastTap: Long = 0L,
    val page: Page = Page(),
    val loading: Boolean = true,
    /** E43: a failed read or write, on the error channel. */
    val error: String? = null,
) {
    /** Every song as the paging sees it. */
    public fun paged(): List<PagedSong> =
        songs.map { PagedSong(it.songId, it.title, it.artistName, set = isRated(it.songId)) }

    /** T5: Unrated means neither rating is set. */
    public fun isRated(songId: String): Boolean =
        ratingsOf(songId).let { it.priority != null || it.confidence != null }

    public fun ratingsOf(songId: String): PartRatings = ratings[songId] ?: PartRatings.UNRATED

    public fun levelOf(songId: String, kind: RatingKind): RatingLevel? = ratingsOf(songId).level(kind)

    /** The page as displayed. */
    public fun visible(): List<RatingsSong> {
        val byId = songs.associateBy { it.songId }
        return page.order.mapNotNull { byId[it] }
    }

    /** A read landed: the songs in title order (T4), their ratings, both shown and stored. */
    public fun loaded(songs: List<RatingsSong>, ratings: Map<String, PartRatings>): RatingsEditor {
        val read = copy(songs = songs.sortedWith(TITLE_ORDER), ratings = ratings, stored = ratings, loading = false)
        return read.copy(page = page.fixed(read.paged()))
    }

    public fun withLetter(letter: Char): RatingsEditor = copy(page = page.withLetter(letter, paged()))

    public fun withQuery(query: String): RatingsEditor = copy(page = page.withQuery(query, paged()))

    public fun withFilter(filter: PageFilter): RatingsEditor = copy(page = page.withFilter(filter, paged()))

    /** RS9: a tap's optimistic half. The tap's number is the result's [lastTap]. */
    public fun tapped(songId: String, kind: RatingKind, level: RatingLevel?): RatingsEditor {
        val tap = lastTap + 1
        return copy(
            ratings = ratings.with(songId, kind, level),
            taps = taps + (RatingKey(songId, kind) to tap),
            lastTap = tap,
            error = null,
        )
    }

    /** F20 B1: tap number [tap] landed, having written [level] ([wrote]) or failed. */
    public fun landed(songId: String, kind: RatingKind, tap: Long, level: RatingLevel?, wrote: Boolean): RatingsEditor {
        val confirmed = if (wrote) stored.with(songId, kind, level) else stored
        if (taps[RatingKey(songId, kind)] != tap) return copy(stored = confirmed)
        val shown = if (wrote) level else (confirmed[songId] ?: PartRatings.UNRATED).level(kind)
        return copy(stored = confirmed, ratings = ratings.with(songId, kind, shown))
    }

    public companion object {
        /** T4's title order: N2's base order. */
        public val TITLE_ORDER: Comparator<RatingsSong> =
            SongSearch.byTitle({ it.title }, { it.artistName }, { it.songId })
    }
}

private fun PartRatings.level(kind: RatingKind): RatingLevel? = when (kind) {
    RatingKind.PRIORITY -> priority
    RatingKind.CONFIDENCE -> confidence
}

private fun Map<String, PartRatings>.with(songId: String, kind: RatingKind, level: RatingLevel?): Map<String, PartRatings> {
    val was = this[songId] ?: PartRatings.UNRATED
    val now = when (kind) {
        RatingKind.PRIORITY -> was.copy(priority = level)
        RatingKind.CONFIDENCE -> was.copy(confidence = level)
    }
    return this + (songId to now)
}
