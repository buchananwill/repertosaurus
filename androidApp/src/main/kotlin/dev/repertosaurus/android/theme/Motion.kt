package dev.repertosaurus.android.theme

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring as composeSpring
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * visual-identity VI18-VI22: one set of physics for everything that answers a touch. Every duration
 * and delay here is scaled by the animator duration scale, so "Remove animations" lands each motion
 * on its end state at once (VI22).
 */
internal object Motion {
    /** VI18: one small overshoot. */
    const val DAMPING: Float = 0.65f

    fun <T> spring(stiffness: Float = Spring.StiffnessMedium): FiniteAnimationSpec<T> =
        composeSpring(dampingRatio = DAMPING, stiffness = stiffness)

    /** VI18: the counter-movement before travel. */
    val Anticipation: Dp = 4.dp

    /** VI20 beat 1. */
    val RowSink: Dp = 2.dp

    /** VI20 beat 2: the rubber stamp meeting paper. */
    const val STAMP_SQUASH: Float = 0.8f
    const val STAMP_SPREAD: Float = 1.12f

    /** VI20: about 450 ms in all, with the gap's spring. */
    const val STAMP_HOLD_MS: Int = 100
    const val ANTICIPATION_MS: Int = 60
    const val LEAVING_MS: Int = 140

    /** VI20 beat 3: the stamp is held long enough to read, then the row gives a little the other way. */
    fun <T> anticipation(): FiniteAnimationSpec<T> = tween(ANTICIPATION_MS, delayMillis = STAMP_HOLD_MS)

    /** VI18's "back-style ease where a spring does not fit": a spring would linger at the edge. */
    fun <T> leaving(): FiniteAnimationSpec<T> = tween(LEAVING_MS, easing = FastOutLinearInEasing)
}
