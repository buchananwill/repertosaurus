package dev.repertosaurus.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The View rules that need neither a device nor a database: what a filter means when its
 * components are absent, and which View the app opens on.
 */
class SessionViewsTest {

    private val guitar = "instrument-guitar"
    private val vocal = "instrument-vocal"
    private val will = "performer-will"

    private fun view(id: String, position: Long) = SessionView(
        id = id,
        name = id,
        filter = ViewFilter.NONE,
        practiceInstrumentId = guitar,
        order = SessionOrder.COLDEST_FIRST,
        position = position,
    )

    // ---- ViewFilter ---------------------------------------------------------------------

    /** V11: absent means "no constraint", and all three absent means the whole repertoire. */
    @Test
    fun onlyAFilterWithNoComponentsAtAllIsUnfiltered() {
        assertTrue(ViewFilter.NONE.unfiltered)
        assertTrue(ViewFilter().unfiltered)
        assertFalse(ViewFilter(performerId = will).unfiltered)
        assertFalse(ViewFilter(instrumentId = vocal).unfiltered)
        assertFalse(ViewFilter(leadOnly = true).unfiltered)
    }

    /** The 0/1 the `filter_lead_only` CHECK and the `:leadOnly` bind both want. */
    @Test
    fun leadOnlyCrossesTheDatabaseBoundaryAsZeroOrOne() {
        assertEquals(0L, ViewFilter.NONE.leadOnlyFlag)
        assertEquals(1L, ViewFilter(leadOnly = true).leadOnlyFlag)
        assertEquals(
            ViewFilter(will, vocal, true),
            ViewFilter.of(will, vocal, leadOnlyFlag = 1L),
        )
        assertEquals(ViewFilter.NONE, ViewFilter.of(null, null, leadOnlyFlag = 0L))
    }

    /** V21: the stand-in for "no Views yet" is not a saved View and never has an id. */
    @Test
    fun theUnsavedViewIsTodaysBehaviourAndCarriesNoId() {
        val unsaved = SessionView.unsaved(guitar, SessionOrder.HOTTEST_FIRST)

        assertFalse(unsaved.saved)
        assertEquals("", unsaved.id)
        assertEquals(ViewFilter.NONE, unsaved.filter)
        assertEquals(guitar, unsaved.practiceInstrumentId)
        assertEquals(SessionOrder.HOTTEST_FIRST, unsaved.order)
        assertEquals(SessionOrder.COLDEST_FIRST, SessionView.unsaved(guitar).order)
    }

    // ---- The home View ------------------------------------------------------------------

    /**
     * V20: the remembered View if it is still there, otherwise the first by `(position,
     * id)` — which is the order the query returns, so this takes the head. A View deleted on
     * another device must never leave the app opening on nothing.
     */
    @Test
    fun theRememberedViewWinsUnlessItHasGone() {
        val views = listOf(view("v-first", 0), view("v-second", 1))

        assertEquals("v-second", SessionViews.resolve("v-second", views)?.id)
        assertEquals("v-first", SessionViews.resolve(null, views)?.id)
        assertEquals(
            "v-first",
            SessionViews.resolve("v-deleted-on-another-device", views)?.id,
            "a stale home id falls back to the first, never to nothing",
        )
        assertNull(SessionViews.resolve("v-first", emptyList()))
    }

    /** The unsaved View has an empty id, so a stale preference can never resolve to it. */
    @Test
    fun aStaleHomeIdCannotResolveToTheUnsavedView() {
        assertNull(SessionViews.resolve(SessionView.UNSAVED_ID, emptyList()))
        assertEquals(
            "v-first",
            SessionViews.resolve(SessionView.UNSAVED_ID, listOf(view("v-first", 0)))?.id,
        )
    }

    // ---- SessionState, now that the View owns the instrument and the direction -----------

    /** The roster's change: `selectedInstrumentId` is the View's, not free-standing state. */
    @Test
    fun theSelectedInstrumentAndTheDirectionComeFromTheActiveView() {
        val empty = SessionState()
        assertNull(empty.selectedInstrumentId)
        assertEquals(SessionOrder.COLDEST_FIRST, empty.order)

        val active = empty.switchingTo(
            SessionView.unsaved(guitar, SessionOrder.HOTTEST_FIRST),
        )
        assertEquals(guitar, active.selectedInstrumentId)
        assertEquals(SessionOrder.HOTTEST_FIRST, active.order)

        // The toggle rewrites the View's direction; persisting it is the caller's job.
        val flipped = active.withOrder(SessionOrder.COLDEST_FIRST)
        assertEquals(SessionOrder.COLDEST_FIRST, flipped.order)
        assertEquals(SessionOrder.COLDEST_FIRST, flipped.view?.order)
    }

    // ---- V13a: the chip tap forks -------------------------------------------------------

    /**
     * V13a. Tapping an instrument chip while a saved View is active **forks to an unsaved
     * View**; it never edits the saved row. The fork keeps the filter and the direction and
     * takes the tapped instrument.
     */
    @Test
    fun tappingAChipForksToAnUnsavedViewRatherThanEditingTheSavedRow() {
        val saved = SessionView(
            id = "v-1",
            name = "Will sings, guitar",
            filter = ViewFilter(will, vocal, true),
            practiceInstrumentId = guitar,
            order = SessionOrder.HOTTEST_FIRST,
            position = 3L,
            notes = "the motivating case",
        )

        val forked = saved.forkedTo(vocal)

        assertFalse(forked.saved, "a chip tap must never produce a row to write")
        assertEquals(SessionView.UNSAVED_ID, forked.id)
        assertEquals(ViewFilter(will, vocal, true), forked.filter, "the filter is kept")
        assertEquals(SessionOrder.HOTTEST_FIRST, forked.order, "and so is the direction")
        assertEquals(vocal, forked.practiceInstrumentId)

        // The saved View is untouched — this is a value, and nothing was persisted.
        assertEquals(guitar, saved.practiceInstrumentId)
        assertEquals(3L, saved.position)
        assertEquals("the motivating case", saved.notes)
        assertEquals("", forked.name)
        assertEquals(0L, forked.position)
        assertNull(forked.notes)
    }

    // ---- The query signature -------------------------------------------------------------

    /**
     * Two Views produce the same rows exactly when identity, filter and practice instrument
     * agree. This guards a coroutine race — dropping the answer to a query the user has
     * already moved on from — that every UI has to guard identically.
     */
    @Test
    fun twoViewsShareAQueryOnlyWhenIdentityFilterAndPracticeInstrumentAgree() {
        val base = SessionView(
            id = "v-1",
            name = "Will sings, guitar",
            filter = ViewFilter(will, vocal, true),
            practiceInstrumentId = guitar,
            order = SessionOrder.COLDEST_FIRST,
            position = 0L,
        )

        assertTrue(base.sameQuery(base.copy()))
        // The direction is applied in Kotlin (V14), so it never changes the query.
        assertTrue(base.sameQuery(base.copy(order = SessionOrder.HOTTEST_FIRST)))
        // Nor do the fields the query does not read.
        assertTrue(base.sameQuery(base.copy(name = "renamed", position = 9L, notes = "x")))

        assertFalse(base.sameQuery(null))
        assertFalse(base.sameQuery(base.copy(id = "v-2")))
        assertFalse(base.sameQuery(base.copy(filter = ViewFilter.NONE)))
        assertFalse(base.sameQuery(base.copy(practiceInstrumentId = vocal)))
        // A fork is a different View by both id and instrument.
        assertFalse(base.sameQuery(base.forkedTo(vocal)))
    }

    // ---- V17, V17a: the stored direction ---------------------------------------------------

    /**
     * V17's fallback. A stored `sort_order` this build cannot parse is a row a later build
     * wrote — a third direction, say — and coldest first is the default that never surprises.
     * The parse is one implementation for `saved_view.sort_order` and for the preference
     * alike; it was written twice, verbatim, before it was extracted.
     */
    @Test
    fun anUnparseableStoredDirectionFallsBackToColdestFirst() {
        assertEquals(SessionOrder.COLDEST_FIRST, SessionOrder.parse("COLDEST_FIRST"))
        assertEquals(SessionOrder.HOTTEST_FIRST, SessionOrder.parse("HOTTEST_FIRST"))
        assertEquals(SessionOrder.COLDEST_FIRST, SessionOrder.parse("MOST_PRACTISED"))
        assertEquals(SessionOrder.COLDEST_FIRST, SessionOrder.parse(""))
        assertEquals(SessionOrder.COLDEST_FIRST, SessionOrder.parse(null))
        assertEquals(SessionOrder.COLDEST_FIRST, SessionOrder.parse("coldest_first"))
    }

    /** Round trip: every constant survives being written out and read back by name. */
    @Test
    fun everyDirectionRoundTripsThroughItsStoredName() {
        for (order in SessionOrder.entries) {
            assertEquals(order, SessionOrder.parse(order.name))
        }
    }

    // ---- The filter in words ---------------------------------------------------------------

    /**
     * The composition rule of the filter summary: which parts appear, in what order, joined
     * how. The *lookup* stays a UI concern and arrives as two functions, which is what makes
     * the rule testable here at all — `SessionRow.badge` lives in the core for the same
     * reason.
     */
    @Test
    fun theFilterSummaryNamesEachPresentComponentInOrder() {
        val performer = { id: String -> if (id == will) "Will" else null }
        val instrument = { id: String -> if (id == vocal) "vocal" else null }

        assertEquals(
            "Every song",
            ViewSummary.summarise(ViewFilter.NONE, performer, instrument),
        )
        assertEquals(
            "Will",
            ViewSummary.summarise(ViewFilter(performerId = will), performer, instrument),
        )
        assertEquals(
            "vocal",
            ViewSummary.summarise(ViewFilter(instrumentId = vocal), performer, instrument),
        )
        assertEquals(
            "lead only",
            ViewSummary.summarise(ViewFilter(leadOnly = true), performer, instrument),
        )
        assertEquals(
            "Will · vocal · lead only",
            ViewSummary.summarise(ViewFilter(will, vocal, true), performer, instrument),
        )
    }

    /** V24: the View holds ids, so a row that has gone reads as a gap rather than vanishing. */
    @Test
    fun theFilterSummaryAdmitsAnIdWhoseRowHasGone() {
        val nothing = { _: String -> null }

        assertEquals(
            "a deleted performer · no instrument · lead only",
            ViewSummary.summarise(ViewFilter(will, vocal, true), nothing, nothing),
        )
    }

    /**
     * **E47, E42: the predicate that tells "your filter names someone who is gone" apart from
     * "you have no songs".**
     *
     * Without it the session screen rendered *"No songs yet. Use Import to load your database
     * file"* over a 479-song repertoire, pointing the user at the one destructive path in the
     * app. It is a rule and it lives beside the summary; the composable branches on it and
     * decides nothing.
     */
    @Test
    fun aFilterNamingARemovedRowIsDistinguishableFromAnEmptyRepertoire() {
        val performer = { id: String -> if (id == will) "Will" else null }
        val instrument = { id: String -> if (id == vocal) "vocal" else null }

        // Nothing named, nothing to be missing — an empty list here really is an empty list.
        assertFalse(ViewSummary.namesRemovedRow(ViewFilter.NONE, performer, instrument))
        assertFalse(
            ViewSummary.namesRemovedRow(ViewFilter(leadOnly = true), performer, instrument),
            "lead-only names no row and can never go stale",
        )

        // Both components resolve: the filter is intact and the list is honestly empty.
        assertFalse(
            ViewSummary.namesRemovedRow(ViewFilter(will, vocal, true), performer, instrument),
        )

        // Either half gone is enough, and it is the *named* half that matters.
        assertTrue(
            ViewSummary.namesRemovedRow(ViewFilter(performerId = "gone"), performer, instrument),
        )
        assertTrue(
            ViewSummary.namesRemovedRow(ViewFilter(instrumentId = "gone"), performer, instrument),
        )
        assertTrue(
            ViewSummary.namesRemovedRow(ViewFilter(will, "gone", false), performer, instrument),
            "an intact performer does not excuse a removed instrument",
        )
    }
}
