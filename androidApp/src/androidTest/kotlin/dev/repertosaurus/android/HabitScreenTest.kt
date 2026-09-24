package dev.repertosaurus.android

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.SampleData
import dev.repertosaurus.habit.HabitScope
import dev.repertosaurus.habit.HabitStats
import dev.repertosaurus.habit.HabitDay
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.InMemoryDevicePreferences
import dev.repertosaurus.session.InMemorySessionPreferences
import dev.repertosaurus.session.ViewCoordinator
import dev.repertosaurus.session.ViewFilter
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * **The scorecards route on the composed app** (scorecards §3): it opens from the drawer, the SC4
 * scope toggle persists, and tapping a cell shows its SC7 line. The whole of [RepertosaurusApp] is
 * composed over one real database holding two events today, one on vocal and one on guitar, with a
 * View practising vocal.
 */
@RunWith(AndroidJUnit4::class)
class HabitScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()

    @After
    fun cleanUp() {
        for (name in names) DatabaseFixtures.delete(context, name)
        context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun theRouteOpensFromTheDrawer() {
        val fixture = fixture("drawer")

        openScorecards()

        compose.onNodeWithTag(HabitTags.SCREEN).assertIsDisplayed()
        await("the card") { fixture.app.habit.state.value.card != null }
        compose.waitForIdle()
        compose.onNodeWithTag(HabitTags.GRID).assertIsDisplayed()
        compose.onNodeWithTag(HabitTags.SCOPE_ALL).assertIsSelected()
    }

    /** SC4: the choice is the device preference, and it is still chosen when the route is reopened. */
    @Test
    fun theScopeTogglePersists() {
        val fixture = fixture("scope")
        openScorecards()
        await("the all-instruments card") { fixture.todayOn(instrumentId = null) == 2L }

        compose.onNodeWithTag(HabitTags.SCOPE_INSTRUMENT).performClick()
        await("the vocal card") { fixture.todayOn(SampleData.VOCAL) == 1L }
        await("the preference written") { fixture.device.habitScope() == HabitScope.PRACTICE_INSTRUMENT }
        compose.waitForIdle()
        compose.onNodeWithTag(HabitTags.SCOPE_INSTRUMENT).assertIsSelected()
        compose.onNodeWithTag(HabitTags.SCOPE_ALL).assertIsNotSelected()

        compose.onNodeWithTag(HabitTags.DONE).performClick()
        compose.waitForIdle()
        openScorecards()
        compose.onNodeWithTag(HabitTags.SCOPE_INSTRUMENT).assertIsSelected()

        // And through the real SharedPreferences: a fresh instance reads the choice back.
        AndroidSessionPreferences(context, PREFERENCES_FILE).rememberHabitScope(HabitScope.PRACTICE_INSTRUMENT)
        assertEquals(HabitScope.PRACTICE_INSTRUMENT, AndroidSessionPreferences(context, PREFERENCES_FILE).habitScope())
    }

    /** SC7: tapping today's cell (the last column, today's weekday row) shows its line. */
    @Test
    fun tappingACellShowsItsLine() {
        val fixture = fixture("tap")
        openScorecards()
        await("the card") { fixture.todayOn(instrumentId = null) == 2L }
        compose.waitForIdle()

        val today = LocalDate.parse(fixture.today)
        val grid = compose.onNodeWithTag(HabitTags.GRID)
        grid.performTouchInput {
            // F24 B11: the composable's own geometry, fitted to the grid's own width.
            val geometry = HabitGridGeometry.fitting(width.toFloat(), HabitCellMax.toPx(), HabitCellGap.toPx())
            click(geometry.centre(GridCell(HabitStats.GRID_WEEKS - 1, today.dayOfWeek.isoDayNumber - 1)))
        }
        compose.waitForIdle()

        compose.onNodeWithTag(HabitTags.LINE).assertTextEquals(Messages.habitDay(HabitDay(today.toString(), 2L, isToday = true)))
    }

    // ---- Harness -------------------------------------------------------------------------

    /**
     * The harness's `SampleData` already holds guitar events 1 to 120 days back, so any "this week"
     * total depends on the weekday the suite runs on. The tests count **today's cell** instead, which
     * holds exactly the fixture's own two events.
     */
    private class Fixture(val holder: DatabaseHolder, val app: AppModels, val device: InMemoryDevicePreferences) {
        val today: String = holder.repository.today()

        /** Today's count on the landed card, if that card was built for [instrumentId]. */
        fun todayOn(instrumentId: String?): Long? = app.habit.state.value.cardFor(instrumentId)?.day(today)?.count
    }

    private fun fixture(suffix: String): Fixture {
        val name = "habit-test-$suffix.db".also { names += it }
        val holder = EditingFixtures.holder(context, name)
        val preferences = InMemorySessionPreferences()
        ViewCoordinator(holder.repository, preferences).createView(
            name = "Singing",
            filter = ViewFilter(),
            practiceInstrumentId = SampleData.VOCAL,
        )
        val song = holder.repository.catalog.songs().first().id
        holder.repository.logPractice(song, SampleData.VOCAL)
        holder.repository.logPractice(song, SampleData.GUITAR)

        val device = InMemoryDevicePreferences()
        val app = EditingFixtures.app(holder, preferences, device)
        await("the logger") { !app.session.state.value.loading && app.session.state.value.view != null }
        compose.setApp(app)
        compose.waitForIdle()
        return Fixture(holder, app, device)
    }

    private fun openScorecards() {
        compose.onNodeWithContentDescription(LOGGER_MENU).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(Messages.HABIT_TITLE).performClick()
        compose.waitForIdle()
    }

    private fun await(what: String, settled: () -> Boolean) = compose.awaitUntil(what, settled)

    private companion object {
        const val LOGGER_MENU = "Menu"
        const val PREFERENCES_FILE = "habit-scope-test"
    }
}
