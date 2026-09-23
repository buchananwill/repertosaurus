package dev.repertosaurus.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** rating-scale RS1-RS3. */
class RatingLevelTest {

    @Test
    fun theFourLevelsAreInOrderWithTheirValuesAndTheVisionsWords() {
        assertEquals(
            listOf(
                Triple(RatingLevel.NOT_AT_ALL, 0L, "not at all"),
                Triple(RatingLevel.SOMEWHAT, 1L, "somewhat"),
                Triple(RatingLevel.CERTAINLY, 2L, "certainly"),
                Triple(RatingLevel.EXCEPTIONALLY, 3L, "exceptionally"),
            ),
            RatingLevel.entries.map { Triple(it, it.value, it.label) },
        )
    }

    @Test
    fun fromStoredReadsZeroToThreeAndEverythingElseAsUnrated() {
        assertNull(RatingLevel.fromStored(null))
        assertNull(RatingLevel.fromStored(-1L))
        assertEquals(RatingLevel.NOT_AT_ALL, RatingLevel.fromStored(0L))
        assertEquals(RatingLevel.EXCEPTIONALLY, RatingLevel.fromStored(3L))
        assertNull(RatingLevel.fromStored(4L))
    }

    @Test
    fun valueIsTheInverseOfFromStored() {
        for (level in RatingLevel.entries) assertEquals(level, RatingLevel.fromStored(level.value))
    }

    /** RS3: an existing feel of 1, 2 or 3 reads unchanged. */
    @Test
    fun storedFeelValuesReadOnTheScaleUnchanged() {
        assertEquals(RatingLevel.SOMEWHAT, RatingLevel.fromStored(1L))
        assertEquals(RatingLevel.CERTAINLY, RatingLevel.fromStored(2L))
        assertEquals(RatingLevel.EXCEPTIONALLY, RatingLevel.fromStored(3L))
    }
}
