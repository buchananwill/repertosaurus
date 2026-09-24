package dev.repertosaurus.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.SampleData
import dev.repertosaurus.session.InMemorySessionPreferences
import dev.repertosaurus.session.ViewCoordinator
import dev.repertosaurus.session.ViewFilter
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **E24 and R26 on the composed app**: back from a drill-down closes the drill-down, back from
 * the route lands on the logger — never more than two presses — and landing there reloads it.
 *
 * The whole of [RepertosaurusApp] is composed with all four ViewModels over one real database,
 * and back is the Activity's own dispatcher, so the handlers are consulted in their real order.
 */
@RunWith(AndroidJUnit4::class)
class RouteNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()

    @After
    fun cleanUp() {
        for (name in names) DatabaseFixtures.delete(context, name)
    }

    /** Songs: detail → back → list → back → logger. */
    @Test
    fun backFromTheSongDetailReturnsToTheListAndThenToTheLogger() {
        val fixture = fixture("songs")
        val valerie = EditingFixtures.song(fixture.holder, "Valerie")

        compose.openFromDrawer("Songs")
        compose.onNodeWithTag(SongsTags.LIST).assertIsDisplayed()
        await("the list") { fixture.songs.state.value.songs.isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag(SongsTags.LIST).performScrollToNode(hasTestTag(SongsTags.row(valerie.id)))
        compose.onNodeWithTag(SongsTags.row(valerie.id)).performClick()
        await("the detail") { fixture.songs.state.value.detail?.record != null }
        compose.waitForIdle()
        compose.onNodeWithTag(SongDetailTags.DETAIL).assertIsDisplayed()

        back()
        compose.onNodeWithTag(SongDetailTags.DETAIL).assertDoesNotExist()
        compose.onNodeWithTag(SongsTags.LIST).assertIsDisplayed()

        back()
        compose.onNodeWithTag(SongsTags.LIST).assertDoesNotExist()
        compose.onNodeWithContentDescription(LOGGER_MENU).assertIsDisplayed()
    }

    /**
     * Repertoire: toggle list → back → performers → back → logger. **R26:** the logger here
     * opens on a View filtered to Coralie on vocal, which holds nothing, and a song toggled on in
     * the route must be in the logger's list on return — not after a restart.
     */
    @Test
    fun backFromTheToggleListReturnsToThePerformersAndThenToAReloadedLogger() {
        val fixture = fixture("repertoire")
        val shake = EditingFixtures.song(fixture.holder, "Shake It Off")
        assertTrue(fixture.session.state.value.pending.isEmpty(), "the filtered View should start empty")

        compose.openFromDrawer("Repertoire")
        await("the performers") { fixture.repertoire.state.value.performers.isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag(RepertoireTags.addRole(fixture.coralie)).performClick()
        compose.onNodeWithText("Vocal").performClick()
        await("the toggle list") { fixture.repertoire.state.value.list?.loading == false }
        compose.waitForIdle()
        compose.onNodeWithTag(RepertoireTags.TOGGLE_LIST).assertIsDisplayed()

        // Triage T5a: Shake It Off is on the S page.
        compose.onNodeWithTag(PagingTags.letter(RepertoireTags.PAGING, 'S')).performClick()
        compose.onNodeWithTag(RepertoireTags.row(shake.id)).performClick()
        await("the toggle") { fixture.repertoire.state.value.list?.inFlight?.isEmpty() == true }

        back()
        compose.onNodeWithTag(RepertoireTags.TOGGLE_LIST).assertDoesNotExist()
        compose.onNodeWithTag(RepertoireTags.PERFORMERS).assertIsDisplayed()

        back()
        compose.onNodeWithTag(RepertoireTags.PERFORMERS).assertDoesNotExist()
        compose.onNodeWithContentDescription(LOGGER_MENU).assertIsDisplayed()

        await("the logger's reload") {
            fixture.session.state.value.pending.any { it.songId == shake.id }
        }
    }

    // ---- Harness -------------------------------------------------------------------------

    private class Fixture(
        val holder: DatabaseHolder,
        val session: SessionViewModel,
        val repertoire: RepertoireViewModel,
        val songs: SongsViewModel,
        val coralie: String,
    )

    private fun fixture(suffix: String): Fixture {
        val name = "navigation-test-$suffix.db".also { names += it }
        val holder = EditingFixtures.holder(context, name)
        val coralie = EditingFixtures.performer(holder, "Coralie")
        val preferences = InMemorySessionPreferences()
        // V20: with no home set, the logger opens on the first saved View — this one.
        ViewCoordinator(holder.repository, preferences).createView(
            name = "Coralie sings",
            filter = ViewFilter(performerId = coralie, instrumentId = SampleData.VOCAL),
            practiceInstrumentId = SampleData.VOCAL,
        )

        val app = EditingFixtures.app(holder, preferences)
        val fixture = Fixture(
            holder = holder,
            session = app.session,
            repertoire = app.repertoire,
            songs = app.songs,
            coralie = coralie,
        )
        await("the logger") { !fixture.session.state.value.loading && fixture.session.state.value.view != null }
        compose.setApp(app)
        compose.waitForIdle()
        return fixture
    }

    private fun await(what: String, settled: () -> Boolean) = compose.awaitUntil(what, settled)

    /** The system back gesture, through the Activity's own dispatcher. */
    private fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
}
