package dev.repertosaurus.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.repertosaurus.android.theme.Tokens
import dev.repertosaurus.habit.HabitBucket
import dev.repertosaurus.habit.HabitCard
import dev.repertosaurus.habit.HabitDay
import dev.repertosaurus.habit.HabitStats
import dev.repertosaurus.session.Messages

/** scorecards SC5: about 12 dp a cell with 2 dp gaps. */
internal val HabitCellMax: Dp = 12.dp
internal val HabitCellGap: Dp = 2.dp

/** One grid cell's place: a week's column and a weekday's row, Monday first (SC5). */
internal data class GridCell(val column: Int, val row: Int)

/**
 * **The grid's geometry in pixels, once** (journal session 11, F24 B11): the draw, the hit-test and
 * the instrumented test all read it, so none of them derives the pitch on its own.
 */
internal data class HabitGridGeometry(val cell: Float, val gap: Float) {
    val pitch: Float get() = cell + gap
    val width: Float get() = span(HabitStats.GRID_WEEKS)
    val height: Float get() = span(HabitStats.DAYS_IN_WEEK)

    fun topLeft(at: GridCell): Offset = Offset(at.column * pitch, at.row * pitch)

    fun centre(at: GridCell): Offset = topLeft(at) + Offset(cell / 2, cell / 2)

    /** The cell under [point], or null in a gap or outside the grid. */
    fun cellAt(point: Offset): GridCell? {
        val column = (point.x / pitch).toInt()
        val row = (point.y / pitch).toInt()
        val inside = point.x >= 0f && point.y >= 0f && column < HabitStats.GRID_WEEKS && row < HabitStats.DAYS_IN_WEEK
        val inGap = point.x - column * pitch >= cell || point.y - row * pitch >= cell
        return GridCell(column, row).takeIf { inside && !inGap }
    }

    /** SC7: the drawn day under [point]. A future cell is not drawn, so it is null too. */
    fun dayAt(card: HabitCard, point: Offset): HabitDay? =
        cellAt(point)?.let { card.weeks.getOrNull(it.column)?.days?.getOrNull(it.row) }

    private fun span(cells: Int): Float = cell * cells + gap * (cells - 1)

    companion object {
        /** As large as [maxCell], or smaller, so the 26 columns fit in [available]. */
        fun fitting(available: Float, maxCell: Float, gap: Float): HabitGridGeometry {
            val columns = HabitStats.GRID_WEEKS
            return HabitGridGeometry(cell = minOf(maxCell, (available - gap * (columns - 1)) / columns), gap = gap)
        }
    }
}

/**
 * **The SC5 grid**: one `Canvas`, today outlined, future cells not drawn, zero a neutral outline and
 * the rest one hue at rising intensity (SC6). SC7: a tap selects a date and navigates nowhere.
 */
@Composable
internal fun HabitGrid(
    card: HabitCard,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val select by rememberUpdatedState(onSelect)
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val geometry = with(density) {
            HabitGridGeometry.fitting(constraints.maxWidth.toFloat(), HabitCellMax.toPx(), HabitCellGap.toPx())
        }
        Canvas(
            modifier = Modifier
                .size(with(density) { geometry.width.toDp() }, with(density) { geometry.height.toDp() })
                .testTag(HabitTags.GRID)
                .semantics { contentDescription = Messages.habitGridDescription }
                .pointerInput(card, geometry) {
                    detectTapGestures { point -> geometry.dayAt(card, point)?.let { select(it.date) } }
                },
        ) {
            card.weeks.forEachIndexed { column, week ->
                week.days.forEachIndexed { row, day ->
                    drawCell(geometry.topLeft(GridCell(column, row)), geometry.cell, day, isSelected = day.date == selected)
                }
            }
        }
    }
}

private fun DrawScope.drawCell(topLeft: Offset, side: Float, day: HabitDay, isSelected: Boolean) {
    val fill = shade(day.bucket)
    if (fill == null) {
        drawRect(Tokens.Paper, topLeft, Size(side, side))
        outline(topLeft, side, Tokens.InkMuted, Tokens.StrokeHairline.toPx())
    } else {
        drawRect(fill, topLeft, Size(side, side))
    }
    if (day.isToday) outline(topLeft, side, Tokens.Ink, Tokens.StrokeRule.toPx())
    if (isSelected) {
        val mark = side / 3
        drawRect(markInk(day.bucket), topLeft + Offset((side - mark) / 2, (side - mark) / 2), Size(mark, mark))
    }
}

/** A square outline drawn inside the cell. */
private fun DrawScope.outline(topLeft: Offset, side: Float, colour: Color, stroke: Float) {
    drawRect(colour, topLeft + Offset(stroke / 2, stroke / 2), Size(side - stroke, side - stroke), style = Stroke(stroke))
}

/** SC6: null for zero, which is an outline. */
private fun shade(bucket: HabitBucket): Color? = when (bucket) {
    HabitBucket.NONE -> null
    HabitBucket.ONE -> Tokens.IndigoStep1
    HabitBucket.FEW -> Tokens.IndigoStep2
    HabitBucket.SEVERAL -> Tokens.IndigoStep3
    HabitBucket.MANY -> Tokens.IndigoStep4
}

/** SC7: the selection mark, in whichever of `Ink` or `Paper` reads on the cell's shade. */
private fun markInk(bucket: HabitBucket): Color = when (bucket) {
    HabitBucket.SEVERAL, HabitBucket.MANY -> Tokens.Paper
    HabitBucket.NONE, HabitBucket.ONE, HabitBucket.FEW -> Tokens.Ink
}
