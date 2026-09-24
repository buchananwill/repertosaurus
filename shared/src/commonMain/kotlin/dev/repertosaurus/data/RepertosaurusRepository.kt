package dev.repertosaurus.data

import dev.repertosaurus.core.Ids
import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.core.Timestamps
import dev.repertosaurus.db.RepertosaurusDatabase
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * A thin repository over the generated SQLDelight queries.
 *
 * Thin is the point: it owns id generation (decisions 2-6), the timestamp format
 * (decision 10) and the local-today default (decision 45), and nothing else. There is no
 * UI state here, no caching and no observation — that belongs a layer up.
 */
public class RepertosaurusRepository(
    private val database: RepertosaurusDatabase,
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
     * The song-child write envelope (style review F14 B1): the one copy of R23b/R23c/R23d around
     * a write to `song_tag`, `song_instrument` or `song_performer`. The catalog's tag and
     * `song_instrument` writes and the capability writes below all go through it. Declared
     * before [catalog], which takes it.
     */
    internal val children: SongChildren = SongChildren(database, deviceId, clock, lookups)

    /**
     * The song aggregate (repertoire-editing R30, the first E32 cut): songs, artists, song
     * tags, groove assignment and `song_instrument` rows. The add-song path, the artist
     * create-on-enter, `artists()` and `UNKNOWN_ARTIST_ID` moved there from here; the rest of
     * E32 remains its own piece of work.
     */
    public val catalog: SongCatalog = SongCatalog(database, deviceId, clock, lookups, children)

    /** A part's priority and confidence (schema-3 M3-M8, M19). Declared before [merge], which takes it. */
    public val ratings: PartRatingStore = PartRatingStore(database, deviceId, clock)

    /** Counted suggestion skips (schema-3 M12-M14, M19). */
    public val skips: SuggestionSkips = SuggestionSkips(database, deviceId, clock, newId)

    /** Merging two songs (repertoire-editing R31-R39): composes [catalog], [children] and [ratings]. */
    public val merge: SongMerge = SongMerge(database, deviceId, clock, newId, catalog, children, ratings)

    /** The scorecards' read (scorecards SC13, SC15). */
    public val habit: PracticeDays = PracticeDays(database, clock, timeZone)

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

    /** repertoire-editing R9: one live song, and whether one `(performer, instrument)` holds it. */
    public data class HeldSong(
        val songId: String,
        val title: String,
        val artistName: String,
        val held: Boolean,
    )

    /**
     * R9: every live song, each with whether this `(performer, instrument)` holds it — one
     * query, `song_performer.sq :: selectSongsWithHeldFlag`. **Unordered**: R7's order is
     * applied once, by `RepertoireCoordinator.ORDER`.
     */
    public fun songsWithHeldFlag(performerId: String, instrumentId: String): List<HeldSong> =
        database.song_performerQueries.selectSongsWithHeldFlag(
            performerId = performerId,
            instrumentId = instrumentId,
        ).executeAsList().map { row ->
            HeldSong(
                songId = row.song_id,
                title = row.title,
                artistName = row.artist_name,
                held = row.held == 1L,
            )
        }

    /**
     * Add a capability, **or revive the tombstone that already holds this triple** (E6).
     *
     * A new row's id is `Ids.songPerformer(songId, performerId, instrumentId)` (decision 4f, V3,
     * V5) and is never assembled by hand — this project has forked its id derivation twice, and
     * `Ids.junction` now refuses the superseded two-key form for this table (V31).
     *
     * **R4a: an existing row is found by its triple, through the unique key**, never by
     * assuming its id is the derived one — a database upgraded 1 → 2 keeps two-key ids (S3), and
     * there the derived id names no row. A new row is inserted under the derived id. There are
     * three outcomes and only one of them is an insert:
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
     * **E38: the read and the branch are one transaction** ([insertOrRevive]), and the insert
     * is [Insert.OrIgnore] — the one derived-id insert on a double-tap path; that type states
     * why, and why never `OR REPLACE`. An insert the clause ignored throws [InsertIgnored] and is
     * never reported as `CREATED` (R4a).
     *
     * R23c: on a removed song nothing is written and the result is [JunctionWrite.SongGone].
     * R23b: a removed performer or instrument is revived, in the same transaction — asking for
     * the row again says the tombstone was wrong, and a live capability on a hidden parent is
     * a row no View can match (E40). Both envelopes are [SongChildren]'s, shared with the tag and
     * `song_instrument` writes.
     */
    public fun addSongPerformer(
        songId: String,
        performerId: String,
        instrumentId: String,
    ): JunctionWrite = children.add(SongChildKey.PERFORMER, songId, listOf(performerId, instrumentId))

    /**
     * The same from a typed performer and instrument name, each created on enter or resolved to
     * the row its name derives (E5, E13) — [SongChildren.addNamed], the one by-name add (F22 B1).
     * R23c: on a removed song, [JunctionWrite.SongGone] and not even the two lookups are written.
     */
    public fun addSongPerformerNamed(
        songId: String,
        performerName: String,
        instrumentName: String,
    ): JunctionWrite = children.addNamed(SongChildKey.PERFORMER, songId, listOf(performerName, instrumentName))

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
     *
     * @return false when nothing was written: the row is not live (E37), or its song is removed
     *   (R23c).
     */
    public fun updateSongPerformer(
        id: String,
        isLead: Long,
        vocalRange: Long?,
        notes: String?,
    ): Boolean {
        require(isLead == 0L || isLead == 1L) { "is_lead is 0 or 1, got $isLead" }
        require(vocalRange == null || vocalRange == 0L || vocalRange == 1L) {
            "vocal_range is 0, 1 or null, got $vocalRange"
        }
        return children.update(SongChildKey.PERFORMER, id) { now ->
            database.song_performerQueries.update(
                is_lead = isLead,
                vocal_range = vocalRange,
                notes = notes,
                updated_at = now,
                device_id = deviceId,
                id = id,
            )
        }
    }

    /**
     * E7: removing a capability is a **soft delete**, like every other mutable row in this
     * schema. A stale device reinserts a hard-deleted row on the next merge — and E6's revive
     * path depends on the tombstone still being there.
     *
     * R23c: on a removed song, [JunctionWrite.SongGone]. R23d: [JunctionWrite.Removed.wrote] is
     * false when the row was not live — absent, or already removed and so not re-stamped.
     */
    public fun removeSongPerformer(id: String): JunctionWrite = children.remove(SongChildKey.PERFORMER, id)

    /**
     * **R4a: remove the capability row that holds `(song, performer, instrument)`**, found by
     * the triple through the unique key — never by assuming its id is
     * `Ids.songPerformer(...)`, which on a database upgraded 1 → 2 names no row (S3). Otherwise
     * exactly [removeSongPerformer]'s rules and results.
     */
    public fun removeSongPerformerByKey(
        songId: String,
        performerId: String,
        instrumentId: String,
    ): JunctionWrite = children.removeByKey(SongChildKey.PERFORMER, songId, listOf(performerId, instrumentId))

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
     *
     * @return false when there was no live View to delete (R23d): an already-removed View is
     *   not re-stamped, and nothing was written.
     */
    public fun deleteSavedView(id: String): Boolean =
        database.softDelete(clock) { now ->
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
     * (decision 45a). [feel] is the optional long-press rating (decision 46, schema-3 M9); its type
 * is the range, and it becomes a stored `Long` only here.
     * [durationSeconds] is null for an untimed tap (schema-3 M10); it is last and defaulted so the
     * tap path's call is unchanged (M11).
     *
     * @return the id of the event just written, so the caller can offer undo.
     */
    public fun logPractice(
        songId: String,
        instrumentId: String,
        contextId: String? = null,
        feel: RatingLevel? = null,
        note: String? = null,
        loggedOn: String = today(),
        durationSeconds: Long? = null,
    ): String {
        require(durationSeconds == null || durationSeconds in 1L..MAX_DURATION_SECONDS) {
            "duration_seconds is 1-$MAX_DURATION_SECONDS or null, got $durationSeconds"
        }
        val id = newId()
        database.practice_eventQueries.insert(
            id = id,
            song_id = songId,
            logged_on = loggedOn,
            instrument_id = instrumentId,
            context_id = contextId,
            feel = feel?.value,
            note = note,
            duration_seconds = durationSeconds,
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
                durationSeconds = row.duration_seconds,
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
        /** Null for a tap-logged, untimed event (schema-3 M10). */
        val durationSeconds: Long?,
    )

    /**
     * repertoire-editing R20: a song's practice history on one instrument — times practised and
     * last practised, voided events excluded (decision 8). Derived, never stored (decision 48).
     */
    public data class PracticeSummary(
        val instrumentId: String,
        val instrumentName: String?,
        val timesPractised: Long,
        val lastPractised: String,
    )

    /**
     * R20: times practised and last practised, per instrument the song has live history on — a
     * pure summary over [practiceHistory], so the two can never disagree. Ordered by instrument
     * name as a deterministic base; decision 18's chip order is `SessionInstruments.displayOrder`,
     * which the screen applies.
     */
    public fun practiceSummary(songId: String): List<PracticeSummary> =
        practiceHistory(songId)
            .groupBy { it.instrumentId }
            .map { (instrumentId, events) ->
                PracticeSummary(
                    instrumentId = instrumentId,
                    instrumentName = events.first().instrumentName,
                    timesPractised = events.size.toLong(),
                    lastPractised = events.maxOf { it.loggedOn },
                )
            }
            .sortedWith(compareBy({ it.instrumentName.orEmpty() }, { it.instrumentId }))

    /** Live count for a song, derived and never stored (decision 48). */
    public fun timesPractised(songId: String): Long =
        database.practice_eventQueries.countLiveBySong(songId).executeAsOne()

    internal companion object {
        /** Schema-3 M10's sanity ceiling on a timed session: a day. */
        const val MAX_DURATION_SECONDS: Long = 86_400L
    }
}
