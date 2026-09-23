package dev.repertosaurus.android.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * visual-identity VI1-VI5. **Stone, not ivory** (journal session 11, D41). The contrast VI2 asks
 * for is asserted by `TokenContrastTest`, not quoted here. The rating ramps live in the shared core
 * and are read only through `LocalColourRamp` (rating-scale RS8).
 */
internal object Tokens {
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

    /** VI9. */
    val Misregistration: Dp = 3.dp

    /** The smallest anything tappable may be, in either direction. */
    val TouchMin: Dp = 48.dp

    /** VI12, amended to the touch minimum (journal session 11, D56 #12). */
    val IconButtonSize: Dp = TouchMin
}
