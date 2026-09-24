package dev.repertosaurus.session

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** suggest SG11, SG15, SG16: the tuning, its handle's step, its lock and its stored form. */
class SuggestTuningTest {

    /** SG16: pure shuffle, skips not counted, the count shown when they are. */
    @Test
    fun theDefaultIsPureShuffle() {
        val tuning = SuggestTuning.DEFAULT
        for (spoke in SuggestSpoke.entries) assertEquals(0.0, tuning.radius(spoke), spoke.name)
        assertTrue(tuning.isShuffle)
        assertEquals(false, tuning.countSkips)
        assertEquals(true, tuning.showSkipCount)
    }

    /** SG11: the handle snaps to 0.05, inside [0, 1]. */
    @Test
    fun theHandleSnapsToTwentiethsInsideTheUnitRange() {
        assertEquals(0.05, SuggestTuning.snap(0.049))
        assertEquals(0.0, SuggestTuning.snap(0.024))
        assertEquals(0.35, SuggestTuning.snap(0.3571))
        assertEquals(1.0, SuggestTuning.snap(1.4))
        assertEquals(0.0, SuggestTuning.snap(-0.3))
        assertEquals(0.0, SuggestTuning.snap(Double.NaN))
        assertEquals(SuggestTuning.STEP, 1.0 / SuggestTuning.STEPS)
    }

    private val will = PartResolution.Resolved(ResolvedPart("will", "Will", "vocal"))

    /**
     * SG11, SG12, SG14: [lockOf] in every combination of counting off or on and the part pending,
     * resolved or none. Written out whole, row by row, rather than derived.
     */
    @Test
    fun theLockInEveryCombination() {
        val ratings = Messages.SUGGEST_LOCKED_RATINGS
        val nobody = Messages.SUGGEST_LOCKED_NO_PERFORMER
        val notCounted = Messages.SUGGEST_LOCKED_SKIPS
        // count, part -> priority, confidence, skips (coldness and hotness are never locked)
        val expected = listOf(
            Triple(false, PartResolution.Pending, listOf(ratings, ratings, notCounted)),
            Triple(false, will, listOf(null, null, notCounted)),
            Triple(false, PartResolution.None, listOf(nobody, nobody, notCounted)),
            Triple(true, PartResolution.Pending, listOf(ratings, ratings, Messages.SUGGEST_LOCKED_SKIPS_PENDING)),
            Triple(true, will, listOf(null, null, null)),
            Triple(true, PartResolution.None, listOf(nobody, nobody, nobody)),
        )
        for ((count, part, reasons) in expected) {
            val what = "countSkips=$count, part=$part"
            assertEquals(null, lockOf(SuggestSpoke.COLDNESS, count, part), what)
            assertEquals(null, lockOf(SuggestSpoke.HOTNESS, count, part), what)
            assertEquals(reasons[0], lockOf(SuggestSpoke.PRIORITY, count, part), what)
            assertEquals(reasons[1], lockOf(SuggestSpoke.CONFIDENCE, count, part), what)
            assertEquals(reasons[2], lockOf(SuggestSpoke.SKIPS, count, part), what)
        }
    }

    /**
     * SG11: a locked spoke is weighed and drawn at zero ([SuggestTuning.effective]), and **its stored radius
     * is kept**, so it comes back when the lock lifts.
     */
    @Test
    fun aLockedSpokeIsEffectivelyZeroAndItsRadiusIsKept() {
        val tuning = SuggestTuning.of(
            SuggestSpoke.COLDNESS to 0.4,
            SuggestSpoke.PRIORITY to 0.8,
            SuggestSpoke.CONFIDENCE to 0.6,
            SuggestSpoke.SKIPS to 0.5,
        )
        assertEquals(0.8, tuning.radius(SuggestSpoke.PRIORITY), "withRadius stores what was set")

        val nobody = tuning.effective(PartResolution.None)
        assertEquals(listOf(0.4, 0.0, 0.0, 0.0, 0.0), SuggestSpoke.entries.map { nobody.radius(it) }.sortedDescending())
        assertEquals(0.4, nobody.radius(SuggestSpoke.COLDNESS))

        val notCounted = tuning.effective(will)
        assertEquals(0.8, notCounted.radius(SuggestSpoke.PRIORITY))
        assertEquals(0.6, notCounted.radius(SuggestSpoke.CONFIDENCE))
        assertEquals(0.0, notCounted.radius(SuggestSpoke.SKIPS), "skips locked while not counted")

        val counted = tuning.copy(countSkips = true).effective(will)
        assertEquals(0.5, counted.radius(SuggestSpoke.SKIPS))
        assertEquals(tuning.copy(countSkips = true), counted, "nothing locked: the tuning as set")
    }

    /** SG10: the two spokes are opposite, and their directions say so. */
    @Test
    fun coldnessAndHotnessAreOpposite() {
        assertEquals(180.0, abs(SuggestSpoke.COLDNESS.angleDegrees - SuggestSpoke.HOTNESS.angleDegrees))
        val (cx, cy) = SuggestSpoke.COLDNESS.direction()
        val (hx, hy) = SuggestSpoke.HOTNESS.direction()
        assertEquals(-1.0, cx, 1e-12)
        assertEquals(1.0, hx, 1e-12)
        assertEquals(0.0, cy + hy, 1e-12)
    }

    /** F26 N3: a tuning cannot be built with a missing spoke or an out-of-range radius. */
    @Test
    fun anInvalidTuningCannotBeBuilt() {
        assertFailsWith<IllegalArgumentException> { SuggestTuning(radii = mapOf(SuggestSpoke.COLDNESS to 0.5)) }
        assertFailsWith<IllegalArgumentException> {
            SuggestTuning(radii = SuggestSpoke.entries.associateWith { 0.0 } + (SuggestSpoke.HOTNESS to 1.5))
        }
        assertFailsWith<IllegalArgumentException> {
            SuggestTuning(radii = SuggestSpoke.entries.associateWith { 0.0 } + (SuggestSpoke.HOTNESS to Double.NaN))
        }
    }

    /** SG11: "Shuffle" zeroes every radius in one tap and keeps the skip settings. */
    @Test
    fun shuffleZeroesEveryRadiusAndKeepsTheSkipSettings() {
        val tuning = SuggestTuning.of(SuggestSpoke.COLDNESS to 0.4, SuggestSpoke.HOTNESS to 0.1)
            .copy(countSkips = true, showSkipCount = false)
            .shuffled()
        assertTrue(tuning.isShuffle)
        assertEquals(true, tuning.countSkips)
        assertEquals(false, tuning.showSkipCount)
    }

    /** SG15, F27 B7: what is written is read back, and **the stored form is byte for byte the v1 one**. */
    @Test
    fun theStoredFormRoundTripsAndIsUnchanged() {
        val tuning = SuggestTuning.of(SuggestSpoke.COLDNESS to 0.35, SuggestSpoke.HOTNESS to 0.05)
            .copy(countSkips = true, showSkipCount = false)
        assertEquals(
            "coldness=0.35;hotness=0.05;priority=0.0;confidence=0.0;skips=0.0;countSkips=true;showSkipCount=false",
            tuning.encode(),
        )
        assertEquals(tuning, SuggestTuning.fromStored(tuning.encode()))
        assertEquals(SuggestTuning.DEFAULT, SuggestTuning.fromStored(SuggestTuning.DEFAULT.encode()))
    }

    /** SG15, schema-compatibility S8: **an unreadable stored tuning reads as the default, and never throws.** */
    @Test
    fun anUnreadableStoredTuningReadsAsTheDefault() {
        val unreadable = listOf(
            null,
            "",
            "   ",
            "garbage",
            "coldness=high",
            "coldness=1.5",
            "coldness=-0.1",
            "coldness=NaN",
            "coldness=0.5;countSkips=maybe",
            "coldness=0.5=0.5",
            "coldness=0.5;;hotness=0.1",
        )
        for (stored in unreadable) assertEquals(SuggestTuning.DEFAULT, SuggestTuning.fromStored(stored), "$stored")
    }

    /** F26 N2: a stored radius is snapped. Every spoke's is read as stored; the lock is applied in use. */
    @Test
    fun aStoredRadiusIsSnapped() {
        val read = SuggestTuning.fromStored("coldness=0.33;hotness=0.049;priority=0.8;confidence=1.0;skips=0.52")
        assertEquals(0.35, read.radius(SuggestSpoke.COLDNESS))
        assertEquals(0.05, read.radius(SuggestSpoke.HOTNESS))
        assertEquals(0.8, read.radius(SuggestSpoke.PRIORITY))
        assertEquals(1.0, read.radius(SuggestSpoke.CONFIDENCE))
        assertEquals(0.5, read.radius(SuggestSpoke.SKIPS))
    }

    /** F26 N2: a pair splits on its first `=` only, so an `=` in a value makes it unreadable, not truncated. */
    @Test
    fun aPairSplitsOnItsFirstEquals() {
        assertEquals(SuggestTuning.DEFAULT, SuggestTuning.fromStored("coldness=0.5=0.5"))
        assertEquals(SuggestTuning.DEFAULT, SuggestTuning.fromStored("countSkips=true=false"))
        assertEquals(SuggestTuning.DEFAULT, SuggestTuning.fromStored("coldness"))
        // An unknown key is ignored whatever its value holds.
        assertEquals(SuggestTuning.of(SuggestSpoke.HOTNESS to 0.2), SuggestTuning.fromStored("tempo=a=b;hotness=0.2"))
    }

    /** A key this build does not know is ignored; one it knows but cannot find keeps its default. */
    @Test
    fun anUnknownKeyIsIgnoredAndAMissingOneDefaults() {
        assertEquals(SuggestTuning.of(SuggestSpoke.COLDNESS to 0.5), SuggestTuning.fromStored("coldness=0.5;tempo=0.9"))
    }
}
