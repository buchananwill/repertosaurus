package dev.repertosaurus.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.repertosaurus.core.Ids
import dev.repertosaurus.data.LookupTableKey
import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.db.RepertosaurusDatabase
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Editing E12 / views V11a: the View eligibility filter excludes soft-deleted performers
 * and instruments.**
 *
 * The `EXISTS` over `song_performer` checked only `song_performer.deleted_at`, which was
 * harmless while **nothing in the app could delete a performer.** E13 puts the performer
 * roster in the drawer, so deletion is now reachable: without the amendment a View keeps
 * filtering on a performer the user removed and its summary renders "a deleted performer".
 *
 * The amendment must not move the unfiltered result, and it does not: measured against the
 * real 479-song database, the motivating View — filter `(Will, vocal, leadOnly = 1)`,
 * practice instrument `guitar` — returns 231 songs, 138 of them never practised on guitar,
 * before and after. Nothing in that data is soft-deleted, which is exactly why the count is
 * the right regression guard.
 */
class ViewEligibilityTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-08-17T10:30:00.250Z")
    }

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: RepertosaurusDatabase
    private lateinit var repository: RepertosaurusRepository
    private lateinit var capabilities: CapabilityCoordinator

    private val vocal = Ids.derived("instrument", "vocal")
    private val guitar = Ids.derived("instrument", "guitar")
    private val will = Ids.derived("performer", "Will")
    private val coralie = Ids.derived("performer", "Coralie")

    private lateinit var valerie: String
    private lateinit var dakota: String
    private lateinit var jolene: String

    @BeforeTest
    fun open() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        RepertosaurusDatabase.Schema.create(driver)
        database = RepertosaurusDatabase(driver)
        repository = RepertosaurusRepository(database, "test-device", fixedClock, TimeZone.UTC)
        capabilities = CapabilityCoordinator(repository)

        valerie = song("Valerie", "The Zutons")
        dakota = song("Dakota", "Stereophonics")
        jolene = song("Jolene", "Dolly Parton")

        lead(valerie, "Will", "vocal")
        lead(dakota, "Coralie", "vocal")
        lead(jolene, "Will", "vocal")
        // jolene also carries a Coralie backing-vocal row, so removing Coralie must not take
        // a song out of a *Will* View by accident.
        capabilities.add(jolene, "Coralie", "backing vocal")
    }

    @AfterTest
    fun close() {
        driver.close()
    }

    /** The baseline the amendment must not move: nothing is deleted, so nothing changes. */
    @Test
    fun withNothingDeletedTheFilterReturnsWhatItAlwaysDid() {
        assertEquals(listOf("Jolene", "Valerie"), titles(performerId = will, leadOnly = 1L))
        assertEquals(listOf("Dakota"), titles(performerId = coralie, leadOnly = 1L))
        assertEquals(3, titles().size, "V11: all-null is every song")
    }

    /**
     * **E12.** Removing a performer takes their songs out of a View filtered on them. Before
     * the amendment the `EXISTS` still matched, the View kept returning their repertoire, and
     * the switcher rendered its summary as "a deleted performer".
     */
    @Test
    fun aSoftDeletedPerformerDropsOutOfViewEligibility() {
        assertEquals(listOf("Jolene", "Valerie"), titles(performerId = will, leadOnly = 1L))

        LookupStores.of(LookupKind.PERFORMER, repository).remove(will)

        assertTrue(
            titles(performerId = will, leadOnly = 1L).isEmpty(),
            "E12: a removed performer's capability rows no longer make a song eligible",
        )
        // The rows are still there — E22 — so this is the filter excluding them, not a delete.
        assertEquals(2L, repository.lookups.usage(LookupTableKey.PERFORMER, will))
        // And a View on somebody else is untouched.
        assertEquals(listOf("Dakota"), titles(performerId = coralie, leadOnly = 1L))
    }

    /** The instrument half of V11a, which the same `EXISTS` also has to honour. */
    @Test
    fun aSoftDeletedInstrumentDropsOutOfViewEligibility() {
        assertEquals(
            listOf("Jolene"),
            titles(performerId = coralie, instrumentId = backingVocal()),
        )

        repository.lookups.remove(LookupTableKey.INSTRUMENT, backingVocal())

        assertTrue(
            titles(performerId = coralie, instrumentId = backingVocal()).isEmpty(),
            "V11a: a removed instrument's capability rows no longer make a song eligible",
        )
        // Coralie's vocal row on Dakota is on a live instrument and survives.
        assertEquals(listOf("Dakota"), titles(performerId = coralie))
    }

    /**
     * The trap in a filter written as a join: an unfiltered View must still return every live
     * song, including the 147-of-479 that carry no capability row at all (V9, V11, V21).
     * Removing a performer must not quietly shrink the whole repertoire.
     */
    @Test
    fun removingAPerformerDoesNotShrinkAnUnfilteredView() {
        val unannotated = song("Wonderwall", "Oasis")
        assertEquals(4, titles().size)

        LookupStores.of(LookupKind.PERFORMER, repository).remove(will)

        assertEquals(4, titles().size, "V21: no filter means every live song, always")
        assertTrue(titles().contains("Wonderwall"))
        assertEquals(unannotated, repository.songsByStaleness(
            practiceInstrumentId = guitar,
            filterPerformerId = null,
            filterInstrumentId = null,
            leadOnly = 0L,
            today = "2026-08-17",
        ).single { it.title == "Wonderwall" }.songId)
    }

    /**
     * A performer filter on a *different* person must not be widened by the join either: the
     * amendment adds two `deleted_at` tests and nothing else.
     */
    @Test
    fun theAmendmentDoesNotWidenAnyOtherCombination() {
        assertEquals(listOf("Dakota", "Jolene"), titles(performerId = coralie))
        assertEquals(listOf("Jolene", "Valerie"), titles(performerId = will))
        assertEquals(
            listOf("Dakota", "Jolene", "Valerie"),
            titles(instrumentId = vocal).sorted(),
        )
        assertEquals(listOf("Dakota", "Jolene", "Valerie"), titles(leadOnly = 1L).sorted())
    }

    // ---- Fixtures ---------------------------------------------------------------------------

    private fun backingVocal(): String = Ids.derived("instrument", "backing vocal")

    private fun titles(
        performerId: String? = null,
        instrumentId: String? = null,
        leadOnly: Long = 0L,
    ): List<String> = repository.songsByStaleness(
        practiceInstrumentId = guitar,
        filterPerformerId = performerId,
        filterInstrumentId = instrumentId,
        leadOnly = leadOnly,
        today = "2026-08-17",
    ).map { it.title }

    private fun song(title: String, artist: String): String =
        repository.createSong(title, repository.findOrCreateArtist(artist))

    private fun lead(songId: String, performer: String, instrument: String) {
        val id = capabilities.add(songId, performer, instrument)
        capabilities.update(
            capabilities.capabilities(songId).single { it.id == id }.copy(isLead = true),
        )
    }
}
