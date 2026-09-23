package dev.repertosaurus.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.repertosaurus.core.Ids
import dev.repertosaurus.data.LookupTableKey
import dev.repertosaurus.data.JunctionWrite
import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.data.Resolution
import dev.repertosaurus.data.SongCatalog
import dev.repertosaurus.db.RepertosaurusDatabase
import dev.repertosaurus.TestClock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Repertoire route's shared core — repertoire-editing R1-R10 — against a real database. */
class RepertoireCoordinatorTest {


    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: RepertosaurusDatabase
    private lateinit var repository: RepertosaurusRepository
    private lateinit var repertoire: RepertoireCoordinator
    private lateinit var capabilities: CapabilityCoordinator

    private val vocal = Ids.derived("instrument", "vocal")
    private val guitar = Ids.derived("instrument", "guitar")
    private val keys = Ids.derived("instrument", "keys")

    private lateinit var charlotte: String
    private lateinit var coralie: String

    @BeforeTest
    fun open() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        RepertosaurusDatabase.Schema.create(driver)
        database = RepertosaurusDatabase(driver)
        repository = RepertosaurusRepository(
            database,
            "test-device",
            TestClock("2026-09-23T10:00:00.000Z"),
            TimeZone.UTC,
        )
        repertoire = RepertoireCoordinator(repository)
        capabilities = CapabilityCoordinator(repository)
        charlotte = repository.lookups.add(LookupTableKey.PERFORMER, "Charlotte")
        coralie = repository.lookups.add(LookupTableKey.PERFORMER, "Coralie")
    }

    @AfterTest
    fun close() {
        driver.close()
    }

    private fun song(title: String, artist: String = "Dolly Parton"): String =
        repository.catalog.addSong(title, SongCatalog.LookupChoice.Typed(artist)).song.songId

    // ---- R7, R9 -------------------------------------------------------------------------

    /**
     * **R9 and R7.** Songs and capability rows are inserted in the wrong order, and the list
     * comes back held first, then by title case-insensitively. Along the way:
     *
     * - a tombstoned capability reads as not held;
     * - a soft-deleted song is absent, even though the pair holds it;
     * - the same performer's row on a *different* instrument does not count, and neither does
     *   a *different* performer's row on the same instrument.
     *
     * Case matters in the unheld half: binary order would put `Banana` before `apple`.
     */
    @Test
    fun theToggleListIsEveryLiveSongHeldFirstThenTitleCaseInsensitively() {
        val zulu = song("zulu")
        val banana = song("Banana", "The Zutons")
        val cherry = song("cherry")
        val apple = song("apple")
        val mango = song("Mango")

        // Capabilities, also out of order.
        capabilities.addById(zulu, charlotte, vocal)
        capabilities.addById(cherry, charlotte, vocal)
        capabilities.addById(mango, charlotte, vocal)
        capabilities.addById(banana, charlotte, keys) // same performer, other instrument
        capabilities.addById(banana, coralie, vocal) // other performer, same instrument
        capabilities.remove(capabilities.addById(apple, charlotte, vocal).id) // tombstoned
        repository.catalog.removeSong(cherry) // held, but removed

        val rows = repertoire.songs(charlotte, vocal)

        assertEquals(
            listOf("Mango" to true, "zulu" to true, "apple" to false, "Banana" to false),
            rows.map { it.title to it.held },
        )
        assertEquals(2, RepertoireCoordinator.heldCount(rows))
        assertEquals("The Zutons", rows.single { it.songId == banana }.artistName)
    }

    /** R3/R7: the search filters over title and artist and re-fixes the order. */
    @Test
    fun searchMatchesTitleOrArtistAndReappliesTheOrder() {
        song("Valerie", "The Zutons")
        val jolene = song("Jolene")
        song("9 to 5")
        capabilities.addById(jolene, charlotte, vocal)

        val rows = repertoire.songs(charlotte, vocal)

        assertEquals(listOf("Valerie"), RepertoireCoordinator.search(rows, "zutons").map { it.title })
        assertEquals(listOf("Jolene"), RepertoireCoordinator.search(rows, "jol").map { it.title })
        assertEquals(
            listOf("Jolene", "9 to 5"),
            RepertoireCoordinator.search(rows, "dolly").map { it.title },
            "held first",
        )
        assertEquals(3, RepertoireCoordinator.search(rows, "  ").size, "blank matches everything")

        // A toggle flips a flag in place; a new search re-fixes the order with it.
        val toggled = rows.map { if (it.title == "Valerie") it.copy(held = true) else it }
        assertEquals(
            listOf("Jolene", "Valerie", "9 to 5"),
            RepertoireCoordinator.search(toggled, "").map { it.title },
        )
    }

    // ---- R4, R6 -------------------------------------------------------------------------

    /**
     * **R4 and E6.** Toggle on, set the facts in the capability editor, toggle off, toggle on:
     * the same row id comes back, and `is_lead`, `vocal_range` and `notes` survive.
     */
    @Test
    fun togglingOffThenOnRevivesTheSameRowWithItsFacts() {
        val jolene = song("Jolene")
        val on = repertoire.setHeld(jolene, charlotte, vocal, held = true)
        assertEquals(JunctionWrite.Added(Ids.songPerformer(jolene, charlotte, vocal), Resolution.CREATED), on)
        val id = on.id

        val fresh = capabilities.capabilities(jolene).single()
        assertEquals(false, fresh.isLead, "R6: a new row gets the schema defaults")
        assertNull(fresh.vocalRange)
        assertNull(fresh.notes)

        capabilities.update(fresh.copy(isLead = true, vocalRange = VocalRange.HIGH, notes = "harmony up"))

        assertEquals(JunctionWrite.Removed(id, wrote = true), repertoire.setHeld(jolene, charlotte, vocal, held = false))
        assertTrue(capabilities.capabilities(jolene).isEmpty())
        assertEquals(false, repertoire.songs(charlotte, vocal).single().held)
        assertEquals(
            JunctionWrite.Removed(id, wrote = false),
            repertoire.setHeld(jolene, charlotte, vocal, held = false),
            "R23d: toggling off an already-off row writes nothing and says so",
        )

        assertEquals(JunctionWrite.Added(id, Resolution.REVIVED), repertoire.setHeld(jolene, charlotte, vocal, held = true))

        val revived = capabilities.capabilities(jolene).single()
        assertEquals(id, revived.id)
        assertEquals(true, revived.isLead)
        assertEquals(VocalRange.HIGH, revived.vocalRange)
        assertEquals("harmony up", revived.notes)
        assertEquals(true, repertoire.songs(charlotte, vocal).single().held)
    }

    /**
     * **R23c.** Toggling on or off a song that has been removed writes nothing, and the result
     * says so, so R8's optimistic row can roll back. A removed instrument, by contrast, is a
     * parent the write revives (R23b).
     */
    @Test
    fun togglingOnARemovedSongWritesNothingAndSaysSo() {
        val jolene = song("Jolene")
        val held = song("Valerie")
        capabilities.addById(held, charlotte, vocal)
        repository.catalog.removeSong(jolene)
        repository.catalog.removeSong(held)

        val id = Ids.songPerformer(jolene, charlotte, vocal)
        assertEquals(JunctionWrite.SongGone(id), repertoire.setHeld(jolene, charlotte, vocal, held = true))
        assertNull(database.song_performerQueries.selectById(id).executeAsOneOrNull(), "no row inserted")

        val heldId = Ids.songPerformer(held, charlotte, vocal)
        val before = database.song_performerQueries.selectById(heldId).executeAsOne()
        assertEquals(JunctionWrite.SongGone(heldId), repertoire.setHeld(held, charlotte, vocal, held = false))
        assertEquals(before, database.song_performerQueries.selectById(heldId).executeAsOne(), "untouched")
    }

    /** **R23b** on a capability: toggling on under a removed instrument revives the instrument. */
    @Test
    fun togglingOnUnderARemovedInstrumentRevivesIt() {
        val jolene = song("Jolene")
        repository.lookups.remove(LookupTableKey.INSTRUMENT, keys)

        val write = repertoire.setHeld(jolene, charlotte, keys, held = true)

        assertEquals(Resolution.CREATED, (write as JunctionWrite.Added).resolution)
        assertTrue(repository.instruments().any { it.id == keys }, "the parent is live again")
        assertEquals(1, capabilities.capabilities(jolene).size, "and the row is visible (E40)")
    }

    /** R5 / E1: toggling never writes practice. */
    @Test
    fun togglingNeverLogsPractice() {
        val jolene = song("Jolene")
        repertoire.setHeld(jolene, charlotte, vocal, held = true)
        repertoire.setHeld(jolene, charlotte, vocal, held = false)

        assertEquals(0L, repository.timesPractised(jolene))
    }

    // ---- R1, R2, R10 --------------------------------------------------------------------

    /**
     * R1/R2/R10: every live performer with the roles they hold and the live instruments they
     * do not, in chip order; a removed performer and a removed instrument are absent.
     */
    @Test
    fun performersListTheirRolesAndTheInstrumentsTheyCouldAdd() {
        val jolene = song("Jolene")
        val removed = repository.lookups.add(LookupTableKey.PERFORMER, "Gone")
        capabilities.addById(jolene, charlotte, keys)
        capabilities.addById(jolene, charlotte, vocal)
        capabilities.addById(jolene, charlotte, guitar)
        repository.lookups.remove(LookupTableKey.PERFORMER, removed)
        repository.lookups.remove(LookupTableKey.INSTRUMENT, guitar)

        val performers = repertoire.performers().associateBy { it.performerName }

        assertEquals(setOf("Charlotte", "Coralie"), performers.keys, "R10: no removed performer")
        val charlotteRoles = performers.getValue("Charlotte")
        assertEquals(listOf("vocal", "keys"), charlotteRoles.roles.map { it.name }, "chip order")
        assertEquals(
            listOf("backing vocal", "bass"),
            charlotteRoles.unheldInstruments.map { it.name },
            "R2/R10: live instruments not held, and not the removed guitar",
        )
        val coralieRoles = performers.getValue("Coralie")
        assertTrue(coralieRoles.roles.isEmpty(), "E15: no rows, no roles")
        assertEquals(
            listOf("vocal", "backing vocal", "bass", "keys"),
            coralieRoles.unheldInstruments.map { it.name },
        )
    }
}
