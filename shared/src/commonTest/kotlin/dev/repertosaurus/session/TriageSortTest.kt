package dev.repertosaurus.session

import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.core.RatingLevel.CERTAINLY
import dev.repertosaurus.core.RatingLevel.EXCEPTIONALLY
import dev.repertosaurus.core.RatingLevel.NOT_AT_ALL
import dev.repertosaurus.core.RatingLevel.SOMEWHAT
import dev.repertosaurus.data.PartRatings
import dev.repertosaurus.data.RepertosaurusRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **triage T6-T9**: the two-control sort, the four triage comparators, unrated-last, and whose ratings a
 * session row carries.
 *
 * The fixture's expected orders are worked by hand from T7's wording, key by key, not by running the
 * comparator. It ties at every key: two rows share priority and confidence and differ in staleness (b, a),
 * two share every key but the title (a, k), a never-practised row ties a practised one (f, e), and an
 * unrated value sits at each key in both directions (d, g, h, i). **The rows go in scrambled.**
 */
class TriageSortTest {

    private fun row(id: String, priority: RatingLevel?, confidence: RatingLevel?, daysSince: Long?) =
        SessionRow("s-$id", id.uppercase(), "Artist", daysSince, 1L, priority, confidence)

    private val a = row("a", EXCEPTIONALLY, NOT_AT_ALL, 10L)
    private val b = row("b", EXCEPTIONALLY, NOT_AT_ALL, 50L)
    private val c = row("c", EXCEPTIONALLY, CERTAINLY, 5L)
    private val d = row("d", EXCEPTIONALLY, null, 100L)
    private val e = row("e", SOMEWHAT, SOMEWHAT, 20L)
    private val f = row("f", SOMEWHAT, SOMEWHAT, null)
    private val g = row("g", null, NOT_AT_ALL, 30L)
    private val h = row("h", null, null, 7L)
    private val i = row("i", null, null, null)
    private val j = row("j", NOT_AT_ALL, EXCEPTIONALLY, 1L)
    private val k = row("k", EXCEPTIONALLY, NOT_AT_ALL, 10L)

    /** Not the answer to any order below, forwards or backwards. */
    private val scrambled = listOf(h, c, j, a, i, e, k, d, g, b, f)

    private fun sorted(order: SessionOrder, rows: List<SessionRow> = scrambled): String =
        rows.sortedWith(order.comparator).joinToString("") { it.songId.removePrefix("s-") }

    // ---- T7, with T8 at every key -------------------------------------------------------------

    /**
     * Priority 3 first (b, a, k tie on confidence 0: coldest first, then A before K; then c at 2; then d,
     * confidence unrated), then priority 1 (f never practised before e), then 0 (j), then priority unrated
     * last (g rated confidence, then i never before h).
     */
    @Test
    fun triagePriorityIsPriorityDownThenConfidenceUpThenColdest() {
        assertEquals("bakcdfejgih", sorted(SessionOrder.TRIAGE_PRIORITY))
    }

    /** Priority 0 first, then 1 (hottest: e, then never f), then 3 (confidence 2, then 0 hottest, then unrated), then unrated. */
    @Test
    fun triagePriorityReversedIsTheExactReverseEndingInHottest() {
        assertEquals("jefcakbdghi", sorted(SessionOrder.TRIAGE_PRIORITY_REVERSED))
    }

    /** Confidence 0 (priority 3 coldest first, then g's unrated priority), 1, 2, 3, then unrated (d rated, then i, h). */
    @Test
    fun triageConfidenceIsConfidenceUpThenPriorityDownThenColdest() {
        assertEquals("bakgfecjdih", sorted(SessionOrder.TRIAGE_CONFIDENCE))
    }

    /** Confidence 3, 2, 1 (hottest: e, f), 0 (priority 3 hottest, then g), then unrated (d, then h, i). */
    @Test
    fun triageConfidenceReversedIsTheExactReverseEndingInHottest() {
        assertEquals("jcefakbgdhi", sorted(SessionOrder.TRIAGE_CONFIDENCE_REVERSED))
    }

    /**
     * **T8: unrated is no statement, not 0.** A priority of 0 leads the reverse and an unrated one does not;
     * a priority of 0 trails the rated ones "need first" and an unrated one trails it too.
     */
    @Test
    fun unratedSortsAfterEveryRatedValueInBothDirections() {
        val zero = row("zero", NOT_AT_ALL, null, 10L)
        val none = row("none", null, null, 500L)
        val three = row("three", EXCEPTIONALLY, null, 1L)
        val rows = listOf(none, zero, three)
        assertEquals("threezeronone", sorted(SessionOrder.TRIAGE_PRIORITY, rows))
        assertEquals("zerothreenone", sorted(SessionOrder.TRIAGE_PRIORITY_REVERSED, rows))

        val confidentZero = row("zero", null, NOT_AT_ALL, 10L)
        val unsure = row("none", null, null, 500L)
        val sure = row("three", null, EXCEPTIONALLY, 1L)
        val byConfidence = listOf(unsure, sure, confidentZero)
        assertEquals("zerothreenone", sorted(SessionOrder.TRIAGE_CONFIDENCE, byConfidence))
        assertEquals("threezeronone", sorted(SessionOrder.TRIAGE_CONFIDENCE_REVERSED, byConfidence))
    }

    /** T8: with nothing rated, every triage sort is plain staleness, in its direction. */
    @Test
    fun withNothingRatedEveryTriageSortDegradesToStaleness() {
        val unrated = scrambled.map { it.ratedBy(null) }
        val coldest = sorted(SessionOrder.COLDEST_FIRST, unrated)
        val hottest = sorted(SessionOrder.HOTTEST_FIRST, unrated)
        assertEquals("fidbgeakhcj", coldest)
        assertEquals("jchakegbdfi", hottest)
        assertEquals(coldest, sorted(SessionOrder.TRIAGE_PRIORITY, unrated))
        assertEquals(coldest, sorted(SessionOrder.TRIAGE_CONFIDENCE, unrated))
        assertEquals(hottest, sorted(SessionOrder.TRIAGE_PRIORITY_REVERSED, unrated))
        assertEquals(hottest, sorted(SessionOrder.TRIAGE_CONFIDENCE_REVERSED, unrated))
    }

    /** V14: the screen's list is re-sorted in the core, from rows the database gave in any order. */
    @Test
    fun thePendingListSortsScrambledRowsByTheTriageOrder() {
        val state = SessionState(view = SessionView.unsaved("guitar"), rows = scrambled, loading = false)
            .withOrder(SessionOrder.TRIAGE_PRIORITY)
        assertEquals("bakcdfejgih", state.pending.joinToString("") { it.songId.removePrefix("s-") })
    }

    // ---- T6: a mode and a direction, one stored order --------------------------------------

    @Test
    fun aModeAndADirectionResolveToTheOneStoredOrder() {
        val table = mapOf(
            (SortMode.STALENESS to false) to SessionOrder.COLDEST_FIRST,
            (SortMode.STALENESS to true) to SessionOrder.HOTTEST_FIRST,
            (SortMode.TRIAGE_PRIORITY to false) to SessionOrder.TRIAGE_PRIORITY,
            (SortMode.TRIAGE_PRIORITY to true) to SessionOrder.TRIAGE_PRIORITY_REVERSED,
            (SortMode.TRIAGE_CONFIDENCE to false) to SessionOrder.TRIAGE_CONFIDENCE,
            (SortMode.TRIAGE_CONFIDENCE to true) to SessionOrder.TRIAGE_CONFIDENCE_REVERSED,
        )
        for ((key, order) in table) {
            assertEquals(order, SessionOrder.of(key.first, key.second))
            assertEquals(key.first, order.mode)
            assertEquals(key.second, order.reversed)
        }
        assertEquals(SessionOrder.entries.toSet(), table.values.toSet(), "every order has exactly one cell")
    }

    @Test
    fun theDirectionFlipsWithinTheModeAndAModeChangeKeepsTheDirection() {
        for (order in SessionOrder.entries) {
            assertEquals(order.mode, order.flipped.mode)
            assertEquals(!order.reversed, order.flipped.reversed)
            assertEquals(order, order.flipped.flipped)
        }
        assertEquals(SessionOrder.TRIAGE_CONFIDENCE_REVERSED, SessionOrder.HOTTEST_FIRST.withMode(SortMode.TRIAGE_CONFIDENCE))
        assertEquals(SessionOrder.COLDEST_FIRST, SessionOrder.TRIAGE_PRIORITY.withMode(SortMode.STALENESS))
        assertFalse(SortMode.STALENESS.readsRatings)
        assertTrue(SortMode.TRIAGE_PRIORITY.readsRatings)
        assertTrue(SortMode.TRIAGE_CONFIDENCE.readsRatings)
    }

    // ---- T9: whose ratings, through the session state -----------------------------------------

    private val will = RepertosaurusRepository.Performer("p-will", "Will")
    private val coralie = RepertosaurusRepository.Performer("p-coralie", "Coralie")
    private val performers = listOf(will, coralie)

    private fun viewFiltering(performerId: String?) = SessionView(
        id = "v",
        name = "View",
        filter = ViewFilter(performerId = performerId, instrumentId = null, leadOnly = false),
        practiceInstrumentId = "i-guitar",
        order = SessionOrder.TRIAGE_PRIORITY,
        position = 0L,
    )

    private fun state(filterPerformerId: String?, owner: String?, known: List<RepertosaurusRepository.Performer>?) =
        SessionState(view = viewFiltering(filterPerformerId), loading = false)
            .withOwnerPerformer(owner)
            .let { if (known == null) it else it.withPerformers(known) }

    /** T9 branch 1: the View's filter performer, on the View's practice instrument, over the owner. */
    @Test
    fun theFilterPerformerIsWhoseRatingsTheRowsRead() {
        val state = state(filterPerformerId = "p-coralie", owner = "p-will", known = performers)
        assertEquals(PartResolution.Resolved(ResolvedPart("p-coralie", "Coralie", "i-guitar")), state.part)
        assertEquals(ResolvedPart("p-coralie", "Coralie", "i-guitar"), state.resolvedPart)
        assertTrue(state.triageAvailable)
    }

    /** T9 branch 2: no filter performer, so the owner (T10). */
    @Test
    fun withNoFilterPerformerTheOwnerIsWhoseRatingsTheRowsRead() {
        val state = state(filterPerformerId = null, owner = "p-will", known = performers)
        assertEquals(ResolvedPart("p-will", "Will", "i-guitar"), state.resolvedPart)
        assertTrue(state.triageAvailable)
    }

    /** T9 branch 3: nobody, so the triage modes are disabled. A removed filter performer is nobody (D59 #5). */
    @Test
    fun withNoPerformerTheTriageModesAreUnavailable() {
        val nobody = state(filterPerformerId = null, owner = null, known = performers)
        assertEquals(PartResolution.None, nobody.part)
        assertNull(nobody.resolvedPart)
        assertFalse(nobody.triageAvailable)

        val removed = state(filterPerformerId = "p-gone", owner = "p-will", known = performers)
        assertEquals(PartResolution.None, removed.part, "a removed filter performer is not replaced by the owner")
        assertFalse(removed.triageAvailable)
    }

    /** F20 N2: before the performers are read, nothing is said, and the modes are not disabled for it. */
    @Test
    fun beforeThePerformersAreReadThePartIsPendingNotNobody() {
        val unread = state(filterPerformerId = null, owner = "p-will", known = null)
        assertEquals(PartResolution.Pending, unread.part)
        assertTrue(unread.triageAvailable)
        assertEquals(PartResolution.Pending, SessionState(performers = performers).part, "and with no View")
    }

    /** The rows carry the resolved part's ratings; a part change makes them stale until they are read again. */
    @Test
    fun ratingsLandOnTheRowsForThePartTheyWereReadFor() {
        val will = ResolvedPart("p-will", "Will", "i-guitar")
        val plain = listOf(a, b, c).map { it.ratedBy(null) }
        val loaded = state(filterPerformerId = null, owner = "p-will", known = performers).withRows(plain)
        assertTrue(loaded.ratingsStale, "rows read with no part, and a part now resolves")

        val rated = loaded.withRatings(will, mapOf("s-b" to PartRatings(EXCEPTIONALLY, null)))
        assertFalse(rated.ratingsStale)
        assertEquals(listOf(null, EXCEPTIONALLY, null), rated.rows.map { it.priority })
        assertEquals("bac", rated.withOrder(SessionOrder.TRIAGE_PRIORITY).pending.joinToString("") { it.title.lowercase() })

        val ownerCleared = rated.withOwnerPerformer(null)
        assertTrue(ownerCleared.ratingsStale, "the ratings are Will's and nobody resolves now")
        val unrated = ownerCleared.withRatings(null, emptyMap())
        assertEquals(listOf<RatingLevel?>(null, null, null), unrated.rows.map { it.priority })
        assertFalse(unrated.ratingsStale)
    }
}
