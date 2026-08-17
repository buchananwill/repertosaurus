package dev.repertaurus.data

import dev.repertaurus.core.Ids
import dev.repertaurus.core.Timestamps
import dev.repertaurus.db.RepertaurusDatabase
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * A thin repository over the generated SQLDelight queries.
 *
 * Thin is the point: it owns id generation (decisions 2-6), the timestamp format
 * (decision 10) and the local-today default (decision 45), and nothing else. There is no
 * UI state here, no caching and no observation — that belongs a layer up.
 */
public class RepertaurusRepository(
    private val database: RepertaurusDatabase,
    private val deviceId: String,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
    private val newId: () -> String = { Ids.random() },
) {

    /**
     * One row of the coldest-first session list.
     *
     * [daysSince] is derived here rather than in SQL (decisions 48, 57), and is null for a
     * song never practised on this instrument — the rows the ordering deliberately puts
     * first.
     */
    public data class StaleSong(
        val songId: String,
        val title: String,
        val artistName: String?,
        val lastPractised: String?,
        val timesPractised: Long,
        val daysSince: Long?,
    )

    /** One row of the `instrument` lookup (decisions 15, 18). */
    public data class Instrument(val id: String, val name: String)

    /** One row of `artist`, for the type-ahead (decisions 16, 20, 21). */
    public data class Artist(val id: String, val name: String, val sortName: String)

    /** One row of `performer` (decisions 25, 26), for the View editor's filter type-ahead. */
    public data class Performer(val id: String, val name: String)

    /**
     * One live row of `saved_view` (views V15-V18), as stored.
     *
     * Deliberately close to the columns: [sortOrder] is V17's stored `SessionOrder` name and
     * [filterLeadOnly] is the 0/1 of the CHECK. Turning either into a typed value is
     * `ViewCoordinator`'s job, so the repository stays the thin thing it is.
     */
    public data class SavedView(
        val id: String,
        val name: String,
        val filterPerformerId: String?,
        val filterInstrumentId: String?,
        val filterLeadOnly: Long,
        val practiceInstrumentId: String,
        val sortOrder: String,
        val position: Long,
        val notes: String?,
    )

    /** The device's local today, `YYYY-MM-DD` (decision 45). */
    public fun today(): String = Timestamps.today(clock, timeZone)

    /**
     * Live instruments, for the Session screen's chip row. Read from the table, never a
     * hardcoded list: decision 15 says adding one must never require a code change.
     */
    public fun instruments(): List<Instrument> =
        database.instrumentQueries.selectAllLive().executeAsList()
            .map { Instrument(id = it.id, name = it.name) }

    /**
     * The seven managed lookup tables (decisions 15, 16; editing E13, E17, E18).
     *
     * **E31: this surface no longer lives here.** The six `*Lookup` methods and their private
     * helper moved to [LookupTables], which is already that aggregate's plumbing and already
     * in the right module — roughly 85 lines out of a class E30 found at 698 lines and 41
     * public members across five unrelated aggregates. E32 owns the rest of the split; **no
     * nested row type moved**, because promoting them is the non-mechanical part and it
     * touches 12 sites in `androidApp`.
     *
     * E34: `addInstrument`, `renameInstrument`, `removeInstrument` and `practiceEventsOn`
     * were named delegates onto this surface for the one kind wired before E18. They lost
     * their last production caller when `InstrumentStore` was replaced, and `instrument` is
     * one of seven equal kinds, so they are gone rather than kept as a bespoke API for one of
     * them.
     */
    internal val lookups: LookupTables = LookupTables(database, deviceId, clock)

    /**
     * E38: run several statements as one unit, so a caller that reads and then writes cannot
     * be interleaved with another writer, and a failure part-way leaves nothing behind.
     *
     * Exposed because the atomic unit is sometimes larger than a repository method:
     * `CapabilityCoordinator.add` performs three writes — two lookups and the capability row
     * — and an interrupted add would otherwise leave an orphan lookup.
     */
    public fun <T> inTransaction(body: () -> T): T =
        database.transactionWithResult { body() }

    /** Live artists, ordered by `sort_name` — the article at the end, per decision 21. */
    public fun artists(): List<Artist> =
        database.artistQueries.selectAllLive().executeAsList()
            .map { Artist(id = it.id, name = it.name, sortName = it.sort_name) }

    /**
     * Live performers, ordered by name. Two columns of a lookup table with no derivation in
     * it — the View editor filters on an id (views V24) and shows the name.
     */
    public fun performers(): List<Performer> =
        database.performerQueries.selectAllLive().executeAsList()
            .map { Performer(id = it.id, name = it.name) }

    // ---- song_performer: the capability write path (editing E1-E11) ---------------------
    //
    // E9: this table has been READ-ONLY since the migration created it. Nothing in the app
    // could add, change or remove a capability row, which is why "who plays what on this
    // song" was unreachable from a phone. Everything below is that path.

    /**
     * One resolved `song_performer` row, as stored — decisions 26, 27; views V1-V9.
     *
     * Deliberately close to the columns, like [SavedView]: [isLead] is the 0/1 of the CHECK
     * and [vocalRange] is V7's two-value integer. Turning either into a typed value is
     * `CapabilityCoordinator`'s job, so the repository stays the thin thing it is.
     */
    public data class SongPerformerRow(
        val id: String,
        val performerId: String,
        val performerName: String,
        val instrumentId: String,
        val instrumentName: String,
        val isLead: Long,
        val vocalRange: Long?,
        val notes: String?,
    )

    /** The line-up of one song: who does what on it (E3, E10). Lead rows first. */
    public fun songPerformers(songId: String): List<SongPerformerRow> =
        database.song_performerQueries.selectBySong(songId).executeAsList().map { row ->
            SongPerformerRow(
                id = row.id,
                performerId = row.performer_id,
                performerName = row.performer_name,
                instrumentId = row.instrument_id,
                instrumentName = row.instrument_name,
                isLead = row.is_lead,
                vocalRange = row.vocal_range,
                notes = row.notes,
            )
        }

    /**
     * Add a capability, **or revive the tombstone that already holds this triple** (E6).
     *
     * The id is `Ids.songPerformer(songId, performerId, instrumentId)` (decision 4f, V3, V5)
     * and is never assembled by hand — this project has forked its id derivation twice, and
     * `Ids.junction` now refuses the superseded two-key form for this table (V31).
     *
     * Because the id is derived from the three keys, re-adding a pair that was removed
     * computes the **same primary key**. There are three outcomes and only one of them is an
     * insert:
     *
     * - no row → insert, `is_lead = 0` (E4), no range, no notes;
     * - a tombstoned row → **restore**: clear `deleted_at`, bump `updated_at`, and touch
     *   nothing else. A plain `INSERT` throws on the primary key and an `INSERT OR REPLACE`
     *   would silently discard the row's `notes` and `vocal_range`;
     * - a live row → nothing. The capability being asked for is already recorded, and
     *   overwriting it would discard facts the user typed.
     *
     * This deliberately takes **no facts**. `is_lead`, `vocal_range` and `notes` are set by
     * [updateSongPerformer] afterwards, so that a revive can never be the thing that quietly
     * resets them.
     *
     * **E38: the read and the branch are one transaction**, and the insert is
     * `INSERT OR IGNORE`. Read-then-write across separate statements with nothing atomic
     * between them means two rapid taps both observe "absent" and the second violates the
     * primary key; the transaction closes that, and the conflict clause makes the losing
     * writer a no-op rather than an exception even where the driver offers no isolation. It
     * is `OR IGNORE` and never `OR REPLACE`, because replace would discard the winner's
     * `notes` and `vocal_range` — the two columns E6 exists to protect.
     *
     * @return the row's id, whichever of the three outcomes happened.
     */
    public fun addSongPerformer(
        songId: String,
        performerId: String,
        instrumentId: String,
    ): String {
        val id = Ids.songPerformer(songId, performerId, instrumentId)
        database.transaction {
            val existing = database.song_performerQueries.selectById(id).executeAsOneOrNull()
            val now = Timestamps.now(clock)
            when {
                existing == null -> database.song_performerQueries.insertIfAbsent(
                    id = id,
                    song_id = songId,
                    performer_id = performerId,
                    instrument_id = instrumentId,
                    is_lead = 0L,
                    vocal_range = null,
                    notes = null,
                    updated_at = now,
                    deleted_at = null,
                    device_id = deviceId,
                )
                existing.deleted_at != null -> database.song_performerQueries.restore(
                    updated_at = now,
                    device_id = deviceId,
                    id = id,
                )
            }
        }
        return id
    }

    /**
     * The three editable facts on a capability row (E4, E8). The three keys are not among
     * them: changing one is a *different* row with a different derived id, not an edit, which
     * is why `song_performer.sq :: update` cannot reach them.
     *
     * **E37: this can never clear a tombstone.** `deleted_at` is not in the statement's `SET`
     * list and `deleted_at IS NULL` is in its `WHERE`, so an edit that arrives after a remove
     * writes nothing at all. The old form wrote `deleted_at = NULL` under a comment saying it
     * was only ever called on a live row — but the UI dispatches each write as its own
     * coroutine, so a remove and an in-flight edit complete in either order, and under
     * last-write-wins the resurrected row would carry the newer timestamp and win on every
     * device permanently.
     */
    public fun updateSongPerformer(
        id: String,
        isLead: Long,
        vocalRange: Long?,
        notes: String?,
    ) {
        require(isLead == 0L || isLead == 1L) { "is_lead is 0 or 1, got $isLead" }
        require(vocalRange == null || vocalRange == 0L || vocalRange == 1L) {
            "vocal_range is 0, 1 or null, got $vocalRange"
        }
        database.song_performerQueries.update(
            is_lead = isLead,
            vocal_range = vocalRange,
            notes = notes,
            updated_at = Timestamps.now(clock),
            device_id = deviceId,
            id = id,
        )
    }

    /**
     * E7: removing a capability is a **soft delete**, like every other mutable row in this
     * schema. A stale device reinserts a hard-deleted row on the next merge — and E6's revive
     * path depends on the tombstone still being there.
     */
    public fun removeSongPerformer(id: String) {
        val now = Timestamps.now(clock)
        database.song_performerQueries.softDelete(
            deleted_at = now,
            updated_at = now,
            device_id = deviceId,
            id = id,
        )
    }

    /**
     * E14, E15: the instruments each performer is recorded on, keyed by performer id.
     *
     * **Derived from `song_performer`, never stored.** There is no `performer_instrument`
     * table and none is proposed: the capability rows already carry the fact and a second
     * place to state it would drift from the first. The accepted gap is that a performer with
     * no capability rows yet is simply absent from this map — the roster still lists them and
     * the subtitle fills in as capabilities are entered.
     *
     * One query for the whole roster rather than one per performer, and the display order is
     * applied in the session layer: SQLite 3.19 has no ordered aggregates (minSdk 26), so an
     * order asserted in SQL here would be whatever the scan happened to produce.
     */
    public fun performerInstruments(): Map<String, List<Instrument>> =
        database.song_performerQueries.selectPerformerInstruments().executeAsList()
            .groupBy({ it.performer_id }, { Instrument(it.instrument_id, it.instrument_name) })

    /**
     * The type-ahead's create-on-enter (decision 16), and the reason decision 17 exists.
     *
     * The id is `UUIDv5(namespace(artist), normalise(name))` (decisions 2, 4), so a name
     * that normalises to one already stored — `Fratellis` against `The Fratellis`, `AC DC`
     * against `AC/DC` — resolves to the *existing row* rather than creating a near
     * duplicate no merge rule could reconcile. The stored display name is left alone: an
     * id is immutable and the canonical spelling is the one already there (decision 5).
     */
    public fun findOrCreateArtist(name: String): String {
        val display = name.trim()
        require(display.isNotEmpty()) { "an artist needs a name" }
        val id = Ids.derived("artist", display)
        if (database.artistQueries.selectById(id).executeAsOneOrNull() == null) {
            database.artistQueries.insert(
                id = id,
                name = display,
                sort_name = sortName(display),
                updated_at = Timestamps.now(clock),
                deleted_at = null,
                device_id = deviceId,
            )
        }
        return id
    }

    /**
     * Add a song: title and artist, nothing else. Every other column is left null, which
     * decisions 27 and 37 require — no key may be mandatory, in phase 1 or later.
     *
     * The id is `UUIDv5(namespace(song), artist_id + "/" + normalise(title))` (decision
     * 4d), so adding a song the repertoire already holds resolves to that row instead of
     * forking it. An existing row is returned untouched rather than overwritten.
     */
    public fun createSong(title: String, artistId: String): String {
        val display = title.trim()
        require(display.isNotEmpty()) { "a song needs a title" }
        val id = Ids.song(artistId, display)
        if (database.songQueries.selectById(id).executeAsOneOrNull() == null) {
            database.songQueries.insert(
                id = id,
                title = display,
                artist_id = artistId,
                reference_recording = null,
                key_signature = null,
                tonal_centre = null,
                tonality_note = null,
                tempo_bpm = null,
                duration_seconds = null,
                decade = null,
                loop_length = null,
                chord_count = null,
                chord_pattern = null,
                groove_id = null,
                mashup_note = null,
                notes = null,
                chart_url = null,
                updated_at = Timestamps.now(clock),
                deleted_at = null,
                device_id = deviceId,
            )
        }
        return id
    }

    /** Article moved to the end, per decision 21. */
    private fun sortName(name: String): String =
        if (name.startsWith("The ")) name.substring(4) + ", The" else name

    /**
     * Staleness-ordered eligible songs: never-practised first, then coldest first. Voided
     * events are excluded (decision 8).
     *
     * [practiceInstrumentId] is the instrument staleness is measured on — and, per views
     * decision V13, the one the session logs to. The three filter parameters are a
     * **separate** question, which songs are eligible at all, and V12 keeps the two apart: a
     * song that passes the filter but has never been touched on the practice instrument still
     * appears here, carrying a null [StaleSong.lastPractised]. That cross-instrument case is
     * the whole point.
     *
     * The filter arrives as primitives rather than as a `ViewFilter`, because a `ViewFilter`
     * is a `session` type and `data` must not depend on `session`. `SessionCoordinator.rows`
     * destructures it; this layer only binds parameters.
     *
     * All four parameters are required and none has a default. The retired signature took an
     * instrument and nothing else, and a caller that silently skips the filter is the exact
     * bug the Views spec exists to fix. Three of them are adjacent and same-typed, so **call
     * this with named arguments**.
     */
    public fun songsByStaleness(
        practiceInstrumentId: String,
        filterPerformerId: String?,
        filterInstrumentId: String?,
        leadOnly: Long,
        today: String = today(),
    ): List<StaleSong> =
        database.songQueries.selectByStaleness(
            practiceInstrumentId = practiceInstrumentId,
            filterPerformerId = filterPerformerId,
            filterInstrumentId = filterInstrumentId,
            leadOnly = leadOnly,
        ).executeAsList().map { row ->
            staleSong(
                songId = row.song_id,
                title = row.title,
                artistName = row.artist_name,
                lastPractised = row.last_practised,
                timesPractised = row.times_practised,
                today = today,
            )
        }

    /**
     * The set-list-scoped session mode's staleness list.
     *
     * It is **not** the same ordering clause as [songsByStaleness] any more, and the comment
     * that said so was wrong: this path predates Views, takes no filter, and ignores the one
     * `songsByStaleness` applies. It is retained rather than folded in per views.md §3 —
     * "practise the Blue Lion set" is a different session shape, and merging the two now would
     * double the query's parameter surface before either half has been used in anger.
     */
    public fun songsByStalenessInSetlist(
        setlistId: String,
        instrumentId: String,
        today: String = today(),
    ): List<StaleSong> =
        database.songQueries.selectByStalenessInSetlist(setlistId, instrumentId)
            .executeAsList().map { row ->
                staleSong(
                    songId = row.song_id,
                    title = row.title,
                    artistName = row.artist_name,
                    lastPractised = row.last_practised,
                    timesPractised = row.times_practised,
                    today = today,
                )
            }

    /**
     * The five columns both staleness queries return, and the one place [StaleSong.daysSince]
     * is derived (decisions 48, 57) — SQLDelight generates a distinct row type per query, so
     * the two cannot share a mapper without this.
     */
    private fun staleSong(
        songId: String,
        title: String,
        artistName: String?,
        lastPractised: String?,
        timesPractised: Long,
        today: String,
    ): StaleSong = StaleSong(
        songId = songId,
        title = title,
        artistName = artistName,
        lastPractised = lastPractised,
        timesPractised = timesPractised,
        daysSince = lastPractised?.let { Timestamps.daysBetween(it, today) },
    )

    // ---- saved_view (views V15-V24) -----------------------------------------------------

    /** Live Views in `(position, id)` order — V18's total order, applied in SQL. */
    public fun savedViews(): List<SavedView> =
        database.saved_viewQueries.selectAllLive().executeAsList().map { row ->
            SavedView(
                id = row.id,
                name = row.name,
                filterPerformerId = row.filter_performer_id,
                filterInstrumentId = row.filter_instrument_id,
                filterLeadOnly = row.filter_lead_only,
                practiceInstrumentId = row.practice_instrument_id,
                sortOrder = row.sort_order,
                position = row.position,
                notes = row.notes,
            )
        }

    /**
     * A new View at the end of the list.
     *
     * The id is **random** (V16), unlike almost everything else here: `name` is the only
     * candidate key and it is user-editable, so deriving from it would break decision 5's
     * rule that an id is immutable while a name is not. `setlist` is random for exactly this
     * reason.
     */
    public fun createSavedView(
        name: String,
        filterPerformerId: String?,
        filterInstrumentId: String?,
        filterLeadOnly: Long,
        practiceInstrumentId: String,
        sortOrder: String,
        notes: String? = null,
    ): SavedView {
        val view = SavedView(
            id = newId(),
            name = name,
            filterPerformerId = filterPerformerId,
            filterInstrumentId = filterInstrumentId,
            filterLeadOnly = filterLeadOnly,
            practiceInstrumentId = practiceInstrumentId,
            sortOrder = sortOrder,
            // SQLDelight cannot prove COALESCE(MAX(...), -1) + 1 non-null over an empty
            // table, so the null case is named here: the first View is position 0.
            position = database.saved_viewQueries
                .nextPosition { next -> next ?: 0L }
                .executeAsOne(),
            notes = notes,
        )
        database.saved_viewQueries.insert(
            id = view.id,
            name = view.name,
            filter_performer_id = view.filterPerformerId,
            filter_instrument_id = view.filterInstrumentId,
            filter_lead_only = view.filterLeadOnly,
            practice_instrument_id = view.practiceInstrumentId,
            sort_order = view.sortOrder,
            position = view.position,
            notes = view.notes,
            updated_at = Timestamps.now(clock),
            deleted_at = null,
            device_id = deviceId,
        )
        return view
    }

    /** Every column a View owns is editable; the id never is (decision 5). */
    public fun updateSavedView(view: SavedView) {
        database.saved_viewQueries.update(
            name = view.name,
            filter_performer_id = view.filterPerformerId,
            filter_instrument_id = view.filterInstrumentId,
            filter_lead_only = view.filterLeadOnly,
            practice_instrument_id = view.practiceInstrumentId,
            sort_order = view.sortOrder,
            position = view.position,
            notes = view.notes,
            updated_at = Timestamps.now(clock),
            device_id = deviceId,
            id = view.id,
        )
    }

    /**
     * V23: a soft delete carrying `deleted_at`, like every other mutable row. A hard delete
     * gets reinserted by any stale device on the next merge.
     */
    public fun deleteSavedView(id: String) {
        val now = Timestamps.now(clock)
        database.saved_viewQueries.softDelete(
            deleted_at = now,
            updated_at = now,
            device_id = deviceId,
            id = id,
        )
    }

    /**
     * The primary tap path. One insert, no read first. The id is random (decision 6):
     * deriving it would collapse two legitimate same-day sessions into one row.
     *
     * [contextId] is nullable — a one-tap log must never require a second chip
     * (decision 45a). [feel] is the optional 1-3 long-press rating (decision 46).
     *
     * @return the id of the event just written, so the caller can offer undo.
     */
    public fun logPractice(
        songId: String,
        instrumentId: String,
        contextId: String? = null,
        feel: Long? = null,
        note: String? = null,
        loggedOn: String = today(),
    ): String {
        require(feel == null || feel in 1L..3L) { "feel is 1-3 or null, got $feel" }
        val id = newId()
        database.practice_eventQueries.insert(
            id = id,
            song_id = songId,
            logged_on = loggedOn,
            instrument_id = instrumentId,
            context_id = contextId,
            feel = feel,
            note = note,
            created_at = Timestamps.now(clock),
            device_id = deviceId,
        )
        return id
    }

    /**
     * Undo. Deleting a practice event is an **append**, not a mutation (decision 8): a row
     * in `practice_event_void` naming the voided event, which reads left-anti-join. A
     * union merge cannot suppress a row, so a mutation here would silently come back on
     * the next sync.
     *
     * @return the id of the void row.
     */
    public fun voidPractice(practiceEventId: String): String {
        val id = newId()
        database.practice_event_voidQueries.insert(
            id = id,
            practice_event_id = practiceEventId,
            created_at = Timestamps.now(clock),
            device_id = deviceId,
        )
        return id
    }

    /** The newest live event, the sensible undo target. */
    public fun mostRecentLiveEvent(): String? =
        database.practice_eventQueries.selectMostRecentLive().executeAsOneOrNull()?.id

    /** Live practice history for one song, voided events removed (decision 8). */
    public fun practiceHistory(songId: String): List<PracticeEntry> =
        database.practice_eventQueries.selectBySong(songId).executeAsList().map { row ->
            PracticeEntry(
                id = row.id,
                loggedOn = row.logged_on,
                instrumentId = row.instrument_id,
                instrumentName = row.instrument_name,
                contextName = row.context_name,
                feel = row.feel,
                note = row.note,
            )
        }

    public data class PracticeEntry(
        val id: String,
        val loggedOn: String,
        val instrumentId: String,
        val instrumentName: String?,
        val contextName: String?,
        val feel: Long?,
        val note: String?,
    )

    /** Live count for a song, derived and never stored (decision 48). */
    public fun timesPractised(songId: String): Long =
        database.practice_eventQueries.countLiveBySong(songId).executeAsOne()

    public companion object {
        /**
         * The seeded placeholder of decision 28a — `UUIDv5(namespace(artist), 'unknown
         * artist')`, and the escape hatch when the user does not want to settle an
         * attribution to log a song. A placeholder is honest; a null FK is not an option
         * because `song.artist_id` is NOT NULL.
         */
        public const val UNKNOWN_ARTIST_ID: String = "cf06771d-4e8d-53fc-83fb-359be7dfaefc"
    }
}
