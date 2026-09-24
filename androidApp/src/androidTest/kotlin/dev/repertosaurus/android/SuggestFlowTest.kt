package dev.repertosaurus.android

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.android.DatabaseFixtures.count
import dev.repertosaurus.android.theme.RepertosaurusWindow
import dev.repertosaurus.session.InMemoryDevicePreferences
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.PartResolution
import dev.repertosaurus.session.SuggestSpoke
import dev.repertosaurus.session.SuggestTuning
import dev.repertosaurus.session.SuggestionDeck
import dev.repertosaurus.session.lockOf
import dev.repertosaurus.session.part
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** **suggest §3's [v1] instrumented checks** on the composed Session screen. */
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
        compose.suggest(screen)

        val current = assertNotNull(screen.current(), "the sheet opened on a card")
        compose.onNode(onTheCard(screen.titleOf(current))).assertExists()
    }

    /** SG6: "Another" shows a different song, and with skips not counted writes nothing (SG3, SG12). */
    @Test
    fun anotherShowsADifferentSong() {
        val screen = fixture.open("another")
        compose.suggest(screen)
        val first = screen.current()
        val writes = writes(screen.holder)

        compose.another()

        val second = assertNotNull(screen.current())
        assertNotEquals(first, second, "a deck, not a die")
        compose.onNode(onTheCard(screen.titleOf(second))).assertExists()
        compose.onNode(onTheCard(screen.titleOf(first))).assertDoesNotExist()
        assertEquals(writes, writes(screen.holder), "\"Another\" wrote nothing")
    }

    /** SG2: "Log it" is the plain tap's log — one `practice_event`, feel null — and it closes the sheet. */
    @Test
    fun logItCreatesExactlyOneEventWithFeelNull() {
        val screen = fixture.open("log-it")
        compose.suggest(screen)
        val songId = screen.current()!!
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

    /** The pool ignores the search (SG5), so a card can be one the list is hiding; it still logs. */
    @Test
    fun logItOnACardTheSearchHides() {
        val screen = fixture.open("search-hidden")
        EditingFixtures.onMain { screen.session.setQuery("zzzz-no-such-song") }
        compose.waitForIdle()
        assertTrue(screen.session.state.value.pending.isEmpty(), "the search hides every row")
        compose.suggest(screen)
        val songId = screen.current()!!
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
        compose.suggest(screen)
        repeat(2) { compose.another() }

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
        compose.suggest(screen)
        compose.another()

        tapAboveTheSheet()
        compose.awaitUntil("the sheet's dismissal") { screen.session.suggestions.deck.value == null }
        compose.waitForIdle()
        drain()

        assertEquals(before, writes(screen.holder), "no event, no void, no skip")
    }

    /** SG4: with everything logged, the card says so, with no draw; a song coming back deals. */
    @Test
    fun anEmptyPoolSaysSoAndDealsWhenASongReturns() {
        val screen = fixture.open("empty-pool")
        val songs = screen.session.state.value.rows.map { it.songId }
        EditingFixtures.onMain { for (song in songs) screen.session.log(song) }
        compose.awaitUntil("every insert") { count(screen.holder, "practice_event") >= songs.size.toLong() }

        compose.suggest(screen)
        assertEquals(SuggestionDeck.EmptyPool, screen.session.suggestions.deck.value)
        compose.onNodeWithText(Messages.SUGGEST_EMPTY_POOL).assertExists()
        compose.onNodeWithTag(SuggestTags.CARD).assertDoesNotExist()

        val lastTap = screen.session.state.value.taps.last()
        EditingFixtures.onMain { screen.session.undo(lastTap.tapId) }
        compose.awaitUntil("the returned song's card") { screen.current() == lastTap.songId }
        compose.waitForIdle()
        compose.onNode(onTheCard(screen.titleOf(lastTap.songId))).assertExists()
    }

    /** Suggest is disabled while a View switch's rows load, and deals from the new rows after. */
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
        compose.suggest(screen)
        val dealt = assertNotNull(screen.current())
        assertTrue(screen.session.state.value.rows.any { it.songId == dealt }, "dealt from the new View")
    }

    /** A rotation keeps the sheet, its card and the open radar; the ViewModel holds the deck. */
    @Test
    fun aRotationKeepsTheSheet() {
        val screen = fixture.build("rotation")
        val restoration = StateRestorationTester(compose)
        restoration.setContent { RepertosaurusWindow { SessionContent(screen) } }
        compose.waitForIdle()
        compose.suggest(screen)
        compose.openTune()
        val card = screen.current()

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertEquals(card, screen.current())
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
        compose.suggest(screen)
        compose.onNodeWithTag(SuggestTags.spoke(SuggestSpoke.COLDNESS)).assertDoesNotExist()
        compose.openTune()

        compose.dragOutward(SuggestSpoke.COLDNESS)
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
     * drag on them changes nothing, with a non-zero Hotness beside them, which a press on a locked handle
     * must not take.
     */
    @Test
    fun theLockedSpokesCannotBeDragged() {
        val hot = SuggestTuning.of(SuggestSpoke.HOTNESS to 0.5)
        val screen = fixture.open("locked", device = InMemoryDevicePreferences(suggestTuning = hot))
        compose.awaitUntil("the performers' read") { screen.session.state.value.part == PartResolution.None }
        compose.suggest(screen)
        compose.openTune()

        val locked = SuggestSpoke.entries.filter { lockOf(it, hot.countSkips, PartResolution.None, ratingsFresh = true) != null }
        assertEquals(listOf(SuggestSpoke.PRIORITY, SuggestSpoke.CONFIDENCE, SuggestSpoke.SKIPS), locked)
        for (spoke in locked) {
            compose.handle(spoke).assertIsNotEnabled()
            compose.dragOutward(spoke)
            assertEquals(hot, screen.settings.suggestTuning.value, "${spoke.name} took a drag")
            val range = compose.handle(spoke).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
            assertEquals(0f, range.current, "${spoke.name} is drawn at zero")
        }
    }

    // ---- Harness -------------------------------------------------------------------------

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

    private fun onTheCard(title: String) = hasText(title) and hasAnyAncestor(hasTestTag(SuggestTags.CARD))

    private companion object {
        const val PREFERENCES = "suggest-flow-test"
    }
}

/** **visual-identity VI22**: with the animator duration scale at 0, "Another" lands the next card at once. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SuggestReducedMotionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(NoMotion)

    private val fixture = SessionScreenFixture(compose, "suggest-reduced-motion-test")

    @After
    fun cleanUp() = fixture.cleanUp()

    /** With both buttons live, and a dragged handle landing on its step. */
    @Test
    fun theSwapAndAHandleLandAtOnce() {
        val screen = fixture.open("no-motion")
        compose.suggest(screen)
        val first = screen.current()

        compose.another()
        val second = screen.current()
        assertNotEquals(first, second)
        assertEquals(1, compose.onAllNodesWithTag(SuggestTags.CARD).fetchSemanticsNodes().size, "one card, settled")
        compose.onNode(hasText(screen.titleOf(second)) and hasAnyAncestor(hasTestTag(SuggestTags.CARD))).assertExists()
        compose.onNodeWithText(Messages.SUGGEST_LOG, ignoreCase = true).assertIsEnabled()
        compose.onNodeWithText(Messages.SUGGEST_ANOTHER, ignoreCase = true).assertIsEnabled()

        compose.openTune()
        compose.dragOutward(SuggestSpoke.HOTNESS)
        compose.awaitUntil("the release") { screen.settings.suggestTuning.value.radius(SuggestSpoke.HOTNESS) > 0.0 }
        val range = compose.handle(SuggestSpoke.HOTNESS).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(screen.settings.suggestTuning.value.radius(SuggestSpoke.HOTNESS).toFloat(), range.current)
    }
}
