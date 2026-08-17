package dev.repertaurus.session

import dev.repertaurus.core.normalise

/**
 * The Session screen's state, as a plain immutable value with pure transitions.
 *
 * It lives in the shared core rather than in the Android layer for one reason: every rule
 * the screen has to get right — coldest-first ordering, a logged row moving out of the
 * way, an undo putting it back exactly where it was, two taps on one song being two
 * sessions (decision 47) — is testable on the JVM with no device. The Compose layer only
 * renders what is here.
 *
 * [rows] is the database's answer, untouched: never-practised first, then coldest first
 * (see `song.sq selectByStaleness`). This class never mutates a row. A tap appends to
 * [taps] and the row is *moved* to the logged section; an undo drops the tap and the row
 * reappears in its original position with its original badge. That is what makes decision
 * 8's "the song returns to its previous staleness position" true by construction rather
 * than by recomputation.
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

    private fun matches(row: SessionRow): Boolean {
        val terms = normalise(query).split(' ').filter { it.isNotEmpty() }
        if (terms.isEmpty()) return true
        val haystack = normalise(row.title + " " + (row.artistName ?: ""))
        return terms.all { haystack.contains(it) }
    }

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

    public fun withRows(rows: List<SessionRow>): SessionState =
        copy(rows = rows, loading = false)

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
    public fun plusTap(tap: SessionTap): SessionState {
        val title = rows.firstOrNull { it.songId == tap.songId }?.title ?: ""
        return copy(
            taps = taps + tap,
            undo = UndoOffer(tapId = tap.tapId, songTitle = title, feel = tap.feel),
            message = null,
        )
    }

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
 * Which end of the repertoire the session list starts from.
 *
 * Coldest first is the default and stays the default. The point of the toggle is that a
 * never-practised song is not *hot*: it has no last practice at all, so it leads in one
 * direction and trails in the other. Nulls are ordered explicitly in both comparators
 * rather than being left to fall wherever a missing value happens to sort, which is how
 * "never" ends up at the top of a hottest-first list and makes the toggle useless.
 */
public enum class SessionOrder {

    /** Never practised, then longest ago. The order the product exists to produce. */
    COLDEST_FIRST,

    /** Most recently practised, with never-practised last. */
    HOTTEST_FIRST,

    ;

    public val comparator: Comparator<SessionRow>
        get() = when (this) {
            COLDEST_FIRST -> compareBy(
                { row: SessionRow -> if (row.daysSince == null) 0 else 1 },
                { row: SessionRow -> -(row.daysSince ?: 0L) },
                { row: SessionRow -> row.title },
            )
            HOTTEST_FIRST -> compareBy(
                { row: SessionRow -> if (row.daysSince == null) 1 else 0 },
                { row: SessionRow -> row.daysSince ?: 0L },
                { row: SessionRow -> row.title },
            )
        }

    public val flipped: SessionOrder
        get() = if (this == COLDEST_FIRST) HOTTEST_FIRST else COLDEST_FIRST

    public companion object {

        /**
         * The one place a stored direction is read back — `saved_view.sort_order` (V17) and
         * `SessionPreferences.lastOrder()` alike. Written twice, verbatim, before this
         * existed.
         *
         * V17a: an unreadable value is a row a later build wrote, and coldest first is the
         * default that never surprises. Note what that means for a third direction: adding
         * the constant without widening the SQL `CHECK` throws at insert, and widening the
         * `CHECK` without adding the constant makes the unknown value silently downgrade here
         * and be written back on the next update. Both halves move together, and a test pins
         * them together.
         */
        public fun parse(name: String?): SessionOrder =
            entries.firstOrNull { it.name == name } ?: COLDEST_FIRST
    }
}

/**
 * **The one title-case helper (E46).** `backing vocal` reads as `Backing Vocal`; the stored
 * name is never touched (decision 5).
 *
 * It lives in the shared core because the capability sheet had re-implemented it **character
 * for character** in a composable, which is one of the two forks this project has already paid
 * for. Every surface that spells a stored lower-case name for a human calls this one: the chip
 * row, the capability chips, the capability dialog, the suggestion rows — and the desktop UI
 * when it arrives.
 */
public fun titleCase(name: String): String =
    name.split(' ').joinToString(" ") { word ->
        if (word.isEmpty()) word else word.replaceFirstChar { it.uppercaseChar() }
    }

/** One instrument chip, read from the `instrument` table — never a hardcoded enum. */
public data class InstrumentChip(val id: String, val name: String) {
    /** `backing vocal` reads as `Backing Vocal` on a chip; the stored name is untouched. */
    public val label: String
        get() = titleCase(name)
}

/**
 * One row of the session list. [daysSince] is null for a song never practised on the
 * selected instrument — the rows the ordering deliberately puts first.
 */
public data class SessionRow(
    val songId: String,
    val title: String,
    val artistName: String?,
    val daysSince: Long?,
    val timesPractised: Long,
) {
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
    val feel: Long?,
    val note: String?,
    val loggedOn: String,
)

/** What the snackbar offers after a tap. */
public data class UndoOffer(val tapId: String, val songTitle: String, val feel: Long?)
