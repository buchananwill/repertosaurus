package dev.repertosaurus.android

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.android.theme.RepertosaurusWindow
import dev.repertosaurus.session.InMemoryDevicePreferences
import dev.repertosaurus.session.InMemorySessionPreferences
import dev.repertosaurus.session.Messages
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** timer TM4, TM10: the bar, the full-screen clock, the drawer's line, and the timer across a restart. */
@RunWith(AndroidJUnit4::class)
class TimerScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val kit = TimerKit(compose, "timer-screen-test")
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @After
    fun cleanUp() {
        kit.fixture.cleanUp()
        context.deleteSharedPreferences(STORE_FILE)
    }

    private fun SessionScreenFixture.Screen.startFirst() {
        val row = session.state.value.pending.first()
        EditingFixtures.onMain { session.startTimer(row.songId, row.title) }
        compose.waitForIdle()
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun fullScreenOpen() = compose.onAllNodesWithTag(TimerTags.FULL_SCREEN).fetchSemanticsNodes().isNotEmpty()

    /** D83: whether any window of this app asks for the screen to stay on. */
    private fun keepsScreenOn(): Boolean =
        shellOutput("dumpsys window windows").split(Regex("(?m)^\\s*Window #"))
            .filter { block -> context.packageName in block }
            .any { block -> block.lineSequence().any { "fl=" in it && "KEEP_SCREEN_ON" in it } }

    private fun back() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    private fun opensWithTheAction(label: String) = SemanticsMatcher("its action reads \"$label\"") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }

    /**
     * TM4: the clock opens the full-screen clock, which keeps the screen on; back returns, and so does a tap on its
     * digits, and neither stops the timer.
     */
    @Test
    fun theFullScreenClockOpensAndCloses() {
        val screen = kit.fixture.open("full-screen", timer = kit.timer)
        screen.startFirst()
        kit.clock.forward(754L)
        assertFalse(keepsScreenOn(), "not before it opens")

        compose.onNodeWithTag(TimerTags.CLOCK).performClick()
        compose.awaitUntil("the clock at 12:34") { fullScreenOpen() && shows("12:34") }
        compose.awaitUntil("the screen kept on") { keepsScreenOn() }
        compose.expandAndShoot("p11", "full-screen-clock")

        back()
        compose.awaitUntil("the clock closed by back") { !fullScreenOpen() }
        compose.awaitUntil("the screen let sleep") { !keepsScreenOn() }
        compose.onNodeWithTag(TimerTags.BAR).assertExists()

        compose.onNodeWithTag(TimerTags.CLOCK).performClick()
        compose.awaitUntil("the clock open again") { fullScreenOpen() }
        compose.onNodeWithTag(TimerTags.FULL_SCREEN_CLOCK).performClick()
        compose.awaitUntil("the clock closed by its digits") { !fullScreenOpen() }
        assertNotNull(screen.session.timer.running.value, "closing is not stopping")
    }

    /** TM4: the time is read with the action, in one merged node, on the bar and on the full-screen digits. */
    @Test
    fun theTimeIsReadWithTheAction() {
        val screen = kit.fixture.open("semantics", timer = kit.timer)
        screen.startFirst()
        kit.clock.forward(754L)
        compose.awaitUntil("the clock at 12:34") { shows("12:34") }

        compose.onNodeWithTag(TimerTags.CLOCK).assert(hasText("12:34")).assert(opensWithTheAction(Messages.TIMER_OPEN_CLOCK))
        compose.onNodeWithTag(TimerTags.CLOCK).performClick()
        compose.awaitUntil("the full-screen clock") { fullScreenOpen() }
        compose.onNodeWithTag(TimerTags.FULL_SCREEN_CLOCK).assert(hasText("12:34")).assert(opensWithTheAction(Messages.TIMER_CLOSE_CLOCK))
    }

    /** TM4: a tap on a blank part of the full-screen clock, over a song's row, logs nothing and closes nothing. */
    @Test
    fun aBlankTapOnTheClockLogsNothing() {
        val screen = kit.fixture.open("blank-tap", timer = kit.timer)
        screen.startFirst()
        compose.onNodeWithTag(TimerTags.CLOCK).performClick()
        compose.awaitUntil("the full-screen clock") { fullScreenOpen() }
        compose.waitForIdle()
        val before = kit.events(screen, "1")

        // The middle of the lowest row on screen, beneath the clock's vertically centred content. Were it a
        // button of the clock, the timer would stop or cancel and the assertions below would fail.
        val list = compose.onAllNodesWithTag(SessionTags.LIST).fetchSemanticsNodes().single()
        val listBottom = list.positionInWindow.y + list.size.height
        val row = list.children
            .filter { it.size.height > 0 && it.positionInWindow.y + it.size.height <= listBottom }
            .maxBy { it.positionInWindow.y }
        val origin = IntArray(2).also { compose.activity.window.decorView.getLocationOnScreen(it) }
        val x = origin[0] + row.positionInWindow.x + row.size.width / 2f
        val y = origin[1] + row.positionInWindow.y + row.size.height / 2f
        tap(x, y)
        compose.waitForIdle()
        Thread.sleep(500L)

        assertEquals(before, kit.events(screen, "1"), "no log beneath the clock")
        assertTrue(fullScreenOpen(), "the clock is still open")

        // The control: the same point with the clock closed is a row, and logs.
        back()
        compose.awaitUntil("the clock closed") { !fullScreenOpen() }
        tap(x, y)
        compose.awaitUntil("the control's log") { kit.events(screen, "1") == before + 1 }
    }

    private fun tap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down + 50L, MotionEvent.ACTION_UP, x, y, 0))
    }

    /** TM4, TM10: a recreation keeps the timer and the open full-screen clock. */
    @Test
    fun theTimerSurvivesARecreation() {
        val screen = kit.fixture.build("recreation", timer = kit.timer)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { RepertosaurusWindow { SessionContent(screen) } }
        compose.waitForIdle()
        screen.startFirst()
        val started = screen.session.timer.running.value
        compose.onNodeWithTag(TimerTags.CLOCK).performClick()
        compose.awaitUntil("the full-screen clock") { fullScreenOpen() }

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertEquals(started, screen.session.timer.running.value)
        compose.awaitUntil("the full-screen clock, restored") { fullScreenOpen() }
    }

    /** TM10: a new ViewModel over the same store, as after a process death, restores the timer and its bar. */
    @Test
    fun theTimerSurvivesAProcessRestart() {
        val first = kit.fixture.build("restart", timerStore = AndroidTimerStore(context, STORE_FILE), timer = kit.timer)
        val row = first.session.state.value.pending.first()
        EditingFixtures.onMain { first.session.startTimer(row.songId, row.title) }
        val started = assertNotNull(first.session.timer.running.value)
        EditingFixtures.await("the store's write") { AndroidTimerStore(context, STORE_FILE).read() == started.timer }

        lateinit var restarted: SessionViewModel
        EditingFixtures.onMain {
            restarted = SessionViewModel(first.holder, InMemorySessionPreferences(), TEST_DEVICE, AndroidTimerStore(context, STORE_FILE), timer = kit.timer)
        }
        EditingFixtures.await("the restore") { restarted.timer.running.value != null }
        assertEquals(started, restarted.timer.running.value)

        val screen = SessionScreenFixture.Screen(first.holder, restarted, DeviceSettings(InMemoryDevicePreferences()))
        compose.setContent { RepertosaurusWindow { SessionContent(screen) } }
        compose.waitForIdle()
        compose.onNode(kit.inTheBar(row.title)).assertExists()
    }

    /** VI8: the bar at a font scale of 1.3, with the widest clock, `h:mm:ss`, clips nothing. */
    @Test
    fun theBarFitsAtALargeFont() {
        val screen = kit.fixture.open("font", fontScale = LARGE_FONT, timer = kit.timer)
        screen.startFirst()
        kit.clock.forward(2L * 3_600L + 34L * 60L + 56L)
        compose.awaitUntil("the clock at 2:34:56") { shows("2:34:56") }
        compose.assertNoTextClipped("the bar", hasAnyAncestor(hasTestTag(TimerTags.BAR)))
        compose.expandAndShoot("p11", "running-bar-fs1.3")
    }

    /** TM4: the drawer's "Timer running" line wraps a long title at a font scale of 1.3, and is a 56 dp target. */
    @Test
    fun theDrawerLineFitsALongTitle() {
        var tapped = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, LARGE_FONT)) {
                RepertosaurusWindow {
                    ModalDrawerSheet { DrawerTimerLine(title = LONG_TITLE, onClick = { tapped = true }) }
                }
            }
        }
        compose.onNodeWithTag(DrawerTags.TIMER).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(DrawerTags.TIMER).assert(hasText(Messages.timerRunning(LONG_TITLE)))
        compose.assertNoTextClipped("the drawer's timer line", hasAnyAncestor(hasTestTag(DrawerTags.TIMER)))
        compose.onNodeWithTag(DrawerTags.TIMER).performClick()
        assertTrue(tapped)
    }

    private companion object {
        const val STORE_FILE = "timer-screen-test"
        const val LARGE_FONT = 1.3f
        const val LONG_TITLE = "The Ballad of the Band Who Played Through the Night and Never Once Stopped for Breath"
    }
}

/** **visual-identity VI22 for the timer**: with the animator duration scale at 0, the bar and the clock arrive and leave at once. */
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

    /** VI18, VI22: the full-screen clock's content springs in and out, and with animations off it is at once. */
    @Test
    fun theFullScreenClockArrivesAndLeavesAtOnce() {
        val screen = fixture.open("no-motion-clock")
        val row = screen.session.state.value.pending.first()
        EditingFixtures.onMain { screen.session.startTimer(row.songId, row.title) }
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(TimerTags.CLOCK).performClick()
        assertWithinFrames("the clock's arrival") { compose.onAllNodesWithTag(TimerTags.FULL_SCREEN).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(TimerTags.FULL_SCREEN_CLOCK).performClick()
        assertWithinFrames("the clock's exit") { compose.onAllNodesWithTag(TimerTags.FULL_SCREEN).fetchSemanticsNodes().isEmpty() }
        compose.mainClock.autoAdvance = true
    }

    private fun assertWithinFrames(what: String, settled: () -> Boolean) {
        repeat(INSTANT_FRAMES) {
            if (settled()) return
            compose.mainClock.advanceTimeByFrame()
        }
        assertTrue(settled(), "$what took more than $INSTANT_FRAMES frames")
    }

    private companion object {
        const val INSTANT_FRAMES = 5
    }
}
