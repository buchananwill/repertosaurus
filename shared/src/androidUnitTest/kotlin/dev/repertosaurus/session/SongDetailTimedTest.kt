package dev.repertosaurus.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.repertosaurus.TestClock
import dev.repertosaurus.core.Ids
import dev.repertosaurus.data.LookupTableKey
import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.data.SongCatalog.LookupChoice
import dev.repertosaurus.db.RepertosaurusDatabase
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * scorecards SC19 through [SongDetailRead.of] over a real database: the timed history's order (F45 N3,
 * N4) and that a voided timed event is out of both its list and its total (F45 N5).
 */
class SongDetailTimedTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: RepertosaurusRepository
    private val clock = TestClock("2026-09-20T09:00:00.000Z")

    /** Ids handed out next, in order; random once empty. */
    private val nextIds = ArrayDeque<String>()

    private lateinit var guitar: String
    private lateinit var vocal: String
    private lateinit var jolene: String

    @BeforeTest
    fun open() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        RepertosaurusDatabase.Schema.create(driver)
        repository = RepertosaurusRepository(RepertosaurusDatabase(driver), "this-phone", clock, TimeZone.UTC) {
            nextIds.removeFirstOrNull() ?: Ids.random()
        }
        guitar = repository.lookups.add(LookupTableKey.INSTRUMENT, "guitar")
        vocal = Ids.derived("instrument", "vocal")
        jolene = repository.catalog.addSong("Jolene", LookupChoice.Typed("Dolly Parton")).song.songId
    }

    @AfterTest
    fun close() {
        driver.close()
    }

    private fun at(iso: String) {
        clock.instant = Instant.parse(iso)
    }

    private fun log(id: String, loggedOn: String, seconds: Long?, instrumentId: String = guitar): String {
        nextIds.addLast(id)
        return repository.logPractice(jolene, instrumentId, loggedOn = loggedOn, durationSeconds = seconds)
    }

    private fun timed(): TimedHistory? = SongDetailRead.of(repository, jolene).timed

    /**
     * Inserted oldest-first and out of order. By hand, newest first:
     * - 20 Sep: t20 (600 s);
     * - 19 Sep: t19-late (created 11:00) before t19-early (created 10:00), by `created_at DESC`;
     *   then t19-a and t19-b, created at the same instant, by id, although t19-b was written first;
     * - 18 Sep: t18 (60 s). The untimed tap on the 19th is not listed.
     */
    @Test
    fun theTimedHistoryIsNewestFirstWithDeterministicTies() {
        at("2026-09-20T08:00:00.000Z")
        log("t18", "2026-09-18", 60L)
        log("t20", "2026-09-20", 600L)
        at("2026-09-20T10:00:00.000Z")
        log("t19-early", "2026-09-19", 120L)
        at("2026-09-20T11:00:00.000Z")
        log("t19-late", "2026-09-19", 180L)
        log("tap", "2026-09-19", null)
        at("2026-09-20T09:00:00.000Z")
        log("t19-b", "2026-09-19", 240L, vocal)
        log("t19-a", "2026-09-19", 300L, vocal)

        val history = timed()!!

        assertEquals(listOf("t20", "t19-late", "t19-early", "t19-a", "t19-b", "t18"), history.events.map { it.id })
        assertEquals(600L + 180L + 120L + 300L + 240L + 60L, history.totalSeconds)
    }

    /** By hand: 1 500 + 300 live = 1 800 s; the voided 900 s is in neither the list nor the total. */
    @Test
    fun aVoidedTimedEventIsOutOfTheListAndTheTotal() {
        log("live-1", "2026-09-12", 1_500L)
        val voided = log("gone", "2026-09-14", 900L)
        log("live-2", "2026-09-10", 300L)
        log("tap", "2026-09-13", null)
        repository.voidPractice(voided)

        val history = timed()!!

        assertEquals(listOf("live-1", "live-2"), history.events.map { it.id })
        assertEquals(1_800L, history.totalSeconds)
    }

    /** F45 N5 with N1: voiding every timed event leaves no timed history, not a zero one. */
    @Test
    fun voidingEveryTimedEventLeavesNoTimedHistory() {
        val first = log("a", "2026-09-12", 1_500L)
        log("tap", "2026-09-13", null)
        val second = log("b", "2026-09-11", 600L)
        repository.voidPractice(first)
        repository.voidPractice(second)

        assertNull(timed())
        assertEquals(1L, SongDetailRead.of(repository, jolene).practice.single().timesPractised)
    }
}
