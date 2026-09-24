package dev.repertosaurus.android

import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.habit.HabitStats
import dev.repertosaurus.session.InMemoryDevicePreferences
import dev.repertosaurus.session.InMemorySessionPreferences
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.ViewCoordinator
import dev.repertosaurus.session.ViewFilter
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber

/**
 * One scorecards route under test: its database, the composed app's models and the device preferences.
 * `SampleData` holds guitar events 1 to 120 days back, so a "this week" total depends on the weekday;
 * tests count **today's cell**, which holds only what their own set-up logged.
 */
internal class HabitRoute(val holder: DatabaseHolder, val app: AppModels, val device: InMemoryDevicePreferences) {
    val today: String = holder.repository.today()

    /** Today's count on the landed card, if that card was built for [instrumentId]. */
    fun todayOn(instrumentId: String?): Long? = app.habit.state.value.cardFor(instrumentId)?.day(today)?.count
}

/**
 * **The whole-app harness**, first the scorecards': a fresh database per route, the whole [RepertosaurusApp]
 * composed over it, the drawer's way into any route, and the tap on today's cell.
 */
internal class HabitRouteHarness(private val compose: ComposeContentTestRule, private val prefix: String) {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()

    /**
     * A fresh database named for [suffix], with [setUp] applied and, when [viewInstrument] is given, a
     * View practising it; then the app composed over it, once the logger (and the View) has loaded.
     */
    fun route(suffix: String, viewInstrument: String? = null, setUp: (DatabaseHolder) -> Unit = {}): HabitRoute {
        val name = "$prefix-$suffix.db".also { names += it }
        val holder = EditingFixtures.holder(context, name)
        val preferences = InMemorySessionPreferences()
        if (viewInstrument != null) {
            ViewCoordinator(holder.repository, preferences).createView(
                name = "Singing",
                filter = ViewFilter(),
                practiceInstrumentId = viewInstrument,
            )
        }
        setUp(holder)
        val device = InMemoryDevicePreferences()
        val app = EditingFixtures.app(holder, preferences, device)
        compose.awaitUntil("the logger") {
            val state = app.session.state.value
            !state.loading && (viewInstrument == null || state.view != null)
        }
        compose.setApp(app)
        compose.waitForIdle()
        return HabitRoute(holder, app, device)
    }

    /** Open a route from the drawer: the scorecards unless [label] names another. */
    fun open(label: String = Messages.HABIT_TITLE) {
        compose.openFromDrawer(label)
    }

    /** Tap today's cell: the last column, today's weekday row, by the composable's own geometry (F24 B11). */
    fun tapToday(route: HabitRoute) {
        val today = LocalDate.parse(route.today)
        compose.onNodeWithTag(HabitTags.GRID).performTouchInput {
            val geometry = HabitGridGeometry.fitting(width.toFloat(), HabitCellMax.toPx(), HabitCellGap.toPx())
            click(geometry.centre(GridCell(HabitStats.GRID_WEEKS - 1, today.dayOfWeek.isoDayNumber - 1)))
        }
        compose.waitForIdle()
    }

    /** Delete every database this harness created. Call from the test's `@After`. */
    fun cleanUp() {
        for (name in names) DatabaseFixtures.delete(context, name)
    }
}
