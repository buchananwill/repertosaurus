package dev.songbook.data

import dev.songbook.core.Ids
import dev.songbook.core.Timestamps
import dev.songbook.db.SongbookDatabase
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Test data for the phase 1 placeholder screen, so the app has something to prove the
 * database opens and the staleness query runs.
 *
 * This is **not** the migration and not seed data. The real repertoire arrives from the
 * phase 0 import. `instrument`, `tag` and `practice_context` are seeded by the schema
 * itself and are not touched here.
 */
public object SampleData {

    public const val GUITAR: String = "f6b6f826-8eeb-5acc-9314-d2c096f780f6"
    public const val BASS: String = "4e6210b7-1029-5855-99c2-32f48d87ec2a"
    public const val VOCAL: String = "6409e4f3-5881-5293-8918-9dbafe572ac6"
    public const val PRACTICE: String = "1a9bb75b-e61f-533f-aaae-30053e06856e"

    private data class Seed(
        val title: String,
        val artist: String,
        val keySignature: Long?,
        val tonalCentre: Long?,
        val practisedDaysAgo: List<Int>,
    )

    private val SONGS = listOf(
        Seed("Valerie", "The Zutons", 2L, 4L, listOf(1, 9)),
        Seed("Shake It Off", "Taylor Swift", 0L, 9L, listOf(3)),
        Seed("Sweet Home Alabama", "Lynyrd Skynyrd", 1L, 7L, listOf(21)),
        Seed("Chelsea Dagger", "The Fratellis", 2L, 2L, listOf(45)),
        Seed("Mr. Brightside", "The Killers", -2L, 10L, emptyList()),
        Seed("Dakota", "Stereophonics", -1L, 5L, emptyList()),
        Seed("Thunderstruck", "AC/DC", 1L, 7L, listOf(120)),
        Seed("Dog Days Are Over", "Florence & the Machine", -3L, 8L, listOf(6)),
    )

    /** Idempotent: does nothing once the song table has rows. */
    public fun installIfEmpty(
        database: SongbookDatabase,
        deviceId: String = "sample",
        clock: Clock = Clock.System,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ) {
        if (database.songQueries.selectAllLive().executeAsList().isNotEmpty()) return

        val now = Timestamps.now(clock)
        val today = clock.now().toLocalDateTime(timeZone).date
        val repository = SongbookRepository(database, deviceId, clock, timeZone)

        database.transaction {
            for (seed in SONGS) {
                val artistId = Ids.derived("artist", seed.artist)
                database.artistQueries.applyMerged(
                    id = artistId,
                    name = seed.artist,
                    sort_name = sortName(seed.artist),
                    updated_at = now,
                    deleted_at = null,
                    device_id = deviceId,
                )

                val songId = Ids.song(artistId, seed.title)
                database.songQueries.insert(
                    id = songId,
                    title = seed.title,
                    artist_id = artistId,
                    reference_recording = null,
                    key_signature = seed.keySignature,
                    tonal_centre = seed.tonalCentre,
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
                    updated_at = now,
                    deleted_at = null,
                    device_id = deviceId,
                )

                for (daysAgo in seed.practisedDaysAgo) {
                    repository.logPractice(
                        songId = songId,
                        instrumentId = GUITAR,
                        contextId = PRACTICE,
                        loggedOn = LocalDate.fromEpochDays(today.toEpochDays() - daysAgo).toString(),
                    )
                }
            }
        }
    }

    /** Article moved to the end, per decision 21. */
    private fun sortName(name: String): String =
        if (name.startsWith("The ")) name.substring(4) + ", The" else name
}
