package dev.repertosaurus.session

import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.data.PartRatings
import dev.repertosaurus.data.RepertosaurusRepository.Performer
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** suggest SG5-SG10 and §3's [v1] verification. */
class SuggesterTest {

    private fun candidate(id: String, days: Long?) = SuggestCandidate(songId = id, daysSince = days)

    private fun tuned(cold: Double = 0.0, hot: Double = 0.0) =
        SuggestTuning.of(SuggestSpoke.COLDNESS to cold, SuggestSpoke.HOTNESS to hot)

    private val SuggestionDeck.current: SuggestCandidate? get() = (this as? SuggestionDeck.Showing)?.current

    /** Never, never, 40d, 10d, 10d, 2d: two ties and the never-practised. */
    private val mixed = listOf(
        candidate("never-a", null),
        candidate("never-b", null),
        candidate("forty", 40L),
        candidate("ten-a", 10L),
        candidate("ten-b", 10L),
        candidate("two", 2L),
    )

    // ---- SG8: the weighting ---------------------------------------------------------------

    /** SG8: **every radius at zero is pure shuffle** — equal, not approximately equal. */
    @Test
    fun allZeroWeightsAreExactlyEqual() {
        val weights = Suggester.weights(mixed, SuggestTuning.DEFAULT)
        assertEquals(mixed.size, weights.size)
        assertTrue(weights.all { it == 1.0 }, "every weight is exactly 1.0: $weights")
    }

    /** SG8: one spoke at full radius makes the neediest exactly 16x the least. */
    @Test
    fun aSingleFullSpokeGivesSixteenToOneBetweenTheExtremes() {
        val cold = Suggester.weights(mixed, tuned(cold = 1.0))
        assertEquals(16.0, cold.first(), "never practised is the neediest under coldness")
        assertEquals(1.0, cold.last(), "the hottest is the least")
        assertEquals(16.0, cold.max() / cold.min())

        val hot = Suggester.weights(mixed, tuned(hot = 1.0))
        assertEquals(1.0, hot.first(), "never practised is the least under hotness")
        assertEquals(16.0, hot.last(), "the hottest is the neediest")
    }

    /** SG10: equal coldness and hotness radii cancel, exactly, at every step of the handle. */
    @Test
    fun coldnessAndHotnessAtEqualRadiiGiveEqualWeights() {
        for (step in 0..20) {
            val r = SuggestTuning.snap(step * SuggestTuning.STEP)
            val weights = Suggester.weights(mixed, tuned(cold = r, hot = r))
            assertEquals(1, weights.toSet().size, "radius $r on both: $weights")
        }
    }

    /** SG10: the larger radius wins by the difference. */
    @Test
    fun theLargerOfColdnessAndHotnessWinsByTheDifference() {
        val both = Suggester.weights(mixed, tuned(cold = 0.75, hot = 0.25))
        val difference = Suggester.weights(mixed, tuned(cold = 0.5))
        val ratios = both.zip(difference) { a, b -> a / b }
        for (ratio in ratios) assertEquals(ratios.first(), ratio, 1e-12, "a constant factor: $ratios")
    }

    // ---- SG9 [v2]: priority, confidence and skips ----------------------------------------------
    //
    // Every expected weight below is worked by hand from SG8 (`16^Σ rₖ·fₖ`) and SG9's rows, never read
    // back from the code. All candidates share one staleness, so the coldness term is zero throughout.

    private fun rated(id: String, priority: RatingLevel? = null, confidence: RatingLevel? = null, skips: Int = 0) =
        SuggestCandidate(id, daysSince = 5L, priority = priority, confidence = confidence, skips = skips)

    private val levels = RatingLevel.entries.map { rated(it.name, priority = it, confidence = it) } + rated("unrated")

    /** SG9 priority, `level / 3`, at radius 0.75: `16^(0.75·k/3) = 2^k`, so 1, 2, 4, 8; unrated is 1. */
    @Test
    fun priorityWeighsLevelOverThree() {
        val weights = Suggester.weights(levels, SuggestTuning.of(SuggestSpoke.PRIORITY to 0.75))
        assertClose(listOf(1.0, 2.0, 4.0, 8.0, 1.0), weights)
    }

    /** SG9 confidence, `(3 − level) / 3`, at radius 0.75: `2^(3−k)`, so 8, 4, 2, 1; unrated is 1. */
    @Test
    fun confidenceWeighsLowerConfidenceAsMoreNeed() {
        val weights = Suggester.weights(levels, SuggestTuning.of(SuggestSpoke.CONFIDENCE to 0.75))
        assertClose(listOf(8.0, 4.0, 2.0, 1.0, 1.0), weights)
    }

    /** SG9: **unrated contributes 0 on every rating spoke**, even at full radius, never an assumed level. */
    @Test
    fun unratedIsZeroOnEveryRatingSpoke() {
        val pool = listOf(rated("unrated"), rated("not-at-all", priority = RatingLevel.NOT_AT_ALL, confidence = RatingLevel.EXCEPTIONALLY))
        val weights = Suggester.weights(pool, SuggestTuning.of(SuggestSpoke.PRIORITY to 1.0, SuggestSpoke.CONFIDENCE to 1.0))
        assertEquals(listOf(1.0, 1.0), weights, "unrated weighs as priority 0 and confidence 3: nothing")
        assertEquals(0.0, Suggester.priorityNeed(null))
        assertEquals(0.0, Suggester.confidenceNeed(null))
    }

    /** SG9 skips, `min(skips, 10) / 10`, at radius 1: 0 → 1, 5 → 16^0.5 = 4, 10 → 16, 25 → 16 (capped). */
    @Test
    fun skipsWeighUpToTenAndNoFurther() {
        val pool = listOf(rated("none"), rated("five", skips = 5), rated("ten", skips = 10), rated("many", skips = 25))
        assertClose(listOf(1.0, 4.0, 16.0, 16.0), Suggester.weights(pool, SuggestTuning.of(SuggestSpoke.SKIPS to 1.0)))
        assertClose(listOf(1.0, 2.0, 4.0, 4.0), Suggester.weights(pool, SuggestTuning.of(SuggestSpoke.SKIPS to 0.5)))
    }

    /**
     * SG8 sums the spokes. X is never practised (coldness 1), priority 3, confidence 0 and ten skips, so
     * every f is 1; Y is the hottest, unrated and unskipped, so every f is 0 but hotness's. At 0.25 on all
     * five: X is `16^(0 + 0.25 + 0.25 + 0.25 + 0.25) = 16`, Y is `16^0.25 = 2`.
     */
    @Test
    fun theFiveSpokesSum() {
        val x = SuggestCandidate("x", null, RatingLevel.EXCEPTIONALLY, RatingLevel.NOT_AT_ALL, skips = 10)
        val y = SuggestCandidate("y", 1L)
        val tuning = SuggestTuning.of(*SuggestSpoke.entries.map { it to 0.25 }.toTypedArray())
        assertClose(listOf(16.0, 2.0), Suggester.weights(listOf(x, y), tuning))

        // Priority 0.5 at "certainly" (2/3) and skips 0.5 at five (1/2): 16^(1/3 + 1/4) = 2^(7/3) = 4·∛2.
        val z = rated("z", priority = RatingLevel.CERTAINLY, skips = 5)
        val mixedSpokes = SuggestTuning.of(SuggestSpoke.PRIORITY to 0.5, SuggestSpoke.SKIPS to 0.5)
        assertClose(listOf(5.0396842), Suggester.weights(listOf(z), mixedSpokes), tolerance = 1e-6)
    }

    /**
     * **Journal F35 N3, the freshness rule:** the rows carry Will's ratings. While Will is the resolved part
     * they weigh; once the owner is Coralie and her ratings have not landed, they weigh nothing.
     */
    @Test
    fun staleRatingsWeighNothing() {
        val will = Performer("will", "Will")
        val coralie = Performer("coralie", "Coralie")
        val view = SessionView("v", "Mine", ViewFilter.NONE, "vocal", SessionOrder.COLDEST_FIRST, position = 0L)
        val rows = listOf(SessionRow("a", "Autumn Leaves", "Kosma", 5L, 3L), SessionRow("b", "Blue Bossa", "Dorham", 5L, 1L))
        val willsPart = ResolvedPart("will", "Will", "vocal")
        val ratedForWill = SessionState(view = view, performers = listOf(will, coralie), ownerPerformerId = "will", loading = false)
            .withRows(rows)
            .withRatings(willsPart, mapOf("a" to PartRatings(RatingLevel.EXCEPTIONALLY, RatingLevel.NOT_AT_ALL)))
        val priority = SuggestTuning.of(SuggestSpoke.PRIORITY to 1.0, SuggestSpoke.CONFIDENCE to 1.0)

        assertEquals(willsPart, ratedForWill.resolvedPart)
        assertEquals(listOf(256.0, 1.0), Suggester.weights(ratedForWill.suggestionPool(emptyMap()), priority), "fresh: 16 × 16")

        val ownerIsCoralie = ratedForWill.withOwnerPerformer("coralie")
        assertEquals(willsPart, ownerIsCoralie.ratedFor, "Coralie's ratings have not landed")
        val stale = ownerIsCoralie.suggestionPool(emptyMap())
        assertEquals(listOf(null, null), stale.map { it.priority })
        assertEquals(listOf(null, null), stale.map { it.confidence })
        assertEquals(listOf(1.0, 1.0), Suggester.weights(stale, priority), "stale ratings weigh as unrated")

        val landed = ownerIsCoralie.withRatings(ResolvedPart("coralie", "Coralie", "vocal"), emptyMap())
        assertEquals(listOf(1.0, 1.0), Suggester.weights(landed.suggestionPool(emptyMap()), priority), "Coralie rated nothing")
    }

    /** SG13, SG14: the pool carries each song's count, and the card the dealt one's. */
    @Test
    fun thePoolAndTheCardCarryTheSkipCounts() {
        val rows = listOf(SessionRow("a", "Autumn Leaves", "Kosma", 12L, 3L), SessionRow("b", "Blue Bossa", "Dorham", null, 0L))
        val state = SessionState(rows = rows, loading = false)
        val pool = state.suggestionPool(mapOf("a" to 4))
        assertEquals(listOf(4, 0), pool.map { it.skips })

        val dealt = SuggestionDeck.Showing(pool.first(), setOf("a"))
        assertEquals(SuggestionCard.Showing(rows.first(), skips = 4), state.suggestionCard(dealt))
    }

    private fun assertClose(expected: List<Double>, actual: List<Double>, tolerance: Double = 1e-9) {
        assertEquals(expected.size, actual.size, "$actual")
        for ((e, a) in expected.zip(actual)) assertEquals(e, a, tolerance, "expected $expected, got $actual")
    }

    // ---- SG9: the percentile -----------------------------------------------------------------

    /**
     * SG9: never practised is the coldest (1), ties share a value, the hottest is 0, and the middle is
     * the share of the candidates it is not tied with that are hotter.
     */
    @Test
    fun thePercentileWithTiesAndNeverPractised() {
        assertEquals(listOf(1.0, 1.0, 0.6, 0.25, 0.25, 0.0), Suggester.coldness(mixed))
    }

    @Test
    fun aPoolWithNothingToCompareIsAllColdest() {
        assertEquals(listOf(1.0), Suggester.coldness(listOf(candidate("only", 5L))))
        assertEquals(listOf(1.0, 1.0), Suggester.coldness(listOf(candidate("a", 5L), candidate("b", 5L))))
        assertEquals(listOf(1.0, 1.0), Suggester.coldness(listOf(candidate("a", null), candidate("b", null))))
    }

    /** Journal D35's clock skew: a negative count is simply the hottest. */
    @Test
    fun aNegativeDayCountIsTheHottest() {
        assertEquals(listOf(0.0, 1.0), Suggester.coldness(listOf(candidate("skewed", -2L), candidate("today", 0L))))
    }

    // ---- SG5: the pool -----------------------------------------------------------------------

    /** SG5: the View's rows minus this session's logs **on this instrument**, and never the search. */
    @Test
    fun thePoolIsTheUnloggedRowsIgnoringTheSearch() {
        val rows = listOf(
            SessionRow("a", "Autumn Leaves", "Kosma", 12L, 3L),
            SessionRow("b", "Blue Bossa", "Dorham", null, 0L),
            SessionRow("c", "Cherokee", "Noble", 3L, 9L),
        )
        val view = SessionView("v", "Guitar", ViewFilter.NONE, "guitar", SessionOrder.COLDEST_FIRST, position = 0L)
        val state = SessionState(view = view, rows = rows, loading = false, query = "autumn")
            .plusTap(SessionTap("t1", "c", "guitar", null, null, "2026-09-24"))
            .plusTap(SessionTap("t2", "a", "bass", null, null, "2026-09-24"))

        assertEquals(listOf("a", "b"), state.suggestionPool.map { it.songId })
        assertEquals(listOf(12L, null), state.suggestionPool.map { it.daysSince })
    }

    /** F27 B2, F31 N3: the card resolves against the rows, and a dealt row that has gone is never blank. */
    @Test
    fun theCardResolvesAndAVanishedRowIsNeverBlank() {
        val row = SessionRow("a", "Autumn Leaves", "Kosma", 12L, 3L)
        val other = SessionRow("b", "Blue Bossa", "Dorham", null, 0L)
        val dealt = SuggestionDeck.Showing(SuggestCandidate("a", 12L), setOf("a"))

        assertEquals(SuggestionCard.Showing(row), SessionState(rows = listOf(row, other)).suggestionCard(dealt))
        assertEquals(SuggestionCard.Exhausted, SessionState(rows = listOf(other)).suggestionCard(dealt), "others remain")
        assertEquals(SuggestionCard.EmptyPool, SessionState(rows = emptyList()).suggestionCard(dealt), "nothing remains")
        assertEquals(SuggestionCard.EmptyPool, SessionState().suggestionCard(SuggestionDeck.EmptyPool))
        assertEquals(SuggestionCard.Exhausted, SessionState().suggestionCard(SuggestionDeck.Exhausted(setOf("a"))))
    }

    // ---- SG6, SG7: the draw and the deck -----------------------------------------------------

    /** SG6: **a deck, not a die.** Weighted hard towards two songs, the deck still deals every one once. */
    @Test
    fun theDeckNeverRepeatsUntilItIsExhausted() {
        val random = Random(7)
        val tuning = tuned(cold = 1.0)
        var deck = SuggestionDeck.open(mixed, tuning, random)
        val dealt = mutableListOf(assertNotNull(deck.current).songId)
        repeat(mixed.size - 1) {
            deck = deck.next(mixed, tuning, random)
            dealt += assertNotNull(deck.current, "dealt ${dealt.size} of ${mixed.size}").songId
        }
        assertEquals(mixed.map { it.songId }.toSet(), dealt.toSet(), "every song once: $dealt")
        assertEquals(mixed.size, dealt.size)

        // SG4: every song shown once, and the card says so.
        deck = deck.next(mixed, tuning, random)
        assertTrue(deck is SuggestionDeck.Exhausted)
        assertNull(deck.current)

        // SG4, SG6: "Another" from there starts a fresh deck.
        deck = deck.next(mixed, tuning, random)
        assertNotNull(deck.current)
        assertEquals(setOf(deck.current!!.songId), deck.shown)
    }

    /** SG6: a song logged since the last draw leaves the pool, and "Another" never proposes it. */
    @Test
    fun theDeckDrawsFromThePoolAsItIsNow() {
        val random = Random(11)
        var deck = SuggestionDeck.open(mixed, SuggestTuning.DEFAULT, random)
        val smaller = mixed.filterNot { it.songId == "two" || it.songId == deck.current!!.songId }
        repeat(smaller.size) {
            deck = deck.next(smaller, SuggestTuning.DEFAULT, random)
            assertTrue(deck.current!!.songId != "two")
        }
        assertTrue(deck.next(smaller, SuggestTuning.DEFAULT, random) is SuggestionDeck.Exhausted)
    }

    /** SG7: the draw is the injected random source's and nothing else's. Pinned. */
    @Test
    fun aSeededDrawSequenceIsPinned() {
        val random = Random(2026)
        val tuning = tuned(cold = 0.5)
        val drawn = List(8) { Suggester.draw(mixed, tuning, random)!!.songId }
        assertEquals(PINNED_SEQUENCE, drawn)
    }

    /** SG4: an empty pool has no draw, no error and no fresh deck to start. */
    @Test
    fun anEmptyPool() {
        val random = Random(1)
        assertEquals(emptyList(), Suggester.coldness(emptyList()))
        assertEquals(emptyList(), Suggester.weights(emptyList(), tuned(cold = 1.0)))
        assertNull(Suggester.draw(emptyList(), SuggestTuning.DEFAULT, random))

        val deck = SuggestionDeck.open(emptyList(), SuggestTuning.DEFAULT, random)
        assertEquals(SuggestionDeck.EmptyPool, deck)
        assertNull(deck.current)
        assertEquals(SuggestionDeck.EmptyPool, deck.next(emptyList(), SuggestTuning.DEFAULT, random))
    }

    // ---- §3: the distribution -----------------------------------------------------------------

    /**
     * 10,000 seeded draws at all-zero are uniform: **each song's count is within five standard
     * deviations of an equal share** (a share of 0.1 over 10,000 draws: 1,000 ± 150).
     */
    @Test
    fun tenThousandDrawsAtAllZeroAreUniform() {
        val pool = distributionPool()
        val counts = drawCounts(pool, SuggestTuning.DEFAULT, Random(20260924))
        val expected = 1.0 / pool.size
        for (song in pool) assertWithinFiveSigma(expected, counts.getValue(song.songId), song.songId)
    }

    /**
     * At coldness 1.0 the never-practised dominate, as SG8 predicts. The prediction is computed here
     * from SG8 and SG9 by hand, not read back from [Suggester.weights]: two never-practised songs weigh
     * 16 each, and the eight practised on days 1-8 weigh `16^((d - 1) / 9)`.
     */
    @Test
    fun tenThousandDrawsAtColdnessOneFavourTheNeverPractised() {
        val pool = distributionPool()
        val counts = drawCounts(pool, tuned(cold = 1.0), Random(20260924))
        val predicted = pool.associate { song ->
            val days = song.daysSince
            song.songId to if (days == null) 16.0 else 16.0.pow((days - 1) / 9.0)
        }
        val total = predicted.values.sum()
        for (song in pool) assertWithinFiveSigma(predicted.getValue(song.songId) / total, counts.getValue(song.songId), song.songId)

        val never = pool.filter { it.daysSince == null }.sumOf { counts.getValue(it.songId) }
        assertTrue(never > DRAWS / 2, "two songs of ten take more than half the draws: $never")
        assertTrue(counts.getValue("never-1") > 10 * counts.getValue("day-1"), "16x the hottest: $counts")
    }

    private fun distributionPool(): List<SuggestCandidate> =
        listOf(candidate("never-1", null), candidate("never-2", null)) +
            (1L..8L).map { candidate("day-$it", it) }

    private fun drawCounts(pool: List<SuggestCandidate>, tuning: SuggestTuning, random: Random): Map<String, Int> {
        val counts = pool.associate { it.songId to 0 }.toMutableMap()
        repeat(DRAWS) {
            val drawn = Suggester.draw(pool, tuning, random)!!.songId
            counts[drawn] = counts.getValue(drawn) + 1
        }
        return counts
    }

    private fun assertWithinFiveSigma(probability: Double, count: Int, what: String) {
        val expected = probability * DRAWS
        val sigma = sqrt(DRAWS * probability * (1 - probability))
        assertTrue(abs(count - expected) <= 5 * sigma, "$what: $count drawn, $expected ± ${5 * sigma} expected")
    }

    private companion object {
        const val DRAWS = 10_000

        /**
         * Recorded from the first run and pinned, so a change to the draw or the weighting shows. At
         * coldness 0.5 the two never-practised songs carry about 57% of the weight, and six of eight here.
         */
        val PINNED_SEQUENCE = listOf("ten-b", "never-a", "never-b", "never-a", "ten-a", "never-a", "never-a", "never-a")
    }
}
