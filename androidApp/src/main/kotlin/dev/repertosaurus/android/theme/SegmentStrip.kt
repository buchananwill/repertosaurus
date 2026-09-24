package dev.repertosaurus.android.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** How a [SegmentStrip] shares out its width (VI13). */
@Immutable
internal sealed interface SegmentLayout {
    /** Every segment the same width: the rating control, the sort. */
    data object Equal : SegmentLayout

    /** Each segment as wide as its content, for a strip inside a horizontal scroll: the instrument chips. */
    data object ContentWidth : SegmentLayout

    /**
     * triage T3's letter strip: wraps as a grid and never scrolls sideways. As many columns as fit at
     * [minCell], then evened out so the rows are as near equal as they can be (27 letters on a phone:
     * three rows of nine). A short last row is filled with `Paper`, so no cell is a block of ink.
     */
    data class Grid(val minCell: Dp) : SegmentLayout
}

/**
 * VI13: segments joined inside one 3 dp `Ink` border, with 2 dp `Ink` dividers. The strip is inked and
 * the segments are laid on it with a gap, so the border and the dividers are one fill that a segment's
 * colour can never cover. The segments in a row share its tallest one's height.
 */
@Composable
internal fun SegmentStrip(
    modifier: Modifier = Modifier,
    layout: SegmentLayout = SegmentLayout.Equal,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val gap = with(density) { Tokens.StrokeRule.roundToPx() }
    val minCell = (layout as? SegmentLayout.Grid)?.let { with(density) { it.minCell.roundToPx() } } ?: 0
    Layout(
        contents = listOf(content, { Box(modifier = Modifier.background(Tokens.Paper)) }),
        modifier = modifier.background(Tokens.Ink).padding(Tokens.StrokeHeavy),
    ) { (segments, filler), constraints ->
        if (segments.isEmpty()) return@Layout layout(constraints.minWidth, constraints.minHeight) {}
        val bounded = constraints.hasBoundedWidth
        val columns = when {
            layout is SegmentLayout.Grid && bounded -> gridColumns(segments.size, constraints.maxWidth, minCell, gap)
            else -> segments.size
        }
        val widths = when {
            layout == SegmentLayout.ContentWidth || !bounded -> segments.map { it.maxIntrinsicWidth(Constraints.Infinity) }
            else -> shares(constraints.maxWidth, columns, gap).let { share -> List(segments.size) { share[it % columns] } }
        }
        val rows = segments.indices.chunked(columns)
        val heights = rows.map { row -> rowHeight(row.map { segments[it] to widths[it] }).coerceAtLeast(constraints.minHeight) }
        val placeables = segments.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i], heights[i / columns])) }
        val shortBy = columns - rows.last().size
        val fillerPlaceable = if (shortBy > 0 && rows.size > 1) {
            val start = rows.last().size
            val width = (start until columns).sumOf { widths[it] } + gap * (shortBy - 1)
            filler.first().measure(Constraints.fixed(width, heights.last()))
        } else {
            null
        }
        val width = rows.first().sumOf { widths[it] } + gap * (rows.first().size - 1)
        val height = heights.sum() + gap * (rows.size - 1)
        layout(width, height) {
            var y = 0
            for ((r, row) in rows.withIndex()) {
                var x = 0
                for (i in row) {
                    placeables[i].place(x, y)
                    x += widths[i] + gap
                }
                if (r == rows.lastIndex) fillerPlaceable?.place(x, y)
                y += heights[r] + gap
            }
        }
    }
}

/** The width shared out over [columns] cells and the gaps between them; the last cell takes the remainder. */
private fun shares(total: Int, columns: Int, gap: Int): List<Int> {
    val usable = (total - gap * (columns - 1)).coerceAtLeast(0)
    val share = usable / columns
    return List(columns) { i -> if (i == columns - 1) usable - share * (columns - 1) else share }
}

private fun gridColumns(count: Int, width: Int, minCell: Int, gap: Int): Int {
    val fit = ((width + gap) / (minCell + gap)).coerceIn(1, count)
    val rows = (count + fit - 1) / fit
    return (count + rows - 1) / rows
}

private fun rowHeight(cells: List<Pair<Measurable, Int>>): Int = cells.maxOf { (m, w) -> m.maxIntrinsicHeight(w) }

/**
 * One segment of a [SegmentStrip]. Selected, it is [selectedFill] — `Ink` by default, a ramp step for a
 * rating (VI14) — with whichever of `Paper` or `Ink` reads on it. A tap is feedback enough: no ripple.
 *
 * VI21: the fill springs in from the centre, clipped to the segment so it never covers a divider. The
 * text changes colour as the fill passes half, so it is never `Paper` on `Paper`.
 *
 * Not [enabled] (style review F21 B4: triage T3's empty letters, P9's disabled triage modes), it is
 * `Ground` with faded content, and not tappable.
 */
@Composable
internal fun Segment(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedFill: Color = Tokens.Ink,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    SegmentFace(
        filled = selected,
        control = Modifier.selectable(
            selected = selected,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            enabled = enabled,
            role = Role.Tab,
            onClick = onClick,
        ),
        modifier = modifier,
        selectedFill = selectedFill,
        enabled = enabled,
        content = content,
    )
}

/**
 * A [Segment]'s face without its semantics: the fill that springs in when [filled], and the content in
 * whichever of `Paper` or `Ink` reads on it. [control] is the touch and the semantics, so a segment, a
 * toggle chip and an action chip share one face.
 */
@Composable
internal fun SegmentFace(
    filled: Boolean,
    control: Modifier,
    modifier: Modifier = Modifier,
    selectedFill: Color = Tokens.Ink,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val grown = animateFloatAsState(if (filled) 1f else 0f, Motion.spring(), label = "segment fill")
    val inked by remember { derivedStateOf { grown.value > 0.5f } }
    val contentColour = when {
        !enabled -> Tokens.InkMuted.copy(alpha = Tokens.DisabledAlpha)
        inked && selectedFill.luminance() < 0.5f -> Tokens.Paper
        else -> Tokens.Ink
    }
    Box(
        modifier = modifier
            .clipToBounds()
            .drawBehind {
                drawRect(if (enabled) Tokens.Paper else Tokens.Ground)
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
            .then(control)
            .heightIn(min = Tokens.TouchMin)
            // Narrow, so a quarter-width rating segment keeps its label (VI8); a segment with room to
            // spare pads its own content.
            .padding(horizontal = 4.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColour) { content() }
    }
}

/**
 * VI8, VI13: **a segment's words, which never clip and never split.** A label wider than its segment wraps
 * between words, and the strip takes its tallest segment's height, so every segment in the row grows with
 * it. A single word wider than the segment ("Confidence" at a font scale of 2.0, journal session 11,
 * D78 #6) is laid out at its own width and drawn smaller, to the segment's width: it shrinks, and is
 * never broken inside ("Confide / nce").
 */
@Composable
internal fun SegmentLabel(text: String, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val widestWord = remember(text, style, measurer) {
        text.split(' ').filter { it.isNotEmpty() }.maxOfOrNull { word ->
            measurer.measure(word, style, softWrap = false, maxLines = 1).size.width
        } ?: 0
    }
    val policy = remember(widestWord) { WholeWords(widestWord + 1) }
    Layout(
        content = {
            // The segment's full width, so a centred line is laid out in the space it is drawn in.
            Text(text, style = style, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        },
        modifier = modifier,
        measurePolicy = policy,
    )
}

/**
 * [SegmentLabel]'s measure: the text at the width it is given, unless its widest word is wider. Then the
 * text is laid out at [widestWord], where every word fits a line, and scaled down to the width given, so the
 * lines still break only between words. Intrinsic heights follow the same rule, because a strip sizes its
 * rows from them.
 */
private class WholeWords(private val widestWord: Int) : MeasurePolicy {

    private fun scale(width: Int): Float = if (width >= widestWord) 1f else width.toFloat() / widestWord

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val text = measurables.single()
        if (!constraints.hasBoundedWidth || constraints.maxWidth >= widestWord) {
            val placeable = text.measure(constraints)
            return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val scale = scale(constraints.maxWidth)
        val placeable = text.measure(Constraints.fixedWidth(widestWord))
        val height = ceil(placeable.height * scale).toInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        return layout(constraints.maxWidth, height) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        scaledHeight(width) { measurables.single().maxIntrinsicHeight(it) }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        scaledHeight(width) { measurables.single().minIntrinsicHeight(it) }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.single().maxIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.single().minIntrinsicWidth(height)

    private inline fun scaledHeight(width: Int, height: (Int) -> Int): Int =
        if (width >= widestWord) height(width) else ceil(height(widestWord) * scale(width)).toInt()
}
