package dev.repertosaurus.android.theme

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring as composeSpring
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * visual-identity VI18-VI22: one set of physics for everything that answers a touch.
 *
 * **Every duration here is scaled by the system's animator duration scale** — Compose reads it into
 * the frame clock — so with "Remove animations" each motion lands on its end state at once (VI22).
 * Nothing here runs unless something was touched or changed: there is no idle motion.
 */
internal object Motion {
    /** VI18: under-damped just enough for one small overshoot. */
    const val DAMPING: Float = 0.65f

    /** The spring every touch-driven motion uses. Medium stiffness settles in about 160 ms. */
    fun <T> spring(stiffness: Float = Spring.StiffnessMedium): FiniteAnimationSpec<T> =
        composeSpring(dampingRatio = DAMPING, stiffness = stiffness)

    /** VI18: the counter-movement before travel. */
    val Anticipation: Dp = 4.dp

    /** VI20 beat 1: a pressed row sinks onto its rule. */
    val RowSink: Dp = 2.dp

    /** VI20 beat 2: how far the badge squashes — the rubber stamp meeting paper. */
    const val STAMP_SQUASH: Float = 0.8f
    const val STAMP_SPREAD: Float = 1.12f

    /** VI20 beats 2-4 (about 450 ms in all): the stamp reads, the row anticipates, then leaves. */
    const val STAMP_HOLD_MS: Long = 100L
    const val ANTICIPATION_MS: Int = 60
    const val LEAVING_MS: Int = 140

    /** A row travelling out: a spring would linger at the edge, so it is an accelerating ease (VI18). */
    fun <T> leaving(): FiniteAnimationSpec<T> = tween(LEAVING_MS, easing = FastOutLinearInEasing)

    fun <T> anticipation(): FiniteAnimationSpec<T> = tween(ANTICIPATION_MS)
}
