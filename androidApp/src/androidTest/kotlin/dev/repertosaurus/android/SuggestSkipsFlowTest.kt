package dev.repertosaurus.android

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.android.DatabaseFixtures.count
import dev.repertosaurus.data.Part
import dev.repertosaurus.session.InMemoryDevicePreferences
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.SuggestSpoke
import dev.repertosaurus.session.SuggestTuning
import dev.repertosaurus.session.SuggestionDeck
import dev.repertosaurus.session.of
import dev.repertosaurus.session.resolvedPart
import dev.repertosaurus.session.suggestionPool
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** **suggest §3's [v2] instrumented checks**: SG12's skip window, SG13's count and SG14's part, composed. */
@RunWith(AndroidJUnit4::class)
class SuggestSkipsFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fixture = SessionScreenFixture(compose, "suggest-skips-test")
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun clearPreferences() {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun cleanUp() = fixture.cleanUp()

    /**
     * **SG13 pinned against schema-3 M14's boundaries**, as the card shows it. Every song in the pool gets the
     * same skips, so whichever card is dealt says the same thing:
     * - two skips show "2 times";
     * - a same-day log after them resets it, and a skip after that log counts again;
     * - a back-dated log resets it too, because it compares on `created_at`;
     * - **a voided log does not reset it**: the boundary falls back to the newest live log.
     */
    @Test
    fun theCountFollowsM14sBoundaries() {
        val screen = fixture.openWithOwner("m14")
        val repository = screen.holder.repository
        val songs = screen.session.state.value.suggestionPool().map { it.songId }
        val part = screen.session.state.value.resolvedPart!!
        fun skipEvery(times: Int) = repeat(times) { for (song in songs) repository.skips.recordSkip(part.of(song)) }
        fun logEvery(loggedOn: String? = null): List<String> = songs.map { song ->
            if (loggedOn == null) repository.logPractice(song, part.instrumentId) else repository.logPractice(song, part.instrumentId, loggedOn = loggedOn)
        }

        skipEvery(2)
        assertEquals(Messages.suggestSkipCount(2L), shownCount(screen, screenshot = "card-skip-count"))

        tick()
        logEvery()
        assertNull(shownCount(screen), "a same-day log after the skips resets the count")
        tick()
        skipEvery(1)
        assertEquals(Messages.suggestSkipCount(1L), shownCount(screen), "a skip after the log counts")

        tick()
        logEvery(loggedOn = "2020-01-01")
        assertNull(shownCount(screen), "a back-dated log resets the count: created_at, not logged_on")

        tick()
        skipEvery(2)
        tick()
        val voided = logEvery()
        assertNull(shownCount(screen))
        for (event in voided) repository.voidPractice(event)
        assertEquals(Messages.suggestSkipCount(2L), shownCount(screen), "a voided log does not reset the count")
    }

    /** SG13: with "Show the count" off, the count is not shown, though it is still read. */
    @Test
    fun showTheCountOffHidesIt() {
        val screen = fixture.openWithOwner("hidden", tuning = COUNTING.copy(showSkipCount = false))
        val part = screen.session.state.value.resolvedPart!!
        for (song in screen.session.state.value.suggestionPool()) screen.holder.repository.skips.recordSkip(part.of(song.songId))
        compose.suggest(screen)
        assertEquals(1L, (screen.session.suggestions.deck.value as SuggestionDeck.Showing).current.skips)
        compose.onNodeWithTag(SuggestTags.SKIP_COUNT, useUnmergedTree = true).assertDoesNotExist()
    }

    /** SG12, SG13, SG15: the two switches under "Tune" persist; "Show the count" is offered only while counting. */
    @Test
    fun theSwitchesPersist() {
        val screen = fixture.open("switches", device = AndroidSessionPreferences(context, PREFERENCES))
        compose.suggest(screen)
        compose.openTune()

        switch(SuggestTags.COUNT_SKIPS).assertIsOff()
        compose.onNodeWithTag(SuggestTags.SHOW_SKIP_COUNT).assertDoesNotExist()
        switch(SuggestTags.COUNT_SKIPS).performClick()
        compose.waitForIdle()
        switch(SuggestTags.COUNT_SKIPS).assertIsOn()
        switch(SuggestTags.SHOW_SKIP_COUNT).assertIsOn()
        switch(SuggestTags.SHOW_SKIP_COUNT).performClick()
        compose.waitForIdle()
        switch(SuggestTags.SHOW_SKIP_COUNT).assertIsOff()

        val set = SuggestTuning.DEFAULT.copy(countSkips = true, showSkipCount = false)
        compose.awaitUntil("the preference's write") { AndroidSessionPreferences(context, PREFERENCES).suggestTuning() == set }
        lateinit var restarted: DeviceSettings
        EditingFixtures.onMain { restarted = DeviceSettings(AndroidSessionPreferences(context, PREFERENCES)) }
        assertEquals(set, restarted.suggestTuning.value)
    }

    /**
     * SG11, SG12, SG14: **all five spokes are live once skips are counted and a part resolves**, and a drag on
     * each rating spoke and the skip spoke takes.
     */
    @Test
    fun theSpokesUnlockWhenCountingAndAPartResolves() {
        val screen = fixture.openWithOwner("unlocked")
        compose.suggest(screen)
        compose.openTune()
        for (spoke in SuggestSpoke.entries) {
            compose.handle(spoke).assertIsEnabled()
            val label = compose.onAllNodes(hasAnyAncestor(hasTestTag(SuggestTags.label(spoke))), useUnmergedTree = true)
            assertEquals(1, label.fetchSemanticsNodes().size, "${spoke.name}: its name and no reason")
        }
        for (spoke in listOf(SuggestSpoke.PRIORITY, SuggestSpoke.CONFIDENCE, SuggestSpoke.SKIPS)) {
            compose.dragOutward(spoke)
            compose.awaitUntil("${spoke.name}'s release") { screen.settings.suggestTuning.value.radius(spoke) > 0.0 }
        }
        compose.onNodeWithTag(SuggestTags.SHEET).performTouchInput { swipeUp() }
        compose.onNodeWithTag(SuggestTags.SHOW_SKIP_COUNT).performScrollTo().assertIsOn()
        compose.screenshot("p9b", "tune-five-spokes-live")
    }

    @Test
    fun countingOffLocksTheSkipSpokeAlone() {
        val screen = fixture.openWithOwner("counting-off", tuning = SuggestTuning.DEFAULT)
        compose.suggest(screen)
        compose.openTune()
        for (spoke in listOf(SuggestSpoke.COLDNESS, SuggestSpoke.HOTNESS, SuggestSpoke.PRIORITY, SuggestSpoke.CONFIDENCE)) {
            compose.handle(spoke).assertIsEnabled()
        }
        compose.handle(SuggestSpoke.SKIPS).assertIsNotEnabled()
        reason(SuggestSpoke.SKIPS, Messages.SUGGEST_LOCKED_SKIPS).assertExists()
    }

    @Test
    fun noPerformerLocksTheRatingAndSkipSpokesWithTheReason() {
        val screen = fixture.open("nobody", device = InMemoryDevicePreferences(suggestTuning = COUNTING))
        compose.awaitUntil("the performers' read") { screen.session.state.value.performers != null }
        compose.suggest(screen)
        compose.openTune()
        for (spoke in listOf(SuggestSpoke.PRIORITY, SuggestSpoke.CONFIDENCE, SuggestSpoke.SKIPS)) {
            compose.handle(spoke).assertIsNotEnabled()
            reason(spoke, Messages.SUGGEST_LOCKED_NO_PERFORMER).assertExists()
        }
    }

    /**
     * SG12: "Another" shows "Skipped · Undo", whose Undo is a full touch target; **Undo brings the card back and
     * writes nothing**, even once the window would have closed.
     */
    @Test
    fun skippedUndoBringsTheCardBackAndWritesNothing() = withOneIoThread { io, drain ->
        val screen = fixture.openWithOwner("undo", io = io)
        compose.suggest(screen)
        val first = screen.current()
        val before = writes(screen.holder)

        compose.another()
        assertNotEquals(first, screen.current())
        compose.onNode(hasText(Messages.SUGGEST_SKIPPED) and hasAnyAncestor(hasTestTag(SuggestTags.SKIPPED)), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(SuggestTags.UNDO_SKIP).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)

        compose.onNodeWithTag(SuggestTags.UNDO_SKIP).performClick()
        compose.waitForIdle()
        assertEquals(first, screen.current(), "the previous card is back")
        compose.onNodeWithTag(SuggestTags.SKIPPED).assertDoesNotExist()

        Thread.sleep(SuggestionHolder.SKIP_WINDOW_MS + 1_000)
        drain()
        assertEquals(before, writes(screen.holder), "no skip, no event")
    }

    /** SG12: left alone, the window closes after 5 s and **exactly one** `suggestion_skip` row is written, for the part. */
    @Test
    fun theWindowClosingWritesOneSkip() {
        val screen = fixture.openWithOwner("window")
        compose.suggest(screen)
        val first = screen.current()!!
        val part = screen.session.state.value.resolvedPart!!
        val before = count(screen.holder, "suggestion_skip")

        compose.another()
        compose.onNodeWithTag(SuggestTags.SKIPPED).assertExists()
        compose.screenshot("p9b", "skipped-undo")
        assertEquals(before, count(screen.holder, "suggestion_skip"), "not while the window is open")
        compose.awaitUntil("the window's close") { count(screen.holder, "suggestion_skip") == before + 1 }
        compose.waitForIdle()
        compose.onNodeWithTag(SuggestTags.SKIPPED).assertDoesNotExist()
        Thread.sleep(1_000)
        assertEquals(before + 1, count(screen.holder, "suggestion_skip"), "exactly one")
        val mine = part.of(first).let { "suggestion_skip WHERE song_id = '${it.songId}' AND performer_id = '${it.performerId}' AND instrument_id = '${it.instrumentId}'" }
        assertEquals(1L, count(screen.holder, mine), "the skipped card's part")
    }

    /** SG3, SG12: **dismissing inside the window writes nothing**, the staged skip included. */
    @Test
    fun dismissingInsideTheWindowWritesNothing() = withOneIoThread { io, drain ->
        val screen = fixture.openWithOwner("dismiss", io = io)
        compose.suggest(screen)
        val before = writes(screen.holder)
        compose.another()
        compose.onNodeWithTag(SuggestTags.SHEET).performTouchInput { swipeDown() }
        compose.awaitUntil("the sheet's dismissal") { screen.session.suggestions.deck.value == null }
        Thread.sleep(SuggestionHolder.SKIP_WINDOW_MS + 1_000)
        drain()
        assertEquals(before, writes(screen.holder), "no event, no void, no skip")
    }

    /** SG12: "Log it" on the card after "Another" writes the staged skip and the one event. */
    @Test
    fun logItWritesTheStagedSkipAndOneEvent() {
        val screen = fixture.openWithOwner("log-it")
        compose.suggest(screen)
        val first = screen.current()
        val skipsBefore = count(screen.holder, "suggestion_skip")
        val eventsBefore = count(screen.holder, "practice_event")
        compose.another()
        val second = screen.current()

        compose.onNodeWithText(Messages.SUGGEST_LOG, ignoreCase = true).performClick()
        compose.awaitUntil("the skip and the log") {
            count(screen.holder, "suggestion_skip") == skipsBefore + 1 && count(screen.holder, "practice_event") == eventsBefore + 1
        }
        assertEquals(1L, count(screen.holder, "suggestion_skip WHERE song_id = '$first'"))
        assertEquals(second, screen.session.state.value.taps.single().songId)
    }

    // ---- Harness -------------------------------------------------------------------------------

    /** Open the sheet, read the card's count line (null when there is none), and close it. */
    private fun shownCount(screen: SessionScreenFixture.Screen, screenshot: String? = null): String? {
        compose.suggest(screen)
        screenshot?.let { compose.screenshot("p9b", it) }
        val lines = compose.onAllNodesWithTag(SuggestTags.SKIP_COUNT, useUnmergedTree = true).fetchSemanticsNodes()
        val text = lines.singleOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        EditingFixtures.onMain { screen.session.suggestions.close() }
        compose.waitForIdle()
        return text
    }

    /** M14 compares `created_at` strictly: a step between writes keeps each phase's timestamps apart. */
    private fun tick() = Thread.sleep(20)

    private fun switch(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag).performScrollTo()

    private fun reason(spoke: SuggestSpoke, reason: String): SemanticsNodeInteraction =
        compose.onNode(hasText(reason) and hasAnyAncestor(hasTestTag(SuggestTags.label(spoke))), useUnmergedTree = true)

    private companion object {
        const val PREFERENCES = "suggest-skips-test"
        val COUNTING = SuggestTuning.DEFAULT.copy(countSkips = true)
    }
}

/**
 * **visual-identity VI22 for SG12**: with the animator duration scale at 0, "Skipped · Undo" appears at once,
 * and Undo lands the previous card at once, one card, with every button live and the line gone.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SuggestSkipReducedMotionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(NoMotion)

    private val fixture = SessionScreenFixture(compose, "suggest-skip-reduced-motion-test")

    @After
    fun cleanUp() = fixture.cleanUp()

    @Test
    fun theSkippedLineAndUndoLandAtOnce() {
        val screen = fixture.openWithOwner("no-motion")
        compose.suggest(screen)
        val first = screen.current()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(Messages.SUGGEST_ANOTHER, ignoreCase = true).performClick()
        assertWithinFrames("the line's arrival") { compose.onAllNodesWithTag(SuggestTags.SKIPPED).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(SuggestTags.UNDO_SKIP).assertIsEnabled().performClick()
        assertWithinFrames("the line's exit") { compose.onAllNodesWithTag(SuggestTags.SKIPPED).fetchSemanticsNodes().isEmpty() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertEquals(first, screen.current())
        assertEquals(1, compose.onAllNodesWithTag(SuggestTags.CARD).fetchSemanticsNodes().size, "one card, settled")
        compose.onNode(hasText(screen.titleOf(first)) and hasAnyAncestor(hasTestTag(SuggestTags.CARD))).assertExists()
        compose.onNodeWithText(Messages.SUGGEST_LOG, ignoreCase = true).assertIsEnabled()
        compose.onNodeWithText(Messages.SUGGEST_ANOTHER, ignoreCase = true).assertIsEnabled()
    }

    /** [settled] within [INSTANT_FRAMES] frames: at once, where a spring would take many more. */
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
