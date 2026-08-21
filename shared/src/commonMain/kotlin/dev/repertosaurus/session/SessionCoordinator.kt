package dev.repertosaurus.session

import dev.repertosaurus.core.Ids
import dev.repertosaurus.core.NearMatches
import dev.repertosaurus.core.normalise
import dev.repertosaurus.data.RepertosaurusRepository

/**
 * Where the Session screen touches the database. Every method here is **blocking** and is
 * meant to be called off the main thread; the pure, instant half of the screen is
 * [SessionState].
 *
 * The split is deliberate. A tap calls [newTap] (pure) and [SessionState.plusTap] (pure)
 * and the UI is already updated; [persist] happens afterwards on a background dispatcher.
 * Nothing on the tap path waits on SQLite.
 */
public class SessionCoordinator(
    private val repository: RepertosaurusRepository,
    private val preferences: SessionPreferences,
    private val newTapId: () -> String = { Ids.random() },
) {

    /** The chip row, read from the `instrument` table (decisions 15, 18). */
    public fun instruments(): List<InstrumentChip> =
        SessionInstruments.chips(repository.instruments())

    /**
     * Which chip to open on: the one last used if it still exists, otherwise the first.
     * The resolved answer is written back, so a first launch is remembered too.
     */
    public fun initialInstrument(chips: List<InstrumentChip>): String? {
        val resolved = SessionInstruments.resolve(preferences.lastInstrumentId(), chips)
        if (resolved != null) preferences.rememberInstrument(resolved)
        return resolved
    }

    /** Remembered across launches — the app must not ask which discipline every session. */
    public fun rememberInstrument(instrumentId: String) {
        preferences.rememberInstrument(instrumentId)
    }

    /**
     * The sort direction from the last session, defaulting to coldest first. Stored the
     * same way as the instrument chip, because it is the same kind of thing: a view
     * preference, never data. No row anywhere records it.
     */
    public fun initialOrder(): SessionOrder = SessionOrder.parse(preferences.lastOrder())

    public fun rememberOrder(order: SessionOrder) {
        preferences.rememberOrder(order.name)
    }

    /**
     * The View's rows: eligible songs by [SessionView.filter], staleness measured on
     * [SessionView.practiceInstrumentId].
     *
     * The direction is applied in [SessionState.pending] rather than here (V14). SQL returns
     * coldest-first as a stable base order and the Kotlin comparator re-sorts it, which is
     * what makes the toggle instant; the two are not redundant.
     *
     * The [ViewFilter] is destructured here rather than handed down. `data` must not depend
     * on `session` — the layering is `session → data → core` — so the repository takes the
     * three components as primitives, and this is the one place that knows a View sent them.
     */
    public fun rows(view: SessionView): List<SessionRow> =
        repository.songsByStaleness(
            practiceInstrumentId = view.practiceInstrumentId,
            filterPerformerId = view.filter.performerId,
            filterInstrumentId = view.filter.instrumentId,
            leadOnly = view.filter.leadOnlyFlag,
        ).map { song ->
            SessionRow(
                songId = song.songId,
                title = song.title,
                artistName = song.artistName,
                daysSince = song.daysSince,
                timesPractised = song.timesPractised,
            )
        }

    /** Pure: allocates the local tap identity. No database contact. */
    public fun newTap(
        songId: String,
        instrumentId: String,
        feel: Long? = null,
        note: String? = null,
        loggedOn: String = repository.today(),
    ): SessionTap = SessionTap(
        tapId = newTapId(),
        songId = songId,
        instrumentId = instrumentId,
        feel = feel,
        note = note?.trim()?.takeIf { it.isNotEmpty() },
        loggedOn = loggedOn,
    )

    /** The insert. Returns the `practice_event` id, which is the undo's target. */
    public fun persist(tap: SessionTap): String = repository.logPractice(
        songId = tap.songId,
        instrumentId = tap.instrumentId,
        feel = tap.feel,
        note = tap.note,
        loggedOn = tap.loggedOn,
    )

    // ---- Adding a song ----------------------------------------------------------------

    /** Every live artist, for the type-ahead's near-match pass. */
    public fun artists(): List<RepertosaurusRepository.Artist> = repository.artists()

    /**
     * Every live performer, for the View editor's filter type-ahead. A few rows, so the table
     * is held whole and matched in memory — the same reason [artists] is.
     */
    public fun performers(): List<RepertosaurusRepository.Performer> = repository.performers()

    /**
     * The type-ahead of decisions 16 and 17: near-matches surfaced *while typing*, so the
     * user sees `The Fratellis` before they can commit a second one.
     */
    public fun suggestArtists(
        query: String,
        limit: Int = 6,
    ): List<RepertosaurusRepository.Artist> = ArtistSuggestions.search(query, artists(), limit)

    /**
     * Create a song from a title and a typed artist name. Both ids are derived (decisions
     * 2, 4, 4d), so an artist that normalises to one already stored resolves to that same
     * row rather than a second one, and two devices adding the same song converge.
     *
     * A blank artist resolves to the seeded `Unknown Artist` (decision 28a) rather than
     * blocking the save: `song.artist_id` is NOT NULL and a musician mid-practice should
     * not have to settle an attribution to log a song.
     *
     * Nothing else is asked for. Decisions 27 and 37 are explicit that no key may be
     * required, and this is not the song editor.
     */
    public fun addSong(title: String, artistName: String): String {
        val artistId = resolveArtist(artistName)
        return repository.createSong(title = title, artistId = artistId)
    }

    /** The same, when the user picked an existing artist out of the suggestions. */
    public fun addSongWithArtistId(title: String, artistId: String): String =
        repository.createSong(title = title, artistId = artistId)

    /** The typed name, an existing row by normalisation, or the seeded placeholder. */
    public fun resolveArtist(artistName: String): String {
        val trimmed = artistName.trim()
        return if (trimmed.isEmpty()) {
            RepertosaurusRepository.UNKNOWN_ARTIST_ID
        } else {
            repository.findOrCreateArtist(trimmed)
        }
    }

    /** Undo: an append to `practice_event_void`, never a delete or an update (decision 8). */
    public fun voidEvent(practiceEventId: String) {
        repository.voidPractice(practiceEventId)
    }
}

/** The chip row's ordering and selection rules. */
public object SessionInstruments {

    /**
     * Decision 18's seed order — vocal, backing vocal, guitar, bass, keys — which is the
     * order the user thinks in, not alphabetical. It is a *display* preference only: an
     * instrument the user adds later is unknown to this list and lands after the seeded
     * five, alphabetically, so decision 15 still holds and adding a row needs no code
     * change. There is no ordering column in the schema and none is proposed here; adding
     * one would be a data-model change, not a screen decision.
     */
    private val SEEDED = listOf("vocal", "backing vocal", "guitar", "bass", "keys")

    /**
     * Decision 18's order over anything that carries an instrument name — the chip row, the
     * manage-instruments list, and E14's performer subtitle, which all have to agree or the
     * same five instruments read in three different orders on three screens.
     *
     * The name is a function rather than a supertype because the three callers hold three
     * unrelated types: an [InstrumentChip], a `LookupRow` and a `RepertosaurusRepository
     * .Instrument`.
     */
    public fun <T> displayOrder(name: (T) -> String): Comparator<T> = compareBy(
        { item ->
            val rank = SEEDED.indexOf(normalise(name(item)))
            if (rank < 0) SEEDED.size else rank
        },
        { item -> name(item) },
    )

    public fun chips(instruments: List<RepertosaurusRepository.Instrument>): List<InstrumentChip> =
        instruments
            .map { InstrumentChip(id = it.id, name = it.name) }
            .sortedWith(displayOrder { it.name })

    /** The remembered chip if it still exists, otherwise the first one. */
    public fun resolve(remembered: String?, chips: List<InstrumentChip>): String? =
        chips.firstOrNull { it.id == remembered }?.id ?: chips.firstOrNull()?.id
}

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
 * What the Session screen remembers between launches: the discipline chip (a phase 1
 * question in the delivery spec — *should the app remember it rather than asking each
 * session?* — this build says yes), the sort direction, and the home View. All three are
 * view preferences and none is data; nothing here is ever synced.
 *
 * The home View is here and **not** an `is_home` column on `saved_view` (V19). A flag on
 * many rows has no total order under last-write-wins: two devices each promoting a different
 * View both end up true and no subsequent sync repairs it. Keeping it local also lets a
 * phone and a desktop open on different Views, which is the behaviour we want.
 *
 * An interface so the rules are testable without a device; Android backs it with
 * `SharedPreferences`.
 */
public interface SessionPreferences {
    public fun lastInstrumentId(): String?
    public fun rememberInstrument(instrumentId: String)
    public fun lastOrder(): String?
    public fun rememberOrder(order: String)
    public fun homeViewId(): String?
    public fun rememberHomeView(viewId: String)
}

/** For tests and previews. */
public class InMemorySessionPreferences(
    private var instrumentId: String? = null,
    private var order: String? = null,
    private var homeViewId: String? = null,
) : SessionPreferences {
    override fun lastInstrumentId(): String? = instrumentId

    override fun rememberInstrument(instrumentId: String) {
        this.instrumentId = instrumentId
    }

    override fun lastOrder(): String? = order

    override fun rememberOrder(order: String) {
        this.order = order
    }

    override fun homeViewId(): String? = homeViewId

    override fun rememberHomeView(viewId: String) {
        this.homeViewId = viewId
    }
}
