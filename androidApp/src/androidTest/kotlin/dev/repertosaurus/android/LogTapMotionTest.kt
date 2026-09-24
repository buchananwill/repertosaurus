package dev.repertosaurus.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.repertosaurus.android.DatabaseFixtures.count
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

    private val fixture = SessionScreenFixture(compose, "log-tap-motion-test")

    @After
    fun cleanUp() = fixture.cleanUp()

    /** Input is never blocked: a second row tapped while the first is still leaving logs at once. */
    @Test
    fun twoRapidTapsOnTwoRowsBothLog() {
        val screen = fixture.open("two-taps")
        val (first, second) = screen.session.state.value.pending.take(2)
        val before = count(screen.holder, "practice_event")

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(first.title).performClick()
        compose.onNodeWithText(second.title).performClick()
        // Both writes land with the clock still held: neither waited on an animation.
        fixture.awaitWithoutFrames("both taps' inserts") { count(screen.holder, "practice_event") == before + 2 }
        // And both exits run at once, neither waiting its turn.
        val tags = listOf(SessionTags.leaving(first.songId), SessionTags.leaving(second.songId))
        advanceUntilShown(tags)
        for (tag in tags) compose.onNodeWithTag(tag).assertExists("both rows are leaving together")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        val logged = screen.session.state.value.logged.map { it.row.songId }
        assertEquals(setOf(first.songId, second.songId), logged.toSet(), "both rows are in the logged section")
        compose.assertSettled(screen, first.songId)
        compose.assertSettled(screen, second.songId)
    }

    /**
     * Roadmap §2 rule 1 and VI20 beat 2: **the event is in the database before any animation frame.**
     * The clock is held from before the tap until the row exists; only then do frames run, and the
     * leaving row appears at the start of its exit.
     */
    @Test
    fun theEventIsWrittenBeforeAnyAnimationFrame() {
        val screen = fixture.open("before-frames")
        val row = screen.session.state.value.pending.first()
        val before = count(screen.holder, "practice_event")

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(row.title).performClick()
        fixture.awaitWithoutFrames("the tap's insert") { count(screen.holder, "practice_event") == before + 1 }

        val tag = SessionTags.leaving(row.songId)
        advanceUntilShown(listOf(tag))
        compose.onNodeWithTag(tag).assertExists("the exit had not begun within ten frames of the write")

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.assertSettled(screen, row.songId)
    }

    /** The state reaches the screen a frame or two after the write; the exit runs for about 300 ms. */
    private fun advanceUntilShown(tags: List<String>) {
        var frames = 0
        while (tags.any { compose.onAllNodesWithTag(it).fetchSemanticsNodes().isEmpty() } && frames < 10) {
            compose.mainClock.advanceTimeByFrame()
            frames++
        }
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

    private val fixture = SessionScreenFixture(compose, "reduced-motion-test")

    @After
    fun cleanUp() = fixture.cleanUp()

    @Test
    fun withAnimationsOffATapEndsInTheSameState() {
        val screen = fixture.open("no-motion")
        val row = screen.session.state.value.pending.first()
        val before = count(screen.holder, "practice_event")

        compose.onNodeWithText(row.title).performClick()
        compose.awaitUntil("the tap's insert") { count(screen.holder, "practice_event") == before + 1 }
        compose.waitForIdle()
        compose.assertSettled(screen, row.songId)
    }

}

/** The end state of a log tap: no row still leaving, and the song's live row in the logged section. */
private fun ComposeContentTestRule.assertSettled(screen: SessionScreenFixture.Screen, songId: String) {
    onNodeWithTag(SessionTags.leaving(songId)).assertDoesNotExist()
    val state = screen.session.state.value
    assertTrue(state.pending.none { it.songId == songId }, "the row has left the pending list")
    assertTrue(state.logged.any { it.row.songId == songId }, "the row is in the logged section")
}
