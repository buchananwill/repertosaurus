package dev.repertosaurus.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.repertosaurus.TestClock
import dev.repertosaurus.core.Ids
import dev.repertosaurus.data.SongCatalog.LookupChoice
import dev.repertosaurus.db.RepertosaurusDatabase
import dev.repertosaurus.habit.DayTally
import dev.repertosaurus.habit.PeriodTotal
import dev.repertosaurus.session.Messages
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `practice_event.sq :: countLiveByDay` through [PracticeDays] (scorecards SC1-SC4, SC13). **Every
 * fixture here is inserted out of date order**, so an answer that happens to come back in insertion
 * order cannot pass for a sorted one.
 */
class PracticeDaysTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: RepertosaurusRepository
    private val clock = TestClock("2026-09-20T09:00:00.000Z")

    private lateinit var guitar: String
    private lateinit var vocal: String
    private lateinit var jolene: String
    private lateinit var valerie: String

    private val days: PracticeDays get() = repository.habit

    @BeforeTest
    fun open() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        RepertosaurusDatabase.Schema.create(driver)
        repository = RepertosaurusRepository(RepertosaurusDatabase(driver), "this-phone", clock, TimeZone.UTC)
        guitar = repository.lookups.add(LookupTableKey.INSTRUMENT, "guitar")
        // Seeded by the schema, as SuggestionSkipsTest relies on.
        vocal = Ids.derived("instrument", "vocal")
        jolene = repository.catalog.addSong("Jolene", LookupChoice.Typed("Dolly Parton")).song.songId
        valerie = repository.catalog.addSong("Valerie", LookupChoice.Typed("The Zutons")).song.songId
    }

    @AfterTest
    fun close() {
        driver.close()
    }

    private fun all(): Map<String, Long> = counts(null)

    /** The events per day, for the SC1-SC4 tests that are about counting. */
    private fun counts(instrumentId: String?): Map<String, Long> =
        days.liveCountsByDay(instrumentId).mapValues { it.value.events }

    /**
     * **SC18: a voided timed event contributes neither count nor minutes.** Inserted out of date order.
     * By hand:
     * - 12 Sep: live 1 500 s and 1 020 s, plus an untimed tap; voided 900 s. So 3 events, 2 520 s.
     * - 5 Sep: two untimed taps; a voided 600 s. So 2 events, and NULL, never 0.
     * - 9 Sep: only a voided 1 200 s, so the day is absent.
     * - 1 Sep: one live 45 s on vocal. So 1 event, 45 s.
     */
    @Test
    fun aVoidedTimedEventContributesNeitherCountNorMinutes() {
        repository.logPractice(jolene, guitar, loggedOn = "2026-09-12", durationSeconds = 1_500L)
        repository.voidPractice(repository.logPractice(valerie, guitar, loggedOn = "2026-09-05", durationSeconds = 600L))
        repository.logPractice(valerie, vocal, loggedOn = "2026-09-01", durationSeconds = 45L)
        repository.voidPractice(repository.logPractice(jolene, guitar, loggedOn = "2026-09-12", durationSeconds = 900L))
        repository.logPractice(valerie, guitar, loggedOn = "2026-09-05")
        repository.voidPractice(repository.logPractice(jolene, vocal, loggedOn = "2026-09-09", durationSeconds = 1_200L))
        repository.logPractice(jolene, vocal, loggedOn = "2026-09-12")
        repository.logPractice(jolene, guitar, loggedOn = "2026-09-05")
        repository.logPractice(valerie, vocal, loggedOn = "2026-09-12", durationSeconds = 1_020L)

        val tallies = days.liveCountsByDay(instrumentId = null)

        assertEquals(listOf("2026-09-01", "2026-09-05", "2026-09-12"), tallies.keys.toList())
        assertEquals(
            mapOf(
                "2026-09-01" to DayTally(1L, 45L),
                "2026-09-05" to DayTally(2L, null),
                "2026-09-12" to DayTally(3L, 2_520L),
            ),
            tallies,
        )
        assertNull(tallies.getValue("2026-09-05").timedSeconds, "an untimed day is null, never 0")

        // The instrument filter applies to minutes too: guitar on the 12th is 1 500 s alone.
        assertEquals(
            mapOf("2026-09-05" to DayTally(2L, null), "2026-09-12" to DayTally(1L, 1_500L)),
            days.liveCountsByDay(guitar),
        )
    }

    /** SC17 through the card: the clock's today is Sunday 20 September, so this week is 14-20. */
    @Test
    fun theCardCarriesMinutesOnlyWhereTimed() {
        repository.logPractice(jolene, guitar, loggedOn = "2026-09-18", durationSeconds = 4_200L)
        repository.logPractice(jolene, guitar, loggedOn = "2026-09-16")
        repository.logPractice(valerie, guitar, loggedOn = "2026-09-18")

        val card = days.card(null)

        assertEquals(PeriodTotal(days = 2, events = 3L, timedSeconds = 4_200L), card.thisWeek)
        assertEquals("Fri 18 Sep: 2 songs · 1 h 10 min timed", Messages.habitDay(card.day("2026-09-18")!!))
        assertEquals("Wed 16 Sep: 1 song", Messages.habitDay(card.day("2026-09-16")!!))
    }

    /** SC13: one row per day with events, counted, and in date order whatever the insertion order. */
    @Test
    fun daysAreCountedAndOrderedByDate() {
        repository.logPractice(jolene, guitar, loggedOn = "2026-09-18")
        repository.logPractice(jolene, guitar, loggedOn = "2026-09-02")
        repository.logPractice(valerie, vocal, loggedOn = "2026-09-18")
        repository.logPractice(jolene, guitar, loggedOn = "2026-09-11")
        repository.logPractice(jolene, guitar, loggedOn = "2026-09-18")

        val counts = all()

        assertEquals(listOf("2026-09-02", "2026-09-11", "2026-09-18"), counts.keys.toList())
        assertEquals(mapOf("2026-09-02" to 1L, "2026-09-11" to 1L, "2026-09-18" to 3L), counts)
    }

    /** Decision 8: a voided event never counts, and a day holding only voided events is absent. */
    @Test
    fun aVoidedEventIsExcluded() {
        repository.logPractice(jolene, guitar, loggedOn = "2026-09-15")
        val voided = repository.logPractice(jolene, guitar, loggedOn = "2026-09-15")
        repository.voidPractice(voided)
        repository.voidPractice(repository.logPractice(valerie, guitar, loggedOn = "2026-09-03"))
        repository.logPractice(valerie, guitar, loggedOn = "2026-09-09")

        assertEquals(mapOf("2026-09-09" to 1L, "2026-09-15" to 1L), all())
    }

    /** **SC3: an event on a soft-deleted song still counts** (unlike E39's usage count). */
    @Test
    fun anEventOnARemovedSongIsIncluded() {
        repository.logPractice(valerie, vocal, loggedOn = "2026-09-12")
        repository.logPractice(jolene, vocal, loggedOn = "2026-09-05")
        assertTrue(repository.catalog.removeSong(valerie))

        assertEquals(mapOf("2026-09-05" to 1L, "2026-09-12" to 1L), all())
    }

    /** SC2: a back-dated log counts on its `logged_on`, not on the day it was written. */
    @Test
    fun aBackDatedEventCountsOnItsLoggedOn() {
        clock.instant = Instant.parse("2026-09-20T18:00:00.000Z")
        repository.logPractice(jolene, guitar, loggedOn = "2026-09-20")
        repository.logPractice(jolene, guitar, loggedOn = "2026-09-01")

        assertEquals(mapOf("2026-09-01" to 1L, "2026-09-20" to 1L), all())
    }

    /** SC4: an instrument id counts only that instrument; null counts every instrument. */
    @Test
    fun theInstrumentFilter() {
        repository.logPractice(jolene, vocal, loggedOn = "2026-09-14")
        repository.logPractice(jolene, guitar, loggedOn = "2026-09-14")
        repository.logPractice(valerie, guitar, loggedOn = "2026-09-07")
        repository.logPractice(valerie, vocal, loggedOn = "2026-09-16")

        assertEquals(mapOf("2026-09-07" to 1L, "2026-09-14" to 1L), counts(guitar))
        assertEquals(mapOf("2026-09-14" to 1L, "2026-09-16" to 1L), counts(vocal))
        assertEquals(mapOf("2026-09-07" to 1L, "2026-09-14" to 2L, "2026-09-16" to 1L), all())
    }

    /**
     * F24 B12: one call builds the card, as of the clock's local today (2026-09-20 here), for the scope
     * asked. The read covers all history (F24 B5); a day after today is dropped by the card, not the SQL.
     */
    @Test
    fun theCardIsBuiltAsOfTodayForTheScope() {
        repository.logPractice(jolene, guitar, loggedOn = "2026-09-21")
        repository.logPractice(jolene, vocal, loggedOn = "2026-09-20")
        repository.logPractice(jolene, guitar, loggedOn = "2019-01-01")
        repository.logPractice(jolene, guitar, loggedOn = "2026-09-18")

        assertEquals(mapOf("2019-01-01" to 1L, "2026-09-18" to 1L, "2026-09-21" to 1L), counts(guitar))

        val guitarCard = days.card(guitar)
        assertEquals(guitar, guitarCard.instrumentId)
        assertEquals("2026-09-20", guitarCard.weeks.last().days.last().date, "today is the clock's")
        assertEquals(PeriodTotal(days = 1, events = 1L), guitarCard.thisWeek, "the 21st is after today")

        assertEquals(PeriodTotal(days = 2, events = 2L), days.card(null).thisWeek)
    }
}
