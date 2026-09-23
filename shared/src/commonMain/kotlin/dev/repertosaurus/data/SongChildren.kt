package dev.repertosaurus.data

import dev.repertosaurus.core.Ids
import dev.repertosaurus.core.Timestamps
import dev.repertosaurus.db.RepertosaurusDatabase
import kotlinx.datetime.Clock

/**
 * The three junction tables that hang off a song — `song_tag` (R17), `song_instrument` (R19)
 * and `song_performer` (E1-E11, R4).
 *
 * The same model as [LookupTableKey] and [LookupTable] (style review F14 B1): an enum names the
 * table, a descriptor of functions over the generated queries is the only place that enum becomes
 * a query, and [SongChildren] writes the rules once over the descriptor. Before this, the R23b /
 * R23c / R23d envelope around a child write was written out three times for remove, twice for
 * update and three times for add — and package 3's merge composes exactly these writes, so it
 * would have copied whichever version sat nearest.
 *
 * Public (package 3b) only so a merge preview can name which table a child row belongs to
 * (`SongMerge.ChildKey`); its parents, and every table behind it, stay internal.
 */
public enum class SongChildKey(
    /**
     * The lookups a row of this table points at, in the order [SongChildRow.parentIds] and the
     * id derivation take them. R23b revives each of these when an add would reference a
     * tombstone.
     */
    internal val parents: List<LookupTableKey>,
) {
    TAG(listOf(LookupTableKey.TAG)),
    INSTRUMENT(listOf(LookupTableKey.INSTRUMENT)),
    PERFORMER(listOf(LookupTableKey.PERFORMER, LookupTableKey.INSTRUMENT)),
}

/**
 * A stored junction row as the envelope and a merge need it: its **stored** id (never assumed to
 * be the derived one — R4a), its song, its parent lookup ids in [SongChildKey.parents] order, and
 * its tombstone.
 */
internal data class SongChildRow(
    val id: String,
    val songId: String,
    val parentIds: List<String>,
    val deletedAt: String?,
)

/** A new junction row, as the envelope hands it to the table's insert. */
internal data class NewSongChild(
    val id: String,
    val songId: String,
    /** The parent lookup ids, in [SongChildKey.parents] order. */
    val parentIds: List<String>,
    val now: String,
)

/**
 * The plumbing for one junction table — the [LookupTable] shape for a song's children. Every
 * member is a statement or a derivation; none is a rule. The rules are [SongChildren]'s.
 */
internal class SongChildTable(
    /**
     * The derived id (decisions 4e, 4f), from the song and the parents in [SongChildKey.parents]
     * order — **the id a new row is inserted under, and nothing else.** An existing row is found
     * by [find], never by this (R4a).
     */
    val id: (songId: String, parentIds: List<String>) -> String,
    /** One row by the id a display read handed out, tombstone included. */
    val row: (id: String) -> SongChildRow?,
    /**
     * **R4a: the row that holds this key, tombstone included, found through the table's unique
     * index.** On a database upgraded 1 → 2 a `song_performer` row keeps its two-key id
     * (schema-compatibility S3), so the derived id names no row there while the key still names
     * exactly one.
     */
    val find: (songId: String, parentIds: List<String>) -> SongChildRow?,
    /**
     * F22 N8: **every** row the song has — tombstoned rows, and rows whose parent lookup is
     * tombstoned, included. The display reads hide both, and a merge must not silently drop
     * them (R38a).
     */
    val bySong: (songId: String) -> List<SongChildRow>,
    /**
     * The INSERT for a new row, **with no facts** (E6): difficulty, patch, lead, range and notes
     * are set by an update afterwards, so a revive can never be what resets them. Returns an
     * [Insert], so the table states which INSERT it uses at the one place it is spelled.
     */
    val insert: (NewSongChild) -> Insert,
    /** Untombstone, touching nothing else (E6). */
    val restore: (id: String, now: String) -> Unit,
    /** The R23d-guarded `softDelete`: one statement, `deleted_at IS NULL` in its `WHERE`. */
    val softDelete: (id: String, now: String) -> Unit,
    /**
     * F22 N8: copy the row [fromId]'s facts onto the row [toId] — through the table's E37-guarded
     * `update`, so it writes nothing onto a tombstone and runs exactly one DML statement ([wrote]'s
     * contract). **Null for `song_tag`, which carries no facts.** For merge (R35, R38a): called by
     * `SongMerge` and nothing else.
     */
    val copyFacts: ((fromId: String, toId: String, now: String) -> Unit)?,
)

/**
 * Where a [SongChildKey] becomes a [SongChildTable]. Exhaustive over the enum with no `else`, as
 * [lookupTable] is (E33): a junction added without a branch is a compile error.
 */
internal fun songChildTable(
    database: RepertosaurusDatabase,
    deviceId: String,
    key: SongChildKey,
): SongChildTable = when (key) {

    SongChildKey.TAG -> {
        val queries = database.song_tagQueries
        val stored = { it: dev.repertosaurus.db.Song_tag ->
            SongChildRow(it.id, it.song_id, listOf(it.tag_id), it.deleted_at)
        }
        SongChildTable(
            id = { songId, parents -> Ids.songTag(songId, parents[0]) },
            row = { id -> queries.selectById(id).executeAsOneOrNull()?.let(stored) },
            find = { songId, parents -> queries.selectByKey(songId, parents[0]).executeAsOneOrNull()?.let(stored) },
            bySong = { songId -> queries.selectAllBySong(songId).executeAsList().map(stored) },
            insert = { new ->
                Insert.Plain {
                    queries.insert(
                        id = new.id,
                        song_id = new.songId,
                        tag_id = new.parentIds[0],
                        updated_at = new.now,
                        deleted_at = null,
                        device_id = deviceId,
                    )
                }
            },
            restore = { id, now -> queries.restore(now, deviceId, id) },
            softDelete = { id, now -> queries.softDelete(now, now, deviceId, id) },
            copyFacts = null,
        )
    }

    SongChildKey.INSTRUMENT -> {
        val queries = database.song_instrumentQueries
        val stored = { it: dev.repertosaurus.db.Song_instrument ->
            SongChildRow(it.id, it.song_id, listOf(it.instrument_id), it.deleted_at)
        }
        SongChildTable(
            id = { songId, parents -> Ids.songInstrument(songId, parents[0]) },
            row = { id -> queries.selectById(id).executeAsOneOrNull()?.let(stored) },
            find = { songId, parents -> queries.selectByKey(songId, parents[0]).executeAsOneOrNull()?.let(stored) },
            bySong = { songId -> queries.selectAllBySong(songId).executeAsList().map(stored) },
            insert = { new ->
                Insert.Plain {
                    queries.insert(
                        id = new.id,
                        song_id = new.songId,
                        instrument_id = new.parentIds[0],
                        difficulty = null,
                        patch = null,
                        notes = null,
                        updated_at = new.now,
                        deleted_at = null,
                        device_id = deviceId,
                    )
                }
            },
            restore = { id, now -> queries.restore(now, deviceId, id) },
            softDelete = { id, now -> queries.softDelete(now, now, deviceId, id) },
            copyFacts = { fromId, toId, now ->
                val from = queries.selectById(fromId).executeAsOne()
                queries.update(
                    difficulty = from.difficulty,
                    patch = from.patch,
                    notes = from.notes,
                    updated_at = now,
                    device_id = deviceId,
                    id = toId,
                )
            },
        )
    }

    // The derived id is `Ids.songPerformer` over all three keys (decision 4f, V3, V5) and never
    // assembled by hand — `Ids.junction` refuses the superseded two-key form for this table
    // (V31). It is used only to insert a new row: an existing one is found by its triple (R4a),
    // because an upgraded database still holds two-key ids. The one derived-id insert on a
    // double-tap path, hence [Insert.OrIgnore] — and an ignore throws [InsertIgnored] (R4a).
    SongChildKey.PERFORMER -> {
        val queries = database.song_performerQueries
        val stored = { it: dev.repertosaurus.db.Song_performer ->
            SongChildRow(it.id, it.song_id, listOf(it.performer_id, it.instrument_id), it.deleted_at)
        }
        SongChildTable(
            id = { songId, parents -> Ids.songPerformer(songId, parents[0], parents[1]) },
            row = { id -> queries.selectById(id).executeAsOneOrNull()?.let(stored) },
            find = { songId, parents ->
                queries.selectByKey(songId, parents[0], parents[1]).executeAsOneOrNull()?.let(stored)
            },
            bySong = { songId -> queries.selectAllBySong(songId).executeAsList().map(stored) },
            insert = { new ->
                Insert.OrIgnore {
                    queries.insertIfAbsent(
                        id = new.id,
                        song_id = new.songId,
                        performer_id = new.parentIds[0],
                        instrument_id = new.parentIds[1],
                        is_lead = 0L,
                        vocal_range = null,
                        notes = null,
                        updated_at = new.now,
                        deleted_at = null,
                        device_id = deviceId,
                    )
                }
            },
            restore = { id, now -> queries.restore(now, deviceId, id) },
            softDelete = { id, now -> queries.softDelete(now, now, deviceId, id) },
            copyFacts = { fromId, toId, now ->
                val from = queries.selectById(fromId).executeAsOne()
                queries.update(
                    is_lead = from.is_lead,
                    vocal_range = from.vocal_range,
                    notes = from.notes,
                    updated_at = now,
                    device_id = deviceId,
                    id = toId,
                )
            },
        )
    }
}

/**
 * **The song-child write envelope, once** (style review F14 B1). Every write to a song's
 * junction rows — add, update, remove, on all three tables — goes through here, and each rule is
 * applied in exactly one place:
 *
 * - **R4a:** an existing row is found by its key through the unique index, never by assuming its
 *   id is the derived one; a new row is inserted under the derived id;
 * - **R23c:** a write to a child of a removed song writes nothing and says so;
 * - **R23b:** an add that would reference a tombstoned parent revives the parent first;
 * - **E6 / E38:** an add is [insertOrRevive] inside one transaction;
 * - **E37 / R23d:** an update never clears a tombstone and a remove never re-stamps one, and
 *   both report whether they wrote.
 *
 * Package 3's merge composes these writes (R38a) and must go through here rather than
 * re-assembling the envelope.
 */
internal class SongChildren(
    private val database: RepertosaurusDatabase,
    private val deviceId: String,
    private val clock: Clock,
    private val lookups: LookupTables,
) {

    private fun table(key: SongChildKey): SongChildTable = songChildTable(database, deviceId, key)

    /**
     * The id a write on this key targets, **without writing** — for reporting a refusal. The
     * stored row's id when a row holds the key (R4a), the derived id otherwise.
     */
    fun idFor(key: SongChildKey, songId: String, parentIds: List<String>): String {
        val table = table(key)
        requireParents(key, parentIds)
        return table.find(songId, parentIds)?.id ?: table.id(songId, parentIds)
    }

    /**
     * Add: insert with no facts under the derived id, or **revive** the tombstone that holds the
     * key keeping its facts (E6), or nothing when it is already live. R4a: the existing row is
     * found by its key, so a two-key row on an upgraded database is revived rather than missed,
     * and an ignored insert throws [InsertIgnored]. R23b: every tombstoned parent is revived first,
     * and that counts as having written (R23d). R23c: on a removed song, [JunctionWrite.SongGone]
     * and nothing — not even a parent — is written.
     */
    fun add(key: SongChildKey, songId: String, parentIds: List<String>): JunctionWrite {
        val table = table(key)
        requireParents(key, parentIds)
        return database.transactionWithResult {
            val id = table.find(songId, parentIds)?.id ?: table.id(songId, parentIds)
            if (!database.songIsLive(songId)) return@transactionWithResult JunctionWrite.SongGone(id)
            // Every parent is revived, not only up to the first that needed it: `map` is eager.
            val revived = key.parents.zip(parentIds).map { (parent, parentId) ->
                lookups.reviveIfRemoved(parent, parentId)
            }
            val now = Timestamps.now(clock)
            val resolution = database.insertOrRevive(
                read = { table.find(songId, parentIds) },
                deletedAt = { it.deletedAt },
                insert = table.insert(NewSongChild(id, songId, parentIds, now)),
                revive = { table.restore(it.id, now) },
            )
            JunctionWrite.Added(id, resolution, revivedParent = revived.any { it })
        }
    }

    /**
     * **F22 B1: the one by-name add.** Each parent is named rather than picked — a typed tag, a
     * typed performer and instrument — and is created on enter or resolved to the row its name
     * derives (E5, E13), then [add] runs over the ids. The lookups and the junction are one
     * transaction (E38).
     *
     * R23c: on a removed song, [JunctionWrite.SongGone] and **nothing** is written — not even the
     * lookups. R23d / F15 N4: a lookup this call revived is a write, whatever the junction did, so
     * the result carries `revivedParent`. (A lookup it created has no junction row yet, so the
     * junction is CREATED and already says so.)
     */
    fun addNamed(key: SongChildKey, songId: String, parentNames: List<String>): JunctionWrite {
        requireParents(key, parentNames)
        return database.transactionWithResult {
            if (!database.songIsLive(songId)) {
                val ids = key.parents.zip(parentNames).map { (parent, name) -> lookups.idFor(parent, name) }
                return@transactionWithResult JunctionWrite.SongGone(idFor(key, songId, ids))
            }
            val resolved = key.parents.zip(parentNames).map { (parent, name) -> lookups.resolve(parent, name) }
            val revived = resolved.any { (_, resolution) -> resolution == Resolution.REVIVED }
            when (val write = add(key, songId, resolved.map { (id, _) -> id })) {
                is JunctionWrite.Added -> if (revived) write.copy(revivedParent = true) else write
                else -> write
            }
        }
    }

    /**
     * Update a row's facts. [statement] is the table's guarded `update` (E37: `deleted_at IS
     * NULL` in its `WHERE`, `deleted_at` not in its `SET`) and is held to [wrote]'s contract.
     *
     * @return false when nothing was written: the row is absent or removed (E37), or its song is
     *   removed (R23c).
     */
    fun update(key: SongChildKey, id: String, statement: (now: String) -> Unit): Boolean =
        database.transactionWithResult {
            val row = table(key).row(id)
            if (row == null || !database.songIsLive(row.songId)) return@transactionWithResult false
            val now = Timestamps.now(clock)
            database.wrote { statement(now) }
        }

    /**
     * Remove by the id a display read handed out: a soft delete (E7), never a `DELETE`. R23c: on
     * a removed song, [JunctionWrite.SongGone]. R23d: [JunctionWrite.Removed.wrote] is false when
     * the row was not live — absent, or already removed and so not re-stamped.
     */
    fun remove(key: SongChildKey, id: String): JunctionWrite {
        val table = table(key)
        return database.transactionWithResult {
            val row = table.row(id)
            when {
                row == null -> JunctionWrite.Removed(id, wrote = false)
                !database.songIsLive(row.songId) -> JunctionWrite.SongGone(id)
                else -> JunctionWrite.Removed(
                    id,
                    wrote = database.softDelete(clock) { now -> table.softDelete(id, now) },
                )
            }
        }
    }

    /**
     * **R4a: remove the row that holds this key**, whatever its id — the Repertoire toggle's off
     * path, which knows the triple and not the row. [remove]'s rules and results, over the row
     * [find][SongChildTable.find] names; when no row holds the key, `Removed(wrote = false)`
     * under the derived id. The lookup and the remove are one transaction.
     */
    fun removeByKey(key: SongChildKey, songId: String, parentIds: List<String>): JunctionWrite =
        database.transactionWithResult { remove(key, idFor(key, songId, parentIds)) }

    private fun <T> requireParents(key: SongChildKey, parents: List<T>) {
        require(parents.size == key.parents.size) {
            "${key.name} takes ${key.parents.size} parents, got ${parents.size}"
        }
    }
}
