package dev.repertaurus.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.repertaurus.core.Ids
import dev.repertaurus.core.Timestamps
import dev.repertaurus.data.RepertaurusRepository
import dev.repertaurus.db.RepertaurusDatabase
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
    private lateinit var database: RepertaurusDatabase
    private lateinit var repository: RepertaurusRepository
    private lateinit var preferences: InMemorySessionPreferences
    private lateinit var coordinator: SessionCoordinator

    private val guitar = Ids.derived("instrument", "guitar")
    private val vocal = Ids.derived("instrument", "vocal")

    @BeforeTest
    fun open() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        RepertaurusDatabase.Schema.create(driver)
        database = RepertaurusDatabase(driver)
        repository = RepertaurusRepository(database, "test-device", fixedClock, TimeZone.UTC)
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

    /** The sort direction is a view preference and is remembered like the chip is. */
    @Test
    fun theSortDirectionIsRememberedAcrossLaunches() {
        assertEquals(SessionOrder.COLDEST_FIRST, coordinator.initialOrder())

        coordinator.rememberOrder(SessionOrder.HOTTEST_FIRST)

        val relaunched = SessionCoordinator(repository, preferences)
        assertEquals(SessionOrder.HOTTEST_FIRST, relaunched.initialOrder())
    }

    @Test
    fun anUnreadableStoredOrderFallsBackToColdestFirst() {
        preferences.rememberOrder("SOMETHING_A_LATER_BUILD_WROTE")
        assertEquals(SessionOrder.COLDEST_FIRST, coordinator.initialOrder())
    }

    /** Both directions over real rows, including where a never-practised song lands. */
    @Test
    fun bothDirectionsOrderTheSameRowsFromEitherEnd() {
        val unplayed = insertSong("Dakota", "Stereophonics")
        val warm = insertSong("Valerie", "The Zutons")
        val cold = insertSong("Chelsea Dagger", "The Fratellis")
        repository.logPractice(warm, guitar, loggedOn = "2026-08-14")
        repository.logPractice(cold, guitar, loggedOn = "2026-06-01")

        val loaded = SessionState()
            .withInstruments(coordinator.instruments(), guitar)
            .withRows(coordinator.rows(guitar))

        assertEquals(listOf(unplayed, cold, warm), loaded.pending.map { it.songId })
        assertEquals(
            listOf(warm, cold, unplayed),
            loaded.withOrder(SessionOrder.HOTTEST_FIRST).pending.map { it.songId },
        )
    }

    // ---- Adding a song ----------------------------------------------------------------

    @Test
    fun addingASongMakesItAppearImmediatelyAsNeverPractised() {
        val songId = coordinator.addSong("Wonderwall", "Oasis")
        val rows = coordinator.rows(guitar)

        assertEquals(songId, rows.single().songId)
        assertEquals("never", rows.single().badge)
        assertNull(rows.single().daysSince)
        assertEquals(0L, rows.single().timesPractised)
        assertEquals("Oasis", rows.single().artistName)

        // And it is immediately loggable, like any other row.
        coordinator.persist(coordinator.newTap(songId, guitar))
        assertEquals("today", coordinator.rows(guitar).single().badge)
    }

    /** Decisions 4 and 4d: the id is derived, not random, so two devices converge. */
    @Test
    fun theNewSongsIdIsTheDerivedId() {
        val songId = coordinator.addSong("Wonderwall", "Oasis")
        val artistId = Ids.derived("artist", "Oasis")

        assertEquals(Ids.song(artistId, "Wonderwall"), songId)
        assertEquals(artistId, coordinator.artists().single { it.name == "Oasis" }.id)
    }

    /**
     * The point of the type-ahead. `Fratellis` must resolve to the stored `The Fratellis`
     * rather than making a second artist that no merge rule could reconcile.
     */
    @Test
    fun anArtistThatNormalisesToAnExistingOneReusesThatRow() {
        insertSong("Chelsea Dagger", "The Fratellis")
        val before = coordinator.artists().size

        val songId = coordinator.addSong("Henrietta", "Fratellis")

        assertEquals(before, coordinator.artists().size, "no second Fratellis")
        val stored = coordinator.artists().single { it.name == "The Fratellis" }
        assertEquals(Ids.song(stored.id, "Henrietta"), songId)
        // The stored display name is left alone: an id is immutable and the canonical
        // spelling is the one already there (decision 5).
        assertEquals(
            "The Fratellis",
            coordinator.rows(guitar).single { it.songId == songId }.artistName,
        )

        // And the same holds through punctuation.
        insertSong("Thunderstruck", "AC/DC")
        val artistsNow = coordinator.artists().size
        coordinator.addSong("Back in Black", "AC DC")
        assertEquals(artistsNow, coordinator.artists().size, "no second AC/DC")
    }

    /** Decision 28a: the escape hatch, not a blocked save. */
    @Test
    fun aBlankArtistResolvesToTheSeededUnknownArtist() {
        val songId = coordinator.addSong("A Song With No Artist", "")

        assertEquals(RepertaurusRepository.UNKNOWN_ARTIST_ID, coordinator.resolveArtist(""))
        assertEquals(
            Ids.derived("artist", "Unknown Artist"),
            RepertaurusRepository.UNKNOWN_ARTIST_ID,
        )
        assertEquals(
            "Unknown Artist",
            coordinator.rows(guitar).single { it.songId == songId }.artistName,
        )
    }

    /** Adding the same song twice is one row: the id is derived from its identity. */
    @Test
    fun addingTheSameSongTwiceDoesNotForkIt() {
        val first = coordinator.addSong("Wonderwall", "Oasis")
        val second = coordinator.addSong("  wonderwall  ", "oasis")

        assertEquals(first, second)
        assertEquals(1, coordinator.rows(guitar).size)
        // The first spelling is the one kept.
        assertEquals("Wonderwall", coordinator.rows(guitar).single().title)
    }

    @Test
    fun aSongAddedAgainstAPickedArtistUsesThatArtistId() {
        insertSong("Chelsea Dagger", "The Fratellis")
        val picked = coordinator.suggestArtists("fratellis").single()

        val songId = coordinator.addSongWithArtistId("Henrietta", picked.id)

        assertEquals(Ids.song(picked.id, "Henrietta"), songId)
        assertEquals(
            "The Fratellis",
            coordinator.rows(guitar).single { it.songId == songId }.artistName,
        )
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
