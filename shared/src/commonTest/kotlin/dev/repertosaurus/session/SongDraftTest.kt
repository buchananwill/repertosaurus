package dev.repertosaurus.session

import dev.repertosaurus.data.SongCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** repertoire-editing R12-R14: the song detail's validation, mirroring `song.sq`'s CHECKs. */
class SongDraftTest {

    private val valid = SongDraft(title = "Jolene", artistName = "Dolly Parton")

    private fun fields(draft: SongDraft): SongCatalog.SongFields =
        assertIs<SongDraftValidation.Valid>(draft.validate()).fields

    private fun errors(draft: SongDraft): Map<SongField, String> =
        assertIs<SongDraftValidation.Invalid>(draft.validate()).errors

    // ---- key_signature BETWEEN -7 AND 7 -------------------------------------------------

    @Test
    fun keySignatureAcceptsBothEndsOfItsRange() {
        assertEquals(-7L, fields(valid.copy(keySignature = -7)).keySignature)
        assertEquals(7L, fields(valid.copy(keySignature = 7)).keySignature)
        assertNull(fields(valid.copy(keySignature = null)).keySignature)
    }

    @Test
    fun keySignatureRefusesJustOutsideItsRange() {
        assertEquals(
            mapOf(SongField.KEY_SIGNATURE to SongDraft.KEY_SIGNATURE_OUT_OF_RANGE),
            errors(valid.copy(keySignature = -8)),
        )
        assertEquals(
            mapOf(SongField.KEY_SIGNATURE to SongDraft.KEY_SIGNATURE_OUT_OF_RANGE),
            errors(valid.copy(keySignature = 8)),
        )
    }

    // ---- tonal_centre BETWEEN 0 AND 11 --------------------------------------------------

    @Test
    fun tonalCentreAcceptsBothEndsOfItsRange() {
        assertEquals(0L, fields(valid.copy(tonalCentre = 0)).tonalCentre)
        assertEquals(11L, fields(valid.copy(tonalCentre = 11)).tonalCentre)
    }

    @Test
    fun tonalCentreRefusesJustOutsideItsRange() {
        assertEquals(setOf(SongField.TONAL_CENTRE), errors(valid.copy(tonalCentre = -1)).keys)
        assertEquals(setOf(SongField.TONAL_CENTRE), errors(valid.copy(tonalCentre = 12)).keys)
    }

    /** R13: the pickers offer exactly the CHECK ranges. */
    @Test
    fun thePickerChoicesAreTheCheckRanges() {
        assertEquals((-7..7).toList(), SongDraft.KEY_SIGNATURE_CHOICES)
        assertEquals((0..11).toList(), SongDraft.TONAL_CENTRE_CHOICES)
    }

    // ---- Mandatory fields (R13) ---------------------------------------------------------

    @Test
    fun titleAndArtistAreRequiredAndNothingElseIs() {
        assertEquals(
            mapOf(SongField.TITLE to SongDraft.REQUIRED, SongField.ARTIST to SongDraft.REQUIRED),
            errors(SongDraft(title = "  ", artistName = "")),
        )
        assertTrue(valid.errors().isEmpty(), "every other field may be blank")
    }

    // ---- Blank → null, numbers ----------------------------------------------------------

    @Test
    fun blankOptionalsBecomeNull() {
        val out = fields(
            valid.copy(
                referenceRecording = " ",
                tonalityNote = "",
                tempoBpm = "  ",
                durationSeconds = "",
                decade = "",
                loopLength = " ",
                chordCount = "",
                chordPattern = "   ",
                grooveName = " ",
                grooveId = "picked-but-cleared",
                mashupNote = "",
                notes = " ",
                chartUrl = "",
            ),
        )
        assertNull(out.referenceRecording)
        assertNull(out.tonalityNote)
        assertNull(out.tempoBpm)
        assertNull(out.durationSeconds)
        assertNull(out.decade)
        assertNull(out.loopLength)
        assertNull(out.chordCount)
        assertNull(out.chordPattern)
        assertNull(out.groove, "a blank groove clears it, whatever id was picked")
        assertNull(out.mashupNote)
        assertNull(out.notes)
        assertNull(out.chartUrl)
    }

    @Test
    fun numbersParseTrimmedAndTextIsTrimmed() {
        val out = fields(
            valid.copy(
                title = "  Jolene ",
                tempoBpm = " 110 ",
                durationSeconds = "161",
                decade = "1970",
                loopLength = "4",
                chordCount = "0",
                notes = " capo 4 ",
            ),
        )
        assertEquals("Jolene", out.title)
        assertEquals(110L, out.tempoBpm)
        assertEquals(161L, out.durationSeconds)
        assertEquals(1970L, out.decade)
        assertEquals(4L, out.loopLength)
        assertEquals(0L, out.chordCount)
        assertEquals("capo 4", out.notes)
    }

    @Test
    fun aNonNumberInAnIntegerFieldIsAnErrorNotAThrow() {
        val draft = valid.copy(
            tempoBpm = "12a",
            durationSeconds = "2:41",
            decade = "70s",
            loopLength = "4.5",
            chordCount = "99999999999999999999",
        )
        assertEquals(
            setOf(
                SongField.TEMPO_BPM,
                SongField.DURATION_SECONDS,
                SongField.DECADE,
                SongField.LOOP_LENGTH,
                SongField.CHORD_COUNT,
            ),
            errors(draft).keys,
        )
        assertTrue(errors(draft).values.all { it == SongDraft.NOT_A_NUMBER })
    }

    // ---- Lookups (R16) ------------------------------------------------------------------

    @Test
    fun aPickedLookupIsCarriedByIdAndATypedOneByName() {
        val picked = fields(valid.copy(artistId = "a-1", grooveName = "Shuffle", grooveId = "g-1"))
        assertEquals(SongCatalog.LookupChoice.Picked("a-1"), picked.artist)
        assertEquals(SongCatalog.LookupChoice.Picked("g-1"), picked.groove)

        val typed = fields(valid.copy(artistName = " Dolly ", grooveName = " Train beat "))
        assertEquals(SongCatalog.LookupChoice.Typed("Dolly"), typed.artist)
        assertEquals(SongCatalog.LookupChoice.Typed("Train beat"), typed.groove)
    }

    // ---- from(record) -------------------------------------------------------------------

    @Test
    fun aDraftFromARecordIsUnchangedUntilEditedAndRoundTrips() {
        val record = SongCatalog.SongRecord(
            id = "s-1",
            title = "Jolene",
            artistId = "a-1",
            artistName = "Dolly Parton",
            referenceRecording = null,
            keySignature = -4L,
            tonalCentre = 1L,
            tonalityNote = null,
            tempoBpm = 110L,
            durationSeconds = null,
            decade = 1970L,
            loopLength = null,
            chordCount = 3L,
            chordPattern = null,
            grooveId = "g-1",
            grooveName = "Train beat",
            mashupNote = null,
            notes = "capo 4",
            chartUrl = null,
        )
        val draft = SongDraft.from(record)

        assertEquals(draft, SongDraft.from(record), "R14: unsaved-changes is inequality")
        assertFalse(draft == draft.copy(notes = "capo 5"))
        val out = fields(draft)
        assertEquals(SongCatalog.LookupChoice.Picked("a-1"), out.artist)
        assertEquals(SongCatalog.LookupChoice.Picked("g-1"), out.groove)
        assertEquals(-4L, out.keySignature)
        assertEquals(1L, out.tonalCentre)
        assertEquals(110L, out.tempoBpm)
        assertNull(out.durationSeconds)
        assertEquals(1970L, out.decade)
        assertEquals(3L, out.chordCount)
        assertEquals("capo 4", out.notes)
    }

    // ---- F22 N8: SongField-keyed accessors ----------------------------------------------

    private val survivor = SongDraft(
        title = "Shake It Off",
        artistName = "Taylor Swift",
        artistId = "a-taylor",
        keySignature = 0,
        tonalCentre = null,
        tempoBpm = "160",
        grooveName = "",
        notes = "  ",
    )

    private val loser = SongDraft(
        title = "Shake If Off",
        artistName = "T. Swift",
        artistId = "a-typo",
        keySignature = -1,
        tonalCentre = 7,
        tempoBpm = "",
        grooveName = "Shuffle",
        grooveId = "g-shuffle",
        notes = "capo 2",
    )

    /** `value` is the draft's own value per field, null when the field is empty — blank text included. */
    @Test
    fun valueReadsEachFieldAndIsNullWhenEmpty() {
        assertEquals("Shake It Off", survivor.value(SongField.TITLE))
        assertEquals("Taylor Swift", survivor.value(SongField.ARTIST))
        assertEquals("0", survivor.value(SongField.KEY_SIGNATURE), "a picked 0 is a value, not empty")
        assertNull(survivor.value(SongField.TONAL_CENTRE))
        assertEquals("160", survivor.value(SongField.TEMPO_BPM))
        assertNull(survivor.value(SongField.GROOVE))
        assertNull(survivor.value(SongField.NOTES), "whitespace is empty")
        assertEquals("7", loser.value(SongField.TONAL_CENTRE))
        assertEquals("Shuffle", loser.value(SongField.GROOVE))
    }

    /**
     * `with` takes exactly one field from the other draft — the artist and the groove with their
     * picked ids — and leaves every other field alone. Taking every field turns one draft into
     * the other, which is the exhaustiveness check.
     */
    @Test
    fun withTakesOneFieldAndItsPickedId() {
        val artist = survivor.with(SongField.ARTIST, loser)
        assertEquals(survivor.copy(artistName = "T. Swift", artistId = "a-typo"), artist)

        val groove = survivor.with(SongField.GROOVE, loser)
        assertEquals(survivor.copy(grooveName = "Shuffle", grooveId = "g-shuffle"), groove)

        assertEquals(survivor.copy(tonalCentre = 7), survivor.with(SongField.TONAL_CENTRE, loser))
        assertEquals(survivor.copy(tempoBpm = ""), survivor.with(SongField.TEMPO_BPM, loser))

        val everything = SongField.entries.fold(survivor) { draft, field -> draft.with(field, loser) }
        assertEquals(loser, everything)
        for (field in SongField.entries) {
            assertEquals(loser.value(field), survivor.with(field, loser).value(field), field.name)
        }
    }

    // ---- The label and the search (R3, R11) ---------------------------------------------

    @Test
    fun theSongLabelIsTitleDashArtist() {
        assertEquals("Jolene — Dolly Parton", songLabel("Jolene", "Dolly Parton"))
        assertEquals("Jolene", songLabel("Jolene", null))
        assertEquals("Jolene", songLabel("Jolene", " "))
    }

    @Test
    fun songSearchMatchesEveryWordAcrossTitleAndArtist() {
        assertTrue(SongSearch.matches("", "Henrietta", "The Fratellis"))
        assertTrue(SongSearch.matches("fratellis henrietta", "Henrietta", "The Fratellis"))
        assertTrue(SongSearch.matches("acdc", "Back in Black", "AC/DC"))
        assertTrue(SongSearch.matches("JOL", "Jolene", null))
        assertFalse(SongSearch.matches("valerie", "Jolene", "Dolly Parton"))
    }
}
