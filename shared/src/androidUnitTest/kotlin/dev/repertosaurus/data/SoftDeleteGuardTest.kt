package dev.repertosaurus.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.repertosaurus.db.RepertosaurusDatabase
import dev.repertosaurus.session.LookupKind
import dev.repertosaurus.session.LookupStores
import dev.repertosaurus.TestClock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **R23d on every softDelete in the schema** (safety review F15 B2, F23 B1): the seven lookup
 * tables, `saved_view`, and the five that no screen reaches yet — `artist_alias`, `setlist`,
 * `setlist_set`, `setlist_item` and `setlist_item_performer`, which R37's set-list re-pointing
 * will be the first to write. (`song`, `artist` and the three song junctions are pinned beside
 * their writers, in `SongCatalogTest` and `CapabilityCoordinatorTest`.) A row that is already
 * removed is **not re-stamped** — under last-write-wins a spurious later `updated_at` would
 * outrank a legitimate revive made on another device — and the caller is told nothing was
 * written, so the screen can say so.
 */
class SoftDeleteGuardTest {

    private val clock = TestClock("2026-09-23T10:00:00.000Z")

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: RepertosaurusDatabase
    private lateinit var repository: RepertosaurusRepository

    @BeforeTest
    fun open() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        RepertosaurusDatabase.Schema.create(driver)
        database = RepertosaurusDatabase(driver)
        repository = RepertosaurusRepository(database, "test-device", clock, TimeZone.UTC)
    }

    @AfterTest
    fun close() {
        driver.close()
    }

    /**
     * Every one of the seven lookup tables, separately: the first removal writes and says so; the
     * second writes nothing — the tombstone keeps its first stamp — and says so; a removal of an
     * id with no row says so too.
     */
    @Test
    fun aSecondRemovalOfALookupWritesNothingOnEverySevenTables() {
        for (key in LookupTableKey.entries) {
            clock.instant = Instant.parse("2026-09-23T10:00:00.000Z")
            val id = repository.lookups.add(key, "guard test ${key.table}")

            assertTrue(repository.lookups.remove(key, id), "${key.table}: the first removal wrote")
            val removed = assertNotNull(row(key, id))
            assertEquals("2026-09-23T10:00:00.000Z", removed.deletedAt, key.table)

            clock.instant = Instant.parse("2026-09-23T12:00:00.000Z")
            assertFalse(repository.lookups.remove(key, id), "${key.table}: R23d, nothing written")
            assertEquals(removed, row(key, id), "${key.table}: not re-stamped")
            assertEquals("2026-09-23T10:00:00.000Z", updatedAt(key, id), "${key.table}: updated_at untouched")

            assertFalse(repository.lookups.remove(key, "no-such-row"), "${key.table}: an absent row")
        }
    }

    /** The store the manage screen calls reports the same, so the screen can say it (F15 B2). */
    @Test
    fun theLookupStoreReportsWhetherItRemoved() {
        val store = LookupStores.of(LookupKind.TAG, repository)
        val id = store.add("party")
        assertTrue(store.remove(id))
        assertFalse(store.remove(id))
    }

    /** `saved_view` is guarded too (F15 B2), and `deleteSavedView` reports it. */
    @Test
    fun aSecondDeletionOfAViewWritesNothing() {
        val view = repository.createSavedView(
            name = "Guard",
            filterPerformerId = null,
            filterInstrumentId = null,
            filterLeadOnly = 0L,
            practiceInstrumentId = dev.repertosaurus.core.Ids.derived("instrument", "vocal"),
            sortOrder = "COLDEST_FIRST",
        )
        assertTrue(repository.deleteSavedView(view.id))
        val removed = database.saved_viewQueries.selectById(view.id).executeAsOne()

        clock.instant = Instant.parse("2026-09-23T12:00:00.000Z")
        assertFalse(repository.deleteSavedView(view.id), "R23d: nothing written")
        assertEquals(removed, database.saved_viewQueries.selectById(view.id).executeAsOne(), "not re-stamped")
    }

    /**
     * **F23 B1: the five softDeletes no screen reaches yet.** Each is run twice through
     * [softDelete], the one path every remove takes: the first writes and says so; the second
     * writes nothing, and the row keeps its first `deleted_at` and `updated_at`.
     */
    @Test
    fun aSecondRemovalWritesNothingOnTheFiveUnreachedTables() {
        val rows = seedTheFiveTables()
        for ((table, softDeleteStatement) in rows) {
            clock.instant = Instant.parse("2026-09-23T10:00:00.000Z")
            assertTrue(database.softDelete(clock) { now -> softDeleteStatement(now) }, "$table: the first removal wrote")
            assertEquals("2026-09-23T10:00:00.000Z", column(table, "deleted_at", ROW), table)

            clock.instant = Instant.parse("2026-09-23T12:00:00.000Z")
            assertFalse(database.softDelete(clock) { now -> softDeleteStatement(now) }, "$table: R23d, nothing written")
            assertEquals("2026-09-23T10:00:00.000Z", column(table, "deleted_at", ROW), "$table: not re-stamped")
            assertEquals("2026-09-23T10:00:00.000Z", column(table, "updated_at", ROW), "$table: updated_at untouched")
        }
    }

    /**
     * One live row, id [ROW], in each of the five tables, with its parents — and each table's
     * generated `softDelete` bound to that row.
     */
    private fun seedTheFiveTables(): List<Pair<String, (String) -> Unit>> {
        val now = "2026-09-23T09:00:00.000Z"
        val device = "test-device"
        val song = repository.catalog.addSong("Valerie", SongCatalog.LookupChoice.Typed("The Zutons"))
        val artistId = repository.catalog.artists().single { it.name == "The Zutons" }.id
        val performerId = repository.lookups.add(LookupTableKey.PERFORMER, "Will")
        database.artist_aliasQueries.insert(ROW, artistId, "Zutons", now, null, device)
        database.setlistQueries.insert(
            id = ROW,
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
        database.setlist_setQueries.insert(
            id = ROW,
            setlist_id = ROW,
            set_no = 1,
            target_minutes = 60,
            updated_at = now,
            deleted_at = null,
            device_id = device,
        )
        database.setlist_itemQueries.insert(
            id = ROW,
            setlist_set_id = ROW,
            song_id = song.song.songId,
            position = "a",
            transpose = 0,
            tempo_override = null,
            note = null,
            updated_at = now,
            deleted_at = null,
            device_id = device,
        )
        database.setlist_item_performerQueries.insert(
            id = ROW,
            setlist_item_id = ROW,
            performer_id = performerId,
            position = 1,
            updated_at = now,
            deleted_at = null,
            device_id = device,
        )
        return listOf(
            "artist_alias" to { stamp -> database.artist_aliasQueries.softDelete(stamp, stamp, device, ROW) },
            "setlist" to { stamp -> database.setlistQueries.softDelete(stamp, stamp, device, ROW) },
            "setlist_set" to { stamp -> database.setlist_setQueries.softDelete(stamp, stamp, device, ROW) },
            "setlist_item" to { stamp -> database.setlist_itemQueries.softDelete(stamp, stamp, device, ROW) },
            "setlist_item_performer" to { stamp ->
                database.setlist_item_performerQueries.softDelete(stamp, stamp, device, ROW)
            },
        )
    }

    private fun column(table: String, column: String, id: String): String? =
        driver.executeQuery(
            null,
            "SELECT $column FROM $table WHERE id = ?",
            { cursor ->
                cursor.next()
                app.cash.sqldelight.db.QueryResult.Value(cursor.getString(0))
            },
            1,
        ) { bindString(0, id) }.value

    private fun updatedAt(key: LookupTableKey, id: String): String =
        driver.executeQuery(
            null,
            "SELECT updated_at FROM ${key.table} WHERE id = ?",
            { cursor ->
                cursor.next()
                app.cash.sqldelight.db.QueryResult.Value(cursor.getString(0)!!)
            },
            1,
        ) { bindString(0, id) }.value

    private companion object {
        /** The one row id seeded into each of the five tables. */
        const val ROW: String = "guard-row"
    }

    /** One lookup row, tombstone included, through the table's own descriptor. */
    private fun row(key: LookupTableKey, id: String): LookupRow? = lookupTable(database, "test-device", key).byId(id)
}
