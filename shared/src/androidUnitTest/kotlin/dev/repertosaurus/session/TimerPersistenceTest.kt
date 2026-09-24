package dev.repertosaurus.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.repertosaurus.TestClock
import dev.repertosaurus.core.Ids
import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.data.SongCatalog.LookupChoice
import dev.repertosaurus.db.RepertosaurusDatabase
import dev.repertosaurus.session.PracticeTimer.Running
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * timer TM7 and TM10 against a real database: what a timer's stop writes, and what a stored timer finds at
 * launch when its song or its instrument has been removed.
 */
class TimerPersistenceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: RepertosaurusDatabase
    private lateinit var repository: RepertosaurusRepository
    private lateinit var coordinator: SessionCoordinator
    private val clock = TestClock("2026-09-24T20:00:00Z")
    private val timer = PracticeTimer(clock) { TimeZone.UTC }
    private val vocal = Ids.derived("instrument", "vocal")

    @BeforeTest
    fun open() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        RepertosaurusDatabase.Schema.create(driver)
        database = RepertosaurusDatabase(driver)
        repository = RepertosaurusRepository(database, "device", clock, TimeZone.UTC)
        coordinator = SessionCoordinator(repository, InMemorySessionPreferences())
    }

    @AfterTest
    fun close() = driver.close()

    private fun song(title: String): String = repository.catalog.addSong(title, LookupChoice.Typed("Dolly Parton")).song.songId

    /** TM7: Stop writes exactly one event, its duration and the start's date, with feel null. */
    @Test
    fun stopWritesOneTimedEventOnTheStartDate() {
        val jolene = song("Jolene")
        clock.instant = Instant.parse("2026-09-24T23:50:00Z")
        val running = timer.start(jolene, vocal)
        clock.instant = Instant.parse("2026-09-25T00:14:00Z")
        val seconds = (timer.stop(running) as StopOutcome.Timed).seconds
        val tap = SessionTap("tap", jolene, vocal, feel = null, note = null, loggedOn = timer.loggedOn(running), durationSeconds = seconds)

        coordinator.persist(tap)

        val history = repository.practiceHistory(jolene)
        assertEquals(1, history.size)
        assertEquals(Triple("2026-09-24", 1_440L, null), history.single().let { Triple(it.loggedOn, it.durationSeconds, it.feel) })
    }

    /** TM7: undo of a timed log is a void, as a tap's is; the event is not deleted. */
    @Test
    fun undoOfATimedLogIsAVoid() {
        val jolene = song("Jolene")
        val eventId = coordinator.persist(SessionTap("tap", jolene, vocal, null, null, "2026-09-24", durationSeconds = 600L))

        coordinator.voidEvent(eventId)

        assertEquals(emptyList(), repository.practiceHistory(jolene))
        assertEquals(true, database.practice_event_voidQueries.isVoided(eventId).executeAsOne())
    }

    /** TM8: an untimed stop writes a null duration, as a tap does. */
    @Test
    fun anUntimedStopWritesNoDuration() {
        val jolene = song("Jolene")
        coordinator.persist(SessionTap("tap", jolene, vocal, null, null, "2026-09-24", durationSeconds = null))
        assertNull(repository.practiceHistory(jolene).single().durationSeconds)
    }

    /** TM10: a soft-deleted song has no title, so its stored timer is dropped. */
    @Test
    fun aRemovedSongHasNoTitle() {
        val jolene = song("Jolene")
        assertEquals("Jolene", coordinator.songTitle(jolene))
        repository.catalog.removeSong(jolene)
        assertNull(coordinator.songTitle(jolene))
    }

    /** TM10: a song merged away is removed, so its stored timer is dropped too; the survivor's runs on. */
    @Test
    fun aMergedAwaySongHasNoTitle() {
        val survivor = song("Jolene")
        val loser = song("Jolen")
        val plan = MergePlan.of(assertNotNull(repository.merge.side(survivor)), assertNotNull(repository.merge.side(loser)))
        repository.merge.merge(assertNotNull(plan.request()))

        assertNull(coordinator.songTitle(loser))
        assertEquals("Jolene", coordinator.songTitle(survivor))
    }

    /** TM10, views V20a: a soft-deleted instrument falls back to the first live chip. */
    @Test
    fun aRemovedInstrumentFallsBackToTheFirstChip() {
        val instruments = LookupStores.of(LookupKind.INSTRUMENT, repository)
        instruments.add("guitar")
        val guitar = Ids.derived("instrument", "guitar")
        val running = Running(song("Jolene"), guitar, 0L)
        assertEquals(guitar, timer.instrumentFor(running, coordinator.instruments()))

        instruments.remove(guitar)

        val chips = coordinator.instruments()
        assertEquals(chips.first().id, timer.instrumentFor(running, chips))
        assertEquals(vocal, chips.first().id, "decision 18's seed order puts vocal first")
    }
}
