package dev.repertosaurus.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.repertosaurus.TestClock
import dev.repertosaurus.core.Ids
import dev.repertosaurus.core.RatingKind
import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.data.SongCatalog.LookupChoice
import dev.repertosaurus.db.RepertosaurusDatabase
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `part_rating` through [PartRatingStore] (schema-3 M3-M8). */
class PartRatingStoreTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: RepertosaurusRepository
    private val clock = TestClock("2026-09-20T09:00:00.000Z")

    private val vocal = Ids.derived("instrument", "vocal")
    private lateinit var guitar: String
    private lateinit var will: String
    private lateinit var song: String
    private lateinit var other: String

    private val ratings: PartRatingStore get() = repository.ratings

    @BeforeTest
    fun open() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        RepertosaurusDatabase.Schema.create(driver)
        repository = RepertosaurusRepository(RepertosaurusDatabase(driver), DEVICE, clock, TimeZone.UTC)
        guitar = repository.lookups.add(LookupTableKey.INSTRUMENT, "guitar")
        will = repository.lookups.add(LookupTableKey.PERFORMER, "Will")
        song = repository.catalog.addSong("Jolene", LookupChoice.Typed("Dolly Parton")).song.songId
        other = repository.catalog.addSong("9 to 5", LookupChoice.Typed("Dolly Parton")).song.songId
    }

    @AfterTest
    fun close() {
        driver.close()
    }

    private fun at(iso: String) {
        clock.instant = Instant.parse(iso)
    }

    /**
     * **M7 against a pre-existing tombstone**, written under an id that is not the derived one:
     * setting the rating revives that row in place — level set, `deleted_at` cleared, `updated_at`
     * bumped, id kept — and never inserts a second row.
     */
    @Test
    fun settingARatingRevivesAPreExistingTombstoneInPlace() {
        driver.execute(
            null,
            """
            INSERT INTO part_rating(id, song_id, performer_id, instrument_id, kind, level,
                                    updated_at, deleted_at, device_id)
            VALUES ('tombstone-row', '$song', '$will', '$vocal', 'PRIORITY', 1,
                    '2026-09-01T00:00:00.000Z', '2026-09-01T00:00:00.000Z', 'other-device')
            """.trimIndent(),
            0,
        )
        at("2026-09-20T10:00:00.000Z")

        assertTrue(ratings.setRating(Part(song, will, vocal), RatingKind.PRIORITY, RatingLevel.EXCEPTIONALLY))

        assertEquals(listOf(listOf("tombstone-row", "3", null, "2026-09-20T10:00:00.000Z", DEVICE)), ratingRows())
        assertEquals(RatingLevel.EXCEPTIONALLY, ratings.ratingsFor(will, vocal).getValue(song).priority)
    }

    /** M7: set, clear to a tombstone, set again — one row, one id. */
    @Test
    fun clearingTombstonesAndSettingAgainRevivesTheSameRow() {
        val part = Part(song, will, vocal)
        at("2026-09-20T10:00:00.000Z")
        ratings.setRating(part, RatingKind.CONFIDENCE, RatingLevel.SOMEWHAT)
        at("2026-09-20T11:00:00.000Z")
        assertTrue(ratings.setRating(part, RatingKind.CONFIDENCE, null))

        assertEquals(emptyMap(), ratings.ratingsFor(will, vocal), "a tombstone reads as unrated")
        assertEquals(
            listOf(Ids.partRating(song, will, vocal, RatingKind.CONFIDENCE), "1", "2026-09-20T11:00:00.000Z"),
            ratingRows().single().take(3),
            "cleared is a tombstone with its level kept, never a null level",
        )
        assertFalse(ratings.setRating(part, RatingKind.CONFIDENCE, null), "not re-stamped")

        at("2026-09-20T12:00:00.000Z")
        ratings.setRating(part, RatingKind.CONFIDENCE, RatingLevel.NOT_AT_ALL)

        assertEquals(
            listOf(Ids.partRating(song, will, vocal, RatingKind.CONFIDENCE), "0", null, "2026-09-20T12:00:00.000Z"),
            ratingRows().single().take(4),
        )
    }

    /** M4: priority and confidence are two rows, and setting one never touches the other. */
    @Test
    fun priorityAndConfidenceAreSeparateRows() {
        val part = Part(song, will, vocal)
        ratings.setRating(part, RatingKind.PRIORITY, RatingLevel.CERTAINLY)
        ratings.setRating(part, RatingKind.CONFIDENCE, RatingLevel.SOMEWHAT)
        ratings.setRating(part, RatingKind.PRIORITY, RatingLevel.EXCEPTIONALLY)

        assertEquals(2, ratingRows().size)
        assertEquals(
            PartRatings(priority = RatingLevel.EXCEPTIONALLY, confidence = RatingLevel.SOMEWHAT),
            ratings.ratingsFor(will, vocal).getValue(song),
        )
    }

    /** M8: keyed on the triple, with no capability row at all; the bulk read answers every part. */
    @Test
    fun ratingsAreReadByTheTripleAndInBulk() {
        val onVocal = Part(song, will, vocal)
        val onGuitar = Part(other, will, guitar)
        val unrated = Part(other, will, vocal)
        ratings.setRating(onVocal, RatingKind.PRIORITY, RatingLevel.CERTAINLY)
        ratings.setRating(onGuitar, RatingKind.CONFIDENCE, RatingLevel.NOT_AT_ALL)
        assertEquals(emptyList(), repository.songPerformers(song), "no capability row exists")

        assertEquals(
            mapOf(
                onVocal to PartRatings(RatingLevel.CERTAINLY, null),
                onGuitar to PartRatings(null, RatingLevel.NOT_AT_ALL),
                unrated to PartRatings.UNRATED,
            ),
            ratings.ratingsFor(listOf(onVocal, onGuitar, unrated)),
        )
    }

    /**
     * **B4 / S8: a row the decoder cannot read is skipped, never thrown on.** A `kind` this build
     * does not know, inserted with the CHECK bypassed, reads as unrated while its neighbour reads.
     */
    @Test
    fun anUndecodableRowIsSkippedByTheRead() {
        ratings.setRating(Part(song, will, vocal), RatingKind.PRIORITY, RatingLevel.CERTAINLY)
        insertUnknownKind(other)

        assertEquals(
            mapOf(song to PartRatings(RatingLevel.CERTAINLY, null)),
            ratings.ratingsFor(will, vocal),
        )
        assertEquals(PartRatings.UNRATED, ratings.ratingsFor(listOf(Part(other, will, vocal))).values.single())
    }

    private fun insertUnknownKind(songId: String) {
        driver.insertRatingOfUnknownKind(songId, will, vocal)
    }

    /** `(id, level, deleted_at, updated_at, device_id)` of every `part_rating` row. */
    private fun ratingRows(): List<List<String?>> =
        driver.rows("SELECT id, level, deleted_at, updated_at, device_id FROM part_rating ORDER BY kind", 5)

    private companion object {
        const val DEVICE: String = "this-phone"
    }
}
