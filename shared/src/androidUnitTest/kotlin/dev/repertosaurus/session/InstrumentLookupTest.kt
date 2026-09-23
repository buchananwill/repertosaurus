package dev.repertosaurus.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.repertosaurus.core.Ids
import dev.repertosaurus.data.LookupTableKey
import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.data.SongCatalog
import dev.repertosaurus.db.RepertosaurusDatabase
import dev.repertosaurus.TestClock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Managing the `instrument` table — decisions 15, 16, 5 and 9 against a real database.
 *
 * `instrument` is a table and not an enum precisely so this is possible without a schema
 * change or a migration. These tests are the proof of that, and of the three rules that
 * make it safe: a derived id, a rename that keeps it, and a delete that is a tombstone.
 *
 * Named for the table and not for a class: this was `InstrumentStoreTest`, after an
 * `InstrumentStore` that E18 replaced with the one shared [LookupStore]. `LookupStoresTest`
 * walks every kind; this one stays because `instrument` is the kind with a display order of
 * its own (decision 18) and the history the chip row reads.
 */
class InstrumentLookupTest {

    private val fixedClock = TestClock("2026-08-16T10:30:00.250Z")

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: RepertosaurusDatabase
    private lateinit var repository: RepertosaurusRepository
    private lateinit var store: LookupStore

    private val guitar = Ids.derived("instrument", "guitar")

    @BeforeTest
    fun open() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        RepertosaurusDatabase.Schema.create(driver)
        database = RepertosaurusDatabase(driver)
        repository = RepertosaurusRepository(database, "test-device", fixedClock, TimeZone.UTC)
        store = LookupStores.of(LookupKind.INSTRUMENT, repository)
    }

    @AfterTest
    fun close() {
        driver.close()
    }

    // ---- Adding -----------------------------------------------------------------------

    /** Decisions 2 and 4: derived, so two devices adding `mandolin` converge on one row. */
    @Test
    fun addingAnInstrumentDerivesItsId() {
        val id = store.add("Mandolin")

        assertEquals(Ids.derived("instrument", "Mandolin"), id)
        assertEquals(Ids.derived("instrument", "mandolin"), id)
        assertEquals("Mandolin", store.items().single { it.id == id }.name)
    }

    /**
     * Decision 18's ordering still holds with more than five: the seeded five in spec
     * order, then anything the user added, alphabetically.
     */
    @Test
    fun anAddedInstrumentTakesItsPlaceInTheChipRow() {
        store.add("Mandolin")
        store.add("Accordion")
        store.add("Ukulele")

        assertEquals(
            listOf("vocal", "backing vocal", "guitar", "bass", "keys",
                "Accordion", "Mandolin", "Ukulele"),
            SessionInstruments.chips(repository.instruments()).map { it.name },
        )
        // And the manage screen shows the same order as the chips.
        assertEquals(
            SessionInstruments.chips(repository.instruments()).map { it.name },
            store.items().map { it.name },
        )
    }

    /** The type-ahead's whole purpose: a typo must be seen before it is committed. */
    @Test
    fun typingUkeleleSurfacesTheExistingUkulele() {
        store.add("Ukulele")

        val suggested = LookupSuggestions.search("Ukelele", store.items())

        assertEquals(listOf("Ukulele"), suggested.map { it.name })
        assertEquals(listOf("Ukulele"), LookupSuggestions.search("ukelele", store.items()).map { it.name })
        assertEquals(listOf("Ukulele"), LookupSuggestions.search("Ukule", store.items()).map { it.name })
    }

    @Test
    fun theSeededInstrumentsAreSurfacedByNearMatchToo() {
        assertEquals(listOf("guitar"), LookupSuggestions.search("Guitr", store.items()).map { it.name })
        assertEquals(listOf("keys"), LookupSuggestions.search("keyz", store.items()).map { it.name })
        assertEquals(listOf("backing vocal"), LookupSuggestions.search("backing vokal", store.items()).map { it.name })
        assertTrue(LookupSuggestions.search("trombone", store.items()).isEmpty(), "a genuinely new value matches nothing")
    }

    /** A name that normalises to an existing one is that row, not a second one. */
    @Test
    fun addingAnInstrumentThatNormalisesToAnExistingOneReusesIt() {
        val before = store.items().size

        val id = store.add("  Guitar  ")

        assertEquals(guitar, id)
        assertEquals(before, store.items().size, "no second guitar")
        assertEquals("guitar", store.items().single { it.id == guitar }.name, "spelling kept")
    }

    // ---- Renaming ---------------------------------------------------------------------

    /**
     * Decision 5: an id is opaque and immutable. Re-deriving it on a rename would orphan
     * every practice event pointing at it — silently, and unrecoverably.
     */
    @Test
    fun renamingKeepsTheIdAndTheHistory() {
        val song = insertSong("Valerie", "The Zutons")
        repository.logPractice(song, guitar, loggedOn = "2026-08-15")

        store.rename(guitar, "Electric Guitar")

        val renamed = store.items().single { it.id == guitar }
        assertEquals("Electric Guitar", renamed.name)
        assertEquals(guitar, renamed.id, "the id must not be re-derived")
        assertEquals(1L, renamed.usageCount)
        assertEquals(1L, repository.timesPractised(song))
        assertEquals("1d", coordinatorRows().single().badge)

        // The new name does *not* derive this id, which is exactly why it is not re-derived.
        assertTrue(Ids.derived("instrument", "Electric Guitar") != guitar)
    }

    // ---- Removing ---------------------------------------------------------------------

    /**
     * Decision 9: a tombstone, never a `DELETE`. A hard delete would be reinserted by a
     * stale device on the next merge, and the events referencing it must survive.
     */
    @Test
    fun removingHidesTheChipButKeepsEveryEvent() {
        val song = insertSong("Valerie", "The Zutons")
        repository.logPractice(song, guitar, loggedOn = "2026-08-15")
        repository.logPractice(song, guitar, loggedOn = "2026-08-16")

        assertEquals(2L, store.items().single { it.id == guitar }.usageCount)

        store.remove(guitar)

        assertTrue(store.items().none { it.id == guitar }, "gone from the manage list")
        assertTrue(
            SessionInstruments.chips(repository.instruments()).none { it.id == guitar },
            "gone from the chip row",
        )

        // The row is still there, tombstoned rather than deleted.
        val row = database.instrumentQueries.selectById(guitar).executeAsOneOrNull()
        assertNotNull(row, "a hard delete would come back on the next merge")
        assertNotNull(row.deleted_at)
        assertEquals("guitar", row.name)

        // And every event it carries is intact and still counted.
        assertEquals(2L, repository.timesPractised(song))
        assertEquals(2L, repository.lookups.usage(LookupTableKey.INSTRUMENT, guitar))
        assertEquals(2, repository.practiceHistory(song).size)
        assertEquals(
            2L,
            repository.songsByStaleness(
                practiceInstrumentId = guitar,
                filterPerformerId = null,
                filterInstrumentId = null,
                leadOnly = 0L,
                today = "2026-08-16",
            ).single().timesPractised,
        )
    }

    @Test
    fun addingBackAnInstrumentThatWasRemovedRestoresIt() {
        store.remove(guitar)
        assertTrue(store.items().none { it.id == guitar })

        val id = store.add("guitar")

        assertEquals(guitar, id)
        assertEquals("guitar", store.items().single { it.id == guitar }.name)
        assertNull(database.instrumentQueries.selectById(guitar).executeAsOne().deleted_at)
    }

    @Test
    fun usageCountIgnoresVoidedEvents() {
        val song = insertSong("Valerie", "The Zutons")
        repository.logPractice(song, guitar, loggedOn = "2026-08-15")
        val undone = repository.logPractice(song, guitar, loggedOn = "2026-08-16")

        repository.voidPractice(undone)

        assertEquals(1L, store.items().single { it.id == guitar }.usageCount)
    }

    private fun coordinatorRows() =
        SessionCoordinator(repository, InMemorySessionPreferences())
            .rows(SessionView.unsaved(guitar))

    private fun insertSong(title: String, artist: String): String {
        return repository.catalog.addSong(title, SongCatalog.LookupChoice.Typed(artist)).song.songId
    }
}
