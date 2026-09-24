package dev.repertosaurus.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** onboarding OB2, as amended by D66: welcome, ramp, then who you are, which no performers skips. */
class OnboardingStepTest {

    @Test
    fun theStepsRunInOrderWithPerformers() {
        assertEquals(OnboardingStep.RAMP, OnboardingStep.WELCOME.next(hasPerformers = true))
        assertEquals(OnboardingStep.PERFORMER, OnboardingStep.RAMP.next(hasPerformers = true))
        assertNull(OnboardingStep.PERFORMER.next(hasPerformers = true), "Done finishes")
    }

    @Test
    fun withNoPerformersTheRampStepFinishes() {
        assertEquals(OnboardingStep.RAMP, OnboardingStep.WELCOME.next(hasPerformers = false))
        assertNull(OnboardingStep.RAMP.next(hasPerformers = false), "the performer step is skipped")
    }

    @Test
    fun backWalksOneStepAndLeavesFromTheWelcome() {
        assertEquals(OnboardingStep.RAMP, OnboardingStep.PERFORMER.previous())
        assertEquals(OnboardingStep.WELCOME, OnboardingStep.RAMP.previous())
        assertNull(OnboardingStep.WELCOME.previous())
    }

    @Test
    fun theDeclarationOrderIsTheStepOrder() {
        // The step transition's direction compares steps, so the order is load-bearing.
        assertEquals(
            listOf(OnboardingStep.WELCOME, OnboardingStep.RAMP, OnboardingStep.PERFORMER),
            OnboardingStep.entries,
        )
    }
}
