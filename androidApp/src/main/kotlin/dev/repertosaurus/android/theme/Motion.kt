package dev.repertosaurus.android.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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

    /**
     * VI18's anticipation, after [holdMs]: a logged row holds [STAMP_HOLD_MS] so its stamp can be read
     * (VI20 beat 3); anything with no stamp holds nothing.
     */
    fun <T> anticipation(holdMs: Int): FiniteAnimationSpec<T> = tween(ANTICIPATION_MS, delayMillis = holdMs)

    /** VI18's "back-style ease where a spring does not fit": a spring would linger at the edge. */
    fun <T> leaving(): FiniteAnimationSpec<T> = tween(LEAVING_MS, easing = FastOutLinearInEasing)
}

/**
 * VI18, VI20: **one departure**, shared by a logged session row and a suggestion card: a small
 * anticipation back to the left, then travel out to the right. [holdMs] goes to
 * [Motion.anticipation].
 */
@Stable
internal class Departure(private val holdMs: Int) {
    private val anticipation = Animatable(0f)
    private val travel = Animatable(0f)

    suspend fun leave() {
        anticipation.animateTo(1f, Motion.anticipation(holdMs))
        travel.animateTo(1f, Motion.leaving())
    }

    /** Back in place, at once: the element now shows something else. */
    suspend fun reset() {
        anticipation.snapTo(0f)
        travel.snapTo(0f)
    }

    /** The offset now, for an element [width] wide. Read in the draw layer, so it recomposes nothing. */
    fun offset(width: Float, anticipationPx: Float): Float {
        val back = anticipationPx * anticipation.value
        return -back + travel.value * (width + back)
    }
}

/** Draw this element where [departure] has it. */
internal fun Modifier.departing(departure: Departure): Modifier =
    graphicsLayer { translationX = departure.offset(size.width, Motion.Anticipation.toPx()) }
