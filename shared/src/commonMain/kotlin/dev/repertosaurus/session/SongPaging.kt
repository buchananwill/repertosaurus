package dev.repertosaurus.session

/**
 * **triage T3-T5a: the paged song lists' shared core** — the letter rule, the filter, the paging
 * state, and the [Page] both the ratings editor and the Repertoire toggle list hold.
 */

/** **triage T3: the letter a song files under, and the strip's pages.** The one letter rule. */
public object SongLetters {

    /** T3: digits and symbols. */
    public const val OTHER: Char = '#'

    /** T3: the strip, in order. */
    public val STRIP: List<Char> = listOf(OTHER) + ('A'..'Z')

    private val ARTICLES = listOf("the", "an", "a")

    /**
     * T3: [title] trimmed, without a leading "The", "A" or "An" and the whitespace after it — any
     * whitespace `Char.isWhitespace` knows, a no-break space included (F20 N5). A title that is only an
     * article keeps it.
     */
    public fun withoutArticle(title: String): String {
        val trimmed = title.trim()
        val article = ARTICLES.firstOrNull {
            trimmed.length > it.length && trimmed.startsWith(it, ignoreCase = true) && trimmed[it.length].isWhitespace()
        } ?: return trimmed
        return trimmed.substring(article.length).trimStart()
    }

    /** T3: the first character of [withoutArticle] in upper case; [OTHER] when that is not A-Z. */
    public fun of(title: String): Char {
        val first = withoutArticle(title).firstOrNull()?.uppercaseChar() ?: return OTHER
        return if (first in 'A'..'Z') first else OTHER
    }
}

/** **triage T5, T5a: the three-way filter.** [SET] is the editor's Rated and the toggle list's On. */
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
 * **triage T3-T5a: the letter, the search and the filter**, screen state and never a preference
 * (T5). [letter] is null until a page is first fixed ([pinned]).
 */
public data class SongPaging(
    val letter: Char? = null,
    val query: String = "",
    val filter: PageFilter = PageFilter.ALL,
) {
    /** T4. */
    public val searching: Boolean get() = query.isNotBlank()

    /** T3: the letters with a song under the filter. Every other letter is greyed. */
    public fun letters(songs: List<PagedSong>): Set<Char> =
        songs.filter { filter.admits(it) }.mapTo(HashSet()) { SongLetters.of(it.title) }

    /** T3: the chosen letter while it has songs, otherwise the first that has. */
    public fun openLetter(songs: List<PagedSong>): Char? {
        val live = letters(songs)
        return letter?.takeIf { it in live } ?: SongLetters.STRIP.firstOrNull { it in live }
    }

    /** [openLetter] written in, so a later change cannot move the page by itself. */
    public fun pinned(songs: List<PagedSong>): SongPaging = copy(letter = openLetter(songs))

    /** T3-T5, in the order given. A search matches across letters through [SongSearch] (T4). */
    public fun visible(songs: List<PagedSong>): List<PagedSong> {
        val admitted = songs.filter { filter.admits(it) }
        if (searching) return admitted.filter { SongSearch.matches(query, it.title, it.artistName) }
        val open = openLetter(songs) ?: return emptyList()
        return admitted.filter { SongLetters.of(it.title) == open }
    }

    /** T3. A letter tapped during a search ends the search. */
    public fun withLetter(letter: Char): SongPaging = copy(letter = letter, query = "")

    /** T4: a search never changes [letter], so clearing it returns there. */
    public fun withQuery(query: String): SongPaging = copy(query = query)

    public fun withFilter(filter: PageFilter): SongPaging = copy(filter = filter)
}

/**
 * **The page a paged list shows** (style review F21 B1): its [paging] and the song ids in [order],
 * **fixed on load and on a letter, search or filter change, never by a tap** (repertoire-editing R7,
 * journal session 11 D59 #2). Each change takes the songs as they now are.
 */
public data class Page(val paging: SongPaging = SongPaging(), val order: List<String> = emptyList()) {

    public fun fixed(songs: List<PagedSong>): Page {
        val pinned = paging.pinned(songs)
        return Page(pinned, pinned.visible(songs).map { it.songId })
    }

    public fun withLetter(letter: Char, songs: List<PagedSong>): Page = Page(paging.withLetter(letter)).fixed(songs)

    public fun withQuery(query: String, songs: List<PagedSong>): Page = Page(paging.withQuery(query)).fixed(songs)

    public fun withFilter(filter: PageFilter, songs: List<PagedSong>): Page = Page(paging.withFilter(filter)).fixed(songs)
}
