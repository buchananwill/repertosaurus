package dev.repertaurus.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.repertaurus.core.Ids
import dev.repertaurus.db.RepertaurusDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decisions 58, 58a-58d: who performs a set list item is a junction table, not a column.
 *
 * Runs against a real SQLite database — Android unit tests run on the host JVM, so the
 * generated schema and every query are exercised without a device.
 */
class SetlistItemPerformerTest {

    private val now = "2026-08-15T10:30:00.250Z"
    private val device = "test-device"

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: RepertaurusDatabase

    private val will = Ids.derived("performer", "Will")
    private val coralie = Ids.derived("performer", "Coralie")
    private val kendra = Ids.derived("performer", "Kendra")

    private lateinit var setlistId: String
    private lateinit var setId: String
    private lateinit var valerie: String
    private lateinit var dakota: String

    @BeforeTest
    fun open() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        RepertaurusDatabase.Schema.create(driver)
        database = RepertaurusDatabase(driver)

        for ((id, name) in listOf(will to "Will", coralie to "Coralie", kendra to "Kendra")) {
            database.performerQueries.insert(
                id = id,
                name = name,
                notes = null,
                updated_at = now,
                deleted_at = null,
                device_id = device,
            )
        }

        setlistId = "setlist-1"
        database.setlistQueries.insert(
            id = setlistId,
            name = "Saturday",
            performed_on = "2026-09-05",
            band_id = null,
            venue_id = null,
            client = null,
            notes = null,
            updated_at = now,
            deleted_at = null,
            device_id = device,
        )

        setId = "set-1"
        database.setlist_setQueries.insert(
            id = setId,
            setlist_id = setlistId,
            set_no = 1,
            target_minutes = 60,
            updated_at = now,
            deleted_at = null,
            device_id = device,
        )

        valerie = insertItem("Valerie", "The Zutons", position = "a")
        dakota = insertItem("Dakota", "Stereophonics", position = "b")
    }

    @AfterTest
    fun close() {
        driver.close()
    }

    // ---- Ordering ---------------------------------------------------------------------

    /** Decision 58: position orders them — 1 is the lead, 2 the co-lead. */
    @Test
    fun aSongStagedWithTwoPerformersReturnsBothInPositionOrder() {
        // Inserted back to front, so a passing result cannot be insertion order.
        stage(valerie, coralie, position = 2)
        stage(valerie, will, position = 1)

        val staged = database.setlist_item_performerQueries.selectForItem(valerie).executeAsList()
        assertEquals(listOf("Will", "Coralie"), staged.map { it.performer_name })
        assertEquals(listOf(1L, 2L), staged.map { it.position })
    }

    /** The print view reads the whole set list in one pass, not one query per item. */
    @Test
    fun theWholeSetlistComesBackInOnePassGroupedByItem() {
        stage(valerie, will, position = 1)
        stage(valerie, coralie, position = 2)
        stage(dakota, kendra, position = 1)

        val rows = database.setlist_item_performerQueries
            .selectForSetlist(setlistId).executeAsList()

        assertEquals(3, rows.size)
        val byItem = rows.groupBy({ it.setlist_item_id }, { it.performer_name })
        assertEquals(listOf("Will", "Coralie"), byItem.getValue(valerie))
        assertEquals(listOf("Kendra"), byItem.getValue(dakota))
    }

    // ---- The derived id (decisions 3, 4, 4d, 58) ---------------------------------------

    /**
     * The id keys on `(setlist_item_id, performer_id)` and nothing else. Moving someone
     * from lead to co-lead updates one row; it must not mint a second, or two devices see
     * the same person staged twice.
     */
    @Test
    fun theDerivedIdIsStableAcrossAPositionChangeAndDiffersPerPerformer() {
        val willOnValerie = Ids.setlistItemPerformer(valerie, will)
        val coralieOnValerie = Ids.setlistItemPerformer(valerie, coralie)
        val willOnDakota = Ids.setlistItemPerformer(dakota, will)

        assertNotEquals(willOnValerie, coralieOnValerie, "different performer, different row")
        assertNotEquals(willOnValerie, willOnDakota, "different item, different row")

        stage(valerie, will, position = 1)
        database.setlist_item_performerQueries.setPosition(
            position = 2,
            updated_at = now,
            device_id = device,
            id = willOnValerie,
        )

        // Re-deriving after the move finds the same row, and there is still only one.
        assertEquals(willOnValerie, Ids.setlistItemPerformer(valerie, will))
        val staged = database.setlist_item_performerQueries.selectForItem(valerie).executeAsList()
        assertEquals(1, staged.size)
        assertEquals(willOnValerie, staged.single().id)
        assertEquals(2L, staged.single().position)
    }

    /** Two devices staging the same performer on the same item converge on one row. */
    @Test
    fun stagingTheSamePerformerTwiceIsOneRow() {
        stage(valerie, will, position = 1)
        database.setlist_item_performerQueries.applyMerged(
            id = Ids.setlistItemPerformer(valerie, will),
            setlist_item_id = valerie,
            performer_id = will,
            position = 1,
            updated_at = "2026-08-15T11:00:00.000Z",
            deleted_at = null,
            device_id = "other-device",
        )
        assertEquals(
            1,
            database.setlist_item_performerQueries.selectForItem(valerie).executeAsList().size,
        )
    }

    // ---- The aggregate (decision 58c) ---------------------------------------------------

    @Test
    fun theAggregateNamesPerformersInPositionOrder() {
        stage(valerie, coralie, position = 2)
        stage(valerie, will, position = 1)

        val row = database.setlist_item_performerQueries
            .selectStagedForSetlist(setlistId).executeAsList().single { it.setlist_item_id == valerie }
        assertEquals("Will, Coralie", row.staged_performers)

        // Swap the two positions: the string must follow the positions, not the ids.
        database.setlist_item_performerQueries.setPosition(
            position = 1,
            updated_at = now,
            device_id = device,
            id = Ids.setlistItemPerformer(valerie, coralie),
        )
        database.setlist_item_performerQueries.setPosition(
            position = 2,
            updated_at = now,
            device_id = device,
            id = Ids.setlistItemPerformer(valerie, will),
        )

        val swapped = database.setlist_item_performerQueries
            .selectStagedForSetlist(setlistId).executeAsList().single { it.setlist_item_id == valerie }
        assertEquals("Coralie, Will", swapped.staged_performers)
    }

    /**
     * The bug class this guards is the one the void anti-join had: a join that filters the
     * parent away. An item with nobody staged must keep its place in the set list.
     */
    @Test
    fun anItemWithNoPerformersIsNotLostFromTheSetlist() {
        stage(valerie, will, position = 1)
        // dakota is deliberately left unstaged.

        val staged = database.setlist_item_performerQueries
            .selectStagedForSetlist(setlistId).executeAsList()
        assertEquals(listOf(valerie, dakota), staged.map { it.setlist_item_id })
        assertEquals("Will", staged[0].staged_performers)
        assertNull(staged[1].staged_performers, "an unstaged item reads as nobody, not as absent")

        // And the set list query itself is unaffected by the junction.
        val items = database.setlist_itemQueries.selectBySetlist(setlistId).executeAsList()
        assertEquals(listOf(valerie, dakota), items.map { it.setlist_item_id })
    }

    /** A soft-deleted staging drops out of every read, and the item survives it. */
    @Test
    fun softDeletingAStagingLeavesTheItemInPlace() {
        stage(valerie, will, position = 1)
        database.setlist_item_performerQueries.softDelete(
            deleted_at = now,
            updated_at = now,
            device_id = device,
            id = Ids.setlistItemPerformer(valerie, will),
        )

        assertTrue(
            database.setlist_item_performerQueries.selectForItem(valerie).executeAsList().isEmpty(),
        )
        val staged = database.setlist_item_performerQueries
            .selectStagedForSetlist(setlistId).executeAsList()
        assertEquals(2, staged.size)
        assertNull(staged.single { it.setlist_item_id == valerie }.staged_performers)
    }

    // ---- Fixtures -----------------------------------------------------------------------

    private fun stage(itemId: String, performerId: String, position: Long) {
        database.setlist_item_performerQueries.insert(
            id = Ids.setlistItemPerformer(itemId, performerId),
            setlist_item_id = itemId,
            performer_id = performerId,
            position = position,
            updated_at = now,
            deleted_at = null,
            device_id = device,
        )
    }

    private fun insertItem(title: String, artist: String, position: String): String {
        val artistId = Ids.derived("artist", artist)
        database.artistQueries.applyMerged(
            id = artistId,
            name = artist,
            sort_name = artist,
            updated_at = now,
            deleted_at = null,
            device_id = device,
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
            device_id = device,
        )
        val itemId = "item-$position"
        database.setlist_itemQueries.insert(
            id = itemId,
            setlist_set_id = setId,
            song_id = songId,
            position = position,
            transpose = 0,
            tempo_override = null,
            note = null,
            updated_at = now,
            deleted_at = null,
            device_id = device,
        )
        return itemId
    }
}
