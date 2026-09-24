package dev.repertosaurus.session

import dev.repertosaurus.data.InsertIgnored
import dev.repertosaurus.data.JunctionWrite
import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.data.RepertosaurusRepository.HeldSong

/**
 * The Repertoire route's shared core — repertoire-editing R1-R10: performer → role → toggle
 * the songs that performer holds on that role.
 *
 * **R5: nothing here reaches the practice path.** Toggling a song is recording a capability,
 * not logging practice (E1); this class writes `song_performer` rows through
 * [CapabilityCoordinator] and nothing else.
 *
 * Blocking, like [CapabilityCoordinator]; the caller keeps it off the main thread. R8's
 * optimistic, serialised, per-row toggling is the screen's job — this is the write it queues.
 */
public class RepertoireCoordinator(
    private val repository: RepertosaurusRepository,
) {

    private val capabilities = CapabilityCoordinator(repository)

    /**
     * R1, R2, R10: every live performer, each with the roles they hold and the live
     * instruments they do not — both in decision 18's chip order.
     *
     * A role is an instrument the performer has at least one live `song_performer` row on,
     * on a live song and a live instrument — **derived, never stored** (E15), read from the
     * same `selectPerformerInstruments` the performer roster's subtitle uses, so the two
     * cannot disagree. Soft-deleted performers and instruments do not appear (R10).
     */
    public fun performers(): List<PerformerRoles> {
        val held = repository.performerInstruments()
        val instruments = SessionInstruments.chips(repository.instruments())
        return repository.performers().map { performer ->
            val heldIds = held[performer.id].orEmpty().mapTo(mutableSetOf()) { it.id }
            PerformerRoles(
                performerId = performer.id,
                performerName = performer.name,
                roles = instruments.filter { it.id in heldIds },
                unheldInstruments = instruments.filterNot { it.id in heldIds },
            )
        }
    }

    /**
     * R3, R9: **every live song in the database** — not the songs in any View — each flagged
     * with whether this `(performer, instrument)` holds it, in R7's [ORDER].
     *
     * R7 fixes the order *when the list loads and when the search changes*; a toggle must not
     * re-sort. So this and [ToggleList.paged] apply [ORDER] and nothing else does — the screen
     * updates a toggled row's flag in place.
     */
    public fun songs(performerId: String, instrumentId: String): List<HeldSong> =
        repository.songsWithHeldFlag(performerId, instrumentId).sortedWith(ORDER)

    /**
     * R4: toggle on adds or revives the `(song, performer, instrument)` row; toggle off
     * soft-deletes it — [CapabilityCoordinator.addById] and [CapabilityCoordinator.remove],
     * reused rather than re-implemented. A revive keeps `is_lead`, `vocal_range` and `notes`
     * (E6), so off-then-on is lossless; a new row gets the schema defaults (R6).
     *
     * **R4a: both halves find the row by its triple**, never by assuming its id is
     * `Ids.songPerformer` of it. A database upgraded 1 → 2 keeps two-key ids (schema-compatibility
     * S3); a derived-id lookup there missed the row, so toggle-off said "already removed" and
     * removed nothing, and toggle-on's `OR IGNORE` was blocked by the unique key and said
     * `CREATED`. Toggle-off is [CapabilityCoordinator.removeByKey]; toggle-on's
     * insert-or-revive reads by the triple, and an ignored insert throws `InsertIgnored`, which
     * R8 rolls back as a failure.
     *
     * @return what the write did (R23c): R8's rollback reads [JunctionWrite.SongGone] as a
     *   refusal; an [JunctionWrite.Added] with `EXISTING` or a [JunctionWrite.Removed] with
     *   `wrote = false` means the row already was as asked.
     */
    public fun setHeld(
        songId: String,
        performerId: String,
        instrumentId: String,
        held: Boolean,
    ): JunctionWrite =
        if (held) {
            capabilities.addById(songId, performerId, instrumentId)
        } else {
            capabilities.removeByKey(songId, performerId, instrumentId)
        }

    public companion object {

        /**
         * **R7's one comparator: held first**, then N2's base order —
         * [SongSearch.byTitle]: title case-insensitively, then artist, then id. Applied once,
         * in [songs] and [ToggleList.paged] (E35's precedent); the SQL deliberately has no
         * `ORDER BY`. R3's search is triage T4's, in [SongPaging].
         */
        public val ORDER: Comparator<HeldSong> =
            compareBy<HeldSong> { if (it.held) 0 else 1 }
                .then(SongSearch.byTitle({ it.title }, { it.artistName }, { it.songId }))

        /** R3's count of songs held, over whatever rows the screen holds. */
        public fun heldCount(rows: List<HeldSong>): Int = rows.count { it.held }
    }
}

/**
 * One performer on the Repertoire route (R1, R2): the roles they hold, as chips, and the live
 * instruments offered under "+ role".
 */
public data class PerformerRoles(
    val performerId: String,
    val performerName: String,
    val roles: List<InstrumentChip>,
    val unheldInstruments: List<InstrumentChip>,
) {
    /**
     * The instrument [id] names on this row, held or not — the toggle list's heading. Null when
     * the instrument is neither (it was removed since the row was read).
     */
    public fun instrument(id: String): InstrumentChip? =
        roles.firstOrNull { it.id == id } ?: unheldInstruments.firstOrNull { it.id == id }
}

/**
 * The Repertoire route's state (R1-R10) — in the core (style review F17 B1) so R7 and R8's pure
 * changes are JVM-tested and the desktop UI renders the same value. The ViewModel, its lock and
 * its scope stay in `androidApp` (E29).
 */
public data class RepertoireState(
    val performers: List<PerformerRoles> = emptyList(),
    val loadingPerformers: Boolean = false,
    /** The open toggle list, or null on the performer list. */
    val list: ToggleList? = null,
    /** E43: a confirmation, and only ever a confirmation. */
    val message: String? = null,
    /** E43: a refusal or failure, rendered as an error line (D97). */
    val error: String? = null,
) {
    /** Apply [change] to the list only if it is still the one [ticket] names. */
    public fun withList(ticket: Long, change: (ToggleList) -> ToggleList): RepertoireState {
        val open = list ?: return this
        return if (open.ticket == ticket) copy(list = change(open)) else this
    }

    /** The performer row [performerId] names, for the toggle list's heading. */
    public fun performer(performerId: String): PerformerRoles? = performers.firstOrNull { it.performerId == performerId }

    /**
     * A read of the open list landed (F22 B4: in the core, beside the state it changes): the
     * rows, or why not — on the error channel.
     */
    public fun withRead(ticket: Long, outcome: Result<List<HeldSong>>): RepertoireState =
        outcome.fold(
            onSuccess = { rows -> withList(ticket) { it.reread(rows) } },
            onFailure = { failure ->
                withList(ticket) { it.copy(loading = false) }
                    .copy(error = Messages.couldNot("read the songs", failure))
            },
        )

    /**
     * **R8: a toggle's write landed.** A refusal ([JunctionWrite.SongGone]) or a failure rolls the
     * row back to `!wanted` and says why on the error channel; anything else lets the optimistic
     * flag stand. A [reread][Pair.second] that came back with the write (R23c: the song was
     * removed elsewhere) is applied too.
     *
     * [describeFailure] words a thrown failure — [Messages.toggleFailed] unless the platform has
     * its own sentence for one (the Android route's dropped write, F18 N2). An [InsertIgnored]
     * (R4a) arrives here as a failure like any other.
     */
    public fun withToggle(
        ticket: Long,
        row: HeldSong,
        wanted: Boolean,
        outcome: Result<Pair<JunctionWrite, List<HeldSong>?>>,
        describeFailure: (label: String, failure: Throwable) -> String = Messages::toggleFailed,
    ): RepertoireState {
        val label = songLabel(row.title, row.artistName)
        val (write, reread) = outcome.getOrNull() ?: (null to null)
        val refusal = when {
            outcome.isFailure -> outcome.exceptionOrNull()?.let { describeFailure(label, it) }
            write is JunctionWrite.SongGone -> Messages.toggleSongGone(label)
            else -> null
        }
        val landed = withList(ticket) { it.landed(row.songId, rollBackTo = if (refusal == null) null else !wanted) }
        val read = if (reread != null) landed.withRead(ticket, Result.success(reread)) else landed
        return if (refusal == null) read else read.copy(error = refusal)
    }
}

/**
 * One `(performer, instrument)`'s toggle list (R3).
 *
 * [rows] is every live song with its current flag, optimistic ones included; [page] is triage T5a's
 * paging over R7's held-first order, fixed as [Page] says, so a toggle never moves a row (R7).
 */
public data class ToggleList(
    val performerId: String,
    val instrumentId: String,
    /** Which open list a result belongs to, so a result for a closed list never lands. */
    val ticket: Long,
    val rows: List<HeldSong> = emptyList(),
    val page: Page = Page(),
    val loading: Boolean = false,
    /** R8: rows whose write has not landed. Each is disabled until it does. */
    val inFlight: Set<String> = emptySet(),
) {
    /** R3's held count, over every song — not only the ones the page is showing. */
    val heldCount: Int get() = RepertoireCoordinator.heldCount(rows)

    /** T5a: every row as the paging sees it, in R7's order; `set` is held. */
    public fun paged(): List<PagedSong> =
        rows.sortedWith(RepertoireCoordinator.ORDER).map { PagedSong(it.songId, it.title, it.artistName, set = it.held) }

    /** The rows as displayed: the page's order, with each row's current flag. */
    public fun visible(): List<HeldSong> {
        val byId = rows.associateBy { it.songId }
        return page.order.mapNotNull { byId[it] }
    }

    /** One row's flag, changed in place — **R7: the order is not touched**. */
    public fun withHeld(songId: String, held: Boolean): ToggleList =
        copy(rows = rows.map { if (it.songId == songId) it.copy(held = held) else it })

    /** R8: the optimistic half of a toggle; the row is disabled until its write lands. */
    public fun toggling(songId: String, held: Boolean): ToggleList =
        withHeld(songId, held).copy(inFlight = inFlight + songId)

    /**
     * R8: the write for [songId] landed. [rollBackTo] is the flag to restore when it was refused
     * or failed; null when the optimistic flag stands.
     */
    public fun landed(songId: String, rollBackTo: Boolean?): ToggleList {
        val settled = copy(inFlight = inFlight - songId)
        return if (rollBackTo == null) settled else settled.withHeld(songId, rollBackTo)
    }

    /**
     * A fresh read. Rows still in flight keep their optimistic flag, since the read cannot know what
     * their writes will do, and the page is fixed again (R7's load moment).
     */
    public fun reread(fresh: List<HeldSong>): ToggleList {
        val pending = rows.filter { it.songId in inFlight }.associateBy { it.songId }
        val read = copy(rows = fresh.map { pending[it.songId] ?: it }, loading = false)
        return read.copy(page = page.fixed(read.paged()))
    }

    public fun withQuery(query: String): ToggleList = copy(page = page.withQuery(query, paged()))

    public fun withLetter(letter: Char): ToggleList = copy(page = page.withLetter(letter, paged()))

    public fun withFilter(filter: PageFilter): ToggleList = copy(page = page.withFilter(filter, paged()))
}
