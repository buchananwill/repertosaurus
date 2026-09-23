package dev.repertosaurus.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.android.EditingFixtures.onMain
import dev.repertosaurus.core.Ids
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.SongCatalog
import dev.repertosaurus.session.Messages
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Artists route, composed over a real database — repertoire-editing R24, R25, R25a. Each
 * removal goes through the real button and the real confirmation, and the refusal is read off
 * the screen's error channel (E43), not only out of the state.
 */
@RunWith(AndroidJUnit4::class)
class ArtistsEditingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()

    @After
    fun cleanUp() {
        for (name in names) DatabaseFixtures.delete(context, name)
    }

    /** R25a: *Unknown Artist* is never removed, whatever its count, and the screen says why. */
    @Test
    fun unknownArtistRemovalIsRefusedAsProtected() {
        val fixture = fixture("protected")

        remove(fixture, SongCatalog.UNKNOWN_ARTIST_ID)

        compose.onNodeWithTag(StatusTags.ERROR).assertTextEquals(Messages.ARTIST_PROTECTED)
        compose.onNodeWithTag(StatusTags.MESSAGE).assertDoesNotExist()
        assertEquals(
            1L,
            DatabaseFixtures.count(
                fixture.holder,
                "artist WHERE id = '${SongCatalog.UNKNOWN_ARTIST_ID}' AND deleted_at IS NULL",
            ),
            "Unknown Artist was removed",
        )
    }

    /** R25: an artist with live songs is refused, and the refusal carries the count. */
    @Test
    fun anArtistWithSongsIsRefusedWithTheCount() {
        val fixture = fixture("refused")
        val zutons = Ids.derived("artist", "The Zutons")

        remove(fixture, zutons)

        compose.onNodeWithTag(StatusTags.ERROR)
            .assertTextEquals(Messages.artistRefused("The Zutons", 1L))
        assertNull(fixture.model.state.value.message)
        assertEquals(1L, fixture.model.state.value.artists.first { it.id == zutons }.liveSongs)
    }

    /**
     * **F16 #8: the route searches in memory.** Typing filters the list to the matching artists,
     * over the name and the sort name, and Clear brings the whole list back.
     */
    @Test
    fun theSearchFiltersTheList() {
        val fixture = fixture("search")
        val zutons = Ids.derived("artist", "The Zutons")
        val all = fixture.model.state.value.artists.size
        assertTrue(all > 1, "the sample data has several artists")

        compose.onNodeWithTag(ArtistTags.SEARCH).performTextInput("zutons")
        compose.waitForIdle()

        val killers = Ids.derived("artist", "The Killers")
        compose.onNodeWithTag(ArtistTags.remove(zutons)).assertIsDisplayed()
        compose.onNodeWithTag(ArtistTags.remove(killers)).assertDoesNotExist()
        compose.onNodeWithText("1 of $all artists").assertIsDisplayed()

        compose.onNodeWithText("Clear").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("1 of $all artists").assertDoesNotExist()
        compose.onNodeWithTag(ArtistTags.LIST).performScrollToNode(hasTestTag(ArtistTags.remove(killers)))
        compose.onNodeWithTag(ArtistTags.remove(killers)).assertIsDisplayed()
    }

    // ---- Harness -------------------------------------------------------------------------

    private class Fixture(val holder: DatabaseHolder, val model: ArtistsViewModel)

    private fun fixture(suffix: String): Fixture {
        val name = "artists-test-$suffix.db".also { names += it }
        val holder = EditingFixtures.holder(context, name)
        lateinit var model: ArtistsViewModel
        onMain { model = ArtistsViewModel(holder) }
        compose.setContent { MaterialTheme { ArtistsScreen(viewModel = model, onBack = {}) } }
        compose.awaitUntil("the artists") { model.state.value.artists.isNotEmpty() && !model.state.value.busy }
        compose.waitForIdle()
        return Fixture(holder, model)
    }

    private fun remove(fixture: Fixture, artistId: String) {
        compose.onNodeWithTag(ArtistTags.LIST).performScrollToNode(hasTestTag(ArtistTags.remove(artistId)))
        compose.onNodeWithTag(ArtistTags.remove(artistId)).performClick()
        compose.onNodeWithTag(ArtistTags.CONFIRM_REMOVE).performClick()
        compose.awaitUntil("the removal") { !fixture.model.state.value.busy && fixture.model.state.value.error != null }
        compose.waitForIdle()
    }
}
