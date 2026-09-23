package dev.repertosaurus.data

import dev.repertosaurus.core.Timestamps
import dev.repertosaurus.db.RepertosaurusDatabase
import kotlinx.datetime.Clock

/**
 * **`suggestion_skip`: counted skips** (schema-3 M12-M14, M19). Reached as
 * `RepertosaurusRepository.skips`. How the suggester weighs them is P4's.
 */
public class SuggestionSkips internal constructor(
    private val database: RepertosaurusDatabase,
    private val deviceId: String,
    private val clock: Clock,
    private val newId: () -> String,
) {

    /**
     * Record one skip (M13): an append with a random id. An undone skip is never written.
     *
     * @return the id of the skip row.
     */
    public fun recordSkip(part: Part): String {
        val id = newId()
        database.suggestion_skipQueries.insert(
            id = id,
            song_id = part.songId,
            performer_id = part.performerId,
            instrument_id = part.instrumentId,
            created_at = Timestamps.now(clock),
            device_id = deviceId,
        )
        return id
    }

    /**
     * M14: skips for [part] since its song was last practised live on its instrument — compared on
     * `created_at`, so a voided log does not reset the count and a back-dated one does.
     */
    public fun skipsSinceLastPractised(part: Part): Long = skipsSinceLastPractised(listOf(part)).getValue(part)

    /** The same for every part asked for, zero where none; one query per distinct instrument. */
    public fun skipsSinceLastPractised(parts: Collection<Part>): Map<Part, Long> {
        val counts = parts.map { it.instrumentId }.distinct().flatMap { instrument ->
            database.suggestion_skipQueries.countSinceLastPractisedOnInstrument(instrument)
                .executeAsList()
                .map { Part(it.song_id, it.performer_id, instrument) to it.skips }
        }.toMap()
        return parts.associateWith { counts[it] ?: 0L }
    }
}
