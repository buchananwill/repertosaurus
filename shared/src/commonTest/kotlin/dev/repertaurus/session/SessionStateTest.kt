package dev.repertaurus.session

import dev.repertaurus.data.RepertaurusRepository
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
        view = SessionView.unsaved(guitar),
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

    /**
     * The toggle. A never-practised song has no last practice at all, so it leads coldest
     * first and trails hottest first — "never" is not "hot", and it must not sort to the
     * top there merely because its value is missing.
     */
    @Test
    fun hottestFirstReversesTheOrderAndSendsNeverPractisedToTheBottom() {
        val hottest = loaded.withOrder(SessionOrder.HOTTEST_FIRST)

        assertEquals(listOf("s-warm", "s-cold", "s-never"), hottest.pending.map { it.songId })
        assertEquals("never", hottest.pending.last().badge)
        assertEquals(
            listOf("s-never", "s-cold", "s-warm"),
            hottest.withOrder(SessionOrder.COLDEST_FIRST).pending.map { it.songId },
        )
    }

    @Test
    fun severalNeverPractisedSongsStayTogetherAtTheRightEnd() {
        val alsoNever = SessionRow("s-never-2", "Dakota", "Stereophonics", null, 0L)
        val state = loaded.copy(rows = loaded.rows + alsoNever)

        // Alphabetical within the never group, at the top coldest-first...
        assertEquals(
            listOf("s-never-2", "s-never", "s-cold", "s-warm"),
            state.pending.map { it.songId },
        )
        // ...and at the bottom hottest-first, in the same internal order.
        assertEquals(
            listOf("s-warm", "s-cold", "s-never-2", "s-never"),
            state.withOrder(SessionOrder.HOTTEST_FIRST).pending.map { it.songId },
        )
    }

    @Test
    fun theDefaultOrderIsColdestFirst() {
        assertEquals(SessionOrder.COLDEST_FIRST, SessionState().order)
        assertEquals(SessionOrder.HOTTEST_FIRST, SessionOrder.COLDEST_FIRST.flipped)
        assertEquals(SessionOrder.COLDEST_FIRST, SessionOrder.HOTTEST_FIRST.flipped)
    }

    @Test
    fun theToggleDoesNotDisturbTheLoggedSection() {
        val after = loaded.plusTap(tap("t1", cold)).withOrder(SessionOrder.HOTTEST_FIRST)

        assertEquals(listOf("s-warm", "s-never"), after.pending.map { it.songId })
        assertEquals(listOf("s-cold"), after.logged.map { it.row.songId })
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

        val onBass = after.copy(view = SessionView.unsaved(bass))
        assertEquals(listOf("s-cold"), onBass.logged.map { it.row.songId })
    }

    /**
     * V22, as reversed. Switching View is a full reload of the rows and it clears the pending
     * **undo offer** — which refers to a tap the user is no longer looking at — but it does
     * **not** clear the session's optimistic taps.
     *
     * An earlier draft cleared them. That broke the ordinary practice motion of flicking
     * guitar → bass → guitar, which must return you to your logged list.
     */
    @Test
    fun switchingViewClearsTheRowsAndTheUndoButKeepsTheTaps() {
        val after = loaded.plusTap(tap("t1", cold)).switchingTo(SessionView.unsaved(bass))

        assertTrue(after.loading)
        assertTrue(after.rows.isEmpty())
        assertEquals(listOf("t1"), after.taps.map { it.tapId }, "a tap is never voided")
        assertNull(after.undo)
        assertEquals(bass, after.selectedInstrumentId)
        // The tap was logged on guitar, so it is out of sight in a bass View — `tapsHere`
        // scopes by practice instrument, without anything being thrown away.
        assertTrue(after.tapsHere.isEmpty())
    }

    /**
     * And the flick back. guitar → bass → guitar returns the musician to the list they were
     * building, which is the whole reason V22 was reversed.
     */
    @Test
    fun flickingToAnotherInstrumentAndBackRestoresTheLoggedList() {
        val there = loaded.plusTap(tap("t1", cold)).switchingTo(SessionView.unsaved(bass))
        val back = there.switchingTo(SessionView.unsaved(guitar)).withRows(loaded.rows)

        assertEquals(listOf("s-cold"), back.logged.map { it.row.songId })
        assertEquals(listOf("s-never", "s-warm"), back.pending.map { it.songId })
    }

    /**
     * A song that the new View's filter excludes drops out on its own: [SessionState.logged]
     * resolves each tap against the rows the View actually returned, so nothing has to be
     * cleared to keep it off the screen.
     */
    @Test
    fun aTapOnASongTheNewViewDoesNotContainSimplyDoesNotAppear() {
        val after = loaded.plusTap(tap("t1", cold))
            .switchingTo(SessionView.unsaved(guitar))
            .withRows(listOf(never, warm))

        assertEquals(listOf("t1"), after.taps.map { it.tapId })
        assertTrue(after.logged.isEmpty(), "the row is not in this View, so it is not shown")
    }

    /**
     * D2. The toggle moves three things, and a switcher rendering from `views` reverts the
     * direction on switch-away-and-back unless the list entry moves too.
     */
    @Test
    fun theToggleRewritesTheMatchingEntryInTheViewList() {
        val saved = SessionView(
            id = "v-1",
            name = "Will sings, guitar",
            filter = ViewFilter.NONE,
            practiceInstrumentId = guitar,
            order = SessionOrder.COLDEST_FIRST,
            position = 0L,
        )
        val other = saved.copy(id = "v-2", name = "other", position = 1L)
        val state = loaded.withViews(listOf(saved, other)).switchingTo(saved)

        val flipped = state.withOrder(SessionOrder.HOTTEST_FIRST)

        assertEquals(SessionOrder.HOTTEST_FIRST, flipped.view?.order)
        assertEquals(
            SessionOrder.HOTTEST_FIRST,
            flipped.views.single { it.id == "v-1" }.order,
            "the switcher renders from `views`, so the active entry has to move with it",
        )
        assertEquals(
            SessionOrder.COLDEST_FIRST,
            flipped.views.single { it.id == "v-2" }.order,
            "and no other View moves",
        )
    }

    /** And before the first View has loaded the toggle is recorded rather than swallowed. */
    @Test
    fun theToggleIsNotASilentNoOpBeforeAViewHasLoaded() {
        val loading = SessionState()
        assertNull(loading.view)
        assertEquals(SessionOrder.COLDEST_FIRST, loading.order)

        val flipped = loading.withOrder(SessionOrder.HOTTEST_FIRST)

        assertEquals(SessionOrder.HOTTEST_FIRST, flipped.order)
        assertEquals(SessionOrder.HOTTEST_FIRST, flipped.fallbackOrder)
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
                RepertaurusRepository.Instrument("i-backing", "backing vocal"),
                RepertaurusRepository.Instrument("i-bass", "bass"),
                RepertaurusRepository.Instrument("i-guitar", "guitar"),
                RepertaurusRepository.Instrument("i-keys", "keys"),
                RepertaurusRepository.Instrument("i-vocal", "vocal"),
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
                RepertaurusRepository.Instrument("i-mandolin", "mandolin"),
                RepertaurusRepository.Instrument("i-vocal", "vocal"),
                RepertaurusRepository.Instrument("i-accordion", "accordion"),
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
