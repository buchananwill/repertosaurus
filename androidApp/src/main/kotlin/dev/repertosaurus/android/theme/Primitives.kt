package dev.repertosaurus.android.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * visual-identity VI5: a solid `Ink` rectangle, no blur, offset down and right by [offset] behind the
 * element. **The layout reserves the room** — the modifier pads the element's end and bottom by the
 * offset and draws into that padding — so nothing clips it, and whatever comes after this modifier
 * (the click target included) stays the element's own size, clear of the shadow.
 */
internal fun Modifier.hardShadow(offset: Dp, colour: Color = Tokens.Ink): Modifier =
    padding(end = offset, bottom = offset).drawBehind {
        val shift = offset.toPx()
        drawRect(colour, topLeft = Offset(shift, shift), size = size)
    }

/** VI4: an `Ink` stroke, square, inside the element's bounds. */
internal fun Modifier.inkBorder(width: Dp = Tokens.StrokeHeavy): Modifier = border(width, Tokens.Ink, RectangleShape)

/**
 * VI12, VI21: pressed, the element sinks down and right onto its shadow, like a printed button pushed
 * flat, and springs back on release. The travel is read at placement, so the animation re-places the
 * element each frame and recomposes nothing.
 */
@Composable
private fun Modifier.pushedFlat(interaction: MutableInteractionSource, by: Dp): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val travel = animateFloatAsState(if (pressed) 1f else 0f, Motion.spring(), label = "pushed flat")
    return offset {
        val shift = (by.toPx() * travel.value).roundToInt()
        IntOffset(shift, shift)
    }
}

/** VI12: the primary button, at most one a screen. The label is set in upper case here (VI6). */
@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .hardShadow(Tokens.ShadowLarge)
            .pushedFlat(interaction, Tokens.ShadowLarge)
            .background(Tokens.Ochre)
            .inkBorder()
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), style = DisplayType.Button, color = Tokens.Ink, textAlign = TextAlign.Center)
    }
}

/**
 * VI12: `Ground`, the same border, no shadow. With no shadow to move onto, a press shows as the
 * inked fill a selected segment wears (VI13) rather than a movement.
 */
@Composable
internal fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .background(if (pressed) Tokens.Ink else Tokens.Ground)
            .inkBorder()
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            style = DisplayType.Button,
            color = if (pressed) Tokens.Paper else Tokens.Ink,
            textAlign = TextAlign.Center,
        )
    }
}

/** VI12: 44 dp square, a 3 dp border and a 3 dp shadow. [contentDescription] names it to TalkBack and to tests. */
@Composable
internal fun InkIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    containerColour: Color = Tokens.Ground,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .hardShadow(Tokens.ShadowSmall)
            .pushedFlat(interaction, Tokens.ShadowSmall)
            .size(Tokens.IconButtonSize)
            .background(containerColour)
            .inkBorder()
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides Tokens.Ink) { content() }
    }
}

/** The menu glyph: three square-ended bars, drawn rather than taken from an icon set (VI3). */
@Composable
internal fun MenuGlyph(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(22.dp, 18.dp).drawBehind {
            val bar = Tokens.StrokeHeavy.toPx()
            val gap = (size.height - 3 * bar) / 2
            for (i in 0..2) {
                drawRect(Tokens.Ink, topLeft = Offset(0f, i * (bar + gap)), size = Size(size.width, bar))
            }
        },
    )
}

/**
 * VI9: the indigo bar. The gradient runs from `IndigoLight` at the top left to `IndigoDark` at the
 * bottom right, at 172° in the CSS sense (180° is straight down), and a 3 dp `Ink` rule closes it
 * (VI4). Content inside defaults to `Paper`.
 */
@Composable
internal fun InkHeader(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    CompositionLocalProvider(LocalContentColor provides Tokens.Paper) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .drawWithCache {
                    val brush = headerGradient(size)
                    val rule = Tokens.StrokeHeavy.toPx()
                    onDrawBehind {
                        drawRect(brush)
                        drawRect(Tokens.Ink, topLeft = Offset(0f, size.height - rule), size = Size(size.width, rule))
                    }
                }
                .padding(bottom = Tokens.StrokeHeavy),
            content = content,
        )
    }
}

private const val HEADER_ANGLE_DEGREES = 172.0

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

/**
 * VI9: display type on the header, `Paper` over a hard `Madder` offset — the risograph
 * misregistration. Upper case here, as all display type is (VI6).
 */
@Composable
internal fun HeaderText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    val shift = with(LocalDensity.current) { Tokens.Misregistration.toPx() }
    Text(
        text = text.uppercase(),
        // A zero blur is a hard shadow: Compose substitutes the smallest radius the platform keeps.
        style = style.copy(shadow = Shadow(Tokens.Madder, Offset(shift, shift), blurRadius = 0f)),
        color = Tokens.Paper,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * VI13: segments joined inside one 3 dp `Ink` border, with 2 dp `Ink` dividers. The strip is inked
 * and the segments are laid on it with a gap, so the border and the dividers are one fill and a
 * segment's own colour can never cover a rule.
 *
 * Every segment takes the tallest one's height. With [equalWidths] they share the width equally —
 * the rating control, the sort — and without it each is as wide as its content, for a strip inside a
 * horizontal scroll (the instrument chips). An unbounded width falls back to content widths.
 */
@Composable
internal fun SegmentStrip(
    modifier: Modifier = Modifier,
    equalWidths: Boolean = true,
    content: @Composable () -> Unit,
) {
    val divider = with(LocalDensity.current) { Tokens.StrokeRule.roundToPx() }
    Layout(
        content = content,
        modifier = modifier.background(Tokens.Ink).padding(Tokens.StrokeHeavy),
    ) { measurables, constraints ->
        if (measurables.isEmpty()) return@Layout layout(constraints.minWidth, constraints.minHeight) {}
        val gaps = divider * (measurables.size - 1)
        val widths = if (equalWidths && constraints.hasBoundedWidth) {
            val share = (constraints.maxWidth - gaps).coerceAtLeast(0) / measurables.size
            val remainder = (constraints.maxWidth - gaps).coerceAtLeast(0) - share * measurables.size
            List(measurables.size) { i -> if (i == measurables.lastIndex) share + remainder else share }
        } else {
            measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        }
        val height = measurables.indices
            .maxOf { i -> measurables[i].maxIntrinsicHeight(widths[i]) }
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i], height)) }
        layout(widths.sum() + gaps, height) {
            var x = 0
            for (placeable in placeables) {
                placeable.place(x, 0)
                x += placeable.width + divider
            }
        }
    }
}

/**
 * One segment of a [SegmentStrip]. Selected, it is [selectedFill] — `Ink` by default, a ramp step for
 * a rating (VI14) — with whichever of `Paper` or `Ink` reads on it. A tap is the feedback it needs, so
 * there is no ripple.
 *
 * VI21: **the selected fill springs in** from the segment's centre, with the same small overshoot as
 * everything else, clipped to the segment so it never covers a divider. The text changes colour as
 * the fill passes half, so it is never `Paper` on `Paper`.
 */
@Composable
internal fun Segment(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedFill: Color = Tokens.Ink,
    fill: Color = Tokens.Paper,
    content: @Composable BoxScope.() -> Unit,
) {
    val grown = animateFloatAsState(if (selected) 1f else 0f, Motion.spring(), label = "segment fill")
    val filled by remember { derivedStateOf { grown.value > 0.5f } }
    val contentColour = if ((if (filled) selectedFill else fill).luminance() < 0.5f) Tokens.Paper else Tokens.Ink
    Box(
        modifier = modifier
            .clipToBounds()
            .drawBehind {
                drawRect(fill)
                val scale = grown.value
                if (scale > 0f) {
                    val grownSize = Size(size.width * scale, size.height * scale)
                    drawRect(
                        selectedFill,
                        topLeft = Offset((size.width - grownSize.width) / 2f, (size.height - grownSize.height) / 2f),
                        size = grownSize,
                    )
                }
            }
            .selectable(
                selected = selected,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .heightIn(min = 48.dp)
            // Narrow, so a quarter-width rating segment keeps its label on a 360 dp phone (VI8); a
            // segment with room to spare pads its own content.
            .padding(horizontal = 4.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColour) { content() }
    }
}
