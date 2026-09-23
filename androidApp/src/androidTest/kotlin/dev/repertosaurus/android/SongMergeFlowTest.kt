package dev.repertosaurus.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.repertosaurus.android.EditingFixtures.onMain
import dev.repertosaurus.core.NoteSpelling
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.SampleData
import dev.repertosaurus.data.SongCatalog
import dev.repertosaurus.session.MergeSide
import dev.repertosaurus.session.SongDraft
import dev.repertosaurus.session.SongDraftValidation
import dev.repertosaurus.session.SongField
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Song merge through the UI — repertoire-editing R31-R39** — over a real database: the sample
 * songs plus the user's own acceptance pair, *Shake If Off* (the workbook typo) and *Shake It
 * Off*. The result is read back out of the catalog and the practice log, never only out of the
 * ViewModel.
 */
@RunWith(AndroidJUnit4::class)
class SongMergeFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val harness = SongsRouteHarness(compose, "merge-test")

    @After
    fun cleanUp() {
        harness.cleanUp()
    }

    /**
     * Detail → "Merge with…" → search and pick → preview → deselect one event → confirm → Merge.
     * The typo is removed, its carried events are on the survivor, the dropped one is not, the
     * detail shows the survivor and the list no longer holds the typo.
     */
    @Test
    fun mergingThroughTheUi() {
        val fixture = fixture("flow")
        val itOff = EditingFixtures.song(fixture.holder, "Shake It Off")
        val ifOff = fixture.typo
        val before = fixture.holder.repository.timesPractised(itOff.id)
        val kept = fixture.holder.repository.logPractice(ifOff.id, SampleData.VOCAL, loggedOn = "2026-09-01")
        val dropped = fixture.holder.repository.logPractice(ifOff.id, SampleData.VOCAL, loggedOn = "2026-09-02")

        openDetail(fixture, itOff)
        startMergeAndPick(fixture, ifOff)

        compose.onNodeWithTag(MergeTags.PREVIEW_LIST).performScrollToNode(hasTestTag(MergeTags.event(dropped)))
        compose.onNodeWithTag(MergeTags.event(dropped)).performClick()
        compose.waitForIdle()
        assertEquals(setOf(dropped), fixture.songs.merge.value?.plan?.droppedEvents)
        assertTrue(fixture.songs.merge.value?.plan?.children.orEmpty().isEmpty())

        compose.onNodeWithTag(MergeTags.PREVIEW_LIST).performScrollToNode(hasTestTag(MergeTags.CONFIRM))
        compose.onNodeWithTag(MergeTags.CONFIRM).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MergeTags.DO_MERGE).performClick()
        await("the merge") { fixture.songs.merge.value == null && fixture.songs.state.value.detail?.record != null }
        compose.waitForIdle()

        val catalog = fixture.holder.repository.catalog
        assertNull(catalog.song(ifOff.id), "the typo is removed")
        assertEquals(before + 1, fixture.holder.repository.timesPractised(itOff.id), "one carried, one dropped")
        assertEquals(0L, fixture.holder.repository.timesPractised(ifOff.id))
        assertTrue(fixture.holder.repository.practiceHistory(itOff.id).any { it.loggedOn == "2026-09-01" })
        assertTrue(fixture.holder.repository.practiceHistory(itOff.id).none { it.loggedOn == "2026-09-02" })
        assertNotNull(kept)

        assertEquals(itOff.id, fixture.songs.state.value.detail?.songId, "the detail shows the survivor")
        assertTrue(fixture.songs.state.value.songs.none { it.id == ifOff.id }, "the list reloaded")
        assertEquals("Merged into Shake It Off — Taylor Swift: 1 practice event carried, 1 dropped.", fixture.songs.state.value.message)
        compose.onNodeWithTag(SongDetailTags.DETAIL).assertIsDisplayed()
    }

    /**
     * **Swapping keeps the other one.** Started from the typo, swapped, merged: *Shake It Off*
     * survives, and the detail — opened on the typo — now shows the survivor.
     */
    @Test
    fun aSwappedMergeOpensTheSurvivor() {
        val fixture = fixture("swap")
        val itOff = EditingFixtures.song(fixture.holder, "Shake It Off")
        val ifOff = fixture.typo

        openDetail(fixture, ifOff)
        startMergeAndPick(fixture, itOff)
        assertEquals(ifOff.id, fixture.songs.merge.value?.plan?.survivor?.record?.id, "the song the merge started from survives by default")
        compose.onNodeWithTag(MergeTags.SWAP).performClick()
        compose.waitForIdle()
        assertEquals(itOff.id, fixture.songs.merge.value?.plan?.survivor?.record?.id)
        compose.onNodeWithTag(MergeTags.field(SongField.TITLE, MergeSide.SURVIVOR)).assertIsDisplayed()

        compose.onNodeWithTag(MergeTags.PREVIEW_LIST).performScrollToNode(hasTestTag(MergeTags.CONFIRM))
        compose.onNodeWithTag(MergeTags.CONFIRM).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MergeTags.DO_MERGE).performClick()
        await("the merge") { fixture.songs.merge.value == null && fixture.songs.state.value.detail?.songId == itOff.id }
        compose.waitForIdle()

        assertNull(fixture.holder.repository.catalog.song(ifOff.id))
        assertEquals("Shake It Off", fixture.songs.state.value.detail?.record?.title)
        compose.onNodeWithTag(SongDetailTags.DETAIL).assertIsDisplayed()
    }

    /**
     * **Back closes the preview first** (E24's overlays ruling): preview → picker → detail → list.
     * Nothing is written on the way out.
     */
    @Test
    fun backClosesThePreviewThenThePickerThenTheDetail() {
        val fixture = fixture("back")
        val itOff = EditingFixtures.song(fixture.holder, "Shake It Off")
        val ifOff = fixture.typo

        openDetail(fixture, itOff)
        startMergeAndPick(fixture, ifOff)

        pressBack()
        compose.onNodeWithTag(MergeTags.PICKER).assertIsDisplayed()
        assertNull(fixture.songs.merge.value?.plan)

        pressBack()
        compose.onNodeWithTag(SongDetailTags.DETAIL).assertIsDisplayed()
        assertNull(fixture.songs.merge.value)

        pressBack()
        compose.onNodeWithTag(SongsTags.LIST).assertIsDisplayed()
        assertNotNull(fixture.holder.repository.catalog.song(ifOff.id), "backing out merged nothing")
        assertNotNull(fixture.holder.repository.catalog.song(itOff.id))
    }

    /** "Merge with…" is dead while the detail has unsaved changes: the merge would drop them. */
    @Test
    fun mergeWaitsForUnsavedChanges() {
        val fixture = fixture("dirty")
        val itOff = EditingFixtures.song(fixture.holder, "Shake It Off")

        openDetail(fixture, itOff)
        compose.onNodeWithTag(SongDetailTags.field(SongField.TITLE)).performScrollTo().performTextReplacement("Shake It Off!")
        compose.waitForIdle()

        compose.onNodeWithTag(SongDetailTags.MERGE).performScrollTo().assertIsNotEnabled()
        onMain { fixture.songs.startMerge() }
        compose.waitForIdle()
        assertNull(fixture.songs.merge.value)
    }

    /**
     * **R40-R42 on screen:** a six-sharp song in G. The detail's tonal-centre chip and the merge
     * preview's tonal-centre chip both read G by default (R41), F♯♯ once the setting says as written,
     * and G again when it is flipped back — with no reload, because each surface names the note at
     * render time from the one setting.
     */
    @Test
    fun theTonalCentreFollowsTheNoteSpellingInTheDetailAndTheMergePreview() {
        val fixture = fixture("spelling")
        val itOff = EditingFixtures.song(fixture.holder, "Shake It Off")
        val record = fixture.holder.repository.catalog.song(itOff.id)!!
        val fields = (SongDraft.from(record).copy(keySignature = 6, tonalCentre = 7).validate() as SongDraftValidation.Valid).fields
        assertTrue(fixture.holder.repository.catalog.updateSong(itOff.id, fields) is SongCatalog.SongSave.Saved)
        assertEquals(NoteSpelling.SIMPLIFIED, fixture.session.noteSpelling.value, "R41: simplified by default")

        openDetail(fixture, itOff)
        val g = SongDetailTags.choice(SongField.TONAL_CENTRE, SongDraft.TONAL_CENTRE_CHOICES.indexOf(7))
        compose.onNodeWithTag(SongDetailTags.field(SongField.TONAL_CENTRE)).performScrollTo()
        compose.onNodeWithTag(g).assertTextEquals("G")
        spell(fixture, NoteSpelling.AS_WRITTEN)
        compose.onNodeWithTag(g).assertTextEquals("F♯♯")

        startMergeAndPick(fixture, fixture.typo)
        val survivor = MergeTags.field(SongField.TONAL_CENTRE, MergeSide.SURVIVOR)
        compose.onNodeWithTag(MergeTags.PREVIEW_LIST).performScrollToNode(hasTestTag(survivor))
        compose.onNodeWithTag(survivor).assertTextEquals("F♯♯")
        spell(fixture, NoteSpelling.SIMPLIFIED)
        compose.onNodeWithTag(survivor).assertTextEquals("G")
    }

    // ---- Harness -------------------------------------------------------------------------

    private fun spell(fixture: Fixture, spelling: NoteSpelling) {
        onMain { fixture.session.setNoteSpelling(spelling) }
        compose.waitForIdle()
    }

    /** The shared Songs route ([SongsRouteHarness]) plus the user's typo, *Shake If Off*. */
    private class Fixture(val route: SongsRoute, val typo: SongCatalog.SongListEntry) {
        val holder: DatabaseHolder get() = route.holder
        val songs: SongsViewModel get() = route.songs
        val session: SessionViewModel get() = route.session
    }

    private fun fixture(suffix: String): Fixture {
        val route = harness.route(suffix) { holder ->
            holder.repository.catalog.addSong("Shake If Off", SongCatalog.LookupChoice.Typed("Taylor Swift"))
        }
        harness.show(route)
        return Fixture(route, EditingFixtures.song(route.holder, "Shake If Off"))
    }

    private fun await(what: String, settled: () -> Boolean) = compose.awaitUntil(what, settled)

    private fun openDetail(fixture: Fixture, song: SongCatalog.SongListEntry) = harness.openDetail(fixture.route, song)

    /** "Merge with…", search for [other] by title, pick it, and wait for the preview. */
    private fun startMergeAndPick(fixture: Fixture, other: SongCatalog.SongListEntry) {
        compose.onNodeWithTag(SongDetailTags.MERGE).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MergeTags.PICKER).assertIsDisplayed()
        compose.onNodeWithTag(MergeTags.SEARCH).performTextReplacement("shake")
        compose.waitForIdle()
        compose.onNodeWithTag(MergeTags.candidate(fixture.songs.merge.value!!.songId)).assertDoesNotExist()
        compose.onNodeWithTag(MergeTags.candidate(other.id)).performClick()
        await("the preview") { fixture.songs.merge.value?.plan != null }
        compose.waitForIdle()
        compose.onNodeWithTag(MergeTags.PREVIEW).assertIsDisplayed()
    }

    private fun pressBack() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
}
