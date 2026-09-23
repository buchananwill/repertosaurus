package dev.repertosaurus.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** rating-scale RS11, at every boundary. */
class HeatTest {

    @Test
    fun neverPractisedIsTheColdestHeat() {
        assertEquals(RatingLevel.NOT_AT_ALL, Heat.level(null))
    }

    /** Journal D35: clock skew can produce a negative count, and it reads as the hottest. */
    @Test
    fun aNegativeDayCountIsTheHottestHeat() {
        assertEquals(RatingLevel.EXCEPTIONALLY, Heat.level(-1L))
    }

    @Test
    fun heatBoundaryThreeFour() {
        assertEquals(RatingLevel.EXCEPTIONALLY, Heat.level(0L))
        assertEquals(RatingLevel.EXCEPTIONALLY, Heat.level(3L))
        assertEquals(RatingLevel.CERTAINLY, Heat.level(4L))
    }

    @Test
    fun heatBoundaryThirteenFourteen() {
        assertEquals(RatingLevel.CERTAINLY, Heat.level(13L))
        assertEquals(RatingLevel.SOMEWHAT, Heat.level(14L))
    }

    @Test
    fun heatBoundaryTwentyNineThirty() {
        assertEquals(RatingLevel.SOMEWHAT, Heat.level(29L))
        assertEquals(RatingLevel.NOT_AT_ALL, Heat.level(30L))
        assertEquals(RatingLevel.NOT_AT_ALL, Heat.level(365L))
    }
}
