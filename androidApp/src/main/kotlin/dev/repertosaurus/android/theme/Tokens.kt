package dev.repertosaurus.android.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * visual-identity VI1-VI5: the palette, the strokes and the shadow offsets. **Stone, not ivory**
 * (journal session 11, D41): nothing here may drift towards a warm cream ground or a clay accent.
 *
 * The rating ramps are not here. They are the user's choice and live in the shared core
 * (rating-scale RS4), read only through `LocalColourRamp` (RS8).
 */
internal object Tokens {
    // VI1. Contrast (VI2), WCAG 2 relative luminance: Ink on Ground 13.6, on Ochre 8.3, on the
    // palest-to-darkest ramp steps 13.6-8.0; Paper on Indigo 9.5 (8.1 at the gradient's light end).
    val Ground: Color = Color(0xFFE6E3DA)
    val Ink: Color = Color(0xFF1B1A17)
    val InkMuted: Color = Color(0xFF45423B)
    val Indigo: Color = Color(0xFF25338A)
    val IndigoLight: Color = Color(0xFF2D3D9A)
    val IndigoDark: Color = Color(0xFF1E2A74)
    val Madder: Color = Color(0xFFD2456F)
    val Ochre: Color = Color(0xFFE3A92F)
    val Paper: Color = Color(0xFFF2EFE6)
    val Field: Color = Color(0xFFFFFFFF)

    /** VI4: containers, buttons, bars and field outlines. */
    val StrokeHeavy: Dp = 3.dp

    /** VI4: list-row rules and segment dividers. */
    val StrokeRule: Dp = 2.dp

    /** VI5: icon buttons and badges. */
    val ShadowSmall: Dp = 3.dp

    /** VI5: the primary button. */
    val ShadowLarge: Dp = 5.dp

    /** VI9: the Madder misregistration under display type on the header. */
    val Misregistration: Dp = 3.dp

    /** VI12. */
    val IconButtonSize: Dp = 44.dp
}
