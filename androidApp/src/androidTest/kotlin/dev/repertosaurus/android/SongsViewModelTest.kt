package dev.repertosaurus.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.android.DatabaseFixtures.count
import dev.repertosaurus.android.EditingFixtures.await
import dev.repertosaurus.android.EditingFixtures.onMain
import dev.repertosaurus.core.Ids
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.session.DetailRequest
import dev.repertosaurus.session.InstrumentEdit
import dev.repertosaurus.session.Messages
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Songs route's state holder, without a screen — safety review F18 N9: tags,
 * `song_instrument`, removal and the `Gone` save, each read back **out of the file**, with the
 * channel each result lands on.
 */
@RunWith(AndroidJUnit4::class)
class SongsViewModelTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()

    @After
    fun cleanUp() {
        for (name in names) DatabaseFixtures.delete(context, name)
    }

    /** R17: a typed tag is created with its row; a tap removes it; a removal that removed nothing says so. */
    @Test
    fun tagsAreAddedRemovedAndAnAlreadyRemovedTagIsSaid() {
        val fixture = fixture("tags")
        onMain { fixture.model.addTagNamed("encore") }
        awaitDetail(fixture)

        val tag = fixture.model.state.value.detail!!.tags.single()
        assertEquals("encore", tag.tagName)
        assertEquals(1L, count(fixture.holder, "song_tag WHERE deleted_at IS NULL"))
        assertNull(fixture.model.state.value.error)

        // Removed underneath the open detail, then tapped: R23d, said on the message channel.
        assertTrue(fixture.holder.repository.catalog.removeTag(tag.id).wrote)
        onMain { fixture.model.toggleTag(tag.tagId) }
        awaitDetail(fixture)
        assertEquals(Messages.ALREADY_REMOVED, fixture.model.state.value.message)
        assertNull(fixture.model.state.value.error, "the channel ruling: already removed is not an error")
        assertTrue(fixture.model.state.value.detail!!.tags.isEmpty())
    }

    /**
     * R19 and F16 #6: an instrument row's edit is held in the detail — so it counts as unsaved
     * work — and Save writes it and clears it.
     */
    @Test
    fun aSongInstrumentEditIsHeldThenSaved() {
        val fixture = fixture("instrument")
        val keys = Ids.derived("instrument", "keys")
        onMain { fixture.model.addSongInstrument(keys) }
        awaitDetail(fixture)
        val row = fixture.model.state.value.detail!!.instruments.single()

        onMain { fixture.model.editSongInstrument(row.id, InstrumentEdit(4L, "Nord Stage", "")) }
        assertTrue(fixture.model.state.value.detail!!.dirty, "an unsaved instrument edit is unsaved work")
        assertFalse(fixture.model.state.value.detail!!.draftDirty)

        onMain { fixture.model.saveSongInstrument(row.id) }
        awaitDetail(fixture)

        val stored = fixture.holder.repository.catalog.songInstruments(fixture.songId).single()
        assertEquals(4L, stored.difficulty)
        assertEquals("Nord Stage", stored.patch)
        assertFalse(fixture.model.state.value.detail!!.dirty, "the saved edit is cleared")

        onMain { fixture.model.removeSongInstrument(row.id) }
        awaitDetail(fixture)
        assertTrue(fixture.model.state.value.detail!!.instruments.isEmpty())
        assertEquals(0L, count(fixture.holder, "song_instrument WHERE deleted_at IS NULL"))
    }

    /** R21: removal closes the detail and says what it removed; the history is kept. */
    @Test
    fun removingTheSongClosesTheDetailAndSaysSo() {
        val fixture = fixture("remove")
        onMain { fixture.model.removeSong() }
        await("the removal") { fixture.model.state.value.detail == null }

        val state = fixture.model.state.value
        assertEquals(DetailRequest.Close, state.detailRequest)
        assertEquals("Removed Valerie — The Zutons. Its practice history is kept.", state.message)
        assertNull(state.error)
        assertEquals(1L, count(fixture.holder, "song WHERE id = '${fixture.songId}' AND deleted_at IS NOT NULL"))
    }

    /** E37 / R14: a save that lands after the song was removed writes nothing, says so, and keeps the draft. */
    @Test
    fun aSaveOnARemovedSongIsGoneAndKeepsTheDraft() {
        val fixture = fixture("gone")
        onMain { fixture.model.editDraft { it.copy(tempoBpm = "99") } }
        assertTrue(fixture.holder.repository.catalog.removeSong(fixture.songId))

        onMain { fixture.model.save() }
        awaitDetail(fixture)

        val state = fixture.model.state.value
        assertEquals(Messages.SAVE_SONG_GONE, state.error)
        assertNull(state.message)
        assertEquals("99", state.detail!!.draft.tempoBpm, "the draft is kept (package 2 ruling #7)")
        assertNull(
            fixture.holder.repository.catalog.song(fixture.songId),
            "the removed song stays removed",
        )
    }

    /**
     * **F28 N4: a merge lands in the database it was previewed from, or in none.** The preview is
     * read, then the holder lets that database go — as an import's swap does — and the confirm is
     * dropped with F18 N2's words rather than merging two songs of a file the user never looked at.
     */
    @Test
    fun aMergePreviewedBeforeTheDatabaseWasReplacedWritesNothing() {
        val fixture = fixture("merge-replaced")
        val other = EditingFixtures.song(fixture.holder, "Shake It Off").id
        onMain { fixture.model.startMerge() }
        onMain { fixture.model.chooseMergeWith(other) }
        await("the preview") { fixture.model.merge.value?.plan != null }

        fixture.holder.close()
        onMain { fixture.model.confirmMerge() }
        // Settled either way: the overlay left open with a reason, or closed by a merge that landed.
        await("the merge") { fixture.model.merge.value?.merging != true }

        assertEquals(Messages.DATABASE_REPLACED, fixture.model.merge.value?.error, "the merge was written after the swap")
        assertNotNull(fixture.holder.repository.catalog.song(fixture.songId))
        assertNotNull(fixture.holder.repository.catalog.song(other))
    }

    // ---- Harness -------------------------------------------------------------------------

    private class Fixture(val holder: DatabaseHolder, val model: SongsViewModel, val songId: String)

    /** A fresh database and the detail open on *Valerie*. */
    private fun fixture(suffix: String): Fixture {
        val name = "songs-model-test-$suffix.db".also { names += it }
        val holder = EditingFixtures.holder(context, name)
        val valerie = EditingFixtures.song(holder, "Valerie").id
        lateinit var model: SongsViewModel
        onMain {
            model = SongsViewModel(holder)
            model.load()
            model.openDetail(valerie)
        }
        val fixture = Fixture(holder, model, valerie)
        awaitDetail(fixture)
        assertNotNull(model.state.value.detail?.record)
        return fixture
    }

    private fun awaitDetail(fixture: Fixture) {
        await("the detail") {
            val detail = fixture.model.state.value.detail
            detail != null && detail.record != null && !detail.busy
        }
    }
}
