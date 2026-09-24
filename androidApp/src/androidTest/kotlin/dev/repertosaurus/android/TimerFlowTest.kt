package dev.repertosaurus.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.android.DatabaseFixtures.count
import dev.repertosaurus.android.theme.RepertosaurusWindow
import dev.repertosaurus.session.InMemorySessionPreferences
import dev.repertosaurus.session.InMemoryDevicePreferences
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.SessionRow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A wall clock the test moves (timer TM11). */
internal class MovableClock(@Volatile var instant: Instant) : Clock {
    override fun now(): Instant = instant

    fun forward(seconds: Long) {
        instant = Instant.fromEpochMilliseconds(instant.toEpochMilliseconds() + seconds * 1_000L)
    }
}

/**
 * timer TM1-TM10 end to end on the Session screen: every write is read back from the database.
 *
 * The clock starts at 23:50 local on 2026-09-20, so every stop past ten minutes crosses midnight and TM7's
 * start-date rule is tested by every timed log below.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TimerFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fixture = SessionScreenFixture(compose, "timer-flow-test")
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val start = LocalDateTime(2026, 9, 20, 23, 50).toInstant(TimeZone.currentSystemDefault())
    private val clock = MovableClock(start)

    @After
    fun cleanUp() {
        fixture.cleanUp()
        context.deleteSharedPreferences(STORE_FILE)
    }

    private fun SessionScreenFixture.Screen.row(index: Int): SessionRow = session.state.value.pending[index]

    /** A row in the list, not the bar's title. */
    private fun inTheList(title: String): SemanticsMatcher = hasText(title) and hasAnyAncestor(hasTestTag(SessionTags.LIST))

    /** TM1: long-press, then the sheet's "Start timer"; [shot] names a screenshot of the open sheet. */
    private fun startFromTheSheet(screen: SessionScreenFixture.Screen, row: SessionRow, shot: String? = null) {
        compose.onNode(inTheList(row.title)).performTouchInput { longClick() }
        compose.waitForIdle()
        val button = compose.onNodeWithText(Messages.TIMER_START, ignoreCase = true).performScrollTo()
        shot?.let { expandTheSheetAndShoot(it) }
        button.performClick()
        compose.awaitUntil("${row.title}'s timer") { screen.session.timer.running.value?.timer?.songId == row.songId }
        compose.waitForIdle()
    }

    /**
     * The package's screenshot, once the display has caught up: a sheet's scroll, the bar's spring and the
     * dialog's window are drawn on real frames, which the test clock does not wait for.
     */
    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)
        compose.screenshot("p11", name)
    }

    /** The feel sheet opens half-expanded, with Log and the timer below the fold: drawn fully for the shot. */
    private fun expandTheSheetAndShoot(name: String) {
        compose.onNodeWithText("Feel").performTouchInput { swipe(center, center - Offset(0f, 900f), durationMillis = 400) }
        shot(name)
    }

    private fun events(screen: SessionScreenFixture.Screen, where: String): Long = count(screen.holder, "practice_event WHERE $where")

    /** TM1, TM4, TM7: start from the sheet, stop, and exactly one event with its duration on the start's date. */
    @Test
    fun startFromTheSheetThenStop() {
        val screen = fixture.open("stop", clock = clock)
        val row = screen.row(0)
        val before = events(screen, "1")

        startFromTheSheet(screen, row, shot = "feel-sheet-start")
        compose.onNodeWithTag(TimerTags.BAR).assertExists()
        compose.onNode(hasText(row.title) and hasAnyAncestor(hasTestTag(TimerTags.BAR))).assertExists()
        shot("running-bar")

        clock.forward(24L * 60L)
        compose.onNodeWithText(Messages.TIMER_STOP, ignoreCase = true).performClick()
        val timed = "song_id = '${row.songId}' AND duration_seconds = 1440 AND logged_on = '2026-09-20' AND feel IS NULL"
        compose.awaitUntil("the timed insert") { events(screen, timed) == 1L }
        compose.waitForIdle()

        assertEquals(before + 1, events(screen, "1"), "exactly one event")
        assertNull(screen.session.timer.running.value)
        compose.onNodeWithText("Logged ${row.title} · 24 min").assertExists()
        compose.onNodeWithTag(TimerTags.BAR).assertDoesNotExist()
    }

    /** TM9: Cancel writes nothing, and Undo restores the same timer, start instant and all. */
    @Test
    fun cancelThenUndoRestoresTheSameStart() {
        val screen = fixture.open("cancel", clock = clock)
        val row = screen.row(0)
        val before = events(screen, "1")
        startFromTheSheet(screen, row)
        val started = assertNotNull(screen.session.timer.running.value)
        clock.forward(90L)

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(Messages.TIMER_CANCEL, ignoreCase = true).performClick()
        repeat(FRAMES) {
            if (compose.onAllNodesWithText(Messages.TIMER_UNDO).fetchSemanticsNodes().isEmpty()) compose.mainClock.advanceTimeByFrame()
        }
        compose.onNodeWithText(Messages.TIMER_CANCELLED).assertExists()
        assertNull(screen.session.timer.running.value)
        compose.onNodeWithText(Messages.TIMER_UNDO).performClick()
        compose.mainClock.autoAdvance = true
        compose.awaitUntil("the restored timer") { screen.session.timer.running.value != null }

        assertEquals(started, screen.session.timer.running.value)
        assertEquals(90L, screen.session.timer.elapsed(started))
        assertEquals(before, events(screen, "1"), "nothing written")
    }

    /** TM2: the sheet's button reads "Switch timer here", and one tap logs the first and times the second. */
    @Test
    fun switch() {
        val screen = fixture.open("switch", clock = clock)
        val first = screen.row(0)
        val second = screen.row(1)
        startFromTheSheet(screen, first)
        clock.forward(600L)

        compose.onNode(inTheList(second.title)).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText(Messages.TIMER_START, ignoreCase = true).assertDoesNotExist()
        expandTheSheetAndShoot("feel-sheet-switch")
        compose.onNodeWithText(Messages.TIMER_SWITCH, ignoreCase = true).performScrollTo().performClick()

        compose.awaitUntil("the first's timed insert") {
            events(screen, "song_id = '${first.songId}' AND duration_seconds = 600 AND logged_on = '2026-09-20'") == 1L
        }
        val running = assertNotNull(screen.session.timer.running.value)
        assertEquals(second.songId to clock.instant.toEpochMilliseconds(), running.timer.songId to running.timer.startedAtEpochMs)
        compose.onNode(hasText(second.title) and hasAnyAncestor(hasTestTag(TimerTags.BAR))).assertExists()
    }

    /** TM5: a plain tap on the timed song logs one untimed event at once, and the timer runs on untouched. */
    @Test
    fun aPlainTapDuringATimerLogsUntimed() {
        val screen = fixture.open("tap", clock = clock)
        val row = screen.row(0)
        startFromTheSheet(screen, row)
        val started = assertNotNull(screen.session.timer.running.value)
        val untimed = "song_id = '${row.songId}' AND duration_seconds IS NULL"
        val before = events(screen, untimed)
        clock.forward(60L)

        compose.onNode(inTheList(row.title)).performClick()
        compose.awaitUntil("the tap's insert") { events(screen, untimed) == before + 1 }
        compose.waitForIdle()

        assertEquals(0L, events(screen, "duration_seconds IS NOT NULL"), "no timed event")
        assertEquals(started, screen.session.timer.running.value)
        compose.onNodeWithTag(TimerTags.BAR).assertExists()
    }

    /** TM4: the clock opens the full-screen clock; back returns, and so does a tap on its digits. */
    @Test
    fun theFullScreenClockOpensAndCloses() {
        val screen = fixture.open("full-screen", clock = clock)
        startFromTheSheet(screen, screen.row(0))
        clock.forward(754L)

        compose.onNodeWithContentDescription(Messages.TIMER_OPEN_CLOCK).performClick()
        compose.onNodeWithTag(TimerTags.FULL_SCREEN).assertExists()
        compose.awaitUntil("the clock at 12:34") {
            compose.onAllNodesWithText("12:34").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(TimerTags.FULL_SCREEN_CLOCK, useUnmergedTree = true).assertExists()
        shot("full-screen-clock")

        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag(TimerTags.FULL_SCREEN).assertDoesNotExist()
        compose.onNodeWithTag(TimerTags.BAR).assertExists()

        compose.onNodeWithContentDescription(Messages.TIMER_OPEN_CLOCK).performClick()
        compose.onNodeWithContentDescription(Messages.TIMER_CLOSE_CLOCK).performClick()
        compose.onNodeWithTag(TimerTags.FULL_SCREEN).assertDoesNotExist()
        assertNotNull(screen.session.timer.running.value, "closing is not stopping")
    }

    /** TM8: over 3 h, Stop asks the one question; "with a time" logs it. */
    @Test
    fun overThreeHoursWithTheTime() {
        val screen = fixture.open("with-time", clock = clock)
        val row = screen.row(0)
        startFromTheSheet(screen, row)
        clock.forward(5L * 3_600L + 12L * 60L)

        compose.onNodeWithText(Messages.TIMER_STOP, ignoreCase = true).performClick()
        compose.onNodeWithTag(TimerTags.QUESTION).assertExists()
        compose.onNodeWithText("Log 5 h 12 min, or log without a time?").assertExists()
        assertNotNull(screen.session.timer.running.value, "still running while asked")
        shot("over-three-hours")

        compose.onNodeWithText("Log 5 h 12 min", ignoreCase = true).performClick()
        compose.awaitUntil("the timed insert") {
            events(screen, "song_id = '${row.songId}' AND duration_seconds = 18720 AND logged_on = '2026-09-20'") == 1L
        }
        compose.onNodeWithTag(TimerTags.QUESTION).assertDoesNotExist()
        assertNull(screen.session.timer.running.value)
    }

    /** TM8: "without a time" logs one untimed event. */
    @Test
    fun overThreeHoursWithoutATime() {
        val screen = fixture.open("without-time", clock = clock)
        val row = screen.row(0)
        val untimed = "song_id = '${row.songId}' AND duration_seconds IS NULL AND logged_on = '2026-09-20'"
        val before = events(screen, untimed)
        startFromTheSheet(screen, row)
        clock.forward(4L * 3_600L)

        compose.onNodeWithText(Messages.TIMER_STOP, ignoreCase = true).performClick()
        compose.onNodeWithText(Messages.TIMER_LOG_WITHOUT, ignoreCase = true).performClick()
        compose.awaitUntil("the untimed insert") { events(screen, untimed) == before + 1 }
        assertEquals(0L, events(screen, "duration_seconds IS NOT NULL"))
        assertNull(screen.session.timer.running.value)
    }

    /** TM4, TM10: a recreation keeps the timer and the open full-screen clock. */
    @Test
    fun theTimerSurvivesARecreation() {
        val screen = fixture.build("recreation", clock = clock)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { RepertosaurusWindow { SessionContent(screen) } }
        compose.waitForIdle()
        startFromTheSheet(screen, screen.row(0))
        val started = screen.session.timer.running.value
        compose.onNodeWithContentDescription(Messages.TIMER_OPEN_CLOCK).performClick()

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertEquals(started, screen.session.timer.running.value)
        compose.onNodeWithTag(TimerTags.FULL_SCREEN).assertExists()
    }

    /** TM10: a new ViewModel over the same store, as after a process death, restores the timer and its bar. */
    @Test
    fun theTimerSurvivesAProcessRestart() {
        val first = fixture.build("restart", timerStore = AndroidTimerStore(context, STORE_FILE), clock = clock)
        val row = first.row(0)
        EditingFixtures.onMain { first.session.startTimer(row.songId, row.title) }
        val started = assertNotNull(first.session.timer.running.value)
        EditingFixtures.await("the store's write") { AndroidTimerStore(context, STORE_FILE).read() == started.timer }

        lateinit var restarted: SessionViewModel
        EditingFixtures.onMain {
            restarted = SessionViewModel(first.holder, InMemorySessionPreferences(), TEST_DEVICE, timerStore = AndroidTimerStore(context, STORE_FILE), clock = clock)
        }
        EditingFixtures.await("the restore") { restarted.timer.running.value != null }
        assertEquals(started, restarted.timer.running.value)

        val screen = SessionScreenFixture.Screen(first.holder, restarted, DeviceSettings(InMemoryDevicePreferences()))
        compose.setContent { RepertosaurusWindow { SessionContent(screen) } }
        compose.waitForIdle()
        compose.onNode(hasText(row.title) and hasAnyAncestor(hasTestTag(TimerTags.BAR))).assertExists()
    }

    /** VI8: the bar at a font scale of 1.3, with the widest clock, `h:mm:ss`, clips nothing. */
    @Test
    fun theBarFitsAtALargeFont() {
        val screen = fixture.open("font", fontScale = LARGE_FONT, clock = clock)
        val row = screen.row(0)
        EditingFixtures.onMain { screen.session.startTimer(row.songId, row.title) }
        clock.forward(2L * 3_600L + 34L * 60L + 56L)
        compose.awaitUntil("the clock at 2:34:56") {
            compose.onAllNodesWithText("2:34:56").fetchSemanticsNodes().isNotEmpty()
        }
        compose.assertNoTextClipped("the bar", hasAnyAncestor(hasTestTag(TimerTags.BAR)))
        shot("running-bar-fs1.3")
    }

    private companion object {
        const val STORE_FILE = "timer-flow-test"
        const val FRAMES = 10
        const val LARGE_FONT = 1.3f
        const val SETTLE_MS = 1_000L
    }
}

/** **visual-identity VI22 for the bar**: with the animator duration scale at 0 it arrives and leaves at once. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TimerReducedMotionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(NoMotion)

    private val fixture = SessionScreenFixture(compose, "timer-reduced-motion-test")

    @After
    fun cleanUp() = fixture.cleanUp()

    @Test
    fun theBarArrivesAndLeavesAtOnce() {
        val screen = fixture.open("no-motion")
        val row = screen.session.state.value.pending.first()
        EditingFixtures.onMain { screen.session.startTimer(row.songId, row.title) }
        compose.waitForIdle()
        val settled = compose.onNodeWithTag(TimerTags.BAR).fetchSemanticsNode().size.height
        EditingFixtures.onMain { screen.session.timer.cancel() }
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        EditingFixtures.onMain { screen.session.startTimer(row.songId, row.title) }
        assertWithinFrames("the bar's arrival") {
            compose.onAllNodesWithTag(TimerTags.BAR).fetchSemanticsNodes().singleOrNull()?.size?.height == settled
        }
        EditingFixtures.onMain { screen.session.stopTimer() }
        assertWithinFrames("the bar's exit") { compose.onAllNodesWithTag(TimerTags.BAR).fetchSemanticsNodes().isEmpty() }
        compose.mainClock.autoAdvance = true
    }

    private fun assertWithinFrames(what: String, settled: () -> Boolean) {
        repeat(INSTANT_FRAMES) {
            if (settled()) return
            compose.mainClock.advanceTimeByFrame()
        }
        kotlin.test.assertTrue(settled(), "$what took more than $INSTANT_FRAMES frames")
    }

    private companion object {
        const val INSTANT_FRAMES = 5
    }
}
