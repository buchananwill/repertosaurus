package dev.repertosaurus.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.repertosaurus.TestClock
import dev.repertosaurus.core.Ids
import dev.repertosaurus.data.SongCatalog.LookupChoice
import dev.repertosaurus.db.RepertosaurusDatabase
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** `suggestion_skip` through [SuggestionSkips]: the M14 boundary (schema-3 M12-M14). */
class SuggestionSkipsTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: RepertosaurusRepository
    private val clock = TestClock("2026-09-20T09:00:00.000Z")

    private val vocal = Ids.derived("instrument", "vocal")
    private lateinit var guitar: String
    private lateinit var song: String
    private lateinit var part: Part

    private val skips: SuggestionSkips get() = repository.skips

    @BeforeTest
    fun open() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        RepertosaurusDatabase.Schema.create(driver)
        repository = RepertosaurusRepository(RepertosaurusDatabase(driver), "this-phone", clock, TimeZone.UTC)
        guitar = repository.lookups.add(LookupTableKey.INSTRUMENT, "guitar")
        val will = repository.lookups.add(LookupTableKey.PERFORMER, "Will")
        song = repository.catalog.addSong("Jolene", LookupChoice.Typed("Dolly Parton")).song.songId
        part = Part(song, will, vocal)
    }

    @AfterTest
    fun close() {
        driver.close()
    }

    private fun at(iso: String) {
        clock.instant = Instant.parse(iso)
    }

    /** M14: a part never practised on the instrument counts every skip it has. */
    @Test
    fun aNeverPractisedPartCountsEverySkip() {
        at("2026-09-20T09:00:00.000Z")
        skips.recordSkip(part)
        at("2026-09-20T09:05:00.000Z")
        skips.recordSkip(part)
        repository.logPractice(song, guitar)

        assertEquals(2L, skips.skipsSinceLastPractised(part), "a guitar log is not a vocal one")
    }

    /** **M14: `created_at`, not `logged_on`.** A skip at 09:00 and a log at 18:00 the same day reset. */
    @Test
    fun aSameDaySkipThenLogResetsTheCount() {
        at("2026-09-20T09:00:00.000Z")
        skips.recordSkip(part)
        at("2026-09-20T18:00:00.000Z")
        repository.logPractice(song, vocal, loggedOn = "2026-09-20")

        assertEquals(0L, skips.skipsSinceLastPractised(part))

        at("2026-09-20T19:00:00.000Z")
        skips.recordSkip(part)
        assertEquals(1L, skips.skipsSinceLastPractised(part), "a skip after the log counts")
    }

    /** M14: a back-dated log resets the count too, because its `created_at` is now. */
    @Test
    fun aBackDatedLogResetsTheCount() {
        at("2026-09-20T09:00:00.000Z")
        skips.recordSkip(part)
        at("2026-09-20T10:00:00.000Z")
        repository.logPractice(song, vocal, loggedOn = "2026-09-01")

        assertEquals(0L, skips.skipsSinceLastPractised(part))
    }

    /** **M14: a voided log does not reset the count**; the boundary falls back to the newest live event. */
    @Test
    fun aVoidedLogDoesNotResetTheCount() {
        at("2026-09-20T08:00:00.000Z")
        repository.logPractice(song, vocal)
        at("2026-09-20T09:00:00.000Z")
        skips.recordSkip(part)
        at("2026-09-20T10:00:00.000Z")
        repository.voidPractice(repository.logPractice(song, vocal))

        assertEquals(1L, skips.skipsSinceLastPractised(part))
    }
}
