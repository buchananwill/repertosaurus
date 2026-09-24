package dev.repertosaurus.session

import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.data.PartRatings
import dev.repertosaurus.data.RepertosaurusRepository

/**
 * The Session screen's state, as a plain immutable value with pure transitions.
 *
 * It lives in the shared core rather than in the Android layer for one reason: every rule
 * the screen has to get right — coldest-first ordering, a logged row moving out of the
 * way, an undo putting it back exactly where it was, two taps on one song being two
 * sessions (decision 47) — is testable on the JVM with no device. The Compose layer only
 * renders what is here.
 *
 * [rows] is the database's answer: never-practised first, then coldest first (see `song.sq
 * selectByStaleness`). A tap never touches a row. It appends to [taps] and the row is *moved*
 * to the logged section; an undo drops the tap and the row reappears in its original position
 * with its original badge. That is what makes decision 8's "the song returns to its previous
 * staleness position" true by construction rather than by recomputation. The only rewrite of
 * a row is [withRatings], which lays the resolved part's ratings on it (triage T9) and leaves
 * its staleness alone.
 *
 * Whose ratings those are is derived in `RatingsPerformer.kt` (`part`, `ratingsStale`).
 */
public data class SessionState(
    val instruments: List<InstrumentChip> = emptyList(),
    val views: List<SessionView> = emptyList(),
    val view: SessionView? = null,
    val rows: List<SessionRow> = emptyList(),
    val taps: List<SessionTap> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    val undo: UndoOffer? = null,
    val message: String? = null,
    val fallbackOrder: SessionOrder = SessionOrder.COLDEST_FIRST,
    /** triage T10: this device's owner performer, as the device preference holds it. */
    val ownerPerformerId: String? = null,
    /** The live performers, **null until they have been read** (F20 N2): not known is not nobody. */
    val performers: List<RepertosaurusRepository.Performer>? = null,
    /** triage T9: the part whose ratings [rows] carry, or null when they carry none. */
    val ratedFor: ResolvedPart? = null,
) {

    /**
     * The instrument being logged to and measured against — the active View's, not
     * free-standing state (views V13). One field, because the instrument a View logs to is
     * the instrument it measures staleness on.
     *
     * **Invariant (V20a): whenever this is non-null it is the id of one of [instruments].**
     * A View can name an instrument that has since been soft-deleted — removal is unguarded
     * and can also arrive by sync — and `ViewCoordinator.start` resolves that against the live
     * chip row before the View ever reaches this state, writing the repair back. Without it no
     * chip highlights and every tap writes `practice_event.instrument_id` pointing at a
     * tombstoned row, silently, because the foreign key is still satisfied.
     */
    public val selectedInstrumentId: String?
        get() = view?.practiceInstrumentId

    /**
     * The direction, likewise the View's (V17). Before the first View has loaded there is no
     * View to hold it, so it falls back to [fallbackOrder] — which is what makes the toggle
     * respond during the initial load instead of silently swallowing the tap.
     */
    public val order: SessionOrder
        get() = view?.order ?: fallbackOrder

    /** Taps belonging to the instrument currently on screen. */
    public val tapsHere: List<SessionTap> by lazy {
        taps.filter { it.instrumentId == selectedInstrumentId }
    }

    /** How many times each song was logged in this session, on this instrument. */
    public val loggedCounts: Map<String, Int> by lazy {
        tapsHere.groupingBy { it.songId }.eachCount()
    }

    /**
     * The main list: everything not yet logged in this session, in [order], filtered by
     * [query].
     *
     * The sort is applied here rather than in SQL so flipping the toggle is instant and
     * needs no round trip — and so that both directions, including where a never-practised
     * song lands, are decided in one tested place instead of two ORDER BY clauses that can
     * drift apart.
     */
    public val pending: List<SessionRow> by lazy {
        rows.filter { it.songId !in loggedCounts && matches(it) }.sortedWith(order.comparator)
    }

    /**
     * The logged section, most recent tap first. A song appears once however many times it
     * was tapped; [LoggedRow.countThisSession] carries the rest, because decision 47 makes
     * the second tap a real second session and not a mistake to be swallowed.
     */
    public val logged: List<LoggedRow> by lazy {
        val byId = rows.associateBy { it.songId }
        tapsHere.asReversed()
            .map { it.songId }
            .distinct()
            .mapNotNull { songId ->
                byId[songId]?.let { LoggedRow(it, loggedCounts.getValue(songId)) }
            }
            .filter { matches(it.row) }
    }

    /** True when there is nothing at all to show — an empty database, not an empty search. */
    public val empty: Boolean get() = !loading && rows.isEmpty()

    /**
     * The search box — **the one song matcher** (style review B1), shared with the Songs and
     * Repertoire lists. Adopting it changed the logger on purpose: `acdc` now finds *AC/DC*.
     */
    private fun matches(row: SessionRow): Boolean =
        SongSearch.matches(query, row.title, row.artistName)

    // ---- Transitions ------------------------------------------------------------------

    public fun withInstruments(instruments: List<InstrumentChip>): SessionState =
        copy(instruments = instruments)

    /** The saved Views available to switch to, in `(position, id)` order (V18). */
    public fun withViews(views: List<SessionView>): SessionState = copy(views = views)

    /**
     * The active View has changed but its rows have not arrived yet.
     *
     * V22: switching View is a **full reload** of the filter, the practice instrument and the
     * sort, and it clears **the pending undo offer** — which refers to a tap the user is no
     * longer looking at. It does **not** clear [taps].
     *
     * An earlier draft did clear them, on the grounds that two Views can share a practice
     * instrument. That was wrong twice over: [tapsHere] already scopes the logged section by
     * practice instrument, and [logged] resolves each tap against the current View's [rows],
     * so a song absent from the new View simply does not appear. Clearing instead broke the
     * ordinary practice motion of flicking guitar → bass → guitar, which today returns you to
     * your logged list and under the draft rule emptied it.
     */
    public fun switchingTo(view: SessionView): SessionState = copy(
        view = view,
        rows = emptyList(),
        loading = true,
        undo = null,
    )

    /** Rows as read. They carry no part's ratings until [withRatings] lays them on. */
    public fun withRows(rows: List<SessionRow>): SessionState =
        copy(rows = rows, loading = false, ratedFor = null)

    /** triage T9: [ratings], read for [part], replace every row's; a song absent from them is unrated (T8). */
    public fun withRatings(part: ResolvedPart?, ratings: Map<String, PartRatings>): SessionState =
        copy(rows = rows.map { it.ratedBy(ratings[it.songId]) }, ratedFor = part)

    public fun withOwnerPerformer(performerId: String?): SessionState = copy(ownerPerformerId = performerId)

    public fun withPerformers(performers: List<RepertosaurusRepository.Performer>): SessionState =
        copy(performers = performers)

    public fun withQuery(query: String): SessionState = copy(query = query)

    /**
     * A view preference, not data: it reorders the same rows and writes nothing here. Where it
     * is *persisted* is V13b's two-branch rule and lives in `ViewCoordinator.rememberOrder`.
     *
     * Three things move, and all three have to:
     *
     * - the active View, which is where [order] reads from;
     * - the matching entry in [views], or a switcher rendering from that list shows the old
     *   direction and switching away and back reverts it;
     * - [fallbackOrder], so the toggle is not a silent no-op before the first View has loaded.
     */
    public fun withOrder(order: SessionOrder): SessionState = copy(
        view = view?.copy(order = order),
        views = if (view == null) views else views.map {
            if (it.id == view.id) it.copy(order = order) else it
        },
        fallbackOrder = order,
    )

    /**
     * The tap path. Pure and instant: no database, no read, no suspension. The caller
     * persists [tap] afterwards, off the main thread (decision 44 — this is the interaction
     * the product exists for and it must never wait on SQLite).
     */
    public fun plusTap(tap: SessionTap): SessionState =
        plusTap(tap, rows.firstOrNull { it.songId == tap.songId }?.title ?: "", timing = null)

    /**
     * timer TM7: a timer's log is a tap in every way but these: [title] is the timer's, since its song need not
     * be in this View, and [timing] is what the undo snackbar says of its time.
     */
    public fun plusTap(tap: SessionTap, title: String, timing: StopOutcome?): SessionState = copy(
        taps = taps + tap,
        undo = UndoOffer(tapId = tap.tapId, songTitle = title, feel = tap.feel, timing = timing),
        message = null,
    )

    /**
     * Undo (decision 8). Dropping the tap is all the UI has to do — the underlying row was
     * never altered, so the song reappears at exactly its previous staleness position,
     * including "never" when that log was its only one.
     */
    public fun minusTap(tapId: String): SessionState = copy(
        taps = taps.filterNot { it.tapId == tapId },
        undo = if (undo?.tapId == tapId) null else undo,
    )

    public fun withoutUndo(): SessionState = copy(undo = null)

    public fun withMessage(message: String?): SessionState = copy(message = message)
}

/**
 * One row of the session list. [daysSince] is null for a song never practised on the
 * selected instrument — the rows the ordering deliberately puts first.
 *
 * [priority] and [confidence] are the resolved part's ratings (triage T9), null when unrated or when
 * no part resolves (T8).
 */
public data class SessionRow(
    val songId: String,
    val title: String,
    val artistName: String?,
    val daysSince: Long?,
    val timesPractised: Long,
    val priority: RatingLevel? = null,
    val confidence: RatingLevel? = null,
) {
    public fun ratedBy(ratings: PartRatings?): SessionRow =
        copy(priority = ratings?.priority, confidence = ratings?.confidence)

    /** The staleness badge. "never" when there is no live event (decisions 8, 48). */
    public val badge: String
        get() = when (daysSince) {
            null -> "never"
            0L -> "today"
            1L -> "1d"
            else -> "${daysSince}d"
        }
}

/** A song logged in this session, with the number of taps it took (decision 47). */
public data class LoggedRow(val row: SessionRow, val countThisSession: Int)

/**
 * One optimistic log. Held in UI state from the instant of the tap; the database write
 * follows asynchronously and its event id is tracked separately by the caller, because the
 * undo may be offered before the insert has finished.
 */
public data class SessionTap(
    val tapId: String,
    val songId: String,
    val instrumentId: String,
    val feel: RatingLevel?,
    val note: String?,
    val loggedOn: String,
    /** timer TM7: null for a tap, and for a timer's untimed log (TM8); schema-3 M10's 1-86400 otherwise. */
    val durationSeconds: Long? = null,
)

/** What the snackbar offers after a tap. [timing] is a timer's (timer TM7, TM8), null for anything else. */
public data class UndoOffer(val tapId: String, val songTitle: String, val feel: RatingLevel?, val timing: StopOutcome? = null)
