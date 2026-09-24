package dev.repertosaurus.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.SampleData
import dev.repertosaurus.habit.HabitCard
import dev.repertosaurus.habit.HabitStats
import dev.repertosaurus.session.InMemoryDevicePreferences
import dev.repertosaurus.session.InMemorySessionPreferences
import dev.repertosaurus.session.Messages
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **scorecards SC16-SC18 on the composed app**: the day line and the week and month totals say
 * "timed" when there are timed events, and are exactly SC7 and SC10's lines when there are none.
 *
 * `SampleData`'s own history is untimed (its `duration_seconds` is null), so the only minutes on the
 * card are this fixture's: today holds one untimed tap and, in the timed case, one 1 500 s (25 min)
 * timed event. By hand: today's line is "…: 2 songs · 25 min timed", and this week's and this
 * month's timed sums are both exactly 1 500 s, whatever the weekday.
 */
@RunWith(AndroidJUnit4::class)
class HabitMinutesTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()

    @After
    fun cleanUp() {
        for (name in names) DatabaseFixtures.delete(context, name)
    }

    @Test
    fun theDayLineAndTotalsSayTimedWhenThereAreTimedEvents() {
        val holder = fixture("timed", timedSeconds = 1_500L)
        val today = holder.repository.today()
        val card = openAndTapToday(holder)

        val day = card.day(today)!!
        assertEquals(2L, day.count)
        assertEquals(1_500L, day.timedSeconds)
        val line = Messages.habitDay(day)
        assertTrue(line.endsWith(": 2 songs · 25 min timed"), line)
        compose.onNodeWithTag(HabitTags.LINE).assertTextEquals(line)

        assertEquals(1_500L, card.thisWeek.timedSeconds)
        assertEquals(1_500L, card.thisMonth.timedSeconds)
        val week = Messages.habitThisWeek(card.thisWeek)
        val month = Messages.habitThisMonth(card.thisMonth)
        assertTrue(week.endsWith(" · 25 min timed"), week)
        assertTrue(month.endsWith(" · 25 min timed"), month)
        compose.onNodeWithText(week).performScrollTo()
        compose.onNodeWithText(month).performScrollTo()
        compose.onNodeWithTag(HabitTags.LINE).performScrollTo()

        compose.screenshot(SCREENSHOTS, "scorecards-day-line-minutes")
    }

    @Test
    fun withNoTimedEventNothingSaysTimed() {
        val holder = fixture("untimed", timedSeconds = null)
        val today = holder.repository.today()
        val card = openAndTapToday(holder)

        val day = card.day(today)!!
        assertEquals(2L, day.count)
        assertNull(day.timedSeconds, "an untimed day is null, never 0")
        assertNull(card.thisWeek.timedSeconds)
        assertNull(card.thisMonth.timedSeconds)
        compose.onNodeWithTag(HabitTags.LINE).assertTextEquals(Messages.habitDay(day))
        compose.onNodeWithText(Messages.habitThisWeek(card.thisWeek)).performScrollTo()
        compose.onNodeWithText(Messages.habitThisMonth(card.thisMonth)).performScrollTo()
        compose.onAllNodesWithText("timed", substring = true).assertCountEquals(0)
    }

    // ---- Harness -------------------------------------------------------------------------

    /** A database with `SampleData`, plus today's untimed vocal tap and, if [timedSeconds], a timed guitar event. */
    private fun fixture(suffix: String, timedSeconds: Long?): DatabaseHolder {
        val name = "habit-minutes-$suffix.db".also { names += it }
        val holder = EditingFixtures.holder(context, name)
        val song = holder.repository.catalog.songs().first().id
        holder.repository.logPractice(song, SampleData.VOCAL)
        holder.repository.logPractice(song, SampleData.GUITAR, durationSeconds = timedSeconds)
        return holder
    }

    /** Compose the app, open the scorecards from the drawer, and tap today's cell. Returns the landed card. */
    private fun openAndTapToday(holder: DatabaseHolder): HabitCard {
        val app = EditingFixtures.app(holder, InMemorySessionPreferences(), InMemoryDevicePreferences())
        compose.awaitUntil("the logger") { !app.session.state.value.loading }
        compose.setApp(app)
        compose.waitForIdle()
        compose.onNodeWithContentDescription(LOGGER_MENU).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(Messages.HABIT_TITLE).performClick()
        compose.waitForIdle()

        val today = holder.repository.today()
        compose.awaitUntil("the card") { app.habit.state.value.cardFor(null)?.day(today)?.count == 2L }
        compose.waitForIdle()
        val date = LocalDate.parse(today)
        compose.onNodeWithTag(HabitTags.GRID).performTouchInput {
            val geometry = HabitGridGeometry.fitting(width.toFloat(), HabitCellMax.toPx(), HabitCellGap.toPx())
            click(geometry.centre(GridCell(HabitStats.GRID_WEEKS - 1, date.dayOfWeek.isoDayNumber - 1)))
        }
        compose.waitForIdle()
        return app.habit.state.value.cardFor(null)!!
    }

    private companion object {
        const val LOGGER_MENU = "Menu"
        const val SCREENSHOTS = "p12"
    }
}
