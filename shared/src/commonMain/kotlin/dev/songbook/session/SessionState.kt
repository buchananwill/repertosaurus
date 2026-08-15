package dev.songbook.session

import dev.songbook.core.normalise

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
    val selectedInstrumentId: String? = null,
    val rows: List<SessionRow> = emptyList(),
    val taps: List<SessionTap> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    val undo: UndoOffer? = null,
    val message: String? = null,
) {

    /** Taps belonging to the instrument currently on screen. */
    public val tapsHere: List<SessionTap> by lazy {
        taps.filter { it.instrumentId == selectedInstrumentId }
    }

    /** How many times each song was logged in this session, on this instrument. */
    public val loggedCounts: Map<String, Int> by lazy {
        tapsHere.groupingBy { it.songId }.eachCount()
    }

    /**
     * The main list: everything not yet logged in this session, in the database's
     * staleness order, filtered by [query].
     */
    public val pending: List<SessionRow> by lazy {
        rows.filter { it.songId !in loggedCounts && matches(it) }
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

    public fun withInstruments(
        instruments: List<InstrumentChip>,
        selectedInstrumentId: String?,
    ): SessionState = copy(instruments = instruments, selectedInstrumentId = selectedInstrumentId)

    /** The chip has changed but its rows have not arrived yet. */
    public fun selecting(instrumentId: String): SessionState =
        copy(selectedInstrumentId = instrumentId, rows = emptyList(), loading = true, undo = null)

    public fun withRows(rows: List<SessionRow>): SessionState =
        copy(rows = rows, loading = false)

    public fun withQuery(query: String): SessionState = copy(query = query)

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

/** One instrument chip, read from the `instrument` table — never a hardcoded enum. */
public data class InstrumentChip(val id: String, val name: String) {
    /** `backing vocal` reads as `Backing Vocal` on a chip; the stored name is untouched. */
    public val label: String
        get() = name.split(' ').joinToString(" ") { word ->
            if (word.isEmpty()) word else word.replaceFirstChar { it.uppercaseChar() }
        }
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
