package dev.repertosaurus.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.repertosaurus.data.SampleData
import dev.repertosaurus.habit.HabitCard
import dev.repertosaurus.session.Messages
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
 * `SampleData`'s own history is untimed, so the only minutes on the card are this fixture's: today
 * holds one untimed tap and, in the timed case, one 1 500 s (25 min) event. By hand: today's line is
 * "…: 2 songs · 25 min timed", and this week's and this month's timed sums are both 1 500 s.
 */
@RunWith(AndroidJUnit4::class)
class HabitMinutesTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val harness = HabitRouteHarness(compose, "habit-minutes")

    @After
    fun cleanUp() {
        harness.cleanUp()
    }

    @Test
    fun theDayLineAndTotalsSayTimedWhenThereAreTimedEvents() {
        val route = route("timed", timedSeconds = 1_500L)
        val card = openAndTapToday(route)

        val day = card.day(route.today)!!
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
        val route = route("untimed", timedSeconds = null)
        val card = openAndTapToday(route)

        val day = card.day(route.today)!!
        assertEquals(2L, day.count)
        assertNull(day.timedSeconds, "an untimed day is null, never 0")
        assertNull(card.thisWeek.timedSeconds)
        assertNull(card.thisMonth.timedSeconds)
        compose.onNodeWithTag(HabitTags.LINE).assertTextEquals(Messages.habitDay(day))
        compose.onNodeWithText(Messages.habitThisWeek(card.thisWeek)).performScrollTo()
        compose.onNodeWithText(Messages.habitThisMonth(card.thisMonth)).performScrollTo()
        compose.onAllNodesWithText("timed", substring = true).assertCountEquals(0)
    }

    /** Today's untimed vocal tap and a guitar event timed at [timedSeconds], or untimed. */
    private fun route(suffix: String, timedSeconds: Long?): HabitRoute = harness.route(suffix) { holder ->
        val song = holder.repository.catalog.songs().first().id
        holder.repository.logPractice(song, SampleData.VOCAL)
        holder.repository.logPractice(song, SampleData.GUITAR, durationSeconds = timedSeconds)
    }

    /** Open the scorecards, wait for today's two events, and tap today's cell. Returns the landed card. */
    private fun openAndTapToday(route: HabitRoute): HabitCard {
        harness.open()
        compose.awaitUntil("the card") { route.todayOn(instrumentId = null) == 2L }
        compose.waitForIdle()
        harness.tapToday(route)
        return route.app.habit.state.value.cardFor(null)!!
    }

    private companion object {
        const val SCREENSHOTS = "p12"
    }
}
