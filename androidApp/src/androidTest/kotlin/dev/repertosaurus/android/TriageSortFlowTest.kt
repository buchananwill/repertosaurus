package dev.repertosaurus.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.android.theme.DirectionGlyph
import dev.repertosaurus.android.theme.DirectionTurn
import dev.repertosaurus.android.theme.RepertosaurusTheme
import dev.repertosaurus.android.theme.RepertosaurusWindow
import dev.repertosaurus.core.RatingKind
import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.Part
import dev.repertosaurus.data.SampleData
import dev.repertosaurus.data.SongCatalog
import dev.repertosaurus.session.CapabilityCoordinator
import dev.repertosaurus.session.InMemoryDevicePreferences
import dev.repertosaurus.session.InMemorySessionPreferences
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.PartResolution
import dev.repertosaurus.session.SessionOrder
import dev.repertosaurus.session.SortMode
import dev.repertosaurus.session.ViewCoordinator
import dev.repertosaurus.session.ViewFilter
import dev.repertosaurus.session.part
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The triage sort on the composed app** (triage T6-T9): the mode sorts the list, a mode, direction or
 * View change scrolls to the top and a rotation does not, the triage modes are disabled with T9's line
 * when nobody resolves, the direction persists to a saved View (views V13b), "Rate these songs" waits for
 * the performers, and the order follows the ratings editor, the owner and the View.
 *
 * Every fixture is the eight sample songs, Will, and his vocal ratings: Valerie priority 3; Dakota
 * priority 1, confidence 0; Shake It Off priority 1, confidence 2. The rest are unrated.
 */
@RunWith(AndroidJUnit4::class)
class TriageSortFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()

    @After
    fun cleanUp() {
        for (name in names) DatabaseFixtures.delete(context, name)
    }

    /** T7 on screen: Priority, then Confidence, reorder the rows as the comparators say (T8: unrated last). */
    @Test
    fun theTriageModeSortsTheSessionList() {
        val fixture = fixture("sorts", owner = true)
        compose.screenshot("p9a", "sort-cold")

        mode(SortMode.TRIAGE_PRIORITY)
        assertEquals(SessionOrder.TRIAGE_PRIORITY, fixture.app.session.state.value.order)
        assertEquals(listOf("Valerie", "Dakota", "Shake It Off"), leading(fixture))
        assertOnScreenInOrder("Valerie", "Dakota", "Shake It Off")
        compose.onNodeWithTag(SortTags.LINE).assertTextEquals(Messages.sortOrder(SessionOrder.TRIAGE_PRIORITY))
        compose.screenshot("p9a", "sort-priority")

        mode(SortMode.TRIAGE_CONFIDENCE)
        assertEquals(listOf("Dakota", "Shake It Off", "Valerie"), leading(fixture), "T8: Valerie's confidence is unrated")
        assertOnScreenInOrder("Dakota", "Shake It Off", "Valerie")
        compose.screenshot("p9a", "sort-confidence")

        direction()
        assertEquals(SessionOrder.TRIAGE_CONFIDENCE_REVERSED, fixture.app.session.state.value.order)
        assertEquals(listOf("Shake It Off", "Dakota", "Valerie"), leading(fixture))
        compose.screenshot("p9a", "sort-confidence-reversed")
    }

    /** F35 B1: a rating set in the editor reorders the logger's triage list on the way back (R26's reload). */
    @Test
    fun aRatingSetInTheEditorReordersTheListOnReturn() {
        val fixture = fixture("editor-return", owner = true)
        mode(SortMode.TRIAGE_PRIORITY)
        assertEquals(listOf("Valerie", "Dakota", "Shake It Off"), leading(fixture))

        compose.onNodeWithText("ALL SONGS ▾").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ViewsTags.RATE_THESE).performClick()
        compose.awaitUntil("the editor's read") { fixture.app.ratings.state.value?.loading == false }
        compose.waitForIdle()
        val chelsea = EditingFixtures.song(fixture.holder, "Chelsea Dagger").id
        compose.onNodeWithTag(RatingTags.segment(RatingsEditorTags.control(chelsea, RatingKind.PRIORITY), RatingLevel.EXCEPTIONALLY))
            .performClick()
        compose.waitForIdle()

        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.awaitUntil("the logger's reload") {
            val state = fixture.app.session.state.value
            !state.loading && state.ratedFor != null && state.rows.any { it.songId == chelsea && it.priority != null }
        }
        compose.waitForIdle()
        assertEquals(listOf("Chelsea Dagger", "Valerie", "Dakota"), leading(fixture), "Chelsea Dagger is priority 3 now")
    }

    /** F35 B1 case 4: switching to a View filtered on Coralie reads her ratings, and the list follows them. */
    @Test
    fun switchingToAnotherPerformersViewReadsTheirRatings() {
        val fixture = fixture("switch", owner = true, coralieView = true)
        assertEquals("Will", fixture.app.session.state.value.ratedFor?.name)
        mode(SortMode.TRIAGE_PRIORITY)

        val coralieView = ViewCoordinator(fixture.holder.repository, InMemorySessionPreferences()).views()
            .single { it.name == "Coralie sings" }
        compose.runOnIdle { fixture.app.session.switchView(coralieView) }
        compose.awaitUntil("Coralie's View") {
            val state = fixture.app.session.state.value
            !state.loading && state.view?.name == "Coralie sings" && state.ratedFor?.name == "Coralie"
        }
        compose.waitForIdle()
        assertEquals(SessionOrder.COLDEST_FIRST, fixture.app.session.state.value.order, "the saved View's own order")
        mode(SortMode.TRIAGE_PRIORITY)
        assertEquals(listOf("Shake It Off", "Valerie", "Dakota"), leading(fixture), "Coralie's ratings, not Will's")
    }

    /** Journal F7, triage T6: a mode change and a direction change each land on the top of the new order. */
    @Test
    fun aModeOrDirectionChangeScrollsTheListToTheTop() {
        val fixture = fixture("scroll", owner = true, extraSongs = 25)

        scrollToBottom(fixture.app.session)
        mode(SortMode.TRIAGE_PRIORITY)
        assertAtTop(fixture.app.session.state.value.pending.first().title)

        scrollToBottom(fixture.app.session)
        direction()
        assertEquals(SessionOrder.TRIAGE_PRIORITY_REVERSED, fixture.app.session.state.value.order)
        assertAtTop(fixture.app.session.state.value.pending.first().title)
    }

    /** F35 B3, F37 B1: a rotation restores the list where it was; it does not jump to the top. */
    @Test
    fun aRotationKeepsTheListPosition() {
        val screens = SessionScreenFixture(compose, "triage-restore")
        try {
            val screen = screens.build("restore")
            for (n in 1..25) {
                screen.holder.repository.catalog.addSong("Extra %02d".format(n), SongCatalog.LookupChoice.Typed("Filler"))
            }
            EditingFixtures.onMain { screen.session.reload() }
            EditingFixtures.awaitSession(screen.session)

            val tester = StateRestorationTester(compose)
            tester.setContent { RepertosaurusWindow { SessionContent(screen) } }
            compose.waitForIdle()
            scrollToBottom(screen.session)

            tester.emulateSavedInstanceStateRestore()
            compose.waitForIdle()
            val pending = screen.session.state.value.pending
            assertTrue(
                compose.onAllNodesWithText(pending.first().title).fetchSemanticsNodes().isEmpty(),
                "the restored list is still scrolled down, not back at its top",
            )
            compose.onNodeWithText(pending.last().title).assertIsDisplayed()
        } finally {
            screens.cleanUp()
        }
    }

    /** T9 branch 3: nobody resolves, so Priority and Confidence are disabled and the line says why. */
    @Test
    fun withNoPerformerTheTriageModesAreDisabledWithTheReason() {
        val fixture = fixture("disabled", owner = false)
        assertEquals(PartResolution.None, fixture.app.session.state.value.part)

        compose.onNodeWithTag(SortTags.mode(SortMode.STALENESS)).assertIsEnabled().assertIsSelected()
        compose.onNodeWithTag(SortTags.mode(SortMode.TRIAGE_PRIORITY)).assertIsNotEnabled()
        compose.onNodeWithTag(SortTags.mode(SortMode.TRIAGE_CONFIDENCE)).assertIsNotEnabled()
        compose.onNodeWithTag(SortTags.LINE).assertTextEquals(Messages.SORT_NEEDS_PERFORMER)
        compose.screenshot("p9a", "sort-disabled")

        compose.onNodeWithTag(SortTags.mode(SortMode.TRIAGE_PRIORITY)).performClick()
        compose.waitForIdle()
        assertEquals(SessionOrder.COLDEST_FIRST, fixture.app.session.state.value.order, "a disabled mode does nothing")
    }

    /** views V13b, triage T6: the resolved order is what a saved View stores, mode and direction alike. */
    @Test
    fun theModeAndDirectionPersistToTheSavedView() {
        val fixture = fixture("persist", owner = true, savedView = true)
        val views = ViewCoordinator(fixture.holder.repository, InMemorySessionPreferences())

        mode(SortMode.TRIAGE_PRIORITY)
        compose.awaitUntil("the mode's write") { views.views().single().order == SessionOrder.TRIAGE_PRIORITY }

        direction()
        compose.awaitUntil("the direction's write") {
            views.views().single().order == SessionOrder.TRIAGE_PRIORITY_REVERSED
        }
        assertEquals(SessionOrder.TRIAGE_PRIORITY_REVERSED, fixture.app.session.state.value.view?.order)
    }

    /**
     * F20 N2: while the performers are unread the part is pending, so "Rate these songs" is not shown (not
     * shown as "nobody") and the triage modes are not disabled for it. Once read, both follow.
     */
    @Test
    fun theViewEntryIsPendingUntilThePerformersLoad() {
        // The first load's read of the rows runs; the performer read after it is held.
        val held = Gate().apply { holdAfter(1) }
        val fixture = fixture("pending", owner = true, io = held, awaitPerformers = false)
        assertEquals(PartResolution.Pending, fixture.app.session.state.value.part)

        compose.onNodeWithText("ALL SONGS ▾").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ViewsTags.RATE_THESE).assertDoesNotExist()
        compose.onNodeWithTag(SortTags.mode(SortMode.TRIAGE_PRIORITY)).assertIsEnabled()

        held.open()
        compose.awaitUntil("the performer read") { fixture.app.session.state.value.part is PartResolution.Resolved }
        compose.waitForIdle()
        compose.onNodeWithTag(ViewsTags.RATE_THESE).assertIsEnabled()
        compose.onNodeWithText(Messages.rateThesePart("Will · vocal")).assertIsDisplayed()
    }

    /**
     * F37 N1, VI8: at font scale 1.3, and at 2 where "Confidence" cannot fit its third, no mode label clips.
     * The label's own text layout is asked, inside its segment: `assertIsDisplayed` passes on a clipped node.
     */
    @Test
    fun theModeLabelsNeverClipAtLargeFonts() {
        val screens = SessionScreenFixture(compose, "triage-font")
        try {
            var scale by mutableStateOf(1.3f)
            val screen = screens.build("large")
            compose.setContent { LargeFont(scale) { RepertosaurusWindow { SessionContent(screen) } } }
            compose.waitForIdle()
            for (mode in SortMode.entries) assertLabelFits(mode)
            compose.screenshot("p9a", "session-font-1.3")

            scale = 2f
            compose.waitForIdle()
            for (mode in SortMode.entries) assertLabelFits(mode)
            compose.screenshot("p9a", "session-font-2.0")
        } finally {
            screens.cleanUp()
        }
    }

    /** VI18, F36 B4: the glyph squashes through nothing and opens pointing the other way; it never rotates. */
    @Test
    fun theDirectionGlyphFlipsThroughNothing() {
        var up by mutableStateOf(false)
        compose.setContent { RepertosaurusTheme { DirectionGlyph(up = up, modifier = Modifier.testTag(GLYPH)) } }
        compose.waitForIdle()
        assertEquals(1f, compose.turn())

        compose.mainClock.autoAdvance = false
        up = true
        val seen = mutableListOf<Float>()
        repeat(40) {
            compose.mainClock.advanceTimeByFrame()
            seen += compose.turn()
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertTrue(seen.any { it > -1f && it < 1f }, "it animates: $seen")
        assertTrue(seen.minOf { kotlin.math.abs(it) } < 0.5f, "it squashes nearly to nothing on the way: $seen")
        assertEquals(-1f, compose.turn(), "and settles pointing up")
    }

    // ---- Harness -------------------------------------------------------------------------------

    private class Fixture(val holder: DatabaseHolder, val app: AppModels)

    /**
     * The sample songs, Will and his ratings. [owner] makes Will the owner (T10). [coralieView] adds
     * Coralie on vocal for Valerie, Dakota and Shake It Off, rates Shake It Off 3 and Valerie 1 for her,
     * and saves "Mine" (no filter) then "Coralie sings". [savedView] saves one unfiltered View.
     */
    private fun fixture(
        suffix: String,
        owner: Boolean,
        extraSongs: Int = 0,
        savedView: Boolean = false,
        coralieView: Boolean = false,
        io: CoroutineDispatcher = Dispatchers.IO,
        awaitPerformers: Boolean = true,
    ): Fixture {
        val name = "triage-sort-test-$suffix.db".also { names += it }
        val holder = EditingFixtures.holder(context, name)
        val will = EditingFixtures.performer(holder, "Will")
        rate(holder, will, "Valerie", priority = RatingLevel.EXCEPTIONALLY)
        rate(holder, will, "Dakota", priority = RatingLevel.SOMEWHAT, confidence = RatingLevel.NOT_AT_ALL)
        rate(holder, will, "Shake It Off", priority = RatingLevel.SOMEWHAT, confidence = RatingLevel.CERTAINLY)
        for (n in 1..extraSongs) {
            holder.repository.catalog.addSong("Extra %02d".format(n), SongCatalog.LookupChoice.Typed("Filler"))
        }
        val preferences = InMemorySessionPreferences()
        val views = ViewCoordinator(holder.repository, preferences)
        if (savedView) {
            views.createView(name = "Triage", filter = ViewFilter.NONE, practiceInstrumentId = SampleData.VOCAL)
        }
        if (coralieView) {
            val coralie = EditingFixtures.performer(holder, "Coralie")
            val capabilities = CapabilityCoordinator(holder.repository)
            for (title in listOf("Valerie", "Dakota", "Shake It Off")) {
                capabilities.addById(EditingFixtures.song(holder, title).id, coralie, SampleData.VOCAL)
            }
            rate(holder, coralie, "Shake It Off", priority = RatingLevel.EXCEPTIONALLY)
            rate(holder, coralie, "Valerie", priority = RatingLevel.SOMEWHAT)
            views.createView(name = "Mine", filter = ViewFilter.NONE, practiceInstrumentId = SampleData.VOCAL)
            views.createView(
                name = "Coralie sings",
                filter = ViewFilter(performerId = coralie, instrumentId = SampleData.VOCAL),
                practiceInstrumentId = SampleData.VOCAL,
            )
        }
        val device = InMemoryDevicePreferences(ownerPerformer = if (owner) will else null)
        val app = EditingFixtures.app(holder, preferences, device, io = io)
        EditingFixtures.await("the logger") {
            val state = app.session.state.value
            !state.loading && state.view != null && (!awaitPerformers || state.performers != null)
        }
        compose.setApp(app)
        compose.waitForIdle()
        if (owner && awaitPerformers) {
            compose.awaitUntil("Will's ratings on the rows") { app.session.state.value.ratedFor?.performerId == will }
        }
        compose.waitForIdle()
        return Fixture(holder, app)
    }

    private fun rate(holder: DatabaseHolder, performer: String, title: String, priority: RatingLevel?, confidence: RatingLevel? = null) {
        val part = Part(EditingFixtures.song(holder, title).id, performer, SampleData.VOCAL)
        priority?.let { holder.repository.ratings.setRating(part, RatingKind.PRIORITY, it) }
        confidence?.let { holder.repository.ratings.setRating(part, RatingKind.CONFIDENCE, it) }
    }

    private fun leading(fixture: Fixture): List<String> =
        fixture.app.session.state.value.pending.take(3).map { it.title }

    private fun mode(mode: SortMode) {
        compose.onNodeWithTag(SortTags.mode(mode)).performClick()
        compose.waitForIdle()
    }

    private fun direction() {
        compose.onNodeWithTag(SortTags.DIRECTION).performClick()
        compose.waitForIdle()
    }

    private fun assertOnScreenInOrder(vararg titles: String) {
        val tops = titles.map { compose.onNodeWithText(it).assertIsDisplayed().getBoundsInRoot().top }
        assertEquals(tops.sorted(), tops, "on screen top to bottom: ${titles.toList()}")
    }

    /** To the last row, and proven there: the order's first row has left the composition. */
    private fun scrollToBottom(session: SessionViewModel) {
        val pending = session.state.value.pending
        compose.onNodeWithTag(SessionTags.LIST).performScrollToIndex(pending.lastIndex)
        compose.waitForIdle()
        assertTrue(
            compose.onAllNodesWithText(pending.first().title).fetchSemanticsNodes().isEmpty(),
            "the list really is scrolled away from its top",
        )
    }

    /** [title] is the list's first row, at the list's top edge. */
    private fun assertAtTop(title: String) {
        compose.waitForIdle()
        val listTop = compose.onNodeWithTag(SessionTags.LIST).getBoundsInRoot().top
        val rowTop = compose.onNodeWithText(title).assertIsDisplayed().getBoundsInRoot().top
        assertTrue(rowTop - listTop < 40.dp, "$title is at the top: row $rowTop, list $listTop")
    }

    /** The mode's label, laid out inside its segment with no visual overflow. */
    private fun assertLabelFits(mode: SortMode) {
        val label = compose.onNode(
            hasText(Messages.sortMode(mode)) and hasAnyAncestor(hasTestTag(SortTags.mode(mode))),
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        label.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()
        assertFalse(
            layout.hasVisualOverflow,
            "${mode.name}'s label clips: width ${layout.didOverflowWidth}, height ${layout.didOverflowHeight}, " +
                "lines ${layout.lineCount}, box ${layout.size}, text ${layout.multiParagraph.width}x${layout.multiParagraph.height}",
        )
        val segment = compose.onNodeWithTag(SortTags.mode(mode)).fetchSemanticsNode().boundsInRoot
        val text = label.boundsInRoot
        assertTrue(
            text.left >= segment.left && text.right <= segment.right && text.top >= segment.top && text.bottom <= segment.bottom,
            "${mode.name}'s label $text lies inside its segment $segment",
        )
    }

    private fun ComposeContentTestRule.turn(): Float = onNodeWithTag(GLYPH).fetchSemanticsNode().config[DirectionTurn]

    private companion object {
        const val GLYPH = "direction-glyph"
    }
}

/** The composition at font scale [scale], as `SessionScreenFixture` sets it: the test owns it, and leaves nothing behind. */
@Composable
private fun LargeFont(scale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, scale), content = content)
}

/**
 * **visual-identity VI22 for the direction glyph** (F37 N4): with the animator duration scale at 0 a flip
 * shows no intermediate frame; the glyph simply points the other way.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DirectionGlyphReducedMotionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(NoMotion)

    @Test
    fun withAnimationsOffTheFlipHasNoIntermediateFrame() {
        var up by mutableStateOf(false)
        compose.setContent { RepertosaurusTheme { DirectionGlyph(up = up, modifier = Modifier.testTag("glyph")) } }
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        up = true
        val seen = mutableListOf<Float>()
        repeat(10) {
            compose.mainClock.advanceTimeByFrame()
            seen += compose.onNodeWithTag("glyph").fetchSemanticsNode().config[DirectionTurn]
        }
        compose.mainClock.autoAdvance = true

        assertTrue(seen.all { it == 1f || it == -1f }, "no frame between down and up: $seen")
        assertEquals(-1f, seen.last())
    }

    private object NoMotion : MotionDurationScale {
        override val scaleFactor: Float = 0f
    }
}
