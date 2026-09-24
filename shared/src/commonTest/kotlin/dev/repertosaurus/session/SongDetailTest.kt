package dev.repertosaurus.session

import dev.repertosaurus.data.SongCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Songs detail's pure state — R14's "unsaved changes", which since F16 #6 includes
 * `song_instrument` edits, and what a re-read keeps. Moved into the core by style review F17 B1.
 */
class SongDetailTest {

    private val record = SongCatalog.SongRecord(
        id = "s-1", title = "Jolene", artistId = "a-1", artistName = "Dolly Parton",
        referenceRecording = null, keySignature = -4L, tonalCentre = 1L, tonalityNote = null,
        tempoBpm = 110L, durationSeconds = null, decade = null, loopLength = null, chordCount = null,
        chordPattern = null, grooveId = null, grooveName = null, mashupNote = null, notes = null,
        chartUrl = null,
    )

    private val keys = SongCatalog.SongInstrument("si-keys", "i-keys", "keys", difficulty = 3L, patch = "Wavestate", notes = null)
    private val guitar = SongCatalog.SongInstrument("si-guitar", "i-guitar", "guitar", difficulty = null, patch = null, notes = null)

    private fun read(vararg instruments: SongCatalog.SongInstrument) =
        SongDetailRead(record, emptyList(), instruments.toList(), emptyList(), emptyList(), timed = null)

    private fun opened(): SongDetail = SongDetail(songId = "s-1").saved(read(keys, guitar))

    @Test
    fun aFreshlyReadDetailIsNotDirty() {
        val detail = opened()
        assertFalse(detail.dirty)
        assertFalse(detail.draftDirty)
    }

    @Test
    fun aDraftEditIsDirty() {
        val detail = opened().copy(draft = opened().draft.copy(tempoBpm = "112"))
        assertTrue(detail.dirty)
        assertTrue(detail.draftDirty)
    }

    /** **F16 #6: an unsaved `song_instrument` edit is unsaved work**, so the discard prompt asks. */
    @Test
    fun anInstrumentEditIsDirtyButIsNotWhatSaveWrites() {
        val detail = opened().withInstrumentEdit(keys, InstrumentEdit(3L, "Nord", ""))
        assertTrue(detail.dirty, "the discard prompt must see the instrument edit")
        assertFalse(detail.draftDirty, "Save writes the song row only")
        assertEquals(InstrumentEdit(3L, "Nord", ""), detail.instrumentEdit(keys))
        assertEquals(InstrumentEdit.of(guitar), detail.instrumentEdit(guitar))
    }

    /** An edit taken back to the stored values is no edit at all. */
    @Test
    fun anEditBackToTheStoredValuesIsClean() {
        val edited = opened().withInstrumentEdit(keys, InstrumentEdit(5L, "Wavestate", ""))
        val back = edited.withInstrumentEdit(keys, InstrumentEdit.of(keys))
        assertFalse(back.dirty)
    }

    /**
     * A re-read keeps an edit whose row is unchanged underneath it — another row's write must not
     * wipe it — and drops one whose row changed or went, as the fields keyed on the row used to.
     */
    @Test
    fun aRereadKeepsOnlyEditsWhoseRowIsUnchanged() {
        val detail = opened()
            .withInstrumentEdit(keys, InstrumentEdit(5L, "Nord", ""))
            .withInstrumentEdit(guitar, InstrumentEdit(2L, "", "capo 2"))

        val keysSaved = keys.copy(difficulty = 5L, patch = "Nord")
        val after = detail.withChildren(read(keysSaved, guitar))

        assertEquals(setOf("si-guitar"), after.instrumentEdits.keys, "the saved row's edit went; the other stayed")
        assertTrue(after.dirty)
        assertFalse(detail.withChildren(read(keys)).instrumentEdits.containsKey("si-guitar"), "a removed row's edit goes")
    }

    /** A result for a detail that is no longer open never lands. */
    @Test
    fun aResultForAnotherSongIsDropped() {
        val state = SongsState(detail = opened())
        assertSame(state, state.withDetail("s-2") { it.copy(busy = true) })
        assertTrue(state.withDetail("s-1") { it.copy(busy = true) }.detail!!.busy)
    }
}
