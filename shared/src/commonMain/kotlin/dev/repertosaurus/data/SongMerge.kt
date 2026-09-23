package dev.repertosaurus.data

import dev.repertosaurus.core.Timestamps
import dev.repertosaurus.db.RepertosaurusDatabase
import kotlinx.datetime.Clock

/**
 * **Merging two songs — repertoire-editing R31-R39, R38a.** A survivor and a loser, chosen by the
 * user; the survivor keeps its id (R22) and the loser is soft-deleted **last** (R31, R38a).
 *
 * The merge composes the writes that already exist rather than re-assembling them (F17 N2, F22
 * N8): the song's own row through [SongCatalog.updateSong], the child rows through
 * [SongChildren]'s envelope and each table's `copyFacts`, the loser's removal through
 * [SongCatalog.removeSong]. The practice log is appended to, never updated (data-model decisions
 * 7-8, R32), and set-list items are re-pointed (R37).
 *
 * **One transaction (R36), and any refusal throws (R38a, F25 N2).** R23c's refusals return values
 * rather than throwing, so every one of them is turned into a throw *here* — [MergeRefused] —
 * and **nothing inside the transaction ever catches.** The reason (F28 N1, which corrects the one
 * F25 N2 gave): SQLDelight 2.0.1 *assigns* the outer transaction's `childrenSuccessful` from the
 * most recent nested transaction to end — it does not AND-accumulate. A nested write that threw
 * and was caught, followed by a sibling that succeeded, therefore leaves the outer transaction
 * looking successful, and it **commits a half-merge**. The throw is mapped to an [Outcome]
 * **outside** the transaction, in [merge].
 *
 * **For the same reason [merge] refuses to run inside someone else's transaction** (F28 N1): it
 * opens its own with `noEnclosing = true`. Nested in a caller's transaction, a merge's refusal
 * would be one more caught child failure — the caller's — and the caller could commit around it.
 * Called that way, [merge] throws `IllegalStateException` to the caller instead of reporting an
 * [Outcome], so the mistake fails loudly.
 *
 * Reached through `RepertosaurusRepository.merge`, the way `catalog` is, so a caller built per
 * call against `holder.repository` picks up a database swap on import (R28). Nothing here
 * decides a route's queue or database binding; the Songs route runs it under its own (F22 N8).
 */
public class SongMerge internal constructor(
    private val database: RepertosaurusDatabase,
    private val deviceId: String,
    private val clock: Clock,
    private val newId: () -> String,
    private val catalog: SongCatalog,
    private val children: SongChildren,
    private val ratings: PartRatingStore,
) {

    // ---- Row and result types -----------------------------------------------------------

    /**
     * A child row by its **key** — the table and the parent lookup ids — which is what names "the
     * same row" on both songs (R35: "where both sides hold the same `(performer, instrument)` or
     * instrument"). Never by id: ids differ per song, and a two-key `song_performer` id is not the
     * derived one at all (R4a).
     */
    public data class ChildKey(val table: SongChildKey, val parentIds: List<String>)

    /**
     * One live child row of one song, as the preview shows it. [parentNames] are in the table's
     * parent order ("Will", "vocal"). [parentRemoved] says a parent lookup is tombstoned — the
     * display reads hide such a row; the merge does not (R38a), and carrying it revives the parent
     * (R23b).
     */
    public data class Child(val key: ChildKey, val parentNames: List<String>, val parentRemoved: Boolean)

    /** One live practice event, as the preview lists it (R33): its date and instrument. */
    public data class Event(
        val id: String,
        val loggedOn: String,
        val instrumentId: String,
        /** Null only when the event's instrument row does not exist at all. */
        val instrumentName: String?,
        val feel: Long?,
        val note: String?,
        /** Null for a tap-logged, untimed event (schema-3 M10). */
        val durationSeconds: Long?,
    )

    /**
     * Everything the preview needs from one song (R38): its row, its live children — **read
     * directly** ([SongChildTable.bySong]), so a row under a removed tag or instrument is here
     * (R38a) — its live events, newest first, and how many live set-list items point at it (R37).
     */
    public data class Side(
        val record: SongCatalog.SongRecord,
        val children: List<Child>,
        val events: List<Event>,
        val setlistItems: Long,
    )

    /**
     * What the user chose (R33-R35). [fields] is the merged row, already through
     * `SongDraft.validate` (R34).
     *
     * **R38b: the request names what the user dropped, never what to keep.** [dropChildren] are
     * the child keys the user deselected: each ends removed, whichever side held it. Every other
     * live child row on either song ends live on the survivor — including a row that appeared
     * after the preview was read, which the user never saw and so never deselected. [dropEvents]
     * names loser events that are voided and **not** copied (R33); every other live loser event is
     * carried, including one logged after the preview was read. Both defaults are the sum.
     */
    public data class Request(
        val survivorId: String,
        val loserId: String,
        val fields: SongCatalog.SongFields,
        val dropChildren: Set<ChildKey> = emptySet(),
        val dropEvents: Set<String> = emptySet(),
    )

    /** What [merge] did. Only [Outcome.Merged] wrote anything; the other two wrote nothing (R36). */
    public sealed interface Outcome {
        public data class Merged(
            val survivorId: String,
            val eventsCarried: Int,
            val eventsDropped: Int,
            val childrenCarried: Int,
            val setlistItemsMoved: Long,
        ) : Outcome

        /** A refusal (R38a): the transaction threw and rolled back. */
        public data class Refused(val reason: Refusal) : Outcome

        /** Anything else that threw inside the transaction — a `CHECK`, an [InsertIgnored]. Rolled back. */
        public data class Failed(val cause: Throwable) : Outcome
    }

    /** Why a merge was refused (R38a's "any refusal"). */
    public enum class Refusal {
        /** One of the two songs was removed before the merge landed (R23c, E37). */
        SONG_GONE,

        /** A write that should have written did not — a child already removed, facts not copied (R23d). */
        NOT_WRITTEN,
    }

    // ---- Reading (the preview) ------------------------------------------------------------

    /** One song's side of a merge, read in one transaction; null when the song is not live. */
    public fun side(songId: String): Side? = database.transactionWithResult {
        val record = catalog.song(songId) ?: return@transactionWithResult null
        Side(
            record = record,
            children = SongChildKey.entries.flatMap { key -> liveRows(key, songId).map { child(key, it) } },
            events = database.practice_eventQueries.selectLiveRowsBySong(songId).executeAsList().map {
                Event(it.id, it.logged_on, it.instrument_id, it.instrument_name, it.feel, it.note, it.duration_seconds)
            },
            setlistItems = database.setlist_itemQueries.countLiveBySong(songId).executeAsOne(),
        )
    }

    private fun child(key: SongChildKey, row: SongChildRow): Child {
        val parents = key.parents.zip(row.parentIds).map { (parent, id) -> lookupTable(database, deviceId, parent).byId(id) }
        return Child(
            key = ChildKey(key, row.parentIds),
            parentNames = parents.zip(row.parentIds).map { (parent, id) -> parent?.name ?: id },
            parentRemoved = parents.any { it?.deletedAt != null },
        )
    }

    // ---- Merging --------------------------------------------------------------------------

    /**
     * Merge [request]'s loser into its survivor, in one transaction (R36):
     *
     * 1. both songs must be live, or [Refusal.SONG_GONE];
     * 2. **R34:** the survivor's row is written with the picked fields ([SongCatalog.updateSong]);
     * 3. **R35 / R38a:** per child table, every key the user deselected is removed from the
     *    survivor, and every live loser row is carried (unless deselected, R38b) and then removed;
     *    ratings follow the same rule ([mergeRatings], schema-3 M20);
     * 4. **R32 / R33:** every live loser event is voided, and each one not dropped is appended to
     *    the survivor as a new row with a new random id, keeping `created_at`;
     * 5. **R37:** live set-list items on the loser are re-pointed to the survivor;
     * 6. **R31:** the loser is soft-deleted — last, after all of its children.
     *
     * Every refusal on the way throws, so the transaction rolls back whole; the throw becomes an
     * [Outcome] only here, outside it (F25 N2, F28 N1).
     *
     * @throws IllegalStateException when called inside an open transaction (F28 N1). That is a
     *   programming error, not an outcome: it is rethrown so the caller's transaction fails with it.
     */
    public fun merge(request: Request): Outcome {
        // Set as the body starts: an exception before it is SQLDelight's `noEnclosing` refusal.
        var entered = false
        return try {
            database.transactionWithResult(noEnclosing = true) {
                entered = true
                mergeInTransaction(request)
            }
        } catch (refused: MergeRefused) {
            Outcome.Refused(refused.reason)
        } catch (failure: Exception) {
            if (!entered && failure is IllegalStateException) throw failure
            Outcome.Failed(failure)
        }
    }

    /** The body of [merge]. **Never catches** (F25 N2, F28 N1): a refusal throws [MergeRefused]. */
    private fun mergeInTransaction(request: Request): Outcome.Merged {
        val survivorId = request.survivorId
        val loserId = request.loserId
        require(survivorId != loserId) { "a song cannot be merged into itself" }
        if (!database.songIsLive(survivorId) || !database.songIsLive(loserId)) throw MergeRefused(Refusal.SONG_GONE)

        // R34: the picked fields, through the one song write. Gone is a refusal (R38a).
        if (catalog.updateSong(survivorId, request.fields) !is SongCatalog.SongSave.Saved) {
            throw MergeRefused(Refusal.SONG_GONE)
        }

        // R35 / R38a: the children, table by table, through the envelope.
        val carried = SongChildKey.entries.sumOf { key -> mergeChildren(key, request) }

        // Schema-3 M20.
        mergeRatings(survivorId, loserId)

        // R32 / R33: void on the loser, append to the survivor.
        val events = database.practice_eventQueries.selectLiveRowsBySong(loserId).executeAsList()
        val now = Timestamps.now(clock)
        var copied = 0
        for (event in events) {
            database.practice_event_voidQueries.insert(
                id = newId(),
                practice_event_id = event.id,
                created_at = now,
                device_id = deviceId,
            )
            if (event.id in request.dropEvents) continue
            database.practice_eventQueries.insert(
                id = newId(),
                song_id = survivorId,
                logged_on = event.logged_on,
                instrument_id = event.instrument_id,
                context_id = event.context_id,
                feel = event.feel,
                note = event.note,
                // Schema-3 M20: a column not named here is silently dropped.
                duration_seconds = event.duration_seconds,
                // R32: the same act of practice, so every "last practised" and ordering read
                // treats the copy exactly as the original.
                created_at = event.created_at,
                device_id = deviceId,
            )
            copied++
        }

        // R37: mutable rows, so they move.
        database.setlist_itemQueries.repointLiveSong(
            survivorId = survivorId,
            updatedAt = now,
            deviceId = deviceId,
            loserId = loserId,
        )
        val moved = database.changesQueries.rowsChanged().executeAsOne()

        // R31 / R38a: the loser goes last, after all of its children.
        if (!catalog.removeSong(loserId)) throw MergeRefused(Refusal.SONG_GONE)

        return Outcome.Merged(
            survivorId = survivorId,
            eventsCarried = copied,
            eventsDropped = events.size - copied,
            childrenCarried = carried,
            setlistItemsMoved = moved,
        )
    }

    /**
     * One child table (R35, R38a, R38b). The survivor's live rows the user deselected are
     * removed, and every other survivor row stays; each live loser row is carried unless its key
     * was deselected, and removed either way — the loser ends with no live children (R38a's
     * "empty loser").
     *
     * @return how many loser rows were carried.
     */
    private fun mergeChildren(key: SongChildKey, request: Request): Int {
        for (row in liveRows(key, request.survivorId)) {
            if (ChildKey(key, row.parentIds) in request.dropChildren) removeOrThrow(key, row.id)
        }
        var carried = 0
        for (row in liveRows(key, request.loserId)) {
            if (ChildKey(key, row.parentIds) !in request.dropChildren) {
                carry(key, row, request.survivorId)
                carried++
            }
            removeOrThrow(key, row.id)
        }
        return carried
    }

    /**
     * **Schema-3 M20: the loser's live ratings, by [carry]'s rule.** A live survivor rating wins;
     * a tombstoned or absent one takes the loser's level (reviving in place, M7). Each loser rating
     * is then cleared, so the loser ends with none live (R38a). A row [PartRatingStore.decode]
     * cannot read is skipped, not thrown on (S8). Skips are not carried: "since last practised" on
     * the survivor comes from its own events.
     */
    private fun mergeRatings(survivorId: String, loserId: String) {
        val now = Timestamps.now(clock)
        val queries = database.part_ratingQueries
        for (row in queries.selectBySong(loserId).executeAsList()) {
            val stored = ratings.decode(row) ?: continue
            if (!stored.live) continue
            val onSurvivor = queries.selectByKey(survivorId, row.performer_id, row.instrument_id, row.kind)
                .executeAsOneOrNull()
            if (onSurvivor == null || onSurvivor.deleted_at != null) {
                ratings.write(stored.part.copy(songId = survivorId), stored.kind, stored.level, now)
            }
            if (!ratings.write(stored.part, stored.kind, level = null, now = now)) {
                throw MergeRefused(Refusal.NOT_WRITTEN)
            }
        }
    }

    /**
     * Carry one loser row to the survivor: **insert or revive the survivor's row by its key**
     * ([SongChildren.add] — R4a finds it through the unique index, R23b revives a removed parent),
     * then copy facts by R35 / R38a:
     *
     * - the survivor's row was live ([Resolution.EXISTING]) — **the survivor's facts win**, nothing
     *   is copied;
     * - it was tombstoned ([Resolution.REVIVED]) or absent ([Resolution.CREATED]) — the loser's row
     *   is the live one the user believes, so **the loser's facts win** and are copied onto it.
     *
     * `song_tag` carries no facts ([SongChildTable.copyFacts] is null), so no table is named here.
     */
    private fun carry(key: SongChildKey, row: SongChildRow, survivorId: String) {
        val added = children.add(key, survivorId, row.parentIds) as? JunctionWrite.Added
            ?: throw MergeRefused(Refusal.SONG_GONE)
        if (added.resolution == Resolution.EXISTING) return
        val copyFacts = songChildTable(database, deviceId, key).copyFacts ?: return
        val now = Timestamps.now(clock)
        if (!database.wrote { copyFacts(row.id, added.id, now) }) throw MergeRefused(Refusal.NOT_WRITTEN)
    }

    /** Remove a live child row, or throw: `SongGone` and "wrote nothing" are both refusals here. */
    private fun removeOrThrow(key: SongChildKey, id: String) {
        when (val removed = children.remove(key, id)) {
            is JunctionWrite.Removed -> if (!removed.wrote) throw MergeRefused(Refusal.NOT_WRITTEN)
            else -> throw MergeRefused(Refusal.SONG_GONE)
        }
    }

    /** A song's live rows on one table — tombstoned **parents** included (R38a), tombstoned rows not. */
    private fun liveRows(key: SongChildKey, songId: String): List<SongChildRow> =
        songChildTable(database, deviceId, key).bySong(songId).filter { it.deletedAt == null }
}

/**
 * **R38a / F25 N2: a merge refusal, thrown so the transaction rolls back.** Internal: it never
 * leaves [SongMerge.merge], which maps it to [SongMerge.Outcome.Refused] outside the transaction.
 */
internal class MergeRefused(val reason: SongMerge.Refusal) : IllegalStateException("merge refused: $reason")
