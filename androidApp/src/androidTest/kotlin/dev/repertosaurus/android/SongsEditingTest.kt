package dev.repertosaurus.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.repertosaurus.android.EditingFixtures.onMain
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.SongCatalog
import dev.repertosaurus.session.SongDraft
import dev.repertosaurus.session.SongDraftValidation
import dev.repertosaurus.session.SongField
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Songs route, composed over a real database — repertoire-editing R11-R23.
 *
 * Each test drives the real [SongsScreen] with a real [SongsViewModel] and the logger's
 * [SessionViewModel] behind it (R18), through the shared [SongsRouteHarness], and reads the result
 * back **out of the catalog**, not out of the ViewModel: a test that asserted only the state the
 * screen shows would agree with a save that never reached the file.
 */
@RunWith(AndroidJUnit4::class)
class SongsEditingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val harness = SongsRouteHarness(compose, "songs-test")

    @After
    fun cleanUp() {
        harness.cleanUp()
    }

    /** R12, R14: edit one field, Save, and the stored row carries it. */
    @Test
    fun aSavedFieldReadsBack() {
        val route = harness.route("save")
        val valerie = EditingFixtures.song(route.holder, "Valerie")

        harness.openDetail(route, valerie)
        compose.onNodeWithTag(SongDetailTags.field(SongField.TEMPO_BPM))
            .performScrollTo()
            .performTextReplacement("112")
        compose.onNodeWithTag(SongDetailTags.SAVE).performClick()
        harness.awaitDetail(route)

        assertEquals(112L, route.holder.repository.catalog.song(valerie.id)?.tempoBpm, "the save did not reach the row")
        assertEquals("Saved.", route.songs.state.value.message)
        assertNull(route.songs.state.value.error)
        assertTrue(route.songs.state.value.detail?.dirty == false, "a confirmed save left the draft dirty")
        compose.onNodeWithTag(SongDetailTags.field(SongField.TEMPO_BPM)).assertTextContains("112")
    }

    /**
     * R13, R14: a tonal centre outside 0-11 **cannot be saved**. The picker never offers one, so
     * the draft is set through the ViewModel — the path any future control would take. The error
     * shows inline, Save is dead, and even a direct call to `save` writes nothing.
     */
    @Test
    fun anInvalidTonalCentreCannotBeSaved() {
        val route = harness.route("tonal-centre")
        val valerie = EditingFixtures.song(route.holder, "Valerie")
        val stored = route.holder.repository.catalog.song(valerie.id)?.tonalCentre

        harness.openDetail(route, valerie)
        onMain { route.songs.editDraft { it.copy(tonalCentre = 12) } }
        compose.waitForIdle()

        compose.onNodeWithTag(SongDetailTags.SAVE).assertIsNotEnabled()
        compose.onNodeWithText(SongDraft.TONAL_CENTRE_OUT_OF_RANGE).performScrollTo().assertIsDisplayed()

        onMain { route.songs.save() }
        harness.awaitDetail(route)
        assertTrue(route.songs.state.value.error.orEmpty().startsWith("Nothing was saved"))
        assertEquals(stored, route.holder.repository.catalog.song(valerie.id)?.tonalCentre, "an invalid tonal centre reached SQLite")

        // And a typed number that is not one is refused the same way, through the real field.
        onMain { route.songs.editDraft { it.copy(tonalCentre = null) } }
        compose.onNodeWithTag(SongDetailTags.field(SongField.CHORD_COUNT))
            .performScrollTo()
            .performTextReplacement("4a")
        compose.waitForIdle()
        compose.onNodeWithTag(SongDetailTags.SAVE).assertIsNotEnabled()
    }

    /** R14: leaving with unsaved changes asks, and Discard leaves the stored row untouched. */
    @Test
    fun discardingUnsavedChangesLeavesTheRowAlone() {
        val route = harness.route("discard")
        val valerie = EditingFixtures.song(route.holder, "Valerie")

        harness.openDetail(route, valerie)
        compose.onNodeWithTag(SongDetailTags.field(SongField.TITLE))
            .performScrollTo()
            .performTextReplacement("Valerie (edited)")
        compose.onNodeWithTag(SongDetailTags.CLOSE).performClick()
        compose.waitForIdle()

        // Keep editing first: the draft must survive the question.
        compose.onNodeWithTag(SongsTags.KEEP_EDITING).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SongDetailTags.field(SongField.TITLE)).assertTextContains("Valerie (edited)")

        compose.onNodeWithTag(SongDetailTags.CLOSE).performClick()
        compose.onNodeWithTag(SongsTags.DISCARD).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(SongsTags.LIST).assertIsDisplayed()
        assertEquals("Valerie", route.holder.repository.catalog.song(valerie.id)?.title, "discard wrote the draft")

        // Reopening shows the stored row, not the abandoned draft.
        harness.openDetail(route, valerie)
        compose.onNodeWithTag(SongDetailTags.field(SongField.TITLE)).assertTextContains("Valerie")
        assertEquals("Valerie", route.songs.state.value.detail?.draft?.title)
    }

    /**
     * **R22 / R23: re-adding a renamed song says where it went and opens it.** After renaming
     * *Valerie* to *Valerie (live)* its id is still `Valerie`'s, so adding *Valerie* again lands on
     * the renamed row — which must be said, not silently returned.
     */
    @Test
    fun reAddingARenamedSongSaysSoAndOpensIt() {
        val route = harness.route("r23")
        val valerie = EditingFixtures.song(route.holder, "Valerie")
        rename(route.holder, valerie.id, "Valerie (live)")
        onMain { route.songs.load() }

        harness.show(route)
        compose.onNodeWithTag(SongsTags.ADD).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(AddSongTags.TITLE).performTextReplacement("Valerie")
        compose.onNodeWithTag(AddSongTags.ARTIST).performTextReplacement("The Zutons")
        compose.onNodeWithTag(AddSongTags.ADD).performClick()

        compose.awaitUntil("the add") { route.songs.state.value.detail?.record != null }
        compose.waitForIdle()

        val said = route.songs.state.value.message
        assertEquals("Already in the repertoire as Valerie (live) — The Zutons.", said)
        assertEquals(valerie.id, route.songs.state.value.detail?.songId, "the add did not open the song it landed on")
        compose.onNodeWithTag(SongDetailTags.DETAIL).assertIsDisplayed()
        compose.onNodeWithTag(StatusTags.MESSAGE).assertTextContains(said!!)
        compose.onNodeWithTag(AddSongTags.SHEET).assertDoesNotExist()
        assertEquals(8, route.holder.repository.catalog.songs().size, "the re-add created a second song")
    }

    /**
     * **F16 #9: the key-signature picker scrolls its selected chip into view.** The row starts at
     * seven flats, so a song in six sharps would otherwise open with its key off the right edge —
     * and a lazy row does not even compose a chip that far out until it is scrolled to.
     */
    @Test
    fun theKeyPickerOpensScrolledToTheSelectedKey() {
        val route = harness.route("key-scroll")
        val valerie = EditingFixtures.song(route.holder, "Valerie")
        val record = route.holder.repository.catalog.song(valerie.id)!!
        val fields = (SongDraft.from(record).copy(keySignature = 6).validate() as SongDraftValidation.Valid).fields
        assertTrue(route.holder.repository.catalog.updateSong(valerie.id, fields) is SongCatalog.SongSave.Saved)

        harness.openDetail(route, valerie)
        compose.onNodeWithTag(SongDetailTags.field(SongField.KEY_SIGNATURE)).performScrollTo()
        compose.waitForIdle()

        val sixSharps = SongDraft.KEY_SIGNATURE_CHOICES.indexOf(6)
        compose.onNodeWithTag(SongDetailTags.choice(SongField.KEY_SIGNATURE, sixSharps)).assertIsDisplayed()
    }

    // ---- Harness -------------------------------------------------------------------------

    private fun rename(holder: DatabaseHolder, songId: String, title: String) {
        val record = holder.repository.catalog.song(songId)!!
        val fields = (SongDraft.from(record).copy(title = title).validate() as SongDraftValidation.Valid).fields
        assertTrue(holder.repository.catalog.updateSong(songId, fields) is SongCatalog.SongSave.Saved)
    }
}
