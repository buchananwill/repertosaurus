package dev.repertosaurus.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** onboarding OB2, as amended by D66, and journal F30: welcome, ramp, then who you are, which no performers skips. */
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

    /** F30 B1: an empty list before the read is not "no performers". */
    @Test
    fun beforeThePerformersAreReadTheRampStepGoesOn() {
        assertEquals(OnboardingStep.PERFORMER, OnboardingStep.RAMP.next(hasPerformers = null))
        assertFalse(OnboardingStep.PERFORMER.skipsItself(hasPerformers = null), "it waits for the read")
    }

    /** F30 N1: the performer step skips itself only once it is known there are none. */
    @Test
    fun onlyThePerformerStepSkipsItselfAndOnlyOnNone() {
        assertTrue(OnboardingStep.PERFORMER.skipsItself(hasPerformers = false))
        assertFalse(OnboardingStep.PERFORMER.skipsItself(hasPerformers = true))
        for (step in listOf(OnboardingStep.WELCOME, OnboardingStep.RAMP)) {
            for (known in listOf(true, false, null)) assertFalse(step.skipsItself(known), "$step with $known")
        }
    }

    @Test
    fun backWalksOneStepAndLeavesFromTheWelcome() {
        assertEquals(OnboardingStep.RAMP, OnboardingStep.PERFORMER.previous())
        assertEquals(OnboardingStep.WELCOME, OnboardingStep.RAMP.previous())
        assertNull(OnboardingStep.WELCOME.previous())
    }

    /** The step transition's direction compares steps, so the declaration order is load-bearing. */
    @Test
    fun theDeclarationOrderIsTheStepOrder() {
        assertEquals(
            listOf(OnboardingStep.WELCOME, OnboardingStep.RAMP, OnboardingStep.PERFORMER),
            OnboardingStep.entries,
        )
    }
}
