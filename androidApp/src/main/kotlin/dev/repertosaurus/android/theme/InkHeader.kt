package dev.repertosaurus.android.theme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * VI9: the indigo bar, closed by a 3 dp `Ink` rule.
 *
 * - [navigation] and [actions] sit on the top line, either side of a gap.
 * - [title] and [subline] sit beneath, in `Paper` over a hard `Madder` offset: the risograph
 *   misregistration, which [DisplayText] picks up here and nowhere else.
 * - [onTitleClick], when given, makes the title and subline one target (the session's View switcher).
 */
@Composable
internal fun InkHeader(
    navigation: @Composable RowScope.() -> Unit,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    subline: (@Composable () -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
) {
    val shift = with(LocalDensity.current) { Tokens.Misregistration.toPx() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawWithCache {
                val brush = headerGradient(size)
                onDrawBehind { drawRect(brush) }
            }
            .inkRule(InkEdge.Bottom),
    ) {
        CompositionLocalProvider(LocalContentColor provides Tokens.Paper) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                navigation()
                Spacer(modifier = Modifier.weight(1f))
                actions()
            }
            CompositionLocalProvider(
                // A zero blur is a hard shadow: Compose substitutes the smallest radius the platform keeps.
                LocalTextStyle provides LocalTextStyle.current.copy(
                    shadow = Shadow(Tokens.Madder, Offset(shift, shift), blurRadius = 0f),
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier)
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                ) {
                    title()
                    subline?.invoke()
                }
            }
        }
    }
}

private const val HEADER_ANGLE_DEGREES = 172.0

/** VI9: `IndigoLight` at the top left to `IndigoDark` at the bottom right, at 172° as CSS measures it. */
private fun headerGradient(size: Size): Brush {
    val radians = Math.toRadians(HEADER_ANGLE_DEGREES)
    val direction = Offset(sin(radians).toFloat(), -cos(radians).toFloat())
    // The CSS gradient line: long enough that both corners on it land on the end colours.
    val half = (abs(size.width * direction.x) + abs(size.height * direction.y)) / 2f
    val centre = Offset(size.width / 2f, size.height / 2f)
    return Brush.linearGradient(
        colors = listOf(Tokens.IndigoLight, Tokens.IndigoDark),
        start = centre - direction * half,
        end = centre + direction * half,
    )
}

/** The bar at a screen's foot, over its content with a 3 dp rule: where the one primary action goes. */
@Composable
internal fun InkFooter(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .inkRule(InkEdge.Top)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
        content = content,
    )
}
