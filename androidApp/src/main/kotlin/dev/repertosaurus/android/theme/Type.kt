package dev.repertosaurus.android.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.repertosaurus.android.R

/**
 * visual-identity VI6: Big Shoulders Display, one variable file pinned at the two weights the spec
 * uses. The variation axis is applied from API 26, which is minSdk. Resource-font variation settings
 * are still marked experimental at this Compose version.
 */
@OptIn(ExperimentalTextApi::class)
internal val DisplayFamily: FontFamily = FontFamily(
    Font(
        R.font.big_shoulders_display,
        weight = FontWeight.ExtraBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(800)),
    ),
    Font(
        R.font.big_shoulders_display,
        weight = FontWeight.Black,
        variationSettings = FontVariation.Settings(FontVariation.weight(900)),
    ),
)

/** VI7: Barlow Semi Condensed at 500, 600 and 700. */
internal val BodyFamily: FontFamily = FontFamily(
    Font(R.font.barlow_semi_condensed_medium, FontWeight.Medium),
    Font(R.font.barlow_semi_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_semi_condensed_bold, FontWeight.Bold),
)

private fun display(size: Int, weight: FontWeight = FontWeight.Black): TextStyle = TextStyle(
    fontFamily = DisplayFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (size * 1.05f).sp,
)

private fun body(size: Int, weight: FontWeight, lineHeight: TextUnit = (size * 1.25f).sp): TextStyle =
    TextStyle(fontFamily = BodyFamily, fontWeight = weight, fontSize = size.sp, lineHeight = lineHeight)

/**
 * VI6, VI8: the display sizes, for the primitives and the screens restyled explicitly. **Display type
 * is set in upper case by the caller** — Compose has no text transform — and in sentence case nowhere.
 */
internal object DisplayType {
    val ScreenTitle: TextStyle = display(46)
    val Subline: TextStyle = display(21, FontWeight.ExtraBold)
    val Button: TextStyle = display(22)
    val Badge: TextStyle = display(20)
    val Heading: TextStyle = display(28)

    /** A rating segment's number (rating-scale RS6). */
    val Number: TextStyle = display(24)
}

/**
 * VI16: what every other screen inherits. **The Material roles are all body face.** Those screens
 * pass their own strings in their own case, and a stock heading in Big Shoulders would put the
 * display face in sentence case, which VI6 forbids. Only `display*`, which no stock component uses,
 * carries the display face.
 */
internal val RepertosaurusTypography: Typography = Typography(
    displayLarge = display(48),
    displayMedium = display(44),
    displaySmall = display(36),
    headlineLarge = body(32, FontWeight.Bold),
    headlineMedium = body(28, FontWeight.Bold),
    headlineSmall = body(24, FontWeight.Bold),
    titleLarge = body(22, FontWeight.Bold),
    // VI8: a song title.
    titleMedium = body(20, FontWeight.Bold),
    titleSmall = body(16, FontWeight.Bold),
    // VI8: body copy.
    bodyLarge = body(18, FontWeight.SemiBold),
    // VI8: an artist.
    bodyMedium = body(15, FontWeight.Medium),
    bodySmall = body(13, FontWeight.Medium),
    labelLarge = body(15, FontWeight.Bold),
    labelMedium = body(13, FontWeight.SemiBold),
    // VI8: a segment label.
    labelSmall = body(11, FontWeight.SemiBold, lineHeight = 13.sp),
)
