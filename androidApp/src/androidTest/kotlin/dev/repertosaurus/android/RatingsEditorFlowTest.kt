package dev.repertosaurus.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.android.DatabaseFixtures.count
import dev.repertosaurus.core.RatingKind
import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.SampleData
import dev.repertosaurus.session.CapabilityCoordinator
import dev.repertosaurus.session.InMemoryDevicePreferences
import dev.repertosaurus.session.InMemorySessionPreferences
import dev.repertosaurus.session.PageFilter
import dev.repertosaurus.session.LookupKind
import dev.repertosaurus.session.LookupStores
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.RatingsSource
import dev.repertosaurus.session.ViewCoordinator
import dev.repertosaurus.session.ViewFilter
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The ratings editor on the composed app** — triage T1-T5a, T9 and rating-scale RS9: both entry
 * points, the one-tap write and clear, the letter pages, search and the filter, the View entry's
 * disabled state, and the same paging on the Repertoire toggle list.
 *
 * Every fixture is the eight sample songs. Their pages: C (Chelsea Dagger), D (Dakota, Dog Days Are
 * Over), M, S (Shake It Off, Sweet Home Alabama), T, V. No song files under A.
 */
@RunWith(AndroidJUnit4::class)
class RatingsEditorFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()

    @After
    fun cleanUp() {
        for (name in names) DatabaseFixtures.delete(context, name)
    }

    // ---- T1: both entry points ----------------------------------------------------------------

    /** T1, the Repertoire route: a role's "Ratings" lists the songs it is enabled on. E24 on the way out. */
    @Test
    fun theRepertoireEntryOpensTheEditorOnTheRole() {
        val fixture = fixture("repertoire-entry", holds = listOf("Dakota", "Valerie"))

        openToggleList(fixture)
        compose.onNodeWithTag(RepertoireTags.RATINGS).performClick()
        awaitEditor(fixture)

        val editor = fixture.app.ratings.state.value!!
        assertEquals(fixture.coralie, editor.target.performerId)
        assertEquals(SampleData.VOCAL, editor.target.instrumentId)
        assertEquals(RatingsSource.EnabledParts, editor.target.source)
        assertEquals(setOf(fixture.id("Dakota"), fixture.id("Valerie")), editor.songs.map { it.songId }.toSet())
        compose.onNodeWithTag(RatingsEditorTags.TITLE).assertTextEquals("CORALIE · VOCAL")

        // E24: back closes the role, editor and all; back again is the logger.
        back()
        compose.onNodeWithTag(RatingsEditorTags.SCREEN).assertDoesNotExist()
        compose.onNodeWithTag(RepertoireTags.PERFORMERS).assertIsDisplayed()
        back()
        compose.onNodeWithContentDescription(LOGGER_MENU).assertIsDisplayed()
    }

    /** T1, T9 branch 1, the View menu: the View's filter performer, its practice instrument, its pool. */
    @Test
    fun theViewEntryOpensTheEditorOnTheViewsPart() {
        val fixture = fixture("view-entry", holds = listOf("Dakota", "Valerie"), coralieView = true)

        openSwitcher("CORALIE SINGS ▾")
        compose.onNodeWithTag(ViewsTags.RATE_THESE).performClick()
        awaitEditor(fixture)
        compose.onNodeWithTag(RatingsEditorTags.SCREEN).assertIsDisplayed()

        val editor = fixture.app.ratings.state.value!!
        assertEquals(fixture.coralie, editor.target.performerId)
        assertEquals(SampleData.VOCAL, editor.target.instrumentId)
        val pool = fixture.app.session.state.value.rows.map { it.songId }.toSet()
        assertEquals(setOf(fixture.id("Dakota"), fixture.id("Valerie")), pool)
        assertEquals(pool, editor.songs.map { it.songId }.toSet(), "the View's pool")

        back()
        compose.onNodeWithTag(RatingsEditorTags.SCREEN).assertDoesNotExist()
        compose.onNodeWithContentDescription(LOGGER_MENU).assertIsDisplayed()
        assertNull(fixture.app.ratings.state.value, "leaving the route closes the editor")
    }

    /** T9 branch 2: no filter performer, so the owner (T10) — on the whole repertoire. */
    @Test
    fun withNoFilterPerformerTheOwnersPartIsRated() {
        val fixture = fixture("owner-entry", owner = true)

        openEditorFromView(fixture)
        val editor = fixture.app.ratings.state.value!!
        assertEquals(fixture.coralie, editor.target.performerId)
        assertEquals(fixture.app.session.state.value.view!!.practiceInstrumentId, editor.target.instrumentId)
        assertEquals(8, editor.songs.size)
    }

    /** T9 branch 3: nobody resolves, so the entry is disabled and says why. Nothing is guessed. */
    @Test
    fun theViewEntryIsDisabledWithItsReasonWhenNoPerformerResolves() {
        val fixture = fixture("no-performer")

        openSwitcher("ALL SONGS ▾")
        compose.onNodeWithTag(ViewsTags.RATE_THESE).assertIsNotEnabled()
        compose.onNodeWithText(Messages.RATE_NEEDS_PERFORMER).assertIsDisplayed()
        compose.screenshot("p8", "view-entry-disabled")

        compose.onNodeWithTag(ViewsTags.RATE_THESE).performClick()
        compose.waitForIdle()
        assertNull(fixture.app.ratings.state.value, "a disabled entry opened the editor")
    }

    /** T9, F20 N6: the View's filter performer wins over the owner, who is someone else. */
    @Test
    fun theFilterPerformerIsRatedEvenWhenAnOwnerIsSet() {
        val fixture = fixture("filter-over-owner", holds = listOf("Dakota"), coralieView = true, ownerName = "Will")

        openSwitcher("CORALIE SINGS ▾")
        compose.onNodeWithTag(ViewsTags.RATE_THESE).performClick()
        awaitEditor(fixture)
        assertEquals(fixture.coralie, fixture.app.ratings.state.value!!.target.performerId, "the filter performer, not the owner")
        compose.onNodeWithTag(RatingsEditorTags.TITLE).assertTextEquals("CORALIE · VOCAL")
    }

    /** T10, F20 N6: a soft-deleted owner resolves to nobody, so the entry is disabled with its reason. */
    @Test
    fun aRemovedOwnerLeavesTheEntryDisabled() {
        val fixture = fixture("removed-owner", owner = true, removeCoralie = true)

        openSwitcher("ALL SONGS ▾")
        compose.onNodeWithTag(ViewsTags.RATE_THESE).assertIsNotEnabled()
        compose.onNodeWithText(Messages.RATE_NEEDS_PERFORMER).assertIsDisplayed()
        assertNull(fixture.app.ratings.state.value)
    }

    // ---- RS9, T2: one tap sets, a tap on the selected level clears ---------------------------

    @Test
    fun aTapWritesARatingAndATapOnTheSelectedLevelClearsIt() {
        val fixture = fixture("tap", owner = true)
        val eventsBefore = count(fixture.holder, "practice_event")
        openEditorFromView(fixture)
        val instrument = fixture.app.ratings.state.value!!.target.instrumentId
        val dakota = fixture.id("Dakota")
        letter('D')

        val certainly = segment(dakota, RatingKind.PRIORITY, RatingLevel.CERTAINLY)
        compose.onNodeWithTag(certainly).performClick()
        compose.onNodeWithTag(certainly).assertIsSelected()
        val part = "part_rating WHERE song_id = '$dakota' AND performer_id = '${fixture.coralie}' " +
            "AND instrument_id = '$instrument'"
        val live = "$part AND kind = 'PRIORITY'"
        compose.awaitUntil("the rating's write") { count(fixture.holder, "$live AND level = 2 AND deleted_at IS NULL") == 1L }
        assertEquals(RatingLevel.CERTAINLY, fixture.holder.repository.ratings.ratingsFor(fixture.coralie, instrument)[dakota]?.priority)
        assertEquals(0L, count(fixture.holder, "$part AND kind = 'CONFIDENCE'"), "the other kind was not touched")

        compose.onNodeWithTag(certainly).performClick()
        compose.onNodeWithTag(certainly).assertIsNotSelected()
        compose.awaitUntil("the clear") { count(fixture.holder, "$live AND deleted_at IS NOT NULL") == 1L }
        assertEquals(0L, count(fixture.holder, "$live AND deleted_at IS NULL"), "schema-3 M7: cleared is a tombstone")
        assertNull(fixture.holder.repository.ratings.ratingsFor(fixture.coralie, instrument)[dakota])
        assertEquals(eventsBefore, count(fixture.holder, "practice_event"), "rating is never on the tap path")
    }

    // ---- T3-T5: letter, search, filter -------------------------------------------------------

    @Test
    fun lettersSearchAndTheFilterPageTheEditor() {
        val fixture = fixture("paging", owner = true)
        openEditorFromView(fixture)
        val chelsea = fixture.id("Chelsea Dagger")
        val dakota = fixture.id("Dakota")
        val dog = fixture.id("Dog Days Are Over")
        val valerie = fixture.id("Valerie")

        // T3: the first letter with songs; a letter with none is greyed and cannot be tapped.
        compose.onNodeWithTag(PagingTags.letter(RatingsEditorTags.PAGING, 'C')).assertIsSelected()
        compose.onNodeWithTag(PagingTags.letter(RatingsEditorTags.PAGING, 'A')).assertIsNotEnabled()
        compose.onNodeWithTag(RatingsEditorTags.row(chelsea)).assertIsDisplayed()
        compose.onNodeWithTag(RatingsEditorTags.row(dakota)).assertDoesNotExist()

        letter('D')
        compose.onNodeWithTag(RatingsEditorTags.row(dakota)).assertIsDisplayed()
        compose.onNodeWithTag(RatingsEditorTags.row(chelsea)).assertDoesNotExist()
        compose.onNodeWithTag(RatingsEditorTags.row(dog)).assertExists()
        compose.screenshot("p8", "editor-letter-page")

        // T4: across letters, under a Search heading; cleared, back to D.
        compose.onNodeWithTag(PagingTags.search(RatingsEditorTags.PAGING)).performTextInput("zutons")
        compose.waitForIdle()
        compose.onNodeWithTag(PagingTags.searchHeading(RatingsEditorTags.PAGING)).assertIsDisplayed()
        compose.onNodeWithTag(RatingsEditorTags.row(valerie)).assertIsDisplayed()
        compose.onNodeWithTag(RatingsEditorTags.row(dakota)).assertDoesNotExist()
        compose.screenshot("p8", "editor-search")
        compose.onNodeWithTag(PagingTags.search(RatingsEditorTags.PAGING)).performTextClearance()
        compose.waitForIdle()
        compose.onNodeWithTag(RatingsEditorTags.row(dakota)).assertIsDisplayed()
        compose.onNodeWithTag(PagingTags.letter(RatingsEditorTags.PAGING, 'D')).assertIsSelected()

        // T5: rate Dakota, then Unrated leaves only Dog Days; Rated only Dakota.
        compose.onNodeWithTag(segment(dakota, RatingKind.CONFIDENCE, RatingLevel.SOMEWHAT)).performClick()
        compose.awaitUntil("the rating") { count(fixture.holder, "part_rating WHERE song_id = '$dakota' AND deleted_at IS NULL") == 1L }
        filter(PageFilter.UNSET)
        compose.onNodeWithTag(RatingsEditorTags.row(dakota)).assertDoesNotExist()
        compose.onNodeWithTag(RatingsEditorTags.row(dog)).assertExists()
        compose.screenshot("p8", "editor-unrated-filter")
        filter(PageFilter.SET)
        compose.onNodeWithTag(RatingsEditorTags.row(dakota)).assertIsDisplayed()
        compose.onNodeWithTag(RatingsEditorTags.row(dog)).assertDoesNotExist()
        assertEquals(listOf(dakota), fixture.app.ratings.state.value!!.page.order)
    }

    /** R7's rule on the editor: under Unrated, a rated row stays until the page is next fixed. */
    @Test
    fun aRatingUnderUnratedLeavesTheRowForItsSecondRating() {
        val fixture = fixture("stays", owner = true)
        openEditorFromView(fixture)
        val dakota = fixture.id("Dakota")
        letter('D')
        filter(PageFilter.UNSET)

        compose.onNodeWithTag(segment(dakota, RatingKind.PRIORITY, RatingLevel.EXCEPTIONALLY)).performClick()
        compose.onNodeWithTag(segment(dakota, RatingKind.CONFIDENCE, RatingLevel.NOT_AT_ALL)).performClick()
        compose.awaitUntil("both ratings") {
            count(fixture.holder, "part_rating WHERE song_id = '$dakota' AND deleted_at IS NULL") == 2L
        }
        compose.onNodeWithTag(RatingsEditorTags.row(dakota)).assertIsDisplayed()
    }

    // ---- T5a: the toggle list ----------------------------------------------------------------

    @Test
    fun theToggleListPagesSearchesAndFiltersLikeTheEditor() {
        val fixture = fixture("toggle-list", holds = listOf("Dakota"))
        val chelsea = fixture.id("Chelsea Dagger")
        val dakota = fixture.id("Dakota")
        val dog = fixture.id("Dog Days Are Over")
        val valerie = fixture.id("Valerie")
        val prefix = RepertoireTags.PAGING

        openToggleList(fixture)

        compose.onNodeWithTag(PagingTags.letter(prefix, 'C')).assertIsSelected()
        compose.onNodeWithTag(PagingTags.letter(prefix, 'A')).assertIsNotEnabled()
        compose.onNodeWithTag(RepertoireTags.row(chelsea)).assertIsDisplayed()
        compose.onNodeWithTag(RepertoireTags.row(dakota)).assertDoesNotExist()

        compose.onNodeWithTag(PagingTags.letter(prefix, 'D')).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RepertoireTags.row(dakota)).assertIsDisplayed()
        compose.onNodeWithTag(RepertoireTags.row(dog)).assertIsDisplayed()
        compose.screenshot("p8", "toggle-list-paging")

        compose.onNodeWithTag(PagingTags.search(prefix)).performTextInput("zutons")
        compose.waitForIdle()
        compose.onNodeWithTag(RepertoireTags.row(valerie)).assertIsDisplayed()
        compose.onNodeWithTag(RepertoireTags.row(dakota)).assertDoesNotExist()
        compose.onNodeWithTag(PagingTags.search(prefix)).performTextClearance()
        compose.waitForIdle()

        // On / Off: the D page by the held flag.
        compose.onNodeWithTag(PagingTags.filter(prefix, PageFilter.SET)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RepertoireTags.row(dakota)).assertIsDisplayed()
        compose.onNodeWithTag(RepertoireTags.row(dog)).assertDoesNotExist()
        compose.onNodeWithTag(PagingTags.filter(prefix, PageFilter.UNSET)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RepertoireTags.row(dog)).assertIsDisplayed()
        compose.onNodeWithTag(RepertoireTags.row(dakota)).assertDoesNotExist()

        // R7: a toggle under Off does not take the row away.
        compose.onNodeWithTag(RepertoireTags.row(dog)).performClick()
        compose.awaitUntil("the toggle") { fixture.app.repertoire.state.value.list?.inFlight?.isEmpty() == true }
        compose.waitForIdle()
        compose.onNodeWithTag(RepertoireTags.row(dog)).assertIsDisplayed()
        assertTrue(fixture.app.repertoire.state.value.list!!.rows.single { it.songId == dog }.held)
    }

    // ---- Harness -------------------------------------------------------------------------------

    private class Fixture(val holder: DatabaseHolder, val app: AppModels, val coralie: String) {
        fun id(title: String): String = EditingFixtures.song(holder, title).id
    }

    /**
     * The eight sample songs and Coralie, holding [holds] on vocal. [coralieView] saves a View
     * filtered on her vocal; [owner] makes her the device's owner (T10), and [ownerName] makes a
     * performer of that name the owner instead. [removeCoralie] soft-deletes her before the app starts.
     */
    private fun fixture(
        suffix: String,
        holds: List<String> = emptyList(),
        coralieView: Boolean = false,
        owner: Boolean = false,
        ownerName: String? = null,
        removeCoralie: Boolean = false,
    ): Fixture {
        val name = "ratings-editor-test-$suffix.db".also { names += it }
        val holder = EditingFixtures.holder(context, name)
        val coralie = EditingFixtures.performer(holder, "Coralie")
        val capabilities = CapabilityCoordinator(holder.repository)
        for (title in holds) capabilities.addById(EditingFixtures.song(holder, title).id, coralie, SampleData.VOCAL)
        val preferences = InMemorySessionPreferences()
        if (coralieView) {
            ViewCoordinator(holder.repository, preferences).createView(
                name = "Coralie sings",
                filter = ViewFilter(performerId = coralie, instrumentId = SampleData.VOCAL),
                practiceInstrumentId = SampleData.VOCAL,
            )
        }
        val someoneElse = ownerName?.let { EditingFixtures.performer(holder, it) }
        if (removeCoralie) LookupStores.of(LookupKind.PERFORMER, holder.repository).remove(coralie)
        val device = InMemoryDevicePreferences(ownerPerformer = someoneElse ?: if (owner) coralie else null)
        val app = EditingFixtures.app(holder, preferences, device)
        compose.awaitUntil("the logger") {
            val state = app.session.state.value
            !state.loading && state.view != null && (removeCoralie || app.session.performers.value.isNotEmpty())
        }
        compose.setApp(app)
        compose.waitForIdle()
        return Fixture(holder, app, coralie)
    }

    /** The Repertoire route, then Coralie's vocal role (style review F21 B15). */
    private fun openToggleList(fixture: Fixture) {
        openRoute("Repertoire")
        compose.awaitUntil("the performers") { fixture.app.repertoire.state.value.performers.isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText("Vocal").performClick()
        compose.awaitUntil("the toggle list") { fixture.app.repertoire.state.value.list?.loading == false }
        compose.waitForIdle()
    }

    private fun openRoute(label: String) {
        compose.onNodeWithContentDescription(LOGGER_MENU).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
    }

    /** The View menu: the header's title opens it. */
    private fun openSwitcher(title: String) {
        compose.onNodeWithText(title).performClick()
        compose.waitForIdle()
    }

    private fun openEditorFromView(fixture: Fixture) {
        openSwitcher("ALL SONGS ▾")
        compose.onNodeWithTag(ViewsTags.RATE_THESE).performClick()
        awaitEditor(fixture)
    }

    private fun awaitEditor(fixture: Fixture) {
        compose.awaitUntil("the editor's read") { fixture.app.ratings.state.value?.loading == false }
        compose.waitForIdle()
    }

    private fun letter(letter: Char) {
        compose.onNodeWithTag(PagingTags.letter(RatingsEditorTags.PAGING, letter)).performClick()
        compose.waitForIdle()
    }

    private fun filter(filter: PageFilter) {
        compose.onNodeWithTag(PagingTags.filter(RatingsEditorTags.PAGING, filter)).performClick()
        compose.waitForIdle()
    }

    private fun segment(songId: String, kind: RatingKind, level: RatingLevel): String =
        RatingTags.segment(RatingsEditorTags.control(songId, kind), level)

    private fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private companion object {
        const val LOGGER_MENU = "Menu"
    }
}
