package dev.repertosaurus.android

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.android.DatabaseFixtures.count
import dev.repertosaurus.data.Part
import dev.repertosaurus.session.DevicePreferences
import dev.repertosaurus.session.InMemoryDevicePreferences
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.SuggestSpoke
import dev.repertosaurus.session.SuggestTuning
import dev.repertosaurus.session.SuggestionDeck
import dev.repertosaurus.session.resolvedPart
import dev.repertosaurus.session.suggestionPool
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        val screen = openWithWill("m14")
        val skips = screen.holder.repository.skips
        val songs = screen.session.state.value.suggestionPool.map { it.songId }
        val part = screen.session.state.value.resolvedPart!!
        fun skipEvery(times: Int) = repeat(times) { for (song in songs) skips.recordSkip(Part(song, part.performerId, part.instrumentId)) }
        fun logEvery(loggedOn: String? = null): List<String> = songs.map { song ->
            if (loggedOn == null) {
                screen.holder.repository.logPractice(song, part.instrumentId)
            } else {
                screen.holder.repository.logPractice(song, part.instrumentId, loggedOn = loggedOn)
            }
        }

        skipEvery(2)
        assertEquals(Messages.suggestSkipCount(2), shownCount(screen, screenshot = "card-skip-count"))

        tick()
        logEvery()
        assertNull(shownCount(screen), "a same-day log after the skips resets the count")
        tick()
        skipEvery(1)
        assertEquals(Messages.suggestSkipCount(1), shownCount(screen), "a skip after the log counts")

        tick()
        logEvery(loggedOn = "2020-01-01")
        assertNull(shownCount(screen), "a back-dated log resets the count: created_at, not logged_on")

        tick()
        skipEvery(2)
        tick()
        val voided = logEvery()
        assertNull(shownCount(screen))
        for (event in voided) screen.holder.repository.voidPractice(event)
        assertEquals(Messages.suggestSkipCount(2), shownCount(screen), "a voided log does not reset the count")
    }

    /** SG13: with "Show the count" off, the count is not shown, though it is still read. */
    @Test
    fun showTheCountOffHidesIt() {
        val screen = openWithWill("hidden", tuning = COUNTING.copy(showSkipCount = false))
        val part = screen.session.state.value.resolvedPart!!
        for (song in screen.session.state.value.suggestionPool) {
            screen.holder.repository.skips.recordSkip(Part(song.songId, part.performerId, part.instrumentId))
        }
        suggest(screen)
        assertEquals(1, (screen.session.suggestions.deck.value as SuggestionDeck.Showing).current.skips)
        compose.onNodeWithTag(SuggestTags.SKIP_COUNT, useUnmergedTree = true).assertDoesNotExist()
    }

    /** SG12, SG13, SG15: the two switches under "Tune" persist; "Show the count" is offered only while counting. */
    @Test
    fun theSwitchesPersist() {
        val screen = fixture.open("switches", device = AndroidSessionPreferences(context, PREFERENCES))
        suggest(screen)
        openTune()

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
     * each rating spoke and the skip spoke takes. Counting off locks skips alone; no performer locks all three.
     */
    @Test
    fun theSpokesUnlockWhenCountingAndAPartResolves() {
        val screen = openWithWill("unlocked")
        suggest(screen)
        openTune()
        for (spoke in SuggestSpoke.entries) handle(spoke).assertIsEnabled()
        for (spoke in SuggestSpoke.entries) {
            compose.onNode(hasAnyAncestor(hasTestTag(SuggestTags.label(spoke))) and hasText(spoke.label), useUnmergedTree = true).assertExists()
            assertEquals(1, compose.onAllNodes(hasAnyAncestor(hasTestTag(SuggestTags.label(spoke))), useUnmergedTree = true).fetchSemanticsNodes().size, "${spoke.name}: no reason")
        }
        for (spoke in listOf(SuggestSpoke.PRIORITY, SuggestSpoke.CONFIDENCE, SuggestSpoke.SKIPS)) {
            compose.dragOut(spoke)
            compose.awaitUntil("${spoke.name}'s release") { screen.settings.suggestTuning.value.radius(spoke) > 0.0 }
        }
        compose.onNodeWithTag(SuggestTags.SHEET).performTouchInput { swipeUp() }
        compose.onNodeWithTag(SuggestTags.SHOW_SKIP_COUNT).performScrollTo().assertIsOn()
        compose.screenshot("p9b", "tune-five-spokes-live")
    }

    @Test
    fun countingOffLocksTheSkipSpokeAlone() {
        openWithWill("counting-off", tuning = SuggestTuning.DEFAULT).let { suggest(it) }
        openTune()
        for (spoke in listOf(SuggestSpoke.COLDNESS, SuggestSpoke.HOTNESS, SuggestSpoke.PRIORITY, SuggestSpoke.CONFIDENCE)) {
            handle(spoke).assertIsEnabled()
        }
        handle(SuggestSpoke.SKIPS).assertIsNotEnabled()
        reason(SuggestSpoke.SKIPS, Messages.SUGGEST_LOCKED_SKIPS).assertExists()
    }

    @Test
    fun noPerformerLocksTheRatingAndSkipSpokesWithTheReason() {
        val screen = fixture.open("nobody", device = InMemoryDevicePreferences(suggestTuning = COUNTING))
        compose.awaitUntil("the performers' read") { screen.session.state.value.performers != null }
        suggest(screen)
        openTune()
        for (spoke in listOf(SuggestSpoke.PRIORITY, SuggestSpoke.CONFIDENCE, SuggestSpoke.SKIPS)) {
            handle(spoke).assertIsNotEnabled()
            reason(spoke, Messages.SUGGEST_LOCKED_NO_PERFORMER).assertExists()
        }
    }

    /**
     * SG12: "Another" shows "Skipped · Undo"; **Undo brings the card back and writes nothing**, even once
     * the window would have closed.
     */
    @Test
    fun skippedUndoBringsTheCardBackAndWritesNothing() = withOneIoThread { io, drain ->
        val screen = openWithWill("undo", io = io)
        suggest(screen)
        val first = screen.current()
        val before = writes(screen)

        another()
        assertNotEquals(first, screen.current())
        compose.onNodeWithTag(SuggestTags.SKIPPED).assertExists()
        compose.onNode(hasText(Messages.SUGGEST_SKIPPED, substring = true) and hasAnyAncestor(hasTestTag(SuggestTags.SKIPPED)), useUnmergedTree = true).assertExists()

        compose.onNodeWithTag(SuggestTags.UNDO_SKIP).performClick()
        compose.waitForIdle()
        assertEquals(first, screen.current(), "the previous card is back")
        compose.onNodeWithTag(SuggestTags.SKIPPED).assertDoesNotExist()

        Thread.sleep(SuggestionHolder.SKIP_WINDOW_MS + 1_000)
        drain()
        assertEquals(before, writes(screen), "no skip, no event")
    }

    /** SG12: left alone, the window closes after 5 s and **exactly one** `suggestion_skip` row is written, for the part. */
    @Test
    fun theWindowClosingWritesOneSkip() {
        val screen = openWithWill("window")
        suggest(screen)
        val first = screen.current()
        val part = screen.session.state.value.resolvedPart!!
        val before = count(screen.holder, "suggestion_skip")

        another()
        compose.screenshot("p9b", "skipped-undo")
        assertEquals(before, count(screen.holder, "suggestion_skip"), "not while the window is open")
        compose.awaitUntil("the window's close") { count(screen.holder, "suggestion_skip") == before + 1 }
        compose.waitForIdle()
        compose.onNodeWithTag(SuggestTags.SKIPPED).assertDoesNotExist()
        Thread.sleep(1_000)
        assertEquals(before + 1, count(screen.holder, "suggestion_skip"), "exactly one")
        val mine = "suggestion_skip WHERE song_id = '$first' AND performer_id = '${part.performerId}' AND instrument_id = '${part.instrumentId}'"
        assertEquals(1L, count(screen.holder, mine), "the skipped card's part")
    }

    /** SG3, SG12: **dismissing inside the window writes nothing**, the staged skip included. */
    @Test
    fun dismissingInsideTheWindowWritesNothing() = withOneIoThread { io, drain ->
        val screen = openWithWill("dismiss", io = io)
        suggest(screen)
        val before = writes(screen)
        another()
        compose.onNodeWithTag(SuggestTags.SHEET).performTouchInput { swipeDown() }
        compose.awaitUntil("the sheet's dismissal") { screen.session.suggestions.deck.value == null }
        Thread.sleep(SuggestionHolder.SKIP_WINDOW_MS + 1_000)
        drain()
        assertEquals(before, writes(screen), "no event, no void, no skip")
    }

    /** SG12: "Log it" on the card after "Another" writes the staged skip and the one event. */
    @Test
    fun logItWritesTheStagedSkipAndOneEvent() {
        val screen = openWithWill("log-it")
        suggest(screen)
        val first = screen.current()
        val skipsBefore = count(screen.holder, "suggestion_skip")
        val eventsBefore = count(screen.holder, "practice_event")
        another()
        val second = screen.current()

        compose.onNodeWithText(Messages.SUGGEST_LOG, ignoreCase = true).performClick()
        compose.awaitUntil("the skip and the log") {
            count(screen.holder, "suggestion_skip") == skipsBefore + 1 && count(screen.holder, "practice_event") == eventsBefore + 1
        }
        assertEquals(1L, count(screen.holder, "suggestion_skip WHERE song_id = '$first'"))
        assertEquals(second, screen.session.state.value.taps.single().songId)
    }

    /** visual-identity VI8: "Tune" with its two switches at font scale 1.3, no label losing a line. */
    @Test
    fun tuneFitsAtALargeFont() {
        val screen = openWithWill("large-font", fontScale = LARGE_FONT)
        suggest(screen)
        openTune()
        compose.onNodeWithTag(SuggestTags.SHOW_SKIP_COUNT).performScrollTo()
        for (tag in listOf(SuggestTags.COUNT_SKIPS, SuggestTags.SHOW_SKIP_COUNT)) {
            val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
            for (text in node.children) {
                val layouts = mutableListOf<TextLayoutResult>()
                if (SemanticsActions.GetTextLayoutResult in text.config) text.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                for (layout in layouts) assertFalse(layout.hasVisualOverflow, "$tag clips at font scale $LARGE_FONT")
            }
        }
        compose.onNodeWithTag(SuggestTags.RADAR).performScrollTo()
        compose.screenshot("p9b", "tune-font-1.3")
    }

    // ---- Harness -------------------------------------------------------------------------------

    /** The sample songs with Will added as this device's owner, so triage T9 resolves his part (SG14). */
    private fun openWithWill(
        suffix: String,
        tuning: SuggestTuning = COUNTING,
        io: CoroutineDispatcher = Dispatchers.IO,
        fontScale: Float? = null,
    ): SessionScreenFixture.Screen {
        val device: DevicePreferences = InMemoryDevicePreferences(suggestTuning = tuning)
        var will: String? = null
        val screen = fixture.open(suffix, fontScale = fontScale, device = device, io = io, prepare = { holder ->
            will = EditingFixtures.performer(holder, "Will").also(device::rememberOwnerPerformer)
        })
        compose.awaitUntil("Will's part") { screen.session.state.value.let { it.resolvedPart?.performerId == will && it.ratedFor == it.resolvedPart } }
        compose.waitForIdle()
        return screen
    }

    private fun suggest(screen: SessionScreenFixture.Screen) {
        compose.onNodeWithContentDescription(Messages.SUGGEST).performClick()
        compose.awaitUntil("the card") { screen.session.suggestions.deck.value is SuggestionDeck.Showing }
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

    /** Open the sheet, read the card's count line (null when there is none), and close it. */
    private fun shownCount(screen: SessionScreenFixture.Screen, screenshot: String? = null): String? {
        suggest(screen)
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

    private fun handle(spoke: SuggestSpoke): SemanticsNodeInteraction =
        compose.onNodeWithTag(SuggestTags.spoke(spoke), useUnmergedTree = true)

    private fun reason(spoke: SuggestSpoke, reason: String): SemanticsNodeInteraction =
        compose.onNode(hasText(reason) and hasAnyAncestor(hasTestTag(SuggestTags.label(spoke))), useUnmergedTree = true)

    private fun SessionScreenFixture.Screen.current(): String =
        (session.suggestions.deck.value as SuggestionDeck.Showing).current.songId

    private fun writes(screen: SessionScreenFixture.Screen): List<Long> =
        listOf("practice_event", "practice_event_void", "suggestion_skip").map { count(screen.holder, it) }

    private fun withOneIoThread(body: (io: CoroutineDispatcher, drain: () -> Unit) -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        val io = executor.asCoroutineDispatcher()
        try {
            body(io) { runBlocking(io) {} }
        } finally {
            io.close()
        }
    }

    private companion object {
        const val PREFERENCES = "suggest-skips-test"
        const val LARGE_FONT = 1.3f
        val COUNTING = SuggestTuning.DEFAULT.copy(countSkips = true)
    }
}

/**
 * **visual-identity VI22 for SG12**: with the animator duration scale at 0, Undo lands the previous card at
 * once, one card, with every button live.
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
    fun undoLandsThePreviousCardAtOnce() {
        val device = InMemoryDevicePreferences(suggestTuning = SuggestTuning.DEFAULT.copy(countSkips = true))
        val screen = fixture.open("no-motion", device = device, prepare = { holder ->
            device.rememberOwnerPerformer(EditingFixtures.performer(holder, "Will"))
        })
        compose.awaitUntil("Will's part") { screen.session.state.value.resolvedPart != null }
        compose.onNodeWithContentDescription(Messages.SUGGEST).performClick()
        compose.awaitUntil("the card") { screen.session.suggestions.deck.value is SuggestionDeck.Showing }
        compose.waitForIdle()
        val first = (screen.session.suggestions.deck.value as SuggestionDeck.Showing).current.songId

        compose.onNodeWithText(Messages.SUGGEST_ANOTHER, ignoreCase = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SuggestTags.UNDO_SKIP).assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(first, (screen.session.suggestions.deck.value as SuggestionDeck.Showing).current.songId)
        assertEquals(1, compose.onAllNodesWithTag(SuggestTags.CARD).fetchSemanticsNodes().size, "one card, settled")
        val title = screen.session.state.value.rows.first { it.songId == first }.title
        compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag(SuggestTags.CARD))).assertExists()
        compose.onNodeWithTag(SuggestTags.SKIPPED).assertDoesNotExist()
        compose.onNodeWithText(Messages.SUGGEST_LOG, ignoreCase = true).assertIsEnabled()
        compose.onNodeWithText(Messages.SUGGEST_ANOTHER, ignoreCase = true).assertIsEnabled()
    }

    private object NoMotion : MotionDurationScale {
        override val scaleFactor: Float = 0f
    }
}

/** A drag from [spoke]'s handle outward along its spoke. */
private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.dragOut(spoke: SuggestSpoke, distance: Float = 120f) {
    val (x, y) = spoke.direction()
    val outward = Offset(x.toFloat(), y.toFloat()) * distance
    onNodeWithTag(SuggestTags.spoke(spoke), useUnmergedTree = true)
        .performScrollTo()
        .performTouchInput { swipe(start = center, end = center + outward, durationMillis = 400) }
    waitForIdle()
}
