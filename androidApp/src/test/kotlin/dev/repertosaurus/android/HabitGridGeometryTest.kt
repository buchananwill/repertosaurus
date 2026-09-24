package dev.repertosaurus.android

import androidx.compose.ui.geometry.Offset
import dev.repertosaurus.habit.HabitStats
import kotlinx.datetime.LocalDate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Safety review F23 N4: the grid's hit-test through [HabitGridGeometry], the type the draw uses
 * (F24 B11). A 12 px cell and a 2 px gap: pitch 14. Today is Wednesday 7 January 2026, so the last
 * column holds Monday to Wednesday and its Thursday to Sunday are not drawn.
 */
class HabitGridGeometryTest {

    private val geometry = HabitGridGeometry(cell = 12f, gap = 2f)
    private val card = HabitStats.build(emptyMap(), LocalDate.parse("2026-01-07"), instrumentId = null)

    @Test
    fun itsSizeIs26By7CellsAndTheGapsBetween() {
        assertEquals(26 * 12f + 25 * 2f, geometry.width)
        assertEquals(7 * 12f + 6 * 2f, geometry.height)
    }

    @Test
    fun columnZeroIsTheOldestMonday() {
        assertEquals("2025-07-14", geometry.dayAt(card, geometry.centre(GridCell(0, 0)))?.date)
        assertEquals("2025-07-20", geometry.dayAt(card, geometry.centre(GridCell(0, 6)))?.date)
        assertEquals("2025-07-14", geometry.dayAt(card, Offset(0f, 0f))?.date, "the cell's top-left corner")
    }

    @Test
    fun todayIsTheLastColumnOnItsWeekdayRow() {
        assertEquals("2026-01-07", geometry.dayAt(card, geometry.centre(GridCell(25, 2)))?.date)
    }

    @Test
    fun aFutureCellHasNoDay() {
        assertEquals(GridCell(25, 3), geometry.cellAt(geometry.centre(GridCell(25, 3))))
        assertNull(geometry.dayAt(card, geometry.centre(GridCell(25, 3))))
        assertNull(geometry.dayAt(card, geometry.centre(GridCell(25, 6))))
    }

    @Test
    fun aGapHasNoCell() {
        assertNull(geometry.cellAt(Offset(12.5f, 6f)), "between columns 0 and 1")
        assertNull(geometry.cellAt(Offset(6f, 13f)), "between rows 0 and 1")
        assertNull(geometry.cellAt(Offset(-1f, 6f)), "left of the grid")
        assertNull(geometry.cellAt(Offset(6f, geometry.height + 1f)), "below the grid")
        assertEquals(GridCell(1, 0), geometry.cellAt(Offset(14f, 0f)), "column 1 starts at one pitch")
    }

    /** It fits: at most the largest cell, smaller when 26 of those would not fit. */
    @Test
    fun fittingCapsTheCellAndShrinksToTheWidth() {
        assertEquals(12f, HabitGridGeometry.fitting(available = 1000f, maxCell = 12f, gap = 2f).cell)
        val narrow = HabitGridGeometry.fitting(available = 310f, maxCell = 12f, gap = 2f)
        assertEquals(10f, narrow.cell)
        assertEquals(310f, narrow.width)
    }
}
