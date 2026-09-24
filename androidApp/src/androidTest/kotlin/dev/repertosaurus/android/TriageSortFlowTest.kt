package dev.repertosaurus.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.core.RatingKind
import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.Part
import dev.repertosaurus.data.SampleData
import dev.repertosaurus.data.SongCatalog
import dev.repertosaurus.session.InMemoryDevicePreferences
import dev.repertosaurus.session.InMemorySessionPreferences
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.PartResolution
import dev.repertosaurus.session.SessionOrder
import dev.repertosaurus.session.SortMode
import dev.repertosaurus.session.ViewCoordinator
import dev.repertosaurus.session.ViewFilter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The triage sort on the composed app** (triage T6-T9, journal F7, F20 N2): the mode sorts the list,
 * a mode or direction change scrolls to the top, the triage modes are disabled with T9's line when
 * nobody resolves, the direction persists to a saved View (views V13b), and "Rate these songs" waits
 * for the performers.
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
        shot("sort-cold")

        mode(SortMode.TRIAGE_PRIORITY)
        assertEquals(SessionOrder.TRIAGE_PRIORITY, fixture.app.session.state.value.order)
        assertEquals(listOf("Valerie", "Dakota", "Shake It Off"), leading(fixture))
        assertOnScreenInOrder("Valerie", "Dakota", "Shake It Off")
        compose.onNodeWithTag(SortTags.LINE).assertTextEquals(Messages.sortOrder(SessionOrder.TRIAGE_PRIORITY))
        shot("sort-priority")

        mode(SortMode.TRIAGE_CONFIDENCE)
        assertEquals(listOf("Dakota", "Shake It Off", "Valerie"), leading(fixture), "T8: Valerie's confidence is unrated")
        assertOnScreenInOrder("Dakota", "Shake It Off", "Valerie")
        shot("sort-confidence")

        direction()
        assertEquals(SessionOrder.TRIAGE_CONFIDENCE_REVERSED, fixture.app.session.state.value.order)
        assertEquals(listOf("Shake It Off", "Dakota", "Valerie"), leading(fixture))
        shot("triage-sorted-list-reversed")
    }

    /** Journal F7, triage T6: a mode change and a direction change each land on the top of the new order. */
    @Test
    fun aModeOrDirectionChangeScrollsTheListToTheTop() {
        val fixture = fixture("scroll", owner = true, extraSongs = 25)

        scrollToBottom(fixture)
        mode(SortMode.TRIAGE_PRIORITY)
        assertAtTop(fixture.app.session.state.value.pending.first().title)

        scrollToBottom(fixture)
        direction()
        assertEquals(SessionOrder.TRIAGE_PRIORITY_REVERSED, fixture.app.session.state.value.order)
        assertAtTop(fixture.app.session.state.value.pending.first().title)
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
        shot("sort-disabled")

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
     * F20 N2, F21 B11: while the performers are unread the part is pending, so "Rate these songs" is not
     * shown (not shown as "nobody") and the triage modes are not disabled for it. Once read, both follow.
     */
    @Test
    fun theViewEntryIsPendingUntilThePerformersLoad() {
        val held = HoldAfterFirst()
        val fixture = fixture("pending", owner = true, io = held, awaitPerformers = false)
        assertEquals(PartResolution.Pending, fixture.app.session.state.value.part)

        compose.onNodeWithText("ALL SONGS ▾").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ViewsTags.RATE_THESE).assertDoesNotExist()
        compose.onNodeWithTag(SortTags.mode(SortMode.TRIAGE_PRIORITY)).assertIsEnabled()

        held.release()
        compose.awaitUntil("the performer read") { fixture.app.session.state.value.part is PartResolution.Resolved }
        compose.waitForIdle()
        compose.onNodeWithTag(ViewsTags.RATE_THESE).assertIsEnabled()
        compose.onNodeWithText(Messages.rateThesePart("Will · vocal")).assertIsDisplayed()
    }

    /** VI8 at a large font: the session screen at 1.3, sort control included, for the screenshot. */
    @Test
    fun theSessionScreenAtFontScaleOnePointThree() {
        val screens = SessionScreenFixture(compose, "triage-font")
        try {
            screens.open("large", fontScale = 1.3f)
            compose.onNodeWithTag(SortTags.mode(SortMode.TRIAGE_CONFIDENCE)).assertIsDisplayed()
            compose.onNodeWithTag(SortTags.DIRECTION).assertIsDisplayed()
            shot("session-font-1.3")
        } finally {
            screens.cleanUp()
        }
    }

    // ---- Harness -------------------------------------------------------------------------------

    private class Fixture(val holder: DatabaseHolder, val app: AppModels)

    private fun fixture(
        suffix: String,
        owner: Boolean,
        extraSongs: Int = 0,
        savedView: Boolean = false,
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
        if (savedView) {
            ViewCoordinator(holder.repository, preferences).createView(
                name = "Triage",
                filter = ViewFilter.NONE,
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

    private fun rate(holder: DatabaseHolder, will: String, title: String, priority: RatingLevel?, confidence: RatingLevel? = null) {
        val part = Part(EditingFixtures.song(holder, title).id, will, SampleData.VOCAL)
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
    private fun scrollToBottom(fixture: Fixture) {
        val state = fixture.app.session.state.value
        compose.onNodeWithTag(SessionTags.LIST).performScrollToIndex(state.pending.lastIndex)
        compose.waitForIdle()
        assertTrue(
            compose.onAllNodesWithText(state.pending.first().title).fetchSemanticsNodes().isEmpty(),
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

    /** The whole screen into the app's external files (`p9a/`), for the P9a report. */
    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = File(context.getExternalFilesDir(null), "p9a").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

/**
 * The logger's IO with **every dispatch after the first held** until [release]: the first is the first
 * load's read of the rows, the second is the performer read (`SessionViewModel.reload`). So the logger
 * settles with its rows and without its performers.
 */
private class HoldAfterFirst : CoroutineDispatcher() {
    private val dispatched = AtomicInteger()
    private val latch = CountDownLatch(1)

    fun release() {
        latch.countDown()
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val held = dispatched.getAndIncrement() >= 1
        Dispatchers.IO.dispatch(context, Runnable {
            if (held) latch.await()
            block.run()
        })
    }
}
