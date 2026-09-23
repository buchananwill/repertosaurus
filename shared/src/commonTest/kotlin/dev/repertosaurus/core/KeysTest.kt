package dev.repertosaurus.core

import dev.repertosaurus.core.NoteSpelling.AS_WRITTEN
import dev.repertosaurus.core.NoteSpelling.SIMPLIFIED
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Decisions 32-40, 55, 56, 57. */
class KeysTest {

    // ---- The full 15 x 12 grid (decision 56) ----------------------------------------

    @Test
    fun everyKeySignatureAgainstEveryTransposeStaysInRange() {
        for (keySignature in -7..7) {
            for (transpose in 0..11) {
                val sounding = Keys.soundingKeySignature(keySignature, transpose)
                assertTrue(
                    sounding in Keys.KEY_SIGNATURE_RANGE,
                    "ks=$keySignature transpose=$transpose gave $sounding, outside -7..+7",
                )
            }
        }
    }

    /**
     * The respelling rule only governs a real transposition (decision 56a), so the grid of
     * non-zero transposes is 1..12. At transpose 0 the stored signature is displayed as
     * stored — see [transposeZeroIsTheIdentityForEveryKeySignature].
     */
    @Test
    fun theGridNeverPrefersALargerSpellingThanNecessary() {
        for (keySignature in -7..7) {
            for (transpose in 1..12) {
                val sounding = Keys.soundingKeySignature(keySignature, transpose)
                val alternative = if (sounding > 0) sounding - 12 else sounding + 12
                assertTrue(
                    abs(sounding) <= abs(alternative),
                    "ks=$keySignature transpose=$transpose gave $sounding when " +
                        "$alternative spells the same key with fewer accidentals",
                )
            }
        }
    }

    @Test
    fun theGridIsConsistentWithTheSoundingPitchClass() {
        // A sounding signature and a sounding pitch class must describe the same key, so
        // two routes to the same sounding key must agree.
        for (keySignature in -7..7) {
            for (transpose in 1..12) {
                val once = Keys.soundingKeySignature(keySignature, transpose)
                val twice = Keys.soundingKeySignature(
                    Keys.soundingKeySignature(keySignature, transpose - 1),
                    1,
                )
                assertEquals(once, twice, "ks=$keySignature transpose=$transpose")
            }
        }
    }

    // ---- Transpose 0 is the identity (decision 56a) -----------------------------------

    /**
     * Decision 56a. `key_signature` is already constrained to -7..+7, so at zero there is
     * nothing out of range to reduce. A stored C-sharp major (+7) must display as C-sharp
     * major, not be silently re-spelled to D-flat major.
     */
    @Test
    fun transposeZeroIsTheIdentityForEveryKeySignature() {
        for (keySignature in -7..7) {
            assertEquals(
                keySignature,
                Keys.soundingKeySignature(keySignature, 0),
                "ks=$keySignature must survive transpose 0 unchanged",
            )
        }
    }

    /** The cases the uniform rule used to break: +/-6 and +/-7 at rest. */
    @Test
    fun storedSixAndSevenAccidentalKeysAreNotRespelledAtRest() {
        assertEquals(7, Keys.soundingKeySignature(7, 0))
        assertEquals(-7, Keys.soundingKeySignature(-7, 0))
        assertEquals(6, Keys.soundingKeySignature(6, 0))
        assertEquals(-6, Keys.soundingKeySignature(-6, 0))
        // C-sharp major stays C-sharp major.
        assertEquals("C♯", Keys.soundingKeyName(keySignature = 7, tonalCentre = 1, transpose = 0, AS_WRITTEN))
    }

    // ---- The cases the rule exists for (decision 56) ---------------------------------

    /** C major up a semitone is D-flat major with five flats, NOT C-sharp major with seven sharps. */
    @Test
    fun cMajorUpASemitoneIsDFlatMajorNotCSharpMajor() {
        assertEquals(-5, Keys.soundingKeySignature(0, 1))
        assertEquals(1, Keys.soundingTonalCentre(0, 1))
        assertEquals("D♭", Keys.soundingKeyName(keySignature = 0, tonalCentre = 0, transpose = 1, AS_WRITTEN))
    }

    /** A minor up a semitone is B-flat minor, not A-sharp minor. */
    @Test
    fun aMinorUpASemitoneIsBFlatMinorNotASharpMinor() {
        assertEquals(-5, Keys.soundingKeySignature(0, 1))
        assertEquals(10, Keys.soundingTonalCentre(9, 1))
        assertEquals("B♭", Keys.soundingKeyName(keySignature = 0, tonalCentre = 9, transpose = 1, AS_WRITTEN))
    }

    /** A minor down a tone -> G minor. */
    @Test
    fun aMinorDownAToneIsGMinor() {
        assertEquals(-2, Keys.soundingKeySignature(0, -2))
        assertEquals(7, Keys.soundingTonalCentre(9, -2))
    }

    /** G -> F. */
    @Test
    fun gDownAToneIsF() {
        assertEquals(-1, Keys.soundingKeySignature(1, -2))
        assertEquals(5, Keys.soundingTonalCentre(7, -2))
    }

    /** G -> A-flat. */
    @Test
    fun gUpASemitoneIsAFlat() {
        assertEquals(-4, Keys.soundingKeySignature(1, 1))
        assertEquals(8, Keys.soundingTonalCentre(7, 1))
        assertEquals("A♭", Keys.soundingKeyName(keySignature = 1, tonalCentre = 7, transpose = 1, AS_WRITTEN))
    }

    /** B-flat -> A-flat. */
    @Test
    fun bFlatDownAToneIsAFlat() {
        assertEquals(-4, Keys.soundingKeySignature(-2, -2))
        assertEquals(8, Keys.soundingTonalCentre(10, -2))
    }

    /** B-flat -> B, and B is the five-sharp spelling, not the seven-flat one. */
    @Test
    fun bFlatUpASemitoneIsB() {
        assertEquals(5, Keys.soundingKeySignature(-2, 1))
        assertEquals(11, Keys.soundingTonalCentre(10, 1))
        assertEquals("B", Keys.soundingKeyName(keySignature = -2, tonalCentre = 10, transpose = 1, AS_WRITTEN))
    }

    // ---- The reduction rule itself ----------------------------------------------------

    @Test
    fun fiveAccidentalsAreKeptBecauseSevenIsWorse() {
        assertEquals(5, Keys.reduce(5))
        assertEquals(-5, Keys.reduce(-5))
    }

    @Test
    fun sixVersusMinusSixBreaksTowardFlats() {
        assertEquals(-6, Keys.reduce(6))
        assertEquals(-6, Keys.reduce(-6))
    }

    @Test
    fun sevenAccidentalsRespellAsFive() {
        assertEquals(-5, Keys.reduce(7))
        assertEquals(5, Keys.reduce(-7))
    }

    @Test
    fun signaturesWithinFourAccidentalsAreUntouched() {
        for (keySignature in -4..4) {
            assertEquals(keySignature, Keys.reduce(keySignature))
        }
    }

    // ---- Pitch class arithmetic (decision 55) -----------------------------------------

    @Test
    fun soundingPitchClassWrapsInBothDirections() {
        assertEquals(0, Keys.soundingTonalCentre(11, 1))
        assertEquals(11, Keys.soundingTonalCentre(0, -1))
        assertEquals(4, Keys.soundingTonalCentre(4, 0))
        assertEquals(4, Keys.soundingTonalCentre(4, 12))
        assertEquals(4, Keys.soundingTonalCentre(4, -24))
        for (pitchClass in 0..11) {
            for (transpose in -24..24) {
                assertTrue(Keys.soundingTonalCentre(pitchClass, transpose) in 0..11)
            }
        }
    }

    // ---- Enharmonic spelling (decision 40) ---------------------------------------------

    @Test
    fun spellingIsDerivedFromTheKeySignature() {
        for (spelling in NoteSpelling.entries) {
            assertEquals("F♯", Keys.noteName(6, keySignature = 1, spelling))   // G major
            assertEquals("G♭", Keys.noteName(6, keySignature = -2, spelling))  // B-flat major
            assertEquals("D♭", Keys.noteName(1, keySignature = -5, spelling))
            assertEquals("B", Keys.noteName(11, keySignature = 5, spelling))
            assertEquals("E♭", Keys.noteName(3, keySignature = -3, spelling))
            assertEquals("C", Keys.noteName(0, keySignature = 0, spelling))
            assertEquals("A", Keys.noteName(9, keySignature = 0, spelling))
        }
    }

    @Test
    fun spellingFallsBackToTheFixedTableWhenTheSignatureIsNull() {
        // Decision 40: flats for pitch classes 1, 3, 6, 8 and 10.
        val expected = listOf(
            "C", "D♭", "D", "E♭", "E", "F",
            "G♭", "G", "A♭", "A", "B♭", "B",
        )
        for (spelling in NoteSpelling.entries) {
            for (pitchClass in 0..11) {
                assertEquals(expected[pitchClass], Keys.noteName(pitchClass, keySignature = null, spelling))
            }
        }
    }

    @Test
    fun everyPitchClassIsNameableForEveryKeySignature() {
        for (spelling in NoteSpelling.entries) {
            for (keySignature in -7..7) {
                for (pitchClass in 0..11) {
                    val name = Keys.noteName(pitchClass, keySignature, spelling)
                    assertTrue(name.isNotEmpty())
                    assertTrue(name[0] in 'A'..'G', "bad letter in $name")
                }
            }
        }
    }

    // ---- Note spelling setting (repertoire-editing R40-R42) ------------------------------

    /**
     * **As written is today's behaviour, unchanged.** Every row was captured from the pre-R40
     * `noteName` over the full 15 x 12 grid before the setting existed; it is not re-derived here.
     */
    @Test
    fun asWrittenIsUnchangedFromBeforeTheSetting() {
        for (keySignature in -7..7) {
            val row = (0..11).map { Keys.noteName(it, keySignature, AS_WRITTEN) }
            assertEquals(CAPTURED_BEFORE_R40.getValue(keySignature), row, "ks=$keySignature")
        }
    }

    /** Simplified: the captured grid with exactly its four double accidentals respelled. */
    @Test
    fun simplifiedRespellsOnlyTheDoubleAccidentals() {
        for (keySignature in -7..7) {
            val row = (0..11).map { Keys.noteName(it, keySignature, SIMPLIFIED) }
            val expected = CAPTURED_BEFORE_R40.getValue(keySignature).map { SIMPLIFIED_DOUBLES[it] ?: it }
            assertEquals(expected, row, "ks=$keySignature")
        }
    }

    @Test
    fun simplifiedNeverShowsADoubleAccidentalAndAsWrittenStillCan() {
        val simplified = mutableListOf<String>()
        val asWritten = mutableListOf<String>()
        for (keySignature in -7..7) {
            for (pitchClass in 0..11) {
                simplified += Keys.noteName(pitchClass, keySignature, SIMPLIFIED)
                asWritten += Keys.noteName(pitchClass, keySignature, AS_WRITTEN)
            }
        }
        assertTrue(simplified.none { "♯♯" in it || "♭♭" in it }, "a double accidental survived simplifying")
        assertEquals(setOf("F♯♯", "C♯♯", "B♭♭", "E♭♭"), asWritten.filter { "♯♯" in it || "♭♭" in it }.toSet())
        // No triple accidental arises in either mode.
        assertTrue((simplified + asWritten).none { "♯♯♯" in it || "♭♭♭" in it })
    }

    /** R40's own example: under six sharps the tonal centre G. */
    @Test
    fun gUnderSixSharpsIsFDoubleSharpAsWrittenAndGSimplified() {
        assertEquals("F♯♯", Keys.noteName(7, keySignature = 6, AS_WRITTEN))
        assertEquals("G", Keys.noteName(7, keySignature = 6, SIMPLIFIED))
        assertEquals("F♯♯", Keys.soundingKeyName(keySignature = 6, tonalCentre = 7, transpose = 0, AS_WRITTEN))
        assertEquals("G", Keys.soundingKeyName(keySignature = 6, tonalCentre = 7, transpose = 0, SIMPLIFIED))
    }

    /** Single-accidental spellings are outside the setting: E♯, B♯, F♭ and C♭ stay in both modes. */
    @Test
    fun singleAccidentalSpellingsAreNotSimplified() {
        for (spelling in NoteSpelling.entries) {
            assertEquals("E♯", Keys.noteName(5, keySignature = 6, spelling))
            assertEquals("B♯", Keys.noteName(0, keySignature = 7, spelling))
            assertEquals("F♭", Keys.noteName(4, keySignature = -7, spelling))
            assertEquals("C♭", Keys.noteName(11, keySignature = -7, spelling))
        }
    }

    /** R41, and how the stored preference reads back. */
    @Test
    fun theStoredSpellingReadsBackAndDefaultsToSimplified() {
        assertEquals(SIMPLIFIED, NoteSpelling.DEFAULT)
        assertEquals(SIMPLIFIED, NoteSpelling.fromStored(null), "never chosen")
        assertEquals(SIMPLIFIED, NoteSpelling.fromStored("ENHARMONIC"), "unknown to this build")
        for (spelling in NoteSpelling.entries) {
            assertEquals(spelling, NoteSpelling.fromStored(spelling.name))
        }
    }

    @Test
    fun keySignatureLabelsReadAsDecision32Describes() {
        assertEquals("3♯", Keys.keySignatureLabel(3))
        assertEquals("2♭", Keys.keySignatureLabel(-2))
        assertEquals("0", Keys.keySignatureLabel(0))
    }

    private companion object {
        /**
         * `noteName(pc, ks)` for pc 0..11, **captured from the build before R40** by running the old
         * function over the grid and pasting its output. Pinned so "as written" provably changed
         * nothing; do not regenerate it from the current implementation.
         */
        val CAPTURED_BEFORE_R40: Map<Int, List<String>> = mapOf(
            -7 to listOf("C", "D♭", "E♭♭", "E♭", "F♭", "F", "G♭", "G", "A♭", "B♭♭", "B♭", "C♭"),
            -6 to listOf("C", "D♭", "D", "E♭", "F♭", "F", "G♭", "G", "A♭", "B♭♭", "B♭", "C♭"),
            -5 to listOf("C", "D♭", "D", "E♭", "F♭", "F", "G♭", "G", "A♭", "A", "B♭", "C♭"),
            -4 to listOf("C", "D♭", "D", "E♭", "E", "F", "G♭", "G", "A♭", "A", "B♭", "C♭"),
            -3 to listOf("C", "D♭", "D", "E♭", "E", "F", "G♭", "G", "A♭", "A", "B♭", "B"),
            -2 to listOf("C", "D♭", "D", "E♭", "E", "F", "G♭", "G", "A♭", "A", "B♭", "B"),
            -1 to listOf("C", "D♭", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B"),
            0 to listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B"),
            1 to listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "G♯", "A", "B♭", "B"),
            2 to listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "B♭", "B"),
            3 to listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"),
            4 to listOf("C", "C♯", "D", "D♯", "E", "E♯", "F♯", "G", "G♯", "A", "A♯", "B"),
            5 to listOf("B♯", "C♯", "D", "D♯", "E", "E♯", "F♯", "G", "G♯", "A", "A♯", "B"),
            6 to listOf("B♯", "C♯", "D", "D♯", "E", "E♯", "F♯", "F♯♯", "G♯", "A", "A♯", "B"),
            7 to listOf("B♯", "C♯", "C♯♯", "D♯", "E", "E♯", "F♯", "F♯♯", "G♯", "A", "A♯", "B"),
        )

        /** R40: the natural of the same pitch, for each double the grid produces. */
        val SIMPLIFIED_DOUBLES: Map<String, String> = mapOf(
            "F♯♯" to "G",
            "C♯♯" to "D",
            "B♭♭" to "A",
            "E♭♭" to "D",
        )
    }
}
