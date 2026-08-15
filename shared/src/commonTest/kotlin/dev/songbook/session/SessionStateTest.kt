package dev.songbook.session

import dev.songbook.data.SongbookRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Session screen's rules, exercised without a device and without a database.
 *
 * Everything here is the behaviour the user sees within a single practice session: what a
 * tap does to the list, what an undo puts back, and what a second tap on the same song
 * means (decision 47 — it is a second session, not a mistake).
 */
class SessionStateTest {

    private val guitar = "instrument-guitar"
    private val bass = "instrument-bass"

    private val never = SessionRow("s-never", "Mr. Brightside", "The Killers", null, 0L)
    private val cold = SessionRow("s-cold", "Chelsea Dagger", "The Fratellis", 75L, 3L)
    private val warm = SessionRow("s-warm", "Valerie", "The Zutons", 1L, 12L)

    /** The database's order, untouched: never-practised first, then coldest first. */
    private val loaded = SessionState(
        instruments = listOf(InstrumentChip(guitar, "guitar")),
        selectedInstrumentId = guitar,
        rows = listOf(never, cold, warm),
        loading = false,
    )

    private fun tap(id: String, row: SessionRow, instrumentId: String = guitar) = SessionTap(
        tapId = id,
        songId = row.songId,
        instrumentId = instrumentId,
        feel = null,
        note = null,
        loggedOn = "2026-08-15",
    )

    // ---- Ordering ---------------------------------------------------------------------

    @Test
    fun neverPractisedLeadsAndTheBadgeSaysSo() {
        assertEquals(listOf("s-never", "s-cold", "s-warm"), loaded.pending.map { it.songId })
        assertEquals("never", loaded.pending[0].badge)
        assertEquals("75d", loaded.pending[1].badge)
        assertEquals("1d", loaded.pending[2].badge)
        assertEquals("today", warm.copy(daysSince = 0L).badge)
    }

    // ---- Tap --------------------------------------------------------------------------

    @Test
    fun aTapMovesTheRowOutOfTheWay() {
        val after = loaded.plusTap(tap("t1", cold))

        assertEquals(listOf("s-never", "s-warm"), after.pending.map { it.songId })
        assertEquals(listOf("s-cold"), after.logged.map { it.row.songId })
        assertEquals(1, after.logged.single().countThisSession)
        assertEquals("t1", after.undo?.tapId)
        assertEquals("Chelsea Dagger", after.undo?.songTitle)
    }

    /**
     * Decision 8. The row's own staleness is never rewritten, so undoing restores the exact
     * position and the exact badge — including "never" when that log was its only one.
     */
    @Test
    fun undoRestoresThePositionAndTheNeverBadge() {
        val after = loaded.plusTap(tap("t1", never))
        assertEquals(listOf("s-cold", "s-warm"), after.pending.map { it.songId })

        val undone = after.minusTap("t1")

        assertEquals(listOf("s-never", "s-cold", "s-warm"), undone.pending.map { it.songId })
        assertEquals("never", undone.pending[0].badge)
        assertNull(undone.pending[0].daysSince)
        assertTrue(undone.logged.isEmpty())
        assertNull(undone.undo)
    }

    /** Decision 47: two passes at one song in a session are two events and one row. */
    @Test
    fun theSameSongTwiceInOneSessionIsTwoTapsAndOneRow() {
        val after = loaded.plusTap(tap("t1", warm)).plusTap(tap("t2", warm))

        assertEquals(2, after.taps.size)
        assertEquals(1, after.logged.size)
        assertEquals(2, after.logged.single().countThisSession)
        assertEquals("t2", after.undo?.tapId, "undo targets the most recent tap")

        // Undoing the second leaves the first standing: the song stays logged, once.
        val undone = after.minusTap("t2")
        assertEquals(1, undone.logged.single().countThisSession)
        assertTrue(undone.pending.none { it.songId == warm.songId })
    }

    @Test
    fun theMostRecentlyLoggedSongIsAtTheTopOfTheLoggedSection() {
        val after = loaded.plusTap(tap("t1", cold)).plusTap(tap("t2", warm))
        assertEquals(listOf("s-warm", "s-cold"), after.logged.map { it.row.songId })
    }

    /** A log on one instrument must not mark the row logged on another. */
    @Test
    fun tapsAreScopedToTheSelectedInstrument() {
        val after = loaded.plusTap(tap("t1", cold, instrumentId = bass))

        assertTrue(after.logged.isEmpty())
        assertEquals(listOf("s-never", "s-cold", "s-warm"), after.pending.map { it.songId })

        val onBass = after.copy(selectedInstrumentId = bass)
        assertEquals(listOf("s-cold"), onBass.logged.map { it.row.songId })
    }

    @Test
    fun selectingAnInstrumentClearsTheRowsButKeepsTheTaps() {
        val after = loaded.plusTap(tap("t1", cold)).selecting(bass)

        assertTrue(after.loading)
        assertTrue(after.rows.isEmpty())
        assertEquals(1, after.taps.size)
        assertNull(after.undo)
    }

    // ---- Search -----------------------------------------------------------------------

    @Test
    fun searchMatchesTitleAndArtistThroughNormalisation() {
        assertEquals(listOf("s-warm"), loaded.withQuery("valerie").pending.map { it.songId })
        assertEquals(listOf("s-never"), loaded.withQuery("killers").pending.map { it.songId })
        // Normalisation strips punctuation and the leading article, so this still matches.
        assertEquals(listOf("s-never"), loaded.withQuery("mr brightside").pending.map { it.songId })
        assertEquals(listOf("s-never"), loaded.withQuery("Mr. Brightside").pending.map { it.songId })
        assertTrue(loaded.withQuery("zzz").pending.isEmpty())
        assertEquals(3, loaded.withQuery("   ").pending.size)
    }

    @Test
    fun searchAlsoFiltersTheLoggedSection() {
        val after = loaded.plusTap(tap("t1", cold)).withQuery("valerie")
        assertTrue(after.logged.isEmpty())
        assertEquals(listOf("s-warm"), after.pending.map { it.songId })
    }

    // ---- Chips ------------------------------------------------------------------------

    @Test
    fun chipsFollowTheSeedOrderNotTheAlphabet() {
        val chips = SessionInstruments.chips(
            listOf(
                SongbookRepository.Instrument("i-backing", "backing vocal"),
                SongbookRepository.Instrument("i-bass", "bass"),
                SongbookRepository.Instrument("i-guitar", "guitar"),
                SongbookRepository.Instrument("i-keys", "keys"),
                SongbookRepository.Instrument("i-vocal", "vocal"),
            ),
        )
        assertEquals(
            listOf("vocal", "backing vocal", "guitar", "bass", "keys"),
            chips.map { it.name },
        )
        assertEquals(listOf("Vocal", "Backing Vocal", "Guitar", "Bass", "Keys"), chips.map { it.label })
    }

    /** Decision 15: a user-added instrument must need no code change. It lands last. */
    @Test
    fun anInstrumentTheUserAddsStillAppears() {
        val chips = SessionInstruments.chips(
            listOf(
                SongbookRepository.Instrument("i-mandolin", "mandolin"),
                SongbookRepository.Instrument("i-vocal", "vocal"),
                SongbookRepository.Instrument("i-accordion", "accordion"),
            ),
        )
        assertEquals(listOf("vocal", "accordion", "mandolin"), chips.map { it.name })
    }

    @Test
    fun theRememberedChipWinsUnlessItHasGone() {
        val chips = listOf(InstrumentChip("i-vocal", "vocal"), InstrumentChip("i-guitar", "guitar"))
        assertEquals("i-guitar", SessionInstruments.resolve("i-guitar", chips))
        assertEquals("i-vocal", SessionInstruments.resolve(null, chips))
        assertEquals("i-vocal", SessionInstruments.resolve("i-deleted", chips))
        assertNull(SessionInstruments.resolve("i-guitar", emptyList()))
    }
}
