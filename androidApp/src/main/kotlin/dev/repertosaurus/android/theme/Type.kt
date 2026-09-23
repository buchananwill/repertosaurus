package dev.repertosaurus.android.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
 * VI6, VI8: **the one display scale.** Set it through [DisplayText], which upper-cases: Compose has no
 * text transform, and display type is in sentence case nowhere.
 */
internal object DisplayType {
    val ScreenTitle: TextStyle = display(46)
    val Heading: TextStyle = display(28)
    val Number: TextStyle = display(24)
    val Button: TextStyle = display(22)
    val Subline: TextStyle = display(21, FontWeight.ExtraBold)
    val Badge: TextStyle = display(20)
}

/**
 * VI6: display type, in upper case. The only way a screen should set the display face.
 *
 * Its shadow, when it has none of its own, is the surrounding [LocalTextStyle]'s: that is how
 * [InkHeader] gives the title and subline its misregistration without each caller knowing about it.
 */
@Composable
internal fun DisplayText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text.uppercase(),
        style = if (style.shadow == null) style.copy(shadow = LocalTextStyle.current.shadow) else style,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * VI16: what every other screen inherits. **The Material roles are all body face**, because those
 * screens pass their own strings in their own case (VI6). The `display*` roles, which no stock
 * component uses, are the display scale.
 */
internal val RepertosaurusTypography: Typography = Typography(
    displayLarge = DisplayType.ScreenTitle,
    displayMedium = DisplayType.Heading,
    displaySmall = DisplayType.Button,
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
