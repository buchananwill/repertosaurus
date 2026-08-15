package dev.songbook.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.songbook.core.Ids
import dev.songbook.core.Timestamps
import dev.songbook.data.SongbookRepository
import dev.songbook.db.SongbookDatabase
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Session screen against a real database, on the host JVM — no device involved.
 *
 * These are the round trips the pure [SessionStateTest] cannot cover: that the ordering the
 * screen renders is the ordering SQLite returns, that an undo really is an append to
 * `practice_event_void` and really does put the song back where it was, and that the chip
 * the user last used survives a restart.
 */
class SessionCoordinatorTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-08-15T10:30:00.250Z")
    }
    private val today = "2026-08-15"

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: SongbookDatabase
    private lateinit var repository: SongbookRepository
    private lateinit var preferences: InMemorySessionPreferences
    private lateinit var coordinator: SessionCoordinator

    private val guitar = Ids.derived("instrument", "guitar")
    private val vocal = Ids.derived("instrument", "vocal")

    @BeforeTest
    fun open() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        SongbookDatabase.Schema.create(driver)
        database = SongbookDatabase(driver)
        repository = SongbookRepository(database, "test-device", fixedClock, TimeZone.UTC)
        preferences = InMemorySessionPreferences()
        coordinator = SessionCoordinator(repository, preferences)
    }

    @AfterTest
    fun close() {
        driver.close()
    }

    // ---- The chip row -----------------------------------------------------------------

    @Test
    fun theChipRowComesFromTheInstrumentTableInSeedOrder() {
        assertEquals(
            listOf("Vocal", "Backing Vocal", "Guitar", "Bass", "Keys"),
            coordinator.instruments().map { it.label },
        )
    }

    /** The phase 1 answer: the app remembers the discipline rather than asking. */
    @Test
    fun theLastInstrumentIsRememberedAcrossLaunches() {
        val chips = coordinator.instruments()

        // First launch: nothing remembered, so the first chip, and it is written back.
        assertEquals(vocal, coordinator.initialInstrument(chips))
        assertEquals(vocal, preferences.lastInstrumentId())

        coordinator.rememberInstrument(guitar)

        // A fresh process, the same stored preference.
        val relaunched = SessionCoordinator(repository, preferences)
        assertEquals(guitar, relaunched.initialInstrument(relaunched.instruments()))
    }

    @Test
    fun aRememberedInstrumentThatNoLongerExistsFallsBackToTheFirst() {
        preferences.rememberInstrument("an-instrument-that-was-deleted")
        assertEquals(vocal, coordinator.initialInstrument(coordinator.instruments()))
    }

    // ---- Ordering, logging, undo ------------------------------------------------------

    @Test
    fun rowsAreColdestFirstWithNeverPractisedLeading() {
        val unplayed = insertSong("Dakota", "Stereophonics")
        val warm = insertSong("Valerie", "The Zutons")
        val cold = insertSong("Chelsea Dagger", "The Fratellis")
        repository.logPractice(warm, guitar, loggedOn = "2026-08-14")
        repository.logPractice(cold, guitar, loggedOn = "2026-06-01")

        val rows = coordinator.rows(guitar)

        assertEquals(listOf(unplayed, cold, warm), rows.map { it.songId })
        assertEquals(listOf("never", "75d", "1d"), rows.map { it.badge })
        assertEquals("Stereophonics", rows[0].artistName)
    }

    /**
     * Decision 8, end to end: tap, then undo, and the song is back exactly where it was —
     * which for a song with no other event means back at the top, badged "never".
     */
    @Test
    fun undoingTheOnlyLogReturnsTheSongToTheTopOfTheList() {
        val unplayed = insertSong("Dakota", "Stereophonics")
        val warm = insertSong("Valerie", "The Zutons")
        val cold = insertSong("Chelsea Dagger", "The Fratellis")
        repository.logPractice(warm, guitar, loggedOn = "2026-08-14")
        repository.logPractice(cold, guitar, loggedOn = "2026-06-01")

        val before = coordinator.rows(guitar)
        assertEquals(listOf(unplayed, cold, warm), before.map { it.songId })

        val tap = coordinator.newTap(unplayed, guitar)
        val eventId = coordinator.persist(tap)

        val logged = coordinator.rows(guitar)
        assertEquals(listOf(cold, warm, unplayed), logged.map { it.songId })
        assertEquals("today", logged.last().badge)

        coordinator.voidEvent(eventId)

        val after = coordinator.rows(guitar)
        assertEquals(before.map { it.songId }, after.map { it.songId })
        assertEquals("never", after[0].badge)
        assertNull(after[0].daysSince)
        assertEquals(0L, after[0].timesPractised)
    }

    /** Voiding one of several events leaves the rest counting. */
    @Test
    fun undoingOneOfSeveralLogsLeavesTheOthers() {
        val song = insertSong("Valerie", "The Zutons")
        repository.logPractice(song, guitar, loggedOn = "2026-06-01")
        val mistake = coordinator.persist(coordinator.newTap(song, guitar))

        assertEquals("today", coordinator.rows(guitar).single().badge)
        coordinator.voidEvent(mistake)

        val row = coordinator.rows(guitar).single()
        assertEquals("75d", row.badge)
        assertEquals(1L, row.timesPractised)
    }

    /** Decision 47: the second tap in a day is a second event, not a duplicate. */
    @Test
    fun twoTapsOnOneSongInOneDayAreTwoEvents() {
        val song = insertSong("Valerie", "The Zutons")

        val first = coordinator.persist(coordinator.newTap(song, guitar))
        val second = coordinator.persist(coordinator.newTap(song, guitar))

        assertNotEquals(first, second)
        assertEquals(2L, repository.timesPractised(song))
        assertEquals(2, repository.practiceHistory(song).size)

        // Still one row on the screen, badged today.
        val row = coordinator.rows(guitar).single()
        assertEquals("today", row.badge)
        assertEquals(2L, row.timesPractised)

        // And undoing one of them leaves the other standing.
        coordinator.voidEvent(second)
        assertEquals(1L, repository.timesPractised(song))
        assertEquals("today", coordinator.rows(guitar).single().badge)
    }

    @Test
    fun logsAreScopedToTheInstrumentTheChipSelected() {
        val song = insertSong("Valerie", "The Zutons")
        coordinator.persist(coordinator.newTap(song, guitar))

        assertEquals("today", coordinator.rows(guitar).single().badge)
        assertEquals("never", coordinator.rows(vocal).single().badge)
    }

    @Test
    fun theLongPressSheetWritesFeelAndNote() {
        val song = insertSong("Valerie", "The Zutons")
        coordinator.persist(coordinator.newTap(song, guitar, feel = 2L, note = "  dropped the bridge  "))

        val entry = repository.practiceHistory(song).single()
        assertEquals(2L, entry.feel)
        assertEquals("dropped the bridge", entry.note)

        // A plain tap logs with feel null (decision 46) and no note.
        val plain = coordinator.newTap(song, guitar)
        assertNull(plain.feel)
        assertNull(plain.note)
        assertNull(coordinator.newTap(song, guitar, note = "   ").note)
    }

    @Test
    fun tapsAreDatedTheDevicesLocalToday() {
        val song = insertSong("Valerie", "The Zutons")
        assertEquals(today, coordinator.newTap(song, guitar).loggedOn)
    }

    /** The whole loop the screen runs: load, tap, undo, with state in between. */
    @Test
    fun theScreenStateSurvivesTheRoundTrip() {
        val unplayed = insertSong("Dakota", "Stereophonics")
        insertSong("Valerie", "The Zutons")

        val chips = coordinator.instruments()
        val selected = coordinator.initialInstrument(chips)
        assertEquals(vocal, selected)

        var state = SessionState()
            .withInstruments(chips, selected)
            .withRows(coordinator.rows(selected!!))
        assertEquals(2, state.pending.size)
        assertTrue(state.logged.isEmpty())

        val tap = coordinator.newTap(unplayed, selected)
        state = state.plusTap(tap)
        val eventId = coordinator.persist(tap)

        assertEquals(1, state.pending.size)
        assertEquals("Dakota", state.logged.single().row.title)

        state = state.minusTap(tap.tapId)
        coordinator.voidEvent(eventId)

        assertEquals(2, state.pending.size)
        assertTrue(state.logged.isEmpty())
        assertEquals(listOf("never", "never"), coordinator.rows(selected).map { it.badge })
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
