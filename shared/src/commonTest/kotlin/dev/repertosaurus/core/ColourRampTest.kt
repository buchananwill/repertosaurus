package dev.repertosaurus.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** rating-scale RS4, RS5. */
class ColourRampTest {

    @Test
    fun everyRampHasExactlyFourSteps() {
        for (ramp in ColourRamp.entries) assertEquals(4, ramp.steps.size, "$ramp")
    }

    /** RS4 as amended: the display names live in the core. */
    @Test
    fun everyRampCarriesItsLabel() {
        assertEquals(
            listOf("Pastel red to blue", "Danger to safe", "Cold to hot"),
            ColourRamp.entries.map { it.label },
        )
    }

    /** RS5 as amended 2026-09-23. */
    @Test
    fun theDefaultIsTheDangerToSafeRamp() {
        assertEquals(ColourRamp.DANGER_TO_SAFE, ColourRamp.DEFAULT)
    }

    @Test
    fun fromStoredReadsEveryNameAndFallsBackToTheDefault() {
        for (ramp in ColourRamp.entries) assertEquals(ramp, ColourRamp.fromStored(ramp.name))
        assertEquals(ColourRamp.DANGER_TO_SAFE, ColourRamp.fromStored(null))
        assertEquals(ColourRamp.DANGER_TO_SAFE, ColourRamp.fromStored("SEPIA"))
    }

    /** Literal RS4 values, so a reordered step list cannot pass. */
    @Test
    fun aStepIsTheRampsColourForThatLevel() {
        assertEquals(0xFF93BDE8, ColourRamp.COLD_TO_HOT.step(RatingLevel.NOT_AT_ALL))
        assertEquals(0xFFF09A7E, ColourRamp.COLD_TO_HOT.step(RatingLevel.EXCEPTIONALLY))
    }
}
