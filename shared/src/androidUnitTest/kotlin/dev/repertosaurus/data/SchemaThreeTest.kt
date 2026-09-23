package dev.repertosaurus.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.repertosaurus.TestClock
import dev.repertosaurus.core.Ids
import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.data.SongCatalog.LookupChoice
import dev.repertosaurus.db.RepertosaurusDatabase
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Schema 3's widened CHECKs and new column** (schema-3 M9, M10, M15, M16). Ratings are in
 * `PartRatingStoreTest`, skips in `SuggestionSkipsTest`, the 2 -> 3 upgrade in
 * `SchemaCompatibilityTest`.
 */
class SchemaThreeTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: RepertosaurusRepository

    private val vocal = Ids.derived("instrument", "vocal")
    private lateinit var song: String

    @BeforeTest
    fun open() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        RepertosaurusDatabase.Schema.create(driver)
        repository = RepertosaurusRepository(
            RepertosaurusDatabase(driver), DEVICE, TestClock("2026-09-20T09:00:00.000Z"), TimeZone.UTC,
        )
        song = repository.catalog.addSong("Jolene", LookupChoice.Typed("Dolly Parton")).song.songId
    }

    @AfterTest
    fun close() {
        driver.close()
    }

    /**
     * M9: feel 0 is "not at all" and is accepted; 4 is refused by the CHECK. The repository takes a
     * `RatingLevel`, so it cannot be handed a 4 at all.
     */
    @Test
    fun feelZeroIsAcceptedAndFourIsRejected() {
        val id = repository.logPractice(song, vocal, feel = RatingLevel.NOT_AT_ALL)
        assertEquals(0L, repository.practiceHistory(song).single { it.id == id }.feel)

        assertCheckFails { insertEvent(feel = "4", duration = "NULL") }
    }

    /** rating-scale RS1, RS3: each level is stored as its value, and nothing else is written. */
    @Test
    fun everyFeelLevelIsStoredAsItsValue() {
        val ids = RatingLevel.entries.associateWith { repository.logPractice(song, vocal, feel = it) }
        val history = repository.practiceHistory(song).associateBy { it.id }
        assertEquals(
            mapOf(RatingLevel.NOT_AT_ALL to 0L, RatingLevel.SOMEWHAT to 1L, RatingLevel.CERTAINLY to 2L, RatingLevel.EXCEPTIONALLY to 3L),
            ids.mapValues { (_, id) -> history.getValue(id).feel },
        )
    }

    /** M10: zero is not a duration; 1 and a day are; beyond a day is refused. Null is a plain tap. */
    @Test
    fun durationSecondsZeroIsRejected() {
        assertFailsWith<IllegalArgumentException> { repository.logPractice(song, vocal, durationSeconds = 0L) }
        assertCheckFails { insertEvent(feel = "NULL", duration = "0") }
        assertCheckFails { insertEvent(feel = "NULL", duration = "86401") }

        val timed = repository.logPractice(song, vocal, durationSeconds = 86_400L)
        val tapped = repository.logPractice(song, vocal)
        val history = repository.practiceHistory(song).associateBy { it.id }
        assertEquals(86_400L, history.getValue(timed).durationSeconds)
        assertNull(history.getValue(tapped).durationSeconds)
    }

    /** M15, M16: the CHECK admits exactly the six names, and a seventh is refused. */
    @Test
    fun theSixSortNamesAreAdmittedAndASeventhIsRejected() {
        val names = listOf(
            "COLDEST_FIRST", "HOTTEST_FIRST",
            "TRIAGE_PRIORITY", "TRIAGE_PRIORITY_REVERSED",
            "TRIAGE_CONFIDENCE", "TRIAGE_CONFIDENCE_REVERSED",
        )
        for (name in names) insertView(name)
        assertEquals(names.sorted(), repository.savedViews().map { it.sortOrder }.sorted())

        assertCheckFails { insertView("TRIAGE_URGENCY") }
        assertEquals(6L, driver.long("SELECT count(*) FROM saved_view"))
    }

    private fun insertView(sortOrder: String) {
        repository.createSavedView(
            name = sortOrder,
            filterPerformerId = null,
            filterInstrumentId = null,
            filterLeadOnly = 0L,
            practiceInstrumentId = vocal,
            sortOrder = sortOrder,
        )
    }

    private fun insertEvent(feel: String, duration: String) {
        driver.execute(
            null,
            """
            INSERT INTO practice_event(id, song_id, logged_on, instrument_id, context_id, feel, note,
                                       duration_seconds, created_at, device_id)
            VALUES ('${Ids.random()}', '$song', '2026-09-20', '$vocal', NULL, $feel, NULL,
                    $duration, '2026-09-20T09:00:00.000Z', '$DEVICE')
            """.trimIndent(),
            0,
        )
    }

    private fun assertCheckFails(block: () -> Unit) {
        val failure = assertFailsWith<Exception> { block() }
        assertTrue("CHECK" in failure.message.orEmpty(), failure.message)
    }

    private companion object {
        const val DEVICE: String = "this-phone"
    }
}
