package dev.repertosaurus.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.android.DatabaseFixtures.count
import dev.repertosaurus.android.theme.RepertosaurusTheme
import dev.repertosaurus.data.DatabaseHolder
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **visual-identity VI20 on the composed Session screen**: the log tap's choreography never delays
 * the write and never blocks the next tap.
 */
@RunWith(AndroidJUnit4::class)
class LogTapMotionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val screens = MotionScreens(compose)

    @After
    fun cleanUp() = screens.cleanUp()

    /** Input is never blocked: a second row tapped while the first is still leaving logs at once. */
    @Test
    fun twoRapidTapsOnTwoRowsBothLog() {
        val screen = screens.open("two-taps")
        val (first, second) = screen.session.state.value.pending.take(2)
        val before = count(screen.holder, "practice_event")

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(first.title).performClick()
        compose.onNodeWithText(second.title).performClick()
        // Both writes land with the clock still held: neither waited on an animation.
        screens.awaitWithoutFrames("both taps' inserts") { count(screen.holder, "practice_event") == before + 2 }
        // And both exits run at once, neither waiting its turn.
        val tags = listOf(SessionTags.leaving(first.songId), SessionTags.leaving(second.songId))
        var frames = 0
        while (tags.any { compose.onAllNodesWithTag(it).fetchSemanticsNodes().isEmpty() } && frames < 10) {
            compose.mainClock.advanceTimeByFrame()
            frames++
        }
        for (tag in tags) compose.onNodeWithTag(tag).assertExists("both rows are leaving together")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        val logged = screen.session.state.value.logged.map { it.row.songId }
        assertEquals(setOf(first.songId, second.songId), logged.toSet(), "both rows are in the logged section")
        screens.assertSettled(screen, first.songId)
        screens.assertSettled(screen, second.songId)
    }

    /**
     * Roadmap §2 rule 1 and VI20 beat 2: **the event is in the database before any animation frame.**
     * The clock is held from before the tap until the row exists, and only then does a frame run —
     * the first, in which the leaving row appears at the start of its exit.
     */
    @Test
    fun theEventIsWrittenBeforeAnyAnimationFrame() {
        val screen = screens.open("before-frames")
        val row = screen.session.state.value.pending.first()
        val before = count(screen.holder, "practice_event")

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(row.title).performClick()
        screens.awaitWithoutFrames("the tap's insert") { count(screen.holder, "practice_event") == before + 1 }

        // The state reaches the screen a frame or two later; the exit then runs for about 300 ms, so
        // it is still under way within the first ten frames.
        val tag = SessionTags.leaving(row.songId)
        var frames = 0
        while (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() && frames < 10) {
            compose.mainClock.advanceTimeByFrame()
            frames++
        }
        compose.onNodeWithTag(tag).assertExists("the exit had not begun within $frames frames of the write")

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        screens.assertSettled(screen, row.songId)
    }
}

/**
 * **VI22**: with the animator duration scale at 0, as "Remove animations" sets it, the same tap ends
 * in the same state. The scale reaches the effects through the rule's effect context, exactly where
 * the app's window recomposer puts the system's.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ReducedMotionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(NoMotion)

    private val screens = MotionScreens(compose)

    @After
    fun cleanUp() = screens.cleanUp()

    @Test
    fun withAnimationsOffATapEndsInTheSameState() {
        val screen = screens.open("no-motion")
        val row = screen.session.state.value.pending.first()
        val before = count(screen.holder, "practice_event")

        compose.onNodeWithText(row.title).performClick()
        compose.awaitUntil("the tap's insert") { count(screen.holder, "practice_event") == before + 1 }
        compose.waitForIdle()
        screens.assertSettled(screen, row.songId)
    }

    private object NoMotion : MotionDurationScale {
        override val scaleFactor: Float = 0f
    }
}

/** The harness both motion tests share: the Session screen in the app's theme, over sample data. */
internal class MotionScreens(private val compose: AndroidComposeTestRule<*, *>) {

    class Screen(val holder: DatabaseHolder, val session: SessionViewModel)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()

    fun open(suffix: String): Screen {
        val name = "log-tap-motion-test-$suffix.db".also { names += it }
        val holder = EditingFixtures.holder(context, name)
        val session = EditingFixtures.session(holder)
        compose.setContent {
            RepertosaurusTheme { SessionScreen(viewModel = session, onOpenDrawer = {}, onExport = {}) }
        }
        compose.waitForIdle()
        return Screen(holder, session)
    }

    /** Poll a database condition on wall-clock time, pumping no frames. */
    fun awaitWithoutFrames(what: String, settled: () -> Boolean) {
        val deadline = System.currentTimeMillis() + BOOT_TIMEOUT_MS
        while (!settled()) {
            check(System.currentTimeMillis() < deadline) { "$what never settled within ${BOOT_TIMEOUT_MS}ms" }
            Thread.sleep(20)
        }
    }

    /** The end state of a log tap: no row still leaving, and the song's live row in the logged section. */
    fun assertSettled(screen: Screen, songId: String) {
        compose.onNodeWithTag(SessionTags.leaving(songId)).assertDoesNotExist()
        val state = screen.session.state.value
        assertTrue(state.pending.none { it.songId == songId }, "the row has left the pending list")
        assertTrue(state.logged.any { it.row.songId == songId }, "the row is in the logged section")
    }

    fun cleanUp() {
        for (name in names) DatabaseFixtures.delete(context, name)
    }
}
