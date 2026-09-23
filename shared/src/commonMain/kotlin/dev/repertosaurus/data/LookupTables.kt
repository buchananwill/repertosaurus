package dev.repertosaurus.data

import dev.repertosaurus.core.Ids
import dev.repertosaurus.core.Timestamps
import dev.repertosaurus.db.RepertosaurusDatabase
import kotlinx.datetime.Clock

/**
 * **E33: the lookup key is an enum, never a raw `String`.**
 *
 * Keeping `data` free of a dependency on the session layer's `LookupKind` is correct and is
 * not in question — the layering is `session → data → core`. But that argues for an enum
 * *here*, not for a stringly-typed key, and the string had already escaped: the capability
 * coordinator spelled `"performer"` and `"instrument"` by hand, so a typo compiled and threw
 * on the editor's first create-on-enter.
 *
 * [table] is the same string `Ids.derived` keys on (decisions 4, 4a, 4b), so the table a row
 * belongs to and the namespace its id is derived from cannot disagree. It is a property of
 * the constant rather than a value anyone types.
 *
 * Fixed at seven branches rather than at twenty: phase 2's sync will copy whatever precedent
 * this file sets, into the one place where a wrong table name silently forks an id.
 */
internal enum class LookupTableKey(val table: String) {
    INSTRUMENT("instrument"),
    PERFORMER("performer"),
    TAG("tag"),
    GROOVE("groove"),
    VENUE("venue"),
    BAND("band"),
    PRACTICE_CONTEXT("practice_context"),
}

/**
 * One row of a managed lookup table, as stored — decisions 15 and 16, editing E13 and E18.
 *
 * [notes] is null for every kind that has no notes column, which is most of them. The kind
 * declares whether it has one ([LookupTable.setNotes]); nothing reads a null here as "the
 * user cleared it".
 */
public data class LookupRow(
    val id: String,
    val name: String,
    val notes: String?,
    /**
     * The tombstone (decision 9). Always null in a list of live rows; the one caller that
     * needs it is the create-on-enter path, which has to know whether the derived id it just
     * computed names a removed row it should revive rather than a row it should insert.
     */
    val deletedAt: String? = null,
)

/**
 * The plumbing for one lookup table, as a set of functions over the generated queries.
 *
 * SQLDelight generates a distinct query class per `.sq` file with no common supertype, so
 * `instrument`, `performer`, `tag`, `groove`, `venue`, `band` and `practice_context` cannot
 * be handled generically without an adapter — and seven copies of add/rename/remove/count on
 * [RepertosaurusRepository] is exactly the kind of duplication decision 15's "one abstraction"
 * exists to avoid. This is that adapter, and it is the only place a table name is turned into
 * a query.
 *
 * [setNotes] is **null for a table with no notes column**, which is how the data layer states
 * the fact `LookupKind.hasNotes` states in the session layer (E16). Two statements of one
 * vocabulary, so a test pins them together — the same discipline views V17a applies to
 * `SessionOrder` and the `saved_view` CHECK.
 */
internal class LookupTable(
    val rows: () -> List<LookupRow>,
    val byId: (id: String) -> LookupRow?,
    val insert: (id: String, name: String, now: String) -> Unit,
    /**
     * Untombstone. Takes the stored row rather than a name, so reviving a lookup keeps the
     * spelling and the notes it already had — asking for a removed value again says the
     * tombstone was wrong, not that everything on the row should be discarded.
     */
    val revive: (row: LookupRow, now: String) -> Unit,
    val rename: (id: String, name: String, now: String) -> Unit,
    val setNotes: ((id: String, notes: String?, now: String) -> Unit)?,
    val softDelete: (id: String, now: String) -> Unit,
    /**
     * E17: **live rows that a soft delete of this row would hide**, and each table defines
     * what counts. Not "practice events" — that is only what `instrument` counts.
     */
    val usage: (id: String) -> Long,
)

/**
 * Where a [LookupTableKey] becomes a [LookupTable]. Adding a managed lookup adds a constant
 * to the key, a branch here and an entry to `LookupKind`; nothing else changes.
 *
 * E33: the `when` is over an enum and therefore **exhaustive with no `else`**, so a kind
 * added without a branch is a compile error rather than a runtime throw on a drawer tap.
 */
internal fun lookupTable(
    database: RepertosaurusDatabase,
    deviceId: String,
    key: LookupTableKey,
): LookupTable = when (key) {

    LookupTableKey.INSTRUMENT -> LookupTable(
        rows = {
            database.instrumentQueries.selectAllLive().executeAsList()
                .map { LookupRow(it.id, it.name, notes = null) }
        },
        byId = {
            database.instrumentQueries.selectById(it).executeAsOneOrNull()
                ?.let { row -> LookupRow(row.id, row.name, notes = null, deletedAt = row.deleted_at) }
        },
        insert = { id, name, now ->
            database.instrumentQueries.insert(id, name, now, null, deviceId)
        },
        revive = { row, now ->
            database.instrumentQueries.applyMerged(row.id, row.name, now, null, deviceId)
        },
        rename = { id, name, now ->
            database.instrumentQueries.update(name, now, deviceId, id)
        },
        setNotes = null,
        softDelete = { id, now ->
            database.instrumentQueries.softDelete(now, now, deviceId, id)
        },
        // Practice events: what the chip row would stop showing history for.
        usage = { database.practice_eventQueries.countLiveByInstrument(it).executeAsOne() },
    )

    LookupTableKey.PERFORMER -> LookupTable(
        rows = {
            database.performerQueries.selectAllLive().executeAsList()
                .map { LookupRow(it.id, it.name, it.notes) }
        },
        byId = {
            database.performerQueries.selectById(it).executeAsOneOrNull()
                ?.let { row -> LookupRow(row.id, row.name, row.notes, deletedAt = row.deleted_at) }
        },
        insert = { id, name, now ->
            database.performerQueries.insert(id, name, null, now, null, deviceId)
        },
        revive = { row, now ->
            database.performerQueries.applyMerged(row.id, row.name, row.notes, now, null, deviceId)
        },
        rename = { id, name, now ->
            database.performerQueries.rename(name, now, deviceId, id)
        },
        setNotes = { id, notes, now ->
            database.performerQueries.setNotes(notes, now, deviceId, id)
        },
        softDelete = { id, now ->
            database.performerQueries.softDelete(now, now, deviceId, id)
        },
        // Capability rows (E17): who-does-what-on-what that removing this person would hide.
        usage = { database.song_performerQueries.countLiveByPerformer(it).executeAsOne() },
    )

    LookupTableKey.TAG -> LookupTable(
        rows = {
            database.tagQueries.selectAllLive().executeAsList()
                .map { LookupRow(it.id, it.name, notes = null) }
        },
        byId = {
            database.tagQueries.selectById(it).executeAsOneOrNull()
                ?.let { row -> LookupRow(row.id, row.name, notes = null, deletedAt = row.deleted_at) }
        },
        insert = { id, name, now -> database.tagQueries.insert(id, name, now, null, deviceId) },
        revive = { row, now ->
            database.tagQueries.applyMerged(row.id, row.name, now, null, deviceId)
        },
        rename = { id, name, now -> database.tagQueries.update(name, now, deviceId, id) },
        setNotes = null,
        softDelete = { id, now -> database.tagQueries.softDelete(now, now, deviceId, id) },
        // Tagged songs (E17).
        usage = { database.tagQueries.countLiveSongs(it).executeAsOne() },
    )

    LookupTableKey.GROOVE -> LookupTable(
        rows = {
            database.grooveQueries.selectAllLive().executeAsList()
                .map { LookupRow(it.id, it.name, notes = null) }
        },
        byId = {
            database.grooveQueries.selectById(it).executeAsOneOrNull()
                ?.let { row -> LookupRow(row.id, row.name, notes = null, deletedAt = row.deleted_at) }
        },
        insert = { id, name, now -> database.grooveQueries.insert(id, name, now, null, deviceId) },
        revive = { row, now ->
            database.grooveQueries.applyMerged(row.id, row.name, now, null, deviceId)
        },
        rename = { id, name, now -> database.grooveQueries.update(name, now, deviceId, id) },
        setNotes = null,
        softDelete = { id, now -> database.grooveQueries.softDelete(now, now, deviceId, id) },
        // Songs set to this groove (E17). song.groove_id is a column, not a junction.
        usage = { database.grooveQueries.countLiveSongs(it).executeAsOne() },
    )

    LookupTableKey.VENUE -> LookupTable(
        rows = {
            database.venueQueries.selectAllLive().executeAsList()
                .map { LookupRow(it.id, it.name, notes = null) }
        },
        byId = {
            database.venueQueries.selectById(it).executeAsOneOrNull()
                ?.let { row -> LookupRow(row.id, row.name, notes = null, deletedAt = row.deleted_at) }
        },
        insert = { id, name, now -> database.venueQueries.insert(id, name, now, null, deviceId) },
        revive = { row, now ->
            database.venueQueries.applyMerged(row.id, row.name, now, null, deviceId)
        },
        rename = { id, name, now -> database.venueQueries.update(name, now, deviceId, id) },
        // E16 states that venue carries a notes column. It does not — see venue.sq, which has
        // id, name and the standard three and nothing else, and data-model decision 50 never
        // gave it one (49a gives band its notes; 24 gives performer theirs). Adding the column
        // is a schema change under schema-compatibility S1 and is deliberately NOT made here.
        setNotes = null,
        softDelete = { id, now -> database.venueQueries.softDelete(now, now, deviceId, id) },
        // Set lists played there (E17).
        usage = { database.venueQueries.countLiveSetlists(it).executeAsOne() },
    )

    LookupTableKey.BAND -> LookupTable(
        rows = {
            database.bandQueries.selectAllLive().executeAsList()
                .map { LookupRow(it.id, it.name, it.notes) }
        },
        byId = {
            database.bandQueries.selectById(it).executeAsOneOrNull()
                ?.let { row -> LookupRow(row.id, row.name, row.notes, deletedAt = row.deleted_at) }
        },
        insert = { id, name, now ->
            database.bandQueries.insert(id, name, null, now, null, deviceId)
        },
        revive = { row, now ->
            database.bandQueries.applyMerged(row.id, row.name, row.notes, now, null, deviceId)
        },
        rename = { id, name, now -> database.bandQueries.rename(name, now, deviceId, id) },
        setNotes = { id, notes, now -> database.bandQueries.setNotes(notes, now, deviceId, id) },
        softDelete = { id, now -> database.bandQueries.softDelete(now, now, deviceId, id) },
        // Set lists performed as this act (E17).
        usage = { database.bandQueries.countLiveSetlists(it).executeAsOne() },
    )

    LookupTableKey.PRACTICE_CONTEXT -> LookupTable(
        rows = {
            database.practice_contextQueries.selectAllLive().executeAsList()
                .map { LookupRow(it.id, it.name, notes = null) }
        },
        byId = {
            database.practice_contextQueries.selectById(it).executeAsOneOrNull()
                ?.let { row -> LookupRow(row.id, row.name, notes = null, deletedAt = row.deleted_at) }
        },
        insert = { id, name, now ->
            database.practice_contextQueries.insert(id, name, now, null, deviceId)
        },
        revive = { row, now ->
            database.practice_contextQueries.applyMerged(row.id, row.name, now, null, deviceId)
        },
        rename = { id, name, now ->
            database.practice_contextQueries.update(name, now, deviceId, id)
        },
        setNotes = null,
        softDelete = { id, now ->
            database.practice_contextQueries.softDelete(now, now, deviceId, id)
        },
        // Practice events logged under this context (E17), voided ones excluded.
        usage = { database.practice_contextQueries.countLiveEvents(it).executeAsOne() },
    )
}

/**
 * The read and write surface of the seven managed lookup tables — decisions 15 and 16,
 * editing E13, E17, E18.
 *
 * **E31: this is the first cut of the repository split.** These methods and their private
 * helper lived on `RepertosaurusRepository`, which E30 took past its own deferral trigger at
 * 698 lines and 41 public members across five unrelated aggregates. This file is already that
 * aggregate's plumbing and already in the right module, so the surface comes to the plumbing
 * rather than the plumbing being exported. **No nested row type moves** — E32 makes the rest
 * of the split its own piece of work, because promoting `RepertosaurusRepository.Performer` and
 * its six siblings touches ~32 sites across 10 files, 12 of them in `androidApp`.
 *
 * `instrument`, `performer`, `tag`, `groove`, `venue`, `band` and `practice_context` are the
 * same shape — a derived id over the normalised name, a display name, a soft delete — so they
 * get one set of methods keyed by [LookupTableKey] rather than seven sets of four.
 */
internal class LookupTables(
    private val database: RepertosaurusDatabase,
    private val deviceId: String,
    private val clock: Clock,
) {

    private fun table(key: LookupTableKey): LookupTable = lookupTable(database, deviceId, key)

    /** Live rows of one lookup table, ordered by name — the store applies any other order. */
    fun rows(key: LookupTableKey): List<LookupRow> = table(key).rows()

    /**
     * E17: **live rows that removing this one would hide**, as that table defines it —
     * practice events for `instrument`, capability rows for `performer`, tagged songs for
     * `tag`, and so on. E22: the screen states this before the removal happens.
     *
     * E39: every one of those counts excludes a row hidden by a tombstone on any parent, so
     * the number never disagrees with the subtitle beside it.
     */
    fun usage(key: LookupTableKey, id: String): Long = table(key).usage(id)

    /**
     * E16: whether this table has a notes column at all, as the data layer sees it.
     * `LookupKind.hasNotes` is the same fact in the session layer and a test pins the two
     * together, so a kind cannot claim a field its table does not have.
     */
    fun hasNotes(key: LookupTableKey): Boolean = table(key).setNotes != null

    /**
     * Create on enter (decisions 15, 16; E5, E13). The id is
     * `UUIDv5(namespace(table), normalise(name))` (decisions 2, 4), so two devices adding
     * `mandolin` independently converge on one row instead of forking it.
     *
     * A name that normalises to an existing row returns that row's id and leaves its stored
     * spelling — and its notes — alone. If that row had been removed, this brings it back:
     * asking for it again is the clearest possible statement that the tombstone was wrong,
     * and a separate row is not on offer anyway, because the derived id is the same one.
     *
     * **E38: the read and the write are one transaction.** This reads by derived id and then
     * branches to insert, revive or no-op across separate statements; without atomicity two
     * rapid taps both observe "absent" and the second violates the primary key.
     *
     * The branch is [insertOrRevive]'s, with a plain `INSERT` ([Insert.Plain] says why). A
     * live row is left exactly as it is: the stored spelling is the canonical one (decision 5)
     * and the notes are the user's.
     */
    fun add(key: LookupTableKey, name: String): String = resolve(key, name).first

    /**
     * [add], also saying how the name resolved — for a caller whose own report has to count a
     * lookup it created or revived as a write (R23d; `SongCatalog.addTagNamed`).
     */
    fun resolve(key: LookupTableKey, name: String): Pair<String, Resolution> {
        val queries = table(key)
        val display = name.trim()
        require(display.isNotEmpty()) { "a ${key.table} needs a name" }
        val id = idFor(key, display)
        val resolution = database.insertOrRevive(
            read = { queries.byId(id) },
            deletedAt = { it.deletedAt },
            insert = Insert.Plain { queries.insert(id, display, Timestamps.now(clock)) },
            revive = { queries.revive(it, Timestamps.now(clock)) },
        )
        return id to resolution
    }

    /**
     * The id a name derives in this table (decisions 2, 4) — the one place a lookup's derived id
     * is computed, so a caller that needs it without writing (to report a refused write) cannot
     * spell it differently from [add].
     */
    fun idFor(key: LookupTableKey, name: String): String = Ids.derived(key.table, name.trim())

    /**
     * R23b: **a write that makes a live row reference a tombstoned parent revives the parent**,
     * in the caller's transaction. Reviving keeps the parent's spelling and notes, as [add]'s
     * revive does. A live parent, or an id with no row, is left alone.
     *
     * @return true when a tombstone was revived.
     */
    fun reviveIfRemoved(key: LookupTableKey, id: String): Boolean {
        val queries = table(key)
        return reviveIfRemoved(queries.byId(id), { it.deletedAt }) { queries.revive(it, Timestamps.now(clock)) }
    }

    /**
     * Rename (decision 5, E21). The id is **not** re-derived: it is opaque and immutable once
     * written, and every referencing row — every `practice_event`, every `song_performer` —
     * points at this one. A re-derived id would orphan the lot, silently.
     */
    fun rename(key: LookupTableKey, id: String, name: String) {
        val display = name.trim()
        require(display.isNotEmpty()) { "a ${key.table} needs a name" }
        table(key).rename(id, display, Timestamps.now(clock))
    }

    /**
     * E16: the notes field, where the table has one. Calling this for a table that does not
     * is a programming error — `LookupKind.hasNotes` is what the screen consults — so it
     * fails loudly rather than writing nothing and looking like it worked.
     */
    fun setNotes(key: LookupTableKey, id: String, notes: String?) {
        val write = table(key).setNotes
            ?: error("${key.table} has no notes column (E16); LookupKind.hasNotes is the guard")
        write(id, notes?.trim()?.takeIf { it.isNotEmpty() }, Timestamps.now(clock))
    }

    /**
     * Remove: a tombstone, never a `DELETE` (decision 9, E7, E22). A hard delete gets
     * reinserted by any stale device on the next merge, and the history pointing at the row
     * must survive either way — it does, and it keeps counting.
     *
     * R23d: a row that is already removed is **not re-stamped** (`deleted_at IS NULL` is in every
     * lookup's `softDelete`), because under last-write-wins a spurious later stamp would outrank
     * a legitimate revive made on another device.
     *
     * @return false when there was no live row to remove: nothing was written.
     */
    fun remove(key: LookupTableKey, id: String): Boolean {
        val queries = table(key)
        return database.softDelete(clock) { now -> queries.softDelete(id, now) }
    }
}
