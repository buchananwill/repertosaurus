package dev.repertosaurus.android

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.data.SampleData
import dev.repertosaurus.habit.HabitDay
import dev.repertosaurus.habit.HabitScope
import dev.repertosaurus.session.Messages
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
    private val harness = HabitRouteHarness(compose, "habit-test")

    @After
    fun cleanUp() {
        harness.cleanUp()
        context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun theRouteOpensFromTheDrawer() {
        val route = route("drawer")

        harness.open()

        compose.onNodeWithTag(HabitTags.SCREEN).assertIsDisplayed()
        await("the card") { route.app.habit.state.value.card != null }
        compose.waitForIdle()
        compose.onNodeWithTag(HabitTags.GRID).assertIsDisplayed()
        compose.onNodeWithTag(HabitTags.SCOPE_ALL).assertIsSelected()
    }

    /** SC4: the choice is the device preference, and it is still chosen when the route is reopened. */
    @Test
    fun theScopeTogglePersists() {
        val route = route("scope")
        harness.open()
        await("the all-instruments card") { route.todayOn(instrumentId = null) == 2L }

        compose.onNodeWithTag(HabitTags.SCOPE_INSTRUMENT).performClick()
        await("the vocal card") { route.todayOn(SampleData.VOCAL) == 1L }
        await("the preference written") { route.device.habitScope() == HabitScope.PRACTICE_INSTRUMENT }
        compose.waitForIdle()
        compose.onNodeWithTag(HabitTags.SCOPE_INSTRUMENT).assertIsSelected()
        compose.onNodeWithTag(HabitTags.SCOPE_ALL).assertIsNotSelected()

        compose.onNodeWithTag(HabitTags.DONE).performClick()
        compose.waitForIdle()
        harness.open()
        compose.onNodeWithTag(HabitTags.SCOPE_INSTRUMENT).assertIsSelected()

        // And through the real SharedPreferences: a fresh instance reads the choice back.
        AndroidSessionPreferences(context, PREFERENCES_FILE).rememberHabitScope(HabitScope.PRACTICE_INSTRUMENT)
        assertEquals(HabitScope.PRACTICE_INSTRUMENT, AndroidSessionPreferences(context, PREFERENCES_FILE).habitScope())
    }

    /** SC7: tapping today's cell shows its line. */
    @Test
    fun tappingACellShowsItsLine() {
        val route = route("tap")
        harness.open()
        await("the card") { route.todayOn(instrumentId = null) == 2L }
        compose.waitForIdle()

        harness.tapToday(route)

        compose.onNodeWithTag(HabitTags.LINE)
            .assertTextEquals(Messages.habitDay(HabitDay(route.today, 2L, isToday = true, timedSeconds = null)))
    }

    /** A View practising vocal, and one untimed vocal and one untimed guitar tap today. */
    private fun route(suffix: String): HabitRoute = harness.route(suffix, viewInstrument = SampleData.VOCAL) { holder ->
        val song = holder.repository.catalog.songs().first().id
        holder.repository.logPractice(song, SampleData.VOCAL)
        holder.repository.logPractice(song, SampleData.GUITAR)
    }

    private fun await(what: String, settled: () -> Boolean) = compose.awaitUntil(what, settled)

    private companion object {
        const val PREFERENCES_FILE = "habit-scope-test"
    }
}
