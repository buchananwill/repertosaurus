package dev.repertosaurus.data

import dev.repertosaurus.core.Ids
import dev.repertosaurus.core.RatingKind
import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.core.Timestamps
import dev.repertosaurus.db.Part_rating
import dev.repertosaurus.db.RepertosaurusDatabase
import kotlinx.datetime.Clock

/** A part: one performer on one instrument on one song — the triple schema-3 M6 keys on. */
public data class Part(val songId: String, val performerId: String, val instrumentId: String)

/** A part's two ratings (schema-3 M4). Null is unrated: never rated, or cleared (M7). */
public data class PartRatings(val priority: RatingLevel?, val confidence: RatingLevel?) {
    public companion object {
        public val UNRATED: PartRatings = PartRatings(priority = null, confidence = null)
    }
}

/**
 * **`part_rating`: a part's priority and confidence** (schema-3 M3-M8, M19). Reached as
 * `RepertosaurusRepository.ratings`; song merge carries ratings through [write] (M20).
 *
 * Reads go by the triple directly, with no join to a live `song_performer` row (M8). Which read a
 * consumer uses is fixed in its own spec (P4, P7).
 */
public class PartRatingStore internal constructor(
    private val database: RepertosaurusDatabase,
    private val deviceId: String,
    private val clock: Clock,
) {

    /**
     * Set or clear one rating (M7, rating-scale RS9): a null [level] tombstones it; a level revives
     * the row holding the key, or inserts one under `Ids.partRating`. No liveness check — M8 lets a
     * rating outlive its part, and the foreign keys refuse a row that names nothing.
     *
     * @return false only when clearing a rating that was not set (R23d).
     */
    public fun setRating(part: Part, kind: RatingKind, level: RatingLevel?): Boolean =
        write(part, kind, level, Timestamps.now(clock))

    /** Live ratings for one `(performer, instrument)`, keyed by song id; unrated songs are absent. */
    public fun ratingsFor(performerId: String, instrumentId: String): Map<String, PartRatings> =
        database.part_ratingQueries.selectLiveByPerformerInstrument(performerId, instrumentId)
            .executeAsList()
            .mapNotNull(::decode)
            .groupBy { it.part.songId }
            .mapValues { (_, rows) -> ratings(rows) }

    /**
     * Every part asked for, [PartRatings.UNRATED] where it has none. One query per distinct
     * `(performer, instrument)`, never a bound `IN` list (SQLite 3.19 caps those at 999).
     */
    public fun ratingsFor(parts: Collection<Part>): Map<Part, PartRatings> {
        val byPair = parts.map { it.performerId to it.instrumentId }.distinct()
            .associateWith { (performer, instrument) -> ratingsFor(performer, instrument) }
        return parts.associateWith { part ->
            byPair.getValue(part.performerId to part.instrumentId)[part.songId] ?: PartRatings.UNRATED
        }
    }

    /** One stored rating, decoded. */
    internal class Stored(val part: Part, val kind: RatingKind, val level: RatingLevel, val live: Boolean)

    /**
     * **The one decoder for a `part_rating` row**, used by every read and by merge. A row whose
     * `kind` or `level` this build cannot read decodes to null and is skipped by every caller,
     * never thrown on (schema-compatibility S8).
     */
    internal fun decode(row: Part_rating): Stored? {
        val kind = RatingKind.fromStored(row.kind) ?: return null
        val level = RatingLevel.fromStored(row.level) ?: return null
        return Stored(Part(row.song_id, row.performer_id, row.instrument_id), kind, level, row.deleted_at == null)
    }

    /**
     * **M7's write, once.** Null clears a live row and reports whether one was. A level updates the
     * row holding the key — reviving a tombstone in place — and only when none does, inserts under
     * the derived id. Two statements, since 3.19 has no UPSERT, in one transaction.
     */
    internal fun write(part: Part, kind: RatingKind, level: RatingLevel?, now: String): Boolean =
        database.transactionWithResult {
            val queries = database.part_ratingQueries
            if (level == null) {
                database.wrote {
                    queries.softDeleteByKey(
                        now = now,
                        deviceId = deviceId,
                        songId = part.songId,
                        performerId = part.performerId,
                        instrumentId = part.instrumentId,
                        kind = kind.name,
                    )
                }
            } else {
                val updated = database.wrote {
                    queries.setLevelByKey(
                        level = level.value,
                        updatedAt = now,
                        deviceId = deviceId,
                        songId = part.songId,
                        performerId = part.performerId,
                        instrumentId = part.instrumentId,
                        kind = kind.name,
                    )
                }
                if (!updated) {
                    queries.insertIfAbsent(
                        id = Ids.partRating(part.songId, part.performerId, part.instrumentId, kind),
                        song_id = part.songId,
                        performer_id = part.performerId,
                        instrument_id = part.instrumentId,
                        kind = kind.name,
                        level = level.value,
                        updated_at = now,
                        device_id = deviceId,
                    )
                    database.requireInserted()
                }
                true
            }
        }

    private fun ratings(rows: List<Stored>): PartRatings {
        val levels = rows.associate { it.kind to it.level }
        return PartRatings(priority = levels[RatingKind.PRIORITY], confidence = levels[RatingKind.CONFIDENCE])
    }
}
