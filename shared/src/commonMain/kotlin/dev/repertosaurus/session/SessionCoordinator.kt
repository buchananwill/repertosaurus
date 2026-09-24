package dev.repertosaurus.session

import dev.repertosaurus.core.Ids
import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.core.normalise
import dev.repertosaurus.data.PartRatings
import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.data.SongCatalog

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

    /**
     * triage T9: [part]'s ratings, keyed by song id, in one read for the whole View (never one query a
     * row). No part is no ratings: every triage sort then degrades to staleness (T8). The rows take
     * them through `SessionState.withRatings`, the one join.
     */
    public fun ratings(part: ResolvedPart?): Map<String, PartRatings> =
        part?.let { repository.ratings.ratingsFor(it.performerId, it.instrumentId) } ?: emptyMap()

    /** Pure: allocates the local tap identity. No database contact. */
    public fun newTap(
        songId: String,
        instrumentId: String,
        feel: RatingLevel? = null,
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
    public fun artists(): List<RepertosaurusRepository.Artist> = repository.catalog.artists()

    /**
     * Every live performer, for the View editor's filter type-ahead. A few rows, so the table
     * is held whole and matched in memory — the same reason [artists] is.
     */
    public fun performers(): List<RepertosaurusRepository.Performer> = repository.performers()

    /**
     * The logger's add-song sheet: a thin delegate to [SongCatalog.addSong], which holds the
     * whole path — the one artist rule (null or blank typed is `Unknown Artist`, decision
     * 28a), the derived ids, R21's revive and R23a's [SongCatalog.SongAdd.differs]. Kept here
     * only so the logger's ViewModel reaches it through the coordinator it already holds; the
     * Songs route calls the catalog directly and needs no `SessionPreferences`.
     */
    public fun addSong(title: String, artist: SongCatalog.LookupChoice?): SongCatalog.SongAdd =
        repository.catalog.addSong(title, artist)

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
 * What the Session screen remembers between launches: the discipline chip (a phase 1
 * question in the delivery spec — *should the app remember it rather than asking each
 * session?* — this build says yes), the sort direction, and the home View. These are view
 * preferences, never data, and nothing here is ever synced. The app-wide display preferences are
 * [DevicePreferences].
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
