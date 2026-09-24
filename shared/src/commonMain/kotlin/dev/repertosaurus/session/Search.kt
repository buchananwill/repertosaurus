package dev.repertosaurus.session

import dev.repertosaurus.core.NearMatches
import dev.repertosaurus.core.normalise
import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.data.SongCatalog

/**
 * **Every matcher and the one song label, in one file** (style review F14 N3, N4). They were
 * spread over `SessionCoordinator.kt` and `SessionState.kt`, which own neither: the logger, the
 * Songs route, the Repertoire toggle list, the Artists route and both performer pickers all
 * search through here, and package 3's merge picker will too.
 *
 * Two kinds of search live here and they are different things:
 *
 * - **type-aheads** ([ArtistSuggestions], [PerformerSuggestions], [LookupSuggestions]) rank near-matches through
 *   [NearMatches], typos included, because their job is to stop a duplicate being created;
 * - **list filters** ([SongSearch], [ArtistSearch]) keep every row whose words contain what was
 *   typed, in the list's own order, because their job is to find a row that exists.
 */

/**
 * The artist type-ahead — decisions 16 and 17, and the mechanism the whole
 * artist-normalisation design rests on.
 *
 * The matching itself is [NearMatches], shared with the instrument type-ahead and with
 * every lookup wired later. Matching runs over [normalise], never the raw string: it strips
 * a leading `The `, folds `&` to `and` and turns punctuation into a space, so `Fratellis`
 * finds `The Fratellis` and `AC DC` finds `AC/DC`. A prefix match over raw text finds
 * neither, and the user creates a second artist that no merge rule can reconcile — which is
 * precisely the failure the source workbook demonstrates 288 times.
 *
 * Because a derived id is `UUIDv5(namespace, normalise(name))`, a match that normalises
 * equal is also a match on the id: typing a name that normalises to an existing one
 * resolves to that row whether or not the user notices the suggestion. A *typo* does not —
 * `Beyonce` and `Beyoncé` derive different ids — which is why [NearMatches] also surfaces
 * near misses by edit distance.
 */
public object ArtistSuggestions {

    public fun search(
        query: String,
        artists: List<RepertosaurusRepository.Artist>,
        limit: Int = 6,
    ): List<RepertosaurusRepository.Artist> =
        NearMatches.search(query, artists, limit) { it.name }
}

/**
 * The performer type-ahead — **one matcher (E46)**, beside [ArtistSuggestions] and on the same
 * reasoning.
 *
 * Two composables reach for this picker: the capability sheet's "Add someone" field and the
 * View editor's filter field. Each had invented its own copy of the browse-when-blank rule and
 * its own limit constant, and the two constants **disagreed on the search cap for the same
 * picker** — the identical field offered eight names in one sheet and six in the other. That is
 * a display rule that is JVM-testable and that the desktop UI will need verbatim, so it is here
 * and not there.
 *
 * The browsing half is [NearMatches.browseOrSearch]: blank means "show me what exists", typed
 * means "find the near-matches", one limit for both.
 */
public object PerformerSuggestions {

    public fun search(
        query: String,
        performers: List<RepertosaurusRepository.Performer>,
        limit: Int = NearMatches.BROWSE_LIMIT,
    ): List<RepertosaurusRepository.Performer> =
        NearMatches.browseOrSearch(query, performers, limit) { it.name }
}

/**
 * **The lookup type-ahead** (style review F22 B2) — one matcher for the seven managed lookups,
 * beside [ArtistSuggestions] and on the same reasoning: near-matches while typing (decision 16),
 * so `Ukelele` meets `Ukulele` **before** a second row is committed. The two normalise
 * differently and would derive different ids, so nothing downstream would ever merge them; the
 * type-ahead is the only defence.
 *
 * The song detail's groove field and the manage screen's add field both match here, over the
 * list they already hold (E36). It replaces the dead `suggest*` members (on `LookupStore`,
 * `CapabilityCoordinator` and `SessionCoordinator`), each a wrapper over [NearMatches.search]
 * that no screen called — the screens called [NearMatches] directly instead.
 */
public object LookupSuggestions {

    public fun search(query: String, items: List<LookupItem>, limit: Int = DEFAULT_LIMIT): List<LookupItem> =
        NearMatches.search(query, items, limit) { it.name }

    /** The type-ahead's cap, the same as [ArtistSuggestions]'. */
    public const val DEFAULT_LIMIT: Int = 6
}

/**
 * **The one song matcher, and the one song order** (style review B1, N2) — the logger's search
 * box ([SessionState]), the Songs route's list (R11) and the Repertoire toggle list (R3) all
 * find the same songs for the same text and list them the same way.
 *
 * Every word typed must appear in the title or the artist, compared over [normalise]; a word
 * also matches with the spaces normalise put where punctuation was taken out, so `acdc` finds
 * *AC/DC* — [NearMatches.rank]'s rule 4, applied to a list filter. A blank query matches
 * everything. Matching may be lossy; nothing here builds an id.
 */
public object SongSearch {

    public fun matches(query: String, title: String, artistName: String?): Boolean =
        wordsMatch(query, title, artistName.orEmpty())

    /**
     * **N2's base order: title case-insensitively, then artist, then id** — total, so a reload
     * never shuffles two songs with one title. Generic because the two lists it orders hold
     * unrelated row types; `RepertoireCoordinator.ORDER` prefixes the held flag to it.
     */
    public fun <T> byTitle(
        title: (T) -> String,
        artistName: (T) -> String?,
        id: (T) -> String,
    ): Comparator<T> =
        compareBy<T, String>(String.CASE_INSENSITIVE_ORDER) { title(it) }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { artistName(it).orEmpty() }
            .thenBy { id(it) }

    /** The Songs route's list order (R11): [byTitle] over its rows. */
    public val LIST_ORDER: Comparator<SongCatalog.SongListEntry> =
        byTitle({ it.title }, { it.artistName }, { it.id })

    /**
     * R11: the Songs route's list, filtered in memory (E36) and ordered — applied once, here,
     * whether or not anything was typed.
     */
    public fun search(
        songs: List<SongCatalog.SongListEntry>,
        query: String,
    ): List<SongCatalog.SongListEntry> =
        songs.filter { matches(query, it.title, it.artistName) }.sortedWith(LIST_ORDER)
}

/**
 * The Artists route's search (session 09, F16 #8) — several hundred artists is a long scroll.
 * In memory over the list the route already holds (E36), with [SongSearch]'s word rule over the
 * name and the sort name, so `zutons` finds *The Zutons* and so does its sort name
 * *Zutons, The*. The list keeps its own order (sort name, decision 21); a filter does not
 * re-sort.
 */
public object ArtistSearch {

    public fun search(artists: List<SongCatalog.ArtistEntry>, query: String): List<SongCatalog.ArtistEntry> =
        artists.filter { wordsMatch(query, it.name, it.sortName) }
}

/**
 * **The one-line song label (repertoire-editing R11, amended D94): `Title — Artist`.** Every surface
 * that names a song inside a sentence or on a single line calls this — snackbars, dialogs, R23's
 * "already in the repertoire as …" — so no screen assembles its own dash. List rows show the title
 * over the artist instead (`SongTitleArtist`). Stored values are shown as stored; a blank artist
 * name leaves the title alone.
 */
public fun songLabel(title: String, artistName: String?): String =
    if (artistName.isNullOrBlank()) title else title + SONG_LABEL_SEPARATOR + artistName

/** [songLabel]'s joiner. Private (style review F14 N4): the label is the API, not its dash. */
private const val SONG_LABEL_SEPARATOR: String = " — "

/**
 * The list filters' one rule: every word of [query] appears in one of [fields], compared over
 * [normalise], or in the fields with normalise's punctuation spaces taken out. Blank matches all.
 */
private fun wordsMatch(query: String, vararg fields: String): Boolean {
    val words = normalise(query).split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val haystack = fields.joinToString(" ") { normalise(it) }
    val squashed = haystack.replace(" ", "")
    return words.all { word -> word in haystack || word in squashed }
}
