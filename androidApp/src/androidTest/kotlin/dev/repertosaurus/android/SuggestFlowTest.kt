package dev.repertosaurus.android

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.android.DatabaseFixtures.count
import dev.repertosaurus.android.theme.RepertosaurusWindow
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.session.InMemoryDevicePreferences
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.PartResolution
import dev.repertosaurus.session.SuggestCandidate
import dev.repertosaurus.session.SuggestSpoke
import dev.repertosaurus.session.SuggestTuning
import dev.repertosaurus.session.SuggestionDeck
import dev.repertosaurus.session.lockOf
import dev.repertosaurus.session.part
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **suggest §3's [v1] instrumented checks** on the composed Session screen, and the P5 fix round's
 * (journal F26 B1, N7; F31 B1, N2, N3).
 */
@RunWith(AndroidJUnit4::class)
class SuggestFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fixture = SessionScreenFixture(compose, "suggest-flow-test")
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun clearPreferences() {
        // commit, not apply: the next test must not race a pending write.
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun cleanUp() = fixture.cleanUp()

    /** SG1, SG2: one tap on the top-bar action shows one card, and the card is the deck's song. */
    @Test
    fun suggestOpensTheCard() {
        val screen = fixture.open("opens")
        suggest()

        val current = assertNotNull(screen.current(), "the sheet opened on a card")
        compose.onNode(onTheCard(screen.titleOf(current.songId))).assertExists()
    }

    /** SG6: "Another" shows a different song, and writes nothing (SG3; skips are [v2]). */
    @Test
    fun anotherShowsADifferentSong() {
        val screen = fixture.open("another")
        suggest()
        val first = screen.current()!!.songId
        val writes = writes(screen.holder)

        another()

        val second = assertNotNull(screen.current()).songId
        assertNotEquals(first, second, "a deck, not a die")
        compose.onNode(onTheCard(screen.titleOf(second))).assertExists()
        compose.onNode(onTheCard(screen.titleOf(first))).assertDoesNotExist()
        assertEquals(writes, writes(screen.holder), "\"Another\" wrote nothing")
    }

    /** SG2: "Log it" is the plain tap's log — one `practice_event`, feel null — and it closes the sheet. */
    @Test
    fun logItCreatesExactlyOneEventWithFeelNull() {
        val screen = fixture.open("log-it")
        suggest()
        val songId = screen.current()!!.songId
        val before = count(screen.holder, "practice_event")
        // The sample data may already hold plain logs of this song, so the check is the difference.
        val plainLogs = "practice_event WHERE song_id = '$songId' AND feel IS NULL AND note IS NULL"
        val plainBefore = count(screen.holder, plainLogs)

        compose.onNodeWithText(Messages.SUGGEST_LOG, ignoreCase = true).performClick()
        compose.awaitUntil("the suggestion's insert") { count(screen.holder, "practice_event") == before + 1 }
        compose.waitForIdle()

        assertEquals(before + 1, count(screen.holder, "practice_event"), "exactly one event")
        assertEquals(plainBefore + 1, count(screen.holder, plainLogs), "the one event is this song's, feel null, no note")
        assertNull(screen.session.suggestions.deck.value, "the sheet closed")
        compose.onNodeWithTag(SuggestTags.SHEET).assertDoesNotExist()
        val state = screen.session.state.value
        assertTrue(state.logged.any { it.row.songId == songId }, "the song moved to the logged section")
        assertEquals(songId, state.taps.single().songId, "one tap, the plain tap's")
        assertNull(state.taps.single().feel)
        assertNotNull(state.undo, "the standard undo is offered")
    }

    /** F26 N7: the pool ignores the search (SG5), so a card can be one the list is hiding; it still logs. */
    @Test
    fun logItOnACardTheSearchHides() {
        val screen = fixture.open("search-hidden")
        EditingFixtures.onMain { screen.session.setQuery("zzzz-no-such-song") }
        compose.waitForIdle()
        assertTrue(screen.session.state.value.pending.isEmpty(), "the search hides every row")
        suggest()
        val songId = screen.current()!!.songId
        val before = count(screen.holder, "practice_event")

        compose.onNodeWithText(Messages.SUGGEST_LOG, ignoreCase = true).performClick()
        compose.awaitUntil("the hidden card's insert") { count(screen.holder, "practice_event") == before + 1 }
        compose.waitForIdle()
        assertEquals(songId, screen.session.state.value.taps.single().songId)
        compose.onNodeWithTag(SessionTags.leaving(songId)).assertDoesNotExist()
    }

    /**
     * SG3: **dismissing the sheet is never a skip.** After two "Another"s a swipe down closes it, and once
     * the one IO thread has drained, nothing has been written.
     */
    @Test
    fun swipingTheSheetAwayWritesNothing() = withOneIoThread { io, drain ->
        val screen = fixture.open("dismiss-swipe", io = io)
        val before = writes(screen.holder)
        suggest()
        repeat(2) { another() }

        compose.onNodeWithTag(SuggestTags.SHEET).performTouchInput { swipeDown() }
        compose.awaitUntil("the sheet's dismissal") { screen.session.suggestions.deck.value == null }
        compose.waitForIdle()
        drain()

        compose.onNodeWithTag(SuggestTags.SHEET).assertDoesNotExist()
        assertEquals(before, writes(screen.holder), "no event, no void, no skip")
        assertTrue(screen.session.state.value.taps.isEmpty())
    }

    /** SG3, the other way out: a tap on the scrim above the sheet. */
    @Test
    fun tappingOutsideTheSheetWritesNothing() = withOneIoThread { io, drain ->
        val screen = fixture.open("dismiss-outside", io = io)
        val before = writes(screen.holder)
        suggest()
        another()

        tapAboveTheSheet()
        compose.awaitUntil("the sheet's dismissal") { screen.session.suggestions.deck.value == null }
        compose.waitForIdle()
        drain()

        assertEquals(before, writes(screen.holder), "no event, no void, no skip")
    }

    /** SG4: with everything logged, the card says so, with no draw; F26 B1: a song coming back deals. */
    @Test
    fun anEmptyPoolSaysSoAndDealsWhenASongReturns() {
        val screen = fixture.open("empty-pool")
        val songs = screen.session.state.value.rows.map { it.songId }
        EditingFixtures.onMain { for (song in songs) screen.session.log(song) }
        compose.awaitUntil("every insert") { count(screen.holder, "practice_event") >= songs.size.toLong() }

        suggest()
        assertEquals(SuggestionDeck.EmptyPool, screen.session.suggestions.deck.value)
        compose.onNodeWithText(Messages.SUGGEST_EMPTY_POOL).assertExists()
        compose.onNodeWithTag(SuggestTags.CARD).assertDoesNotExist()

        val lastTap = screen.session.state.value.taps.last()
        EditingFixtures.onMain { screen.session.undo(lastTap.tapId) }
        compose.awaitUntil("the returned song's card") { screen.current()?.songId == lastTap.songId }
        compose.waitForIdle()
        compose.onNode(onTheCard(screen.titleOf(lastTap.songId))).assertExists()
    }

    /** F26 B1: Suggest is disabled while a View switch's rows load, and deals from the new rows after. */
    @Test
    fun suggestIsDisabledWhileTheRowsLoad() {
        val gate = Gate()
        val screen = fixture.open("loading", io = gate)
        val state = screen.session.state.value
        val other = state.instruments.first { it.id != state.selectedInstrumentId }

        gate.close()
        EditingFixtures.onMain { screen.session.selectInstrument(other.id) }
        compose.waitForIdle()
        assertTrue(screen.session.state.value.loading)
        compose.onNodeWithContentDescription(Messages.SUGGEST).assertIsNotEnabled().performClick()
        compose.waitForIdle()
        assertNull(screen.session.suggestions.deck.value, "nothing dealt from rows still loading")

        gate.open()
        compose.awaitUntil("the new View's rows") { !screen.session.state.value.loading }
        compose.onNodeWithContentDescription(Messages.SUGGEST).assertIsEnabled()
        suggest()
        val dealt = assertNotNull(screen.current()).songId
        assertTrue(screen.session.state.value.rows.any { it.songId == dealt }, "dealt from the new View")
    }

    /** F26 N7: a rotation keeps the sheet, its card and the open radar; the ViewModel holds the deck. */
    @Test
    fun aRotationKeepsTheSheet() {
        val screen = fixture.build("rotation")
        val restoration = StateRestorationTester(compose)
        restoration.setContent { RepertosaurusWindow { SessionContent(screen) } }
        compose.waitForIdle()
        suggest()
        openTune()
        val card = screen.current()!!.songId

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertEquals(card, screen.current()?.songId)
        compose.onNode(onTheCard(screen.titleOf(card))).assertExists()
        compose.onNodeWithTag(SuggestTags.RADAR).assertExists()
    }

    /**
     * SG11, SG15: a handle dragged out is persisted on release, and a **fresh** `DeviceSettings` over a
     * fresh `SharedPreferences` reader — the ViewModel restarted — reads it back. "Tune" is collapsed
     * until opened.
     */
    @Test
    fun theTuningPersistsAcrossAViewModelRestart() {
        val screen = fixture.open("persist", device = AndroidSessionPreferences(context, PREFERENCES))
        suggest()
        compose.onNodeWithTag(SuggestTags.spoke(SuggestSpoke.COLDNESS)).assertDoesNotExist()
        openTune()

        dragOutward(SuggestSpoke.COLDNESS, distance = 120f)
        compose.awaitUntil("the handle's release") { screen.settings.suggestTuning.value.radius(SuggestSpoke.COLDNESS) > 0.0 }
        val tuned = screen.settings.suggestTuning.value
        val coldness = tuned.radius(SuggestSpoke.COLDNESS)
        assertEquals(coldness, SuggestTuning.snap(coldness), "snapped to a 0.05 step")
        compose.awaitUntil("the preference's write") { AndroidSessionPreferences(context, PREFERENCES).suggestTuning() == tuned }

        lateinit var restarted: DeviceSettings
        EditingFixtures.onMain { restarted = DeviceSettings(AndroidSessionPreferences(context, PREFERENCES)) }
        assertEquals(tuned, restarted.suggestTuning.value)
    }

    /**
     * SG11, SG14: with no performer to resolve, priority, confidence and skips are drawn, disabled, and a
     * drag on them changes nothing — F26 N7: with a non-zero Hotness beside them, which a press on a locked
     * handle must not take.
     */
    @Test
    fun theLockedSpokesCannotBeDragged() {
        val hot = SuggestTuning.of(SuggestSpoke.HOTNESS to 0.5)
        val screen = fixture.open("locked", device = InMemoryDevicePreferences(suggestTuning = hot))
        compose.awaitUntil("the performers' read") { screen.session.state.value.part == PartResolution.None }
        suggest()
        openTune()

        val locked = SuggestSpoke.entries.filter { lockOf(it, hot.countSkips, PartResolution.None) != null }
        assertEquals(listOf(SuggestSpoke.PRIORITY, SuggestSpoke.CONFIDENCE, SuggestSpoke.SKIPS), locked)
        for (spoke in locked) {
            handle(spoke).assertIsNotEnabled()
            dragOutward(spoke, distance = 120f)
            assertEquals(hot, screen.settings.suggestTuning.value, "${spoke.name} took a drag")
            val range = handle(spoke).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
            assertEquals(0f, range.current, "${spoke.name} is drawn at zero")
        }
    }

    /**
     * F31 B1, visual-identity VI8: at font scale 1.3 every spoke label, the two-line locked ones included,
     * lies inside the radar and no line of it is lost.
     */
    @Test
    fun theSpokeLabelsFitAtALargeFont() {
        fixture.open("large-font", fontScale = LARGE_FONT)
        suggest()
        openTune()
        compose.onNodeWithTag(SuggestTags.RADAR).performScrollTo()
        val radar = compose.onNodeWithTag(SuggestTags.RADAR).fetchSemanticsNode().boundsInRoot

        for (spoke in SuggestSpoke.entries) {
            val label = compose.onNodeWithTag(SuggestTags.label(spoke), useUnmergedTree = true).fetchSemanticsNode()
            val bounds = label.boundsInRoot
            assertTrue(
                bounds.left >= radar.left && bounds.right <= radar.right && bounds.top >= radar.top && bounds.bottom <= radar.bottom,
                "${spoke.name}'s label $bounds lies outside the radar $radar",
            )
            for (text in label.children) {
                val layouts = mutableListOf<TextLayoutResult>()
                if (SemanticsActions.GetTextLayoutResult in text.config) {
                    text.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                }
                for (layout in layouts) {
                    val lost = layout.didOverflowHeight || (0 until layout.lineCount).any(layout::isLineEllipsized)
                    assertTrue(!lost, "${spoke.name}'s label loses a line at font scale $LARGE_FONT")
                }
            }
        }
    }

    // ---- Harness -------------------------------------------------------------------------

    private fun suggest() {
        compose.onNodeWithContentDescription(Messages.SUGGEST).performClick()
        compose.waitForIdle()
    }

    private fun another() {
        compose.onNodeWithText(Messages.SUGGEST_ANOTHER, ignoreCase = true).performClick()
        compose.waitForIdle()
    }

    private fun openTune() {
        compose.onNodeWithTag(SuggestTags.TUNE).performScrollTo().performClick()
        compose.waitForIdle()
    }

    /**
     * A real tap on the scrim, just below the top of the screen and above the sheet. Material 3 clears
     * the scrim's semantics, so there is no node to click: the tap is injected as the system would.
     */
    private fun tapAboveTheSheet() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val metrics = context.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val y = metrics.heightPixels * 0.12f
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
    }

    private fun handle(spoke: SuggestSpoke): SemanticsNodeInteraction =
        compose.onNodeWithTag(SuggestTags.spoke(spoke), useUnmergedTree = true)

    /** A drag from the handle's centre outward along its spoke, by [distance] pixels. */
    private fun dragOutward(spoke: SuggestSpoke, distance: Float) = compose.dragOutward(spoke, distance)

    private fun onTheCard(title: String) = hasText(title) and hasAnyAncestor(hasTestTag(SuggestTags.CARD))

    /**
     * Run [body] with a session on **one** IO thread, and a `drain` that returns once every IO task queued
     * before it has run: the only honest way to say "nothing was written" of writes that are async.
     */
    private fun withOneIoThread(body: (io: kotlinx.coroutines.CoroutineDispatcher, drain: () -> Unit) -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        val io = executor.asCoroutineDispatcher()
        try {
            body(io) { runBlocking(io) {} }
        } finally {
            io.close()
        }
    }

    private companion object {
        const val PREFERENCES = "suggest-flow-test"
        const val LARGE_FONT = 1.3f
    }
}

/**
 * **F31 N5, visual-identity VI22**: with the animator duration scale at 0, "Another" lands the next card
 * at once with both buttons live, and a dragged handle lands on its step.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SuggestReducedMotionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(NoMotion)

    private val fixture = SessionScreenFixture(compose, "suggest-reduced-motion-test")

    @After
    fun cleanUp() = fixture.cleanUp()

    @Test
    fun theSwapAndAHandleLandAtOnce() {
        val screen = fixture.open("no-motion")
        compose.onNodeWithContentDescription(Messages.SUGGEST).performClick()
        compose.waitForIdle()
        val first = screen.current()!!.songId

        compose.onNodeWithText(Messages.SUGGEST_ANOTHER, ignoreCase = true).performClick()
        compose.waitForIdle()
        val second = screen.current()!!.songId
        assertNotEquals(first, second)
        assertEquals(1, compose.onAllNodesWithTag(SuggestTags.CARD).fetchSemanticsNodes().size, "one card, settled")
        compose.onNode(hasText(screen.titleOf(second)) and hasAnyAncestor(hasTestTag(SuggestTags.CARD))).assertExists()
        compose.onNodeWithText(Messages.SUGGEST_LOG, ignoreCase = true).assertIsEnabled()
        compose.onNodeWithText(Messages.SUGGEST_ANOTHER, ignoreCase = true).assertIsEnabled()

        compose.onNodeWithTag(SuggestTags.TUNE).performScrollTo().performClick()
        compose.waitForIdle()
        compose.dragOutward(SuggestSpoke.HOTNESS, distance = 120f)
        compose.awaitUntil("the release") { screen.settings.suggestTuning.value.radius(SuggestSpoke.HOTNESS) > 0.0 }
        val range = compose.onNodeWithTag(SuggestTags.spoke(SuggestSpoke.HOTNESS), useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(screen.settings.suggestTuning.value.radius(SuggestSpoke.HOTNESS).toFloat(), range.current)
    }

    private object NoMotion : MotionDurationScale {
        override val scaleFactor: Float = 0f
    }
}

private fun SessionScreenFixture.Screen.current(): SuggestCandidate? =
    (session.suggestions.deck.value as? SuggestionDeck.Showing)?.current

private fun SessionScreenFixture.Screen.titleOf(songId: String): String =
    session.state.value.rows.first { it.songId == songId }.title

/** Every table a suggestion could conceivably write to. */
private fun writes(holder: DatabaseHolder): List<Long> =
    listOf("practice_event", "practice_event_void", "suggestion_skip").map { count(holder, it) }

private fun ComposeContentTestRule.dragOutward(spoke: SuggestSpoke, distance: Float) {
    val (x, y) = spoke.direction()
    val outward = Offset(x.toFloat(), y.toFloat()) * distance
    onNodeWithTag(SuggestTags.spoke(spoke), useUnmergedTree = true)
        .performScrollTo()
        .performTouchInput { swipe(start = center, end = center + outward, durationMillis = 400) }
    waitForIdle()
}
