package dev.repertosaurus.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.repertosaurus.core.Ids
import dev.repertosaurus.core.uuid5
import dev.repertosaurus.data.JunctionWrite
import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.data.Resolution
import dev.repertosaurus.data.SchemaV1Fixture
import dev.repertosaurus.data.SongMerge
import dev.repertosaurus.db.RepertosaurusDatabase
import dev.repertosaurus.TestClock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Repertoire-editing R4a on the database the user's phone may actually hold.**
 *
 * A database upgraded 1 → 2 in place keeps its `song_performer` rows under their **two-key** ids
 * (schema-compatibility S3): `UUIDv5(namespace(song_performer), song/performer)`, not the
 * three-key id `Ids.songPerformer` derives. Every earlier test and every emulator run used a
 * freshly built database with three-key ids, which is why none of them could see this (F23 N2).
 *
 * So this file builds that database for real: the dump of the user's pre-Views schema
 * ([SchemaV1Fixture.fromRealDump]), a version-1 capability row written under its two-key id, and
 * the real `1.sqm` migration — exactly as `SchemaCompatibilityTest` runs it. Then the Repertoire
 * toggle is driven over the row the migration left behind.
 */
class TwoKeyCapabilityTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: RepertosaurusRepository
    private lateinit var repertoire: RepertoireCoordinator

    private val vocal = SchemaV1Fixture.VOCAL_ID
    private val artist = Ids.derived("artist", "Dolly Parton")
    private val jolene = Ids.song(artist, "Jolene")
    private val will = Ids.derived("performer", "Will")

    /** The id version 1 wrote: two keys, no instrument. */
    private val twoKeyId = uuid5(Ids.namespaceFor("song_performer"), "$jolene/$will")

    /** The id this build derives for the same capability. */
    private val threeKeyId = Ids.songPerformer(jolene, will, vocal)

    @BeforeTest
    fun open() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
    }

    @AfterTest
    fun close() {
        driver.close()
    }

    /**
     * The upgraded database: version 1, one song, one performer and one version-1 capability
     * row under its two-key id — live, or already removed ([deletedAt]) — then the real 1 → 2
     * migration, which backfills `vocal` and leaves the id alone (S3).
     */
    private fun upgradedDatabase(deletedAt: String?, alsoInVersionOne: () -> Unit = {}) {
        SchemaV1Fixture.fromRealDump(driver)
        execute(
            "INSERT INTO artist(id, name, sort_name, updated_at, deleted_at, device_id) " +
                "VALUES ('$artist', 'Dolly Parton', 'Dolly Parton', '$STAMP', NULL, 'phone')",
        )
        execute(
            "INSERT INTO song(id, title, artist_id, updated_at, deleted_at, device_id) " +
                "VALUES ('$jolene', 'Jolene', '$artist', '$STAMP', NULL, 'phone')",
        )
        execute(
            "INSERT INTO performer(id, name, notes, updated_at, deleted_at, device_id) " +
                "VALUES ('$will', 'Will', NULL, '$STAMP', NULL, 'phone')",
        )
        val tombstone = deletedAt?.let { "'$it'" } ?: "NULL"
        execute(
            "INSERT INTO song_performer(id, song_id, performer_id, is_lead, vocal_range, notes, " +
                "updated_at, deleted_at, device_id) " +
                "VALUES ('$twoKeyId', '$jolene', '$will', 1, 1, 'harmony up', '$STAMP', $tombstone, 'phone')",
        )
        alsoInVersionOne()

        RepertosaurusDatabase.Schema.migrate(driver, 1, RepertosaurusDatabase.Schema.version)

        repository = RepertosaurusRepository(
            RepertosaurusDatabase(driver),
            "test-device",
            TestClock("2026-09-23T10:00:00.000Z"),
            TimeZone.UTC,
        )
        repertoire = RepertoireCoordinator(repository)
    }

    /** The one row the table holds for the triple, whatever its id. */
    private fun rows() = RepertosaurusDatabase(driver).song_performerQueries.selectAllBySong(jolene).executeAsList()

    /** The fixture really is the two-key case: the migration kept the old id. */
    @Test
    fun theMigrationLeavesTheCapabilityUnderItsTwoKeyId() {
        upgradedDatabase(deletedAt = null)

        assertNotEquals(twoKeyId, threeKeyId)
        assertEquals(listOf(twoKeyId), rows().map { it.id })
        assertEquals(vocal, rows().single().instrument_id)
    }

    /** **R4a: the held list reads the two-key row** — it joins on the triple, not the id. */
    @Test
    fun theHeldListReadsATwoKeyRow() {
        upgradedDatabase(deletedAt = null)

        val row = repertoire.songs(will, vocal).single()

        assertEquals("Jolene" to true, row.title to row.held)
        assertEquals(1, RepertoireCoordinator.heldCount(repertoire.songs(will, vocal)))
    }

    /**
     * **R4a, toggle-off.** Before the fix the toggle looked the row up by the derived three-key
     * id, found nothing, said "already removed" (`wrote = false`) and removed nothing. It must
     * remove the two-key row and say it wrote.
     */
    @Test
    fun toggleOffRemovesATwoKeyRow() {
        upgradedDatabase(deletedAt = null)

        val off = repertoire.setHeld(jolene, will, vocal, held = false)

        assertEquals(JunctionWrite.Removed(twoKeyId, wrote = true), off)
        assertNotNull(rows().single().deleted_at, "the two-key row is tombstoned")
        assertEquals(false, repertoire.songs(will, vocal).single().held)
    }

    /**
     * **R4a, toggle-on.** Before the fix the toggle found no row at the derived id, ran its
     * `OR IGNORE` insert, which the unique key on the triple silently blocked, and reported
     * `CREATED` while the row stayed removed. It must revive the two-key row, keeping its facts
     * (E6), and insert nothing.
     */
    @Test
    fun toggleOnRevivesATwoKeyRow() {
        upgradedDatabase(deletedAt = STAMP)
        assertEquals(false, repertoire.songs(will, vocal).single().held)

        val on = repertoire.setHeld(jolene, will, vocal, held = true)

        assertEquals(JunctionWrite.Added(twoKeyId, Resolution.REVIVED), on)
        val row = rows().single()
        assertEquals(twoKeyId, row.id, "revived in place, no second row")
        assertNull(row.deleted_at)
        assertEquals(Triple(1L, 1L, "harmony up"), Triple(row.is_lead, row.vocal_range, row.notes), "E6: facts kept")
        assertEquals(true, repertoire.songs(will, vocal).single().held)
    }

    /** Off, then on, then on again: the same two-key row throughout, and an existing row is `EXISTING`. */
    @Test
    fun aTwoKeyRowSurvivesARoundTrip() {
        upgradedDatabase(deletedAt = null)

        assertEquals(JunctionWrite.Removed(twoKeyId, wrote = true), repertoire.setHeld(jolene, will, vocal, held = false))
        assertEquals(JunctionWrite.Removed(twoKeyId, wrote = false), repertoire.setHeld(jolene, will, vocal, held = false))
        assertEquals(JunctionWrite.Added(twoKeyId, Resolution.REVIVED), repertoire.setHeld(jolene, will, vocal, held = true))
        assertEquals(JunctionWrite.Added(twoKeyId, Resolution.EXISTING), repertoire.setHeld(jolene, will, vocal, held = true))
        assertEquals(listOf(twoKeyId), rows().map { it.id })
    }

    /**
     * The capability editor's by-name add (F22 B1) lands on the two-key row as well, and a
     * brand-new capability on the same database still gets the derived three-key id.
     */
    @Test
    fun theByNameAddFindsTheTwoKeyRowAndANewRowIsDerived() {
        upgradedDatabase(deletedAt = null)
        val capabilities = CapabilityCoordinator(repository)

        assertEquals(JunctionWrite.Added(twoKeyId, Resolution.EXISTING), capabilities.add(jolene, "Will", "vocal"))

        val guitar = Ids.derived("instrument", "guitar")
        val added = capabilities.add(jolene, "Will", "guitar")
        assertEquals(JunctionWrite.Added(Ids.songPerformer(jolene, will, guitar), Resolution.CREATED), added)
    }

    /**
     * **F25 N5: toggle-on over a two-key tombstone whose performer is removed too.** R4a finds the
     * row by its triple and revives it in place; R23b revives the performer in the same
     * transaction, and the result says a parent came back.
     */
    @Test
    fun toggleOnRevivesATwoKeyRowAndItsRemovedPerformer() {
        upgradedDatabase(deletedAt = STAMP)
        execute("UPDATE performer SET deleted_at = '$STAMP' WHERE id = '$will'")

        val on = repertoire.setHeld(jolene, will, vocal, held = true)

        assertEquals(JunctionWrite.Added(twoKeyId, Resolution.REVIVED, revivedParent = true), on)
        assertEquals(listOf(twoKeyId), rows().map { it.id }, "revived in place, no second row")
        assertNull(rows().single().deleted_at)
        assertNull(RepertosaurusDatabase(driver).performerQueries.selectById(will).executeAsOne().deleted_at, "R23b")
        assertEquals(true, repertoire.songs(will, vocal).single { it.songId == jolene }.held)
    }

    /**
     * **Merge on a database with two-key capability ids** (R4a, R35, R38a). *Jolene* holds Will's
     * row under its two-key id, tombstoned; the typo *Jolenne* holds Will's and Ann's rows, live,
     * both two-key. The merge must find each survivor row by its triple, not by the derived id:
     *
     * - Will: the survivor's two-key tombstone is revived **in place** and the loser's facts win;
     * - Ann: the survivor has no row, so one is inserted under the derived three-key id;
     * - both loser rows end tombstoned, under their own two-key ids.
     */
    @Test
    fun aMergeOverTwoKeyCapabilityRows() {
        val typo = Ids.song(artist, "Jolenne")
        val ann = Ids.derived("performer", "Ann")
        val typoWill = uuid5(Ids.namespaceFor("song_performer"), "$typo/$will")
        val typoAnn = uuid5(Ids.namespaceFor("song_performer"), "$typo/$ann")
        upgradedDatabase(deletedAt = STAMP) {
            execute(
                "INSERT INTO song(id, title, artist_id, updated_at, deleted_at, device_id) " +
                    "VALUES ('$typo', 'Jolenne', '$artist', '$STAMP', NULL, 'phone')",
            )
            execute(
                "INSERT INTO performer(id, name, notes, updated_at, deleted_at, device_id) " +
                    "VALUES ('$ann', 'Ann', NULL, '$STAMP', NULL, 'phone')",
            )
            execute(
                "INSERT INTO song_performer(id, song_id, performer_id, is_lead, vocal_range, notes, " +
                    "updated_at, deleted_at, device_id) VALUES " +
                    "('$typoWill', '$typo', '$will', 0, 0, 'from the typo', '$STAMP', NULL, 'phone'), " +
                    "('$typoAnn', '$typo', '$ann', 1, NULL, 'lead', '$STAMP', NULL, 'phone')",
            )
        }
        val plan = MergePlan.of(assertNotNull(repository.merge.side(jolene)), assertNotNull(repository.merge.side(typo)))
        assertEquals(2, plan.children.count { it.onLoser && it.kept })

        val outcome = repository.merge.merge(assertNotNull(plan.request()))

        assertEquals(2, (outcome as SongMerge.Outcome.Merged).childrenCarried)
        val survivor = rows().associateBy { it.performer_id }
        assertEquals(twoKeyId, survivor.getValue(will).id, "the two-key tombstone was revived in place")
        assertNull(survivor.getValue(will).deleted_at)
        assertEquals(Triple(0L, 0L, "from the typo"), survivor.getValue(will).let { Triple(it.is_lead, it.vocal_range, it.notes) }, "R38a: the loser's facts")
        assertEquals(Ids.songPerformer(jolene, ann, vocal), survivor.getValue(ann).id, "a new row takes the derived id")
        assertEquals(Triple(1L, null, "lead"), survivor.getValue(ann).let { Triple(it.is_lead, it.vocal_range, it.notes) })
        val loser = RepertosaurusDatabase(driver).song_performerQueries.selectAllBySong(typo).executeAsList()
        assertEquals(setOf(typoWill, typoAnn), loser.map { it.id }.toSet())
        assertTrue(loser.all { it.deleted_at != null }, "the loser's rows are removed, under their own ids")
        assertNull(repository.catalog.song(typo), "and the loser last")
    }

    private fun execute(sql: String) {
        driver.execute(null, sql, 0)
    }

    private companion object {
        const val STAMP: String = "2026-08-15T00:00:00.000Z"
    }
}
