package dev.repertosaurus.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.repertosaurus.core.Ids
import dev.repertosaurus.data.SongCatalog.LookupChoice
import dev.repertosaurus.db.RepertosaurusDatabase
import dev.repertosaurus.TestClock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The song-child envelope's own rules, pinned where no route test reaches them: the order of the
 * envelope (F23 N3), an ignored `OR IGNORE` (R4a), and the two members merge will need (F22 N8).
 */
class SongChildrenTest {


    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: RepertosaurusDatabase
    private lateinit var repository: RepertosaurusRepository

    private val vocal = Ids.derived("instrument", "vocal")
    private val keys = Ids.derived("instrument", "keys")

    @BeforeTest
    fun open() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        RepertosaurusDatabase.Schema.create(driver)
        database = RepertosaurusDatabase(driver)
        repository = RepertosaurusRepository(
            database,
            DEVICE,
            TestClock("2026-09-23T10:00:00.000Z"),
            TimeZone.UTC,
        )
    }

    @AfterTest
    fun close() {
        driver.close()
    }

    private fun song(title: String): String =
        repository.catalog.addSong(title, LookupChoice.Typed("Dolly Parton")).song.songId

    private fun table(key: SongChildKey): SongChildTable = songChildTable(database, DEVICE, key)

    // ---- F23 N3: the envelope's order --------------------------------------------------------

    /**
     * **F23 N3: the song check runs before any parent revive.** Every parent of all three junction
     * tables is tombstoned and so is the song. An add on each is refused with `SongGone`, and
     * **every parent stays removed** — R23c's "nothing is written" includes R23b's revive, which
     * would otherwise bring back a lookup for a write that never lands.
     */
    @Test
    fun theSongCheckRunsBeforeAnyParentIsRevived() {
        val jolene = song("Jolene")
        val will = repository.lookups.add(LookupTableKey.PERFORMER, "Will")
        val party = repository.lookups.add(LookupTableKey.TAG, "party")
        assertTrue(repository.lookups.remove(LookupTableKey.PERFORMER, will))
        assertTrue(repository.lookups.remove(LookupTableKey.INSTRUMENT, keys))
        assertTrue(repository.lookups.remove(LookupTableKey.TAG, party))
        assertTrue(repository.catalog.removeSong(jolene))

        assertEquals(
            JunctionWrite.SongGone(Ids.songPerformer(jolene, will, keys)),
            repository.addSongPerformer(jolene, will, keys),
        )
        assertEquals(JunctionWrite.SongGone(Ids.songInstrument(jolene, keys)), repository.catalog.addSongInstrument(jolene, keys))
        assertEquals(JunctionWrite.SongGone(Ids.songTag(jolene, party)), repository.catalog.addTag(jolene, party))

        assertNotNull(database.performerQueries.selectById(will).executeAsOne().deleted_at, "performer not revived")
        assertNotNull(database.instrumentQueries.selectById(keys).executeAsOne().deleted_at, "instrument not revived")
        assertNotNull(database.tagQueries.selectById(party).executeAsOne().deleted_at, "tag not revived")
    }

    // ---- R4a: an ignored OR IGNORE is a failure ----------------------------------------------

    /**
     * **R4a: an `OR IGNORE` that writes nothing is a failure, never `CREATED`.** A read that
     * misses the row holding the key — exactly what the derived-id lookup did on a two-key
     * database — leads to an insert the unique key blocks. It throws [InsertIgnored], and the
     * transaction around it rolls back: the row that was there is the only row.
     */
    @Test
    fun anInsertTheUniqueKeyIgnoresThrowsRatherThanReportingCreated() {
        val jolene = song("Jolene")
        val will = repository.lookups.add(LookupTableKey.PERFORMER, "Will")
        val held = repository.addSongPerformer(jolene, will, vocal)
        val performer = table(SongChildKey.PERFORMER)
        val derived = performer.id(jolene, listOf(will, vocal))
        // A second row for the same triple under another id: the unique key refuses it.
        val collision = NewSongChild("not-the-stored-id", jolene, listOf(will, vocal), "2026-09-23T11:00:00.000Z")

        assertFailsWith<InsertIgnored> {
            database.insertOrRevive(
                read = { null },
                deletedAt = { _: SongChildRow -> null },
                insert = performer.insert(collision),
                revive = {},
            )
        }

        assertEquals(listOf(held.id), performer.bySong(jolene).map { it.id })
        assertEquals(derived, held.id)
        assertNull(database.song_performerQueries.selectById("not-the-stored-id").executeAsOneOrNull())
    }

    // ---- F22 N8: bySong and copyFacts ----------------------------------------------------------

    /**
     * **F22 N8: `bySong` reads every row** — a tombstoned row and a row under a tombstoned parent
     * included, both of which the display reads hide (R38a) — with each row's stored id, song,
     * parents and tombstone, on all three tables.
     */
    @Test
    fun bySongReadsEveryRowTheDisplayReadsHide() {
        val jolene = song("Jolene")
        val other = song("Valerie")
        val will = repository.lookups.add(LookupTableKey.PERFORMER, "Will")
        val party = repository.lookups.add(LookupTableKey.TAG, "party")
        val slow = repository.lookups.add(LookupTableKey.TAG, "slow")
        repository.addSongPerformer(jolene, will, vocal)
        val removedRow = repository.addSongPerformer(jolene, will, keys).id
        repository.removeSongPerformer(removedRow)
        repository.addSongPerformer(other, will, vocal)
        repository.catalog.addTag(jolene, party)
        repository.catalog.addTag(jolene, slow)
        repository.lookups.remove(LookupTableKey.TAG, slow)
        repository.catalog.addSongInstrument(jolene, keys)
        repository.lookups.remove(LookupTableKey.INSTRUMENT, keys)

        val performers = table(SongChildKey.PERFORMER).bySong(jolene).sortedBy { it.parentIds[1] }
        assertEquals(2, performers.size, "the other song's row is not read")
        assertEquals(setOf(listOf(will, vocal), listOf(will, keys)), performers.map { it.parentIds }.toSet())
        assertNotNull(performers.single { it.id == removedRow }.deletedAt, "the tombstoned row is read")
        assertTrue(performers.all { it.songId == jolene })

        val tags = table(SongChildKey.TAG).bySong(jolene)
        assertEquals(setOf(party, slow), tags.map { it.parentIds.single() }.toSet(), "the removed tag's row is read")
        assertEquals(listOf("party"), repository.catalog.songTags(jolene).map { it.tagName }, "the display read hides it")

        val instruments = table(SongChildKey.INSTRUMENT).bySong(jolene)
        assertEquals(listOf(listOf(keys)), instruments.map { it.parentIds }, "under a removed instrument, still read")
        assertTrue(repository.catalog.songInstruments(jolene).isEmpty(), "the display read hides it")
    }

    /**
     * **F22 N8: `copyFacts` copies a row's facts onto another row, and is null for tags**, which
     * carry none. It is the table's E37-guarded update, so it writes nothing onto a tombstone.
     */
    @Test
    fun copyFactsCopiesTheFactsAndIsAbsentForTags() {
        assertNull(table(SongChildKey.TAG).copyFacts)

        val jolene = song("Jolene")
        val valerie = song("Valerie")
        val from = repository.catalog.addSongInstrument(jolene, keys).id
        val to = repository.catalog.addSongInstrument(valerie, keys).id
        assertTrue(repository.catalog.updateSongInstrument(from, 4L, "Rhodes", "capo 2"))

        val copy = assertNotNull(table(SongChildKey.INSTRUMENT).copyFacts)
        assertTrue(database.wrote { copy(from, to, "2026-09-23T11:00:00.000Z") })
        val copied = repository.catalog.songInstruments(valerie).single()
        assertEquals(Triple(4L, "Rhodes", "capo 2"), Triple(copied.difficulty, copied.patch, copied.notes))

        val will = repository.lookups.add(LookupTableKey.PERFORMER, "Will")
        val lead = repository.addSongPerformer(jolene, will, vocal).id
        val plain = repository.addSongPerformer(valerie, will, vocal).id
        assertTrue(repository.updateSongPerformer(lead, isLead = 1L, vocalRange = 1L, notes = "harmony up"))
        val copyCapability = assertNotNull(table(SongChildKey.PERFORMER).copyFacts)
        assertTrue(database.wrote { copyCapability(lead, plain, "2026-09-23T11:00:00.000Z") })
        val row = database.song_performerQueries.selectById(plain).executeAsOne()
        assertEquals(Triple(1L, 1L, "harmony up"), Triple(row.is_lead, row.vocal_range, row.notes))

        // E37: onto a tombstone, nothing.
        assertTrue(repository.removeSongPerformer(plain).wrote)
        assertEquals(false, database.wrote { copyCapability(lead, plain, "2026-09-23T12:00:00.000Z") })
    }

    private companion object {
        const val DEVICE: String = "test-device"
    }
}
