package dev.songbook.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.songbook.core.Ids
import dev.songbook.core.Timestamps
import dev.songbook.db.SongbookDatabase
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the generated SQLDelight schema and the repository against a real SQLite
 * database. Android unit tests run on the host JVM, so the JDBC driver is enough and no
 * device is needed.
 */
class SongbookDatabaseTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-08-15T10:30:00.250Z")
    }

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: SongbookDatabase
    private lateinit var repository: SongbookRepository

    private val guitar = Ids.derived("instrument", "guitar")
    private val bass = Ids.derived("instrument", "bass")

    @BeforeTest
    fun open() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        SongbookDatabase.Schema.create(driver)
        database = SongbookDatabase(driver)
        repository = SongbookRepository(
            database = database,
            deviceId = "test-device",
            clock = fixedClock,
            timeZone = TimeZone.UTC,
        )
    }

    @AfterTest
    fun close() {
        driver.close()
    }

    // ---- Schema and seed rows ---------------------------------------------------------

    @Test
    fun schemaCreatesAndSeedsTheLookupTables() {
        assertEquals(5, database.instrumentQueries.selectAllLive().executeAsList().size)
        assertEquals(5, database.tagQueries.selectAllLive().executeAsList().size)
        assertEquals(4, database.practice_contextQueries.selectAllLive().executeAsList().size)
    }

    /** The seeded ids must be the ones the shared core derives, or nothing converges. */
    @Test
    fun seededIdsMatchTheDerivedIds() {
        val instruments = database.instrumentQueries.selectAllLive().executeAsList()
        for (instrument in instruments) {
            assertEquals(
                Ids.derived("instrument", instrument.name),
                instrument.id,
                "instrument '${instrument.name}'",
            )
        }
        val tags = database.tagQueries.selectAllLive().executeAsList()
        for (tag in tags) {
            assertEquals(Ids.derived("tag", tag.name), tag.id, "tag '${tag.name}'")
        }
        val contexts = database.practice_contextQueries.selectAllLive().executeAsList()
        for (context in contexts) {
            assertEquals(
                Ids.derived("practice_context", context.name),
                context.id,
                "practice_context '${context.name}'",
            )
        }
    }

    @Test
    fun timestampsSatisfyTheGlobChecks() {
        // Decision 10: every timestamp column has a GLOB CHECK, so a wrongly formatted
        // value is a constraint failure rather than a silent sort defect.
        val stamp = Timestamps.now(fixedClock)
        assertEquals("2026-08-15T10:30:00.250Z", stamp)
        val song = insertSong("Valerie", "The Zutons")
        assertNotNull(database.songQueries.selectById(song).executeAsOneOrNull())
    }

    // ---- Staleness ordering -----------------------------------------------------------

    @Test
    fun stalenessPutsNeverPractisedFirstThenColdestFirst() {
        val cold = insertSong("Chelsea Dagger", "The Fratellis")
        val warm = insertSong("Valerie", "The Zutons")
        val unplayed = insertSong("Dakota", "Stereophonics")

        repository.logPractice(cold, guitar, loggedOn = "2026-06-01")
        repository.logPractice(warm, guitar, loggedOn = "2026-08-14")

        val rows = repository.songsByStaleness(guitar, today = "2026-08-15")
        assertEquals(listOf(unplayed, cold, warm), rows.map { it.songId })
        assertNull(rows[0].lastPractised)
        assertNull(rows[0].daysSince)
        assertEquals(75L, rows[1].daysSince)
        assertEquals(1L, rows[2].daysSince)
        assertEquals("The Fratellis", rows[1].artistName)
    }

    @Test
    fun stalenessIsScopedToOneInstrument() {
        val song = insertSong("Thunderstruck", "AC/DC")
        repository.logPractice(song, bass, loggedOn = "2026-08-14")

        val onBass = repository.songsByStaleness(bass, today = "2026-08-15").single()
        assertEquals(1L, onBass.timesPractised)
        assertEquals(1L, onBass.daysSince)

        val onGuitar = repository.songsByStaleness(guitar, today = "2026-08-15").single()
        assertEquals(0L, onGuitar.timesPractised)
        assertNull(onGuitar.lastPractised)
    }

    // ---- Append, void, and what decision 8 actually requires ---------------------------

    /**
     * Decision 8. Voiding the only event for a song must return it to never-practised, not
     * remove it from the session list. This is a regression test: with the anti-join in the
     * WHERE clause rather than the join condition, the song disappeared entirely.
     */
    @Test
    fun voidingTheOnlyEventReturnsTheSongToNeverPractised() {
        val song = insertSong("Valerie", "The Zutons")
        val event = repository.logPractice(song, guitar, loggedOn = "2026-08-14")

        val before = repository.songsByStaleness(guitar, today = "2026-08-15").single()
        assertEquals(1L, before.timesPractised)

        repository.voidPractice(event)

        val after = repository.songsByStaleness(guitar, today = "2026-08-15")
        assertEquals(1, after.size, "the song must still be listed after an undo")
        assertEquals(0L, after[0].timesPractised)
        assertNull(after[0].lastPractised)
        assertNull(after[0].daysSince)
    }

    @Test
    fun voidingLeavesEarlierEventsAlone() {
        val song = insertSong("Valerie", "The Zutons")
        repository.logPractice(song, guitar, loggedOn = "2026-08-01")
        val mistake = repository.logPractice(song, guitar, loggedOn = "2026-08-14")

        repository.voidPractice(mistake)

        val row = repository.songsByStaleness(guitar, today = "2026-08-15").single()
        assertEquals(1L, row.timesPractised)
        assertEquals("2026-08-01", row.lastPractised)
        assertEquals(14L, row.daysSince)
        assertEquals(1, repository.practiceHistory(song).size)
    }

    /** Decision 47: two passes at the same piece in one day are two real sessions. */
    @Test
    fun theSameSongTwiceInADayIsTwoEvents() {
        val song = insertSong("Valerie", "The Zutons")
        val first = repository.logPractice(song, guitar, loggedOn = "2026-08-15")
        val second = repository.logPractice(song, guitar, loggedOn = "2026-08-15")

        assertTrue(first != second)
        assertEquals(2L, repository.timesPractised(song))
        assertEquals(2, repository.practiceHistory(song).size)
    }

    /** Decision 45a: a one-tap log must never require a second chip. */
    @Test
    fun contextIsOptional() {
        val song = insertSong("Dakota", "Stereophonics")
        repository.logPractice(song, guitar)
        assertNull(repository.practiceHistory(song).single().contextName)
    }

    @Test
    fun undoTargetIsTheNewestLiveEvent() {
        // selectMostRecentLive orders by created_at, so the clock has to advance between
        // taps for "newest" to mean anything.
        val ticking = object : Clock {
            private var tick = 0L
            override fun now(): Instant =
                Instant.fromEpochMilliseconds(1_786_000_000_000L + tick++)
        }
        val ticked = SongbookRepository(database, "test-device", ticking, TimeZone.UTC)

        val song = insertSong("Valerie", "The Zutons")
        val older = ticked.logPractice(song, guitar, loggedOn = "2026-08-01")
        val newest = ticked.logPractice(song, guitar, loggedOn = "2026-08-14")

        assertEquals(newest, ticked.mostRecentLiveEvent())
        ticked.voidPractice(newest)
        assertEquals(older, ticked.mostRecentLiveEvent())
    }

    // ---- Sample data ------------------------------------------------------------------

    @Test
    fun sampleDataInstallsOnceAndIsQueryable() {
        SampleData.installIfEmpty(database, clock = fixedClock, timeZone = TimeZone.UTC)
        val songs = database.songQueries.selectAllLive().executeAsList()
        assertTrue(songs.isNotEmpty())

        SampleData.installIfEmpty(database, clock = fixedClock, timeZone = TimeZone.UTC)
        assertEquals(songs.size, database.songQueries.selectAllLive().executeAsList().size)

        val rows = repository.songsByStaleness(SampleData.GUITAR, today = "2026-08-15")
        assertEquals(songs.size, rows.size)
        // Never-practised songs sort first.
        assertNull(rows.first().lastPractised)
    }

    private fun insertSong(title: String, artist: String): String {
        val artistId = Ids.derived("artist", artist)
        val now = Timestamps.now(fixedClock)
        database.artistQueries.applyMerged(
            id = artistId,
            name = artist,
            sort_name = artist,
            updated_at = now,
            deleted_at = null,
            device_id = "test-device",
        )
        val songId = Ids.song(artistId, title)
        database.songQueries.insert(
            id = songId,
            title = title,
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
            updated_at = now,
            deleted_at = null,
            device_id = "test-device",
        )
        return songId
    }
}
