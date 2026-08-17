package dev.repertaurus.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.repertaurus.core.Ids
import dev.repertaurus.data.LookupTableKey
import dev.repertaurus.data.RepertaurusRepository
import dev.repertaurus.db.RepertaurusDatabase
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The song-capability editor's shared core — editing E1-E11 against a real database.
 *
 * `song_performer` has been **read-only since the migration created it** (E9); everything
 * exercised here is a path that did not exist. Android unit tests run on the host JVM, so the
 * generated schema and every query are exercised without a device.
 */
class CapabilityCoordinatorTest {

    /** Movable, so a revive's bumped `updated_at` is distinguishable from the original. */
    private class MovableClock(var instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    private val clock = MovableClock(Instant.parse("2026-08-17T10:00:00.000Z"))

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: RepertaurusDatabase
    private lateinit var repository: RepertaurusRepository
    private lateinit var capabilities: CapabilityCoordinator

    private val vocal = Ids.derived("instrument", "vocal")
    private val backingVocal = Ids.derived("instrument", "backing vocal")
    private val keys = Ids.derived("instrument", "keys")

    private lateinit var jolene: String
    private lateinit var valerie: String

    @BeforeTest
    fun open() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        RepertaurusDatabase.Schema.create(driver)
        database = RepertaurusDatabase(driver)
        repository = RepertaurusRepository(database, "test-device", clock, TimeZone.UTC)
        capabilities = CapabilityCoordinator(repository)

        jolene = song("Jolene", "Dolly Parton")
        valerie = song("Valerie", "The Zutons")
    }

    @AfterTest
    fun close() {
        driver.close()
    }

    // ---- E6: the revive path -------------------------------------------------------------

    /**
     * **E6, with the spec's own worked example.** Charlotte on `keys` for *Jolene* is added,
     * then removed, then added again.
     *
     * The id is derived from `song_id/performer_id/instrument_id`, so the re-add computes the
     * **same primary key**. A plain `INSERT` throws on it; an `INSERT OR REPLACE` succeeds and
     * silently discards the row's `notes` and `vocal_range`. The correct write clears
     * `deleted_at` and bumps `updated_at`, and this asserts all four consequences: one row,
     * the same id, the tombstone gone, and the facts intact.
     */
    @Test
    fun addingBackARemovedCapabilityRevivesTheSameRowWithItsFactsIntact() {
        val charlotte = repository.lookups.add(LookupTableKey.PERFORMER, "Charlotte")
        val id = capabilities.add(jolene, "Charlotte", "keys")
        assertEquals(Ids.songPerformer(jolene, charlotte, keys), id, "E9: derived, not invented")

        // Facts the user typed, which a naive re-add would throw away.
        capabilities.update(
            capabilities.capabilities(jolene).single().copy(
                isLead = true,
                notes = "capo 3, plays the intro",
            ),
        )
        val created = database.song_performerQueries.selectById(id).executeAsOne()
        assertEquals("2026-08-17T10:00:00.000Z", created.updated_at)

        clock.instant = Instant.parse("2026-08-17T11:00:00.000Z")
        capabilities.remove(id)
        assertTrue(capabilities.capabilities(jolene).isEmpty(), "gone from the editor")
        assertNotNull(
            database.song_performerQueries.selectById(id).executeAsOne().deleted_at,
            "E7: a tombstone, not a DELETE",
        )

        clock.instant = Instant.parse("2026-08-17T12:00:00.000Z")
        val again = capabilities.add(jolene, "Charlotte", "keys")

        assertEquals(id, again, "the re-add derives X again, not a new id")
        val rows = database.song_performerQueries.selectChangedSince("").executeAsList()
        assertEquals(1, rows.size, "one row, never two")

        val revived = rows.single()
        assertNull(revived.deleted_at, "deleted_at cleared")
        assertEquals("2026-08-17T12:00:00.000Z", revived.updated_at, "updated_at bumped")
        assertEquals("capo 3, plays the intro", revived.notes, "notes survive the revive")
        assertEquals(1L, revived.is_lead, "is_lead survives the revive")
        assertEquals(1, capabilities.capabilities(jolene).size, "back in the editor")
    }

    /**
     * The same, for `vocal_range` specifically — the other column an `INSERT OR REPLACE`
     * would have discarded, and the one V7 leaves unconstrained against instrument.
     */
    @Test
    fun aRevivedVocalRowKeepsItsVocalRange() {
        val id = capabilities.add(valerie, "Coralie", "vocal")
        capabilities.update(
            capabilities.capabilities(valerie).single().copy(vocalRange = VocalRange.HIGH),
        )

        capabilities.remove(id)
        clock.instant = Instant.parse("2026-08-17T13:00:00.000Z")
        capabilities.add(valerie, "Coralie", "vocal")

        val revived = capabilities.capabilities(valerie).single()
        assertEquals(id, revived.id)
        assertEquals(VocalRange.HIGH, revived.vocalRange)
    }

    /** Adding a capability that is already live changes nothing at all. */
    @Test
    fun addingALiveCapabilityAgainIsANoOp() {
        val id = capabilities.add(valerie, "Coralie", "vocal")
        capabilities.update(capabilities.capabilities(valerie).single().copy(notes = "harmony"))
        val before = database.song_performerQueries.selectById(id).executeAsOne()

        clock.instant = Instant.parse("2026-08-17T14:00:00.000Z")
        assertEquals(id, capabilities.add(valerie, "Coralie", "vocal"))

        assertEquals(before, database.song_performerQueries.selectById(id).executeAsOne())
        assertEquals(1, capabilities.capabilities(valerie).size)
    }

    // ---- E9: the derived id --------------------------------------------------------------

    /**
     * E9 and views V31. The two-key form still *compiles* for this table, and without the
     * table-aware guard it would quietly return the superseded id — permanently, because an
     * id is immutable once written (decision 5).
     */
    @Test
    fun theTwoKeyJunctionFormIsRefusedForSongPerformer() {
        val coralie = repository.lookups.add(LookupTableKey.PERFORMER, "Coralie")
        assertFailsWith<IllegalArgumentException> {
            Ids.junction("song_performer", valerie, coralie)
        }
    }

    // ---- E3: one row per (song, performer, instrument) -----------------------------------

    /**
     * E3 and V2: Coralie holds a `vocal` row and a `backing vocal` row on the same song, and
     * neither shadows the other. V8 is why those are two instruments and not one flag.
     */
    @Test
    fun onePersonHoldsSeveralInstrumentsOnOneSongWithoutCollision() {
        capabilities.add(valerie, "Coralie", "vocal")
        capabilities.add(valerie, "Coralie", "backing vocal")
        capabilities.add(valerie, "Charlotte", "keys")

        val held = capabilities.capabilities(valerie)
        assertEquals(3, held.size)
        assertEquals(
            setOf(vocal, backingVocal, keys),
            held.map { it.instrumentId }.toSet(),
        )
        assertEquals(3, held.map { it.id }.toSet().size, "three distinct derived ids")
    }

    // ---- E10: grouped by performer -------------------------------------------------------

    /**
     * E10: a performer holding three instruments is **one entry with three chips**, not three
     * entries. The rows are added in the wrong order deliberately — a passing result must not
     * be insertion order.
     */
    @Test
    fun theEditorGroupsRowsByPerformerInChipOrder() {
        capabilities.add(valerie, "Charlotte", "keys")
        capabilities.add(valerie, "Charlotte", "guitar")
        capabilities.add(valerie, "Charlotte", "backing vocal")
        capabilities.add(valerie, "Coralie", "vocal")

        val lineUp = capabilities.lineUp(valerie)
        assertEquals(2, lineUp.size, "two people, not four rows")

        val charlotte = lineUp.single { it.performerName == "Charlotte" }
        assertEquals(
            listOf("backing vocal", "guitar", "keys"),
            charlotte.capabilities.map { it.instrumentName },
            "decision 18's chip order, not insertion order and not alphabetical by id",
        )
        assertFalse(charlotte.leads)
    }

    /** Lead performers come first, so the line-up reads as a line-up (E4, E10). */
    @Test
    fun theLeadPerformerHeadsTheLineUp() {
        capabilities.add(valerie, "Coralie", "backing vocal")
        val willsRow = capabilities.add(valerie, "Will", "vocal")
        capabilities.update(
            capabilities.capabilities(valerie).single { it.id == willsRow }.copy(isLead = true),
        )

        val lineUp = capabilities.lineUp(valerie)
        assertEquals(listOf("Will", "Coralie"), lineUp.map { it.performerName })
        assertTrue(lineUp.first().leads)
    }

    // ---- E4: is_lead ---------------------------------------------------------------------

    /** E4: offered on every instrument, defaulting off, and scoped to its own row (V6). */
    @Test
    fun isLeadDefaultsOffAndIsScopedToItsInstrument() {
        val vocalRow = capabilities.add(valerie, "Will", "vocal")
        val guitarRow = capabilities.add(valerie, "Will", "guitar")
        assertTrue(capabilities.capabilities(valerie).none { it.isLead }, "defaults off")

        capabilities.update(
            capabilities.capabilities(valerie).single { it.id == guitarRow }.copy(isLead = true),
        )

        val held = capabilities.capabilities(valerie).associateBy { it.id }
        assertTrue(held.getValue(guitarRow).isLead, "lead guitar")
        assertFalse(held.getValue(vocalRow).isLead, "and still not lead vocal")
    }

    /** E4 again: `backing vocal` gets no special case — a featured backing vocalist is real. */
    @Test
    fun backingVocalCanBeLead() {
        val row = capabilities.add(valerie, "Coralie", "backing vocal")
        capabilities.update(
            capabilities.capabilities(valerie).single().copy(isLead = true),
        )
        assertTrue(capabilities.capabilities(valerie).single { it.id == row }.isLead)
    }

    // ---- E8: vocal_range -----------------------------------------------------------------

    /** E8: range is offered on the two voices and refused everywhere else. */
    @Test
    fun vocalRangeIsOfferedOnVoicesOnly() {
        capabilities.add(valerie, "Coralie", "vocal")
        capabilities.add(valerie, "Coralie", "backing vocal")
        capabilities.add(valerie, "Coralie", "guitar")

        val held = capabilities.capabilities(valerie).associateBy { it.instrumentName }
        assertTrue(held.getValue("vocal").rangeApplies)
        assertTrue(held.getValue("backing vocal").rangeApplies)
        assertFalse(held.getValue("guitar").rangeApplies)

        assertFailsWith<IllegalArgumentException> {
            capabilities.update(held.getValue("guitar").copy(vocalRange = VocalRange.HIGH))
        }
        assertNull(
            database.song_performerQueries
                .selectById(held.getValue("guitar").id).executeAsOne().vocal_range,
            "and nothing was written",
        )
    }

    /**
     * E21 and decision 5: renaming the instrument must not move the range field, because the
     * id is what E8 matches on and a rename never re-derives it.
     */
    @Test
    fun renamingVocalKeepsTheRangeFieldWhereItBelongs() {
        capabilities.add(valerie, "Coralie", "vocal")
        repository.lookups.rename(LookupTableKey.INSTRUMENT, vocal, "Lead Voice")

        val row = capabilities.capabilities(valerie).single()
        assertEquals("Lead Voice", row.instrumentName)
        assertTrue(row.rangeApplies, "matched on the id, which the rename did not touch")
    }

    // ---- E5: create on enter -------------------------------------------------------------

    /**
     * E5: a performer or instrument the user names and that does not exist is created rather
     * than blocking the save — through the same derived-id path the manage screen uses (E13),
     * so a name that normalises to an existing row resolves to that row.
     */
    @Test
    fun bothFieldsCreateOnEnterAndReuseAnExistingRowByNormalisation() {
        capabilities.add(valerie, "Sophie-Mae", "Mandolin")

        val row = capabilities.capabilities(valerie).single()
        assertEquals(Ids.derived("performer", "Sophie-Mae"), row.performerId)
        assertEquals(Ids.derived("instrument", "Mandolin"), row.instrumentId)

        // A second spelling that normalises the same resolves to those rows, not to new ones.
        capabilities.add(jolene, "  sophie mae ", "  mandolin  ")
        assertEquals(row.performerId, capabilities.capabilities(jolene).single().performerId)
        assertEquals(row.instrumentId, capabilities.capabilities(jolene).single().instrumentId)
        assertEquals(1, repository.performers().count { it.name == "Sophie-Mae" })
    }

    /** Decision 17d: the type-ahead is the only defence against `Ukelele` / `Ukulele`. */
    @Test
    fun theInstrumentTypeAheadSurfacesANearMatchBeforeADuplicateIsCommitted() {
        capabilities.add(valerie, "Will", "Ukulele")

        assertEquals(listOf("Ukulele"), capabilities.suggestInstruments("Ukelele", capabilities.instruments()).map { it.name })
        assertEquals(listOf("Will"), capabilities.suggestPerformers("Wil", capabilities.performers()).map { it.name })
    }

    // ---- E1: this is not the practice path -----------------------------------------------

    /**
     * **E1, pinned rather than asserted in prose.** The editor writes `song_performer` rows
     * only and must not write `practice_event` under any path. The two are one long-press
     * apart, which is exactly why this test exists.
     */
    @Test
    fun nothingInTheCapabilityEditorEverWritesAPracticeEvent() {
        val id = capabilities.add(valerie, "Coralie", "vocal")
        capabilities.update(
            capabilities.capabilities(valerie).single()
                .copy(isLead = true, vocalRange = VocalRange.LOW, notes = "sits low"),
        )
        capabilities.remove(id)
        capabilities.add(valerie, "Coralie", "vocal")
        capabilities.lineUp(valerie)

        assertEquals(0L, repository.timesPractised(valerie))
        assertEquals(0L, repository.lookups.usage(LookupTableKey.INSTRUMENT, vocal))
        assertTrue(repository.practiceHistory(valerie).isEmpty())
        assertNull(repository.mostRecentLiveEvent())
    }

    // ---- E7: removal is a soft delete ----------------------------------------------------

    /** E7: the tombstone stays, and it is what E6's revive path needs to find. */
    @Test
    fun removingACapabilityIsATombstone() {
        val id = capabilities.add(valerie, "Coralie", "vocal")
        capabilities.remove(id)

        val row = database.song_performerQueries.selectById(id).executeAsOneOrNull()
        assertNotNull(row, "a hard delete comes back on the next merge")
        assertNotNull(row.deleted_at)
        assertTrue(capabilities.capabilities(valerie).isEmpty())
    }

    /**
     * **E37: an update can never clear a tombstone, and this is the race it is defended
     * against.**
     *
     * The UI dispatches each write as its own coroutine, so a remove and an in-flight edit on
     * the same row complete in either order. That order is reproduced here exactly: the editor
     * reads a row, the row is removed, and *then* the edit the user had already started lands
     * on the stale value.
     *
     * The old statement wrote `deleted_at = NULL`, so this sequence resurrected the row —
     * and under last-write-wins (decision 11) the resurrected row carried the newer timestamp
     * and would have won on every device, permanently. The defence was a comment saying the
     * caller only ever passes a live row. A comment is not a guard.
     */
    @Test
    fun updatingARemovedCapabilityLeavesTheTombstoneInPlace() {
        val id = capabilities.add(valerie, "Coralie", "vocal")
        // What the open editor is still holding when the remove lands.
        val stale = capabilities.capabilities(valerie).single()

        clock.instant = Instant.parse("2026-08-17T11:00:00.000Z")
        capabilities.remove(id)

        clock.instant = Instant.parse("2026-08-17T12:00:00.000Z")
        capabilities.update(stale.copy(isLead = true, notes = "harmony", vocalRange = VocalRange.HIGH))

        val row = database.song_performerQueries.selectById(id).executeAsOne()
        assertNotNull(row.deleted_at, "E37: the tombstone must survive the update")
        assertEquals(
            "2026-08-17T11:00:00.000Z",
            row.updated_at,
            "E37: the update matched no row, so it wrote nothing — not even a timestamp",
        )
        assertEquals(0L, row.is_lead, "and none of the three facts landed")
        assertNull(row.notes)
        assertNull(row.vocal_range)
        assertTrue(capabilities.capabilities(valerie).isEmpty(), "still out of the editor")
    }

    /** The same statement still edits a live row, which is the whole point of keeping it. */
    @Test
    fun updatingALiveCapabilityStillWritesAllThreeFacts() {
        capabilities.add(valerie, "Coralie", "vocal")

        clock.instant = Instant.parse("2026-08-17T12:00:00.000Z")
        capabilities.update(
            capabilities.capabilities(valerie).single()
                .copy(isLead = true, notes = "harmony", vocalRange = VocalRange.HIGH),
        )

        val row = capabilities.capabilities(valerie).single()
        assertTrue(row.isLead)
        assertEquals("harmony", row.notes)
        assertEquals(VocalRange.HIGH, row.vocalRange)
        assertEquals(
            "2026-08-17T12:00:00.000Z",
            database.song_performerQueries.selectById(row.id).executeAsOne().updated_at,
        )
    }

    // ---- E38: atomicity --------------------------------------------------------------------

    /**
     * **E38: two concurrent adds of the same triple leave exactly one row, and neither
     * throws.**
     *
     * `addSongPerformer` reads by derived id and then branches to insert, revive or no-op, so
     * two taps landing together both observe "absent" — and the derived id (V3) is the *same*
     * id, by design, so before E38 the second writer's plain `INSERT` violated the primary
     * key. An `INSERT OR REPLACE` would not have thrown but would have discarded the winner's
     * `notes` and `vocal_range`, which is exactly what E6 forbids; the statement is
     * `INSERT OR IGNORE`, so the loser writes nothing at all.
     *
     * **The interleave is reproduced deterministically, and that is deliberate.** A
     * thread-based version of this test exercises the *driver*, not the schema: production is
     * `AndroidSqliteDriver`, which serialises transactions per database, while these tests run
     * on `JdbcSqliteDriver`, which shares **one** JDBC connection across threads and fails
     * with `cannot start a transaction within a transaction` the moment two threads are inside
     * `transaction {}` together. Measured, not assumed — the threaded form was written first
     * and failed on exactly that. So the losing writer's move is played by hand here, at the
     * moment the race produces it, and the assertion is the end state the user sees.
     */
    @Test
    fun twoConcurrentAddsOfTheSameTripleLeaveOneRowAndNeitherThrows() {
        val coralie = repository.lookups.add(LookupTableKey.PERFORMER, "Coralie")
        val id = Ids.songPerformer(valerie, coralie, vocal)

        // Writer B reads and sees nothing. This is the observation the whole race turns on.
        assertNull(database.song_performerQueries.selectById(id).executeAsOneOrNull())

        // Writer A completes in full while B is between its read and its write, and types a
        // fact onto the row — the fact an OR REPLACE would silently discard.
        assertEquals(id, capabilities.addById(valerie, coralie, vocal))
        capabilities.update(
            capabilities.capabilities(valerie).single().copy(notes = "capo 3"),
        )

        // B now writes on its stale observation.
        database.song_performerQueries.insertIfAbsent(
            id = id,
            song_id = valerie,
            performer_id = coralie,
            instrument_id = vocal,
            is_lead = 0L,
            vocal_range = null,
            notes = null,
            updated_at = "2026-08-17T10:00:00.000Z",
            deleted_at = null,
            device_id = "other-thread",
        )

        val rows = database.song_performerQueries.selectChangedSince("").executeAsList()
        assertEquals(1, rows.size, "one row, never two — the derived id is the same id")
        assertNull(rows.single().deleted_at, "and it is live")
        assertEquals("capo 3", rows.single().notes, "the loser wrote nothing, not even a null")
        assertEquals(1, capabilities.capabilities(valerie).size)
    }

    /**
     * **E38's other half: an interrupted add leaves no orphan lookup.** Creating a capability
     * from two typed names performs three writes — the performer, the instrument, and the row
     * joining them. Here the second fails, and the first must not survive it.
     */
    @Test
    fun anAddThatFailsPartWayLeavesNoLookupBehind() {
        assertFailsWith<IllegalArgumentException> {
            capabilities.add(valerie, "Nadia", "   ")
        }

        val nadia = Ids.derived("performer", "Nadia")
        assertNull(
            database.performerQueries.selectById(nadia).executeAsOneOrNull(),
            "E38: the performer written before the failure is rolled back, not orphaned",
        )
        assertTrue(repository.performers().none { it.name == "Nadia" })
        assertTrue(capabilities.capabilities(valerie).isEmpty())
    }

    // ---- E40: a removed instrument hides its capability rows -------------------------------

    /**
     * **E40: the editor hides a row whose instrument is soft-deleted**, exactly as it already
     * hides one whose performer is. View eligibility excludes both (V11a), so a row surviving
     * here would be a capability no View can ever match — noise in the one screen whose job is
     * to state what is true.
     */
    @Test
    fun theEditorHidesARowWhoseInstrumentWasRemoved() {
        capabilities.add(valerie, "Charlotte", "keys")
        capabilities.add(valerie, "Charlotte", "vocal")
        assertEquals(2, capabilities.capabilities(valerie).size)

        repository.lookups.remove(LookupTableKey.INSTRUMENT, keys)

        val held = capabilities.capabilities(valerie)
        assertEquals(listOf("vocal"), held.map { it.instrumentName })
        assertEquals(listOf("vocal"), capabilities.lineUp(valerie).single().capabilities.map {
            it.instrumentName
        })
        // The row itself is untouched — a tombstoned instrument hides it, it does not delete it.
        assertNotNull(
            database.song_performerQueries
                .selectById(Ids.songPerformer(valerie, Ids.derived("performer", "Charlotte"), keys))
                .executeAsOneOrNull(),
        )
    }

    // ---- E35: one comparator, applied once -------------------------------------------------

    /**
     * **E35, asserted as a LIST and not as a set.** `capabilities()` returned SQL order —
     * instruments alphabetically — while `lineUp()` re-sorted into chip order, so the same
     * rows read differently on two surfaces. The previous test for this compared sets, which
     * is exactly why it got through.
     *
     * The rows are added in the wrong order deliberately, so a passing result cannot be
     * insertion order.
     */
    @Test
    fun theFlatListAndTheGroupedListAgreeOnOrder() {
        capabilities.add(valerie, "Charlotte", "keys")
        capabilities.add(valerie, "Charlotte", "guitar")
        capabilities.add(valerie, "Charlotte", "backing vocal")
        val willsRow = capabilities.add(valerie, "Will", "vocal")
        capabilities.add(valerie, "Will", "guitar")
        capabilities.update(
            capabilities.capabilities(valerie).single { it.id == willsRow }.copy(isLead = true),
        )

        // Will leads, so Will heads the line-up; inside each performer, decision 18's chips.
        assertEquals(
            listOf(
                "Will" to "vocal",
                "Will" to "guitar",
                "Charlotte" to "backing vocal",
                "Charlotte" to "guitar",
                "Charlotte" to "keys",
            ),
            capabilities.capabilities(valerie).map { it.performerName to it.instrumentName },
        )

        // And the grouped view is that same sequence, grouped — not a second ordering.
        assertEquals(
            capabilities.capabilities(valerie).map { it.performerName to it.instrumentName },
            capabilities.lineUp(valerie).flatMap { entry ->
                entry.capabilities.map { entry.performerName to it.instrumentName }
            },
        )
    }

    // ---- Fixtures ------------------------------------------------------------------------

    private fun song(title: String, artist: String): String =
        repository.createSong(title, repository.findOrCreateArtist(artist))
}
