package dev.repertosaurus.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.repertosaurus.android.DatabaseFixtures.count
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.PracticeTimer
import dev.repertosaurus.session.SessionRow
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

/** The timer's instrumented harness: a Session screen on a clock the test moves. */
internal class TimerKit(val compose: androidx.compose.ui.test.junit4.ComposeContentTestRule, prefix: String) {
    val fixture = SessionScreenFixture(compose, prefix)

    /** 23:50 local on 2026-09-20: every stop past ten minutes crosses midnight (TM7). */
    val start = LocalDateTime(2026, 9, 20, 23, 50).toInstant(TimeZone.currentSystemDefault())
    val clock = MovableClock(start)
    val timer = PracticeTimer(clock)

    fun SessionScreenFixture.Screen.row(index: Int): SessionRow = session.state.value.pending[index]

    /** A row in the list, not the bar's title. */
    fun inTheList(title: String): SemanticsMatcher = hasText(title) and hasAnyAncestor(hasTestTag(SessionTags.LIST))

    fun inTheBar(title: String): SemanticsMatcher = hasText(title) and hasAnyAncestor(hasTestTag(TimerTags.BAR))

    /** timer TM1: long-press, then the sheet's "Start timer"; [shot] names a screenshot of the open sheet. */
    fun startFromTheSheet(screen: SessionScreenFixture.Screen, row: SessionRow, shot: String? = null) {
        compose.onNode(inTheList(row.title)).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText(Messages.TIMER_START, ignoreCase = true).performScrollTo()
        shot?.let { compose.settleAndShoot("p11", it) }
        compose.onNodeWithText(Messages.TIMER_START, ignoreCase = true).performClick()
        compose.awaitUntil("${row.title}'s timer") { screen.session.timer.running.value?.timer?.songId == row.songId }
        compose.waitForIdle()
    }

    fun events(screen: SessionScreenFixture.Screen, where: String): Long = count(screen.holder, "practice_event WHERE $where")

    /** With the test clock held, frames until [shown] holds: a snackbar arrives in a frame or two. */
    @OptIn(ExperimentalTestApi::class)
    fun framesUntil(what: String, frames: Int = FRAMES, shown: () -> Boolean) {
        repeat(frames) {
            if (shown()) return
            compose.mainClock.advanceTimeByFrame()
        }
        kotlin.test.assertTrue(shown(), "$what took more than $frames frames")
    }

    fun shows(text: String): Boolean = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** A test body with the kit in scope. */
    fun test(body: TimerKit.() -> Unit) = body()

    companion object {
        const val FRAMES = 10

        /** About a second of frames: the snackbars' crossfade, and far inside a snackbar's four seconds. */
        const val CROSSFADE_FRAMES = 60
    }
}

/** timer TM1-TM12 end to end on the Session screen: every write is read back from the database. */
@RunWith(AndroidJUnit4::class)
class TimerFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val kit = TimerKit(compose, "timer-flow-test")

    @After
    fun cleanUp() = kit.fixture.cleanUp()

    /**
     * TM1, TM4, TM7: start from the sheet, stop, and exactly one event with its duration on the start's date.
     * VI12: while it runs, Add song is secondary.
     */
    @Test
    fun startFromTheSheetThenStop() = kit.test {
        val screen = fixture.open("stop", timer = timer)
        val row = screen.row(0)
        val before = events(screen, "1")

        startFromTheSheet(screen, row, shot = "feel-sheet-start")
        compose.onNodeWithTag(TimerTags.BAR).assertExists()
        compose.onNode(inTheBar(row.title)).assertExists()
        compose.settleAndShoot("p11", "running-bar")

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

    /** TM7: Undo on a timed log voids it, as a tap's undo does. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun undoOfATimedLogIsAVoid() = kit.test {
        val screen = fixture.open("undo", timer = timer)
        val row = screen.row(0)
        val voids = count(screen.holder, "practice_event_void")
        startFromTheSheet(screen, row)
        clock.forward(600L)

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(Messages.TIMER_STOP, ignoreCase = true).performClick()
        framesUntil("the log's snackbar") { shows("Logged ${row.title} · 10 min") }
        compose.onNodeWithText(Messages.UNDO).performClick()
        compose.mainClock.autoAdvance = true

        compose.awaitUntil("the void") { count(screen.holder, "practice_event_void") == voids + 1 }
        val timed = "song_id = '${row.songId}' AND duration_seconds = 600"
        assertEquals(1L, events(screen, timed), "the event stays, voided")
        assertEquals(
            1L,
            count(screen.holder, "practice_event_void v JOIN practice_event e ON e.id = v.practice_event_id WHERE e.duration_seconds = 600"),
        )
    }

    /** TM9: Cancel writes nothing, and Undo restores the same timer, start instant and all. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun cancelThenUndoRestoresTheSameStart() = kit.test {
        val screen = fixture.open("cancel", timer = timer)
        val row = screen.row(0)
        val before = events(screen, "1")
        startFromTheSheet(screen, row)
        val started = assertNotNull(screen.session.timer.running.value)
        clock.forward(90L)

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(Messages.TIMER_CANCEL, ignoreCase = true).performClick()
        framesUntil("the cancel's snackbar") { shows(Messages.TIMER_CANCELLED) }
        assertNull(screen.session.timer.running.value)
        compose.onNodeWithText(Messages.UNDO).performClick()
        compose.mainClock.autoAdvance = true
        compose.awaitUntil("the restored timer") { screen.session.timer.running.value != null }

        assertEquals(started, screen.session.timer.running.value)
        assertEquals(90L, screen.session.timer.elapsed(started))
        assertEquals(before, events(screen, "1"), "nothing written")
    }

    /**
     * TM9: a tap's snackbar never hides the cancel's. Cancel within the tap's window takes the snackbar at once,
     * and its Undo still restores the timer.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun cancelInsideATapsWindowStillOffersUndo() = kit.test {
        val screen = fixture.open("contention", timer = timer)
        val timed = screen.row(0)
        val tapped = screen.row(1)
        startFromTheSheet(screen, timed)
        val started = assertNotNull(screen.session.timer.running.value)

        compose.mainClock.autoAdvance = false
        compose.onNode(inTheList(tapped.title)).performClick()
        framesUntil("the tap's snackbar") { shows("Logged ${tapped.title}") }
        compose.onNodeWithText(Messages.TIMER_CANCEL, ignoreCase = true).performClick()
        framesUntil("the cancel's snackbar, at once") { shows(Messages.TIMER_CANCELLED) }
        framesUntil("the tap's snackbar making way", frames = TimerKit.CROSSFADE_FRAMES) { !shows("Logged ${tapped.title}") }
        compose.onNodeWithText(Messages.UNDO).performClick()
        compose.mainClock.autoAdvance = true

        compose.awaitUntil("the restored timer") { screen.session.timer.running.value != null }
        assertEquals(started, screen.session.timer.running.value)
    }

    /** TM2: the sheet's button reads "Switch timer here", and one tap logs the first and times the second. */
    @Test
    fun switch() = kit.test {
        val screen = fixture.open("switch", timer = timer)
        val first = screen.row(0)
        val second = screen.row(1)
        startFromTheSheet(screen, first)
        clock.forward(600L)

        compose.onNode(inTheList(second.title)).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText(Messages.TIMER_START, ignoreCase = true).assertDoesNotExist()
        compose.settleAndShoot("p11", "feel-sheet-switch")
        compose.onNodeWithText(Messages.TIMER_SWITCH, ignoreCase = true).performScrollTo().performClick()

        compose.awaitUntil("the first's timed insert") {
            events(screen, "song_id = '${first.songId}' AND duration_seconds = 600 AND logged_on = '2026-09-20'") == 1L
        }
        val running = assertNotNull(screen.session.timer.running.value)
        assertEquals(second.songId to clock.instant.toEpochMilliseconds(), running.timer.songId to running.timer.startedAtEpochMs)
        compose.onNode(inTheBar(second.title)).assertExists()
    }

    /** TM1: "Time it" on the suggestion card starts the card's timer and logs nothing. */
    @Test
    fun timeItStartsTheCardsTimer() = kit.test {
        val screen = fixture.open("time-it", timer = timer)
        val before = events(screen, "1")
        compose.suggest(screen)
        val dealt = assertNotNull(screen.current())

        compose.onNodeWithText(Messages.TIMER_TIME_IT, ignoreCase = true).performClick()
        compose.awaitUntil("the card's timer") { screen.session.timer.running.value?.timer?.songId == dealt }
        compose.waitForIdle()

        assertNull(screen.session.suggestions.deck.value, "the sheet closed")
        assertEquals(before, events(screen, "1"), "nothing logged")
        compose.onNode(inTheBar(screen.titleOf(dealt))).assertExists()
    }

    /** TM5: a plain tap on the timed song logs one untimed event at once, and the timer runs on untouched. */
    @Test
    fun aPlainTapDuringATimerLogsUntimed() = kit.test {
        val screen = fixture.open("tap", timer = timer)
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

    /** TM8: over 3 h, Stop asks the one question; "with a time" logs it. */
    @Test
    fun overThreeHoursWithTheTime() = kit.test {
        val screen = fixture.open("with-time", timer = timer)
        val row = screen.row(0)
        startFromTheSheet(screen, row)
        clock.forward(5L * 3_600L + 12L * 60L)

        compose.onNodeWithText(Messages.TIMER_STOP, ignoreCase = true).performClick()
        compose.onNodeWithTag(TimerTags.QUESTION).assertExists()
        compose.onNodeWithText("Log 5 h 12 min, or log without a time?").assertExists()
        assertNotNull(screen.session.timer.running.value, "still running while asked")
        compose.settleAndShoot("p11", "over-three-hours")

        compose.onNodeWithText("Log 5 h 12 min", ignoreCase = true).performClick()
        compose.awaitUntil("the timed insert") {
            events(screen, "song_id = '${row.songId}' AND duration_seconds = 18720 AND logged_on = '2026-09-20'") == 1L
        }
        compose.onNodeWithTag(TimerTags.QUESTION).assertDoesNotExist()
        assertNull(screen.session.timer.running.value)
    }

    /** TM8: "without a time" logs one untimed event. */
    @Test
    fun overThreeHoursWithoutATime() = kit.test {
        val screen = fixture.open("without-time", timer = timer)
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
}
