package dev.songbook.core

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
        assertEquals("C♯", Keys.soundingKeyName(keySignature = 7, tonalCentre = 1, transpose = 0))
    }

    // ---- The cases the rule exists for (decision 56) ---------------------------------

    /** C major up a semitone is D-flat major with five flats, NOT C-sharp major with seven sharps. */
    @Test
    fun cMajorUpASemitoneIsDFlatMajorNotCSharpMajor() {
        assertEquals(-5, Keys.soundingKeySignature(0, 1))
        assertEquals(1, Keys.soundingTonalCentre(0, 1))
        assertEquals("D♭", Keys.soundingKeyName(keySignature = 0, tonalCentre = 0, transpose = 1))
    }

    /** A minor up a semitone is B-flat minor, not A-sharp minor. */
    @Test
    fun aMinorUpASemitoneIsBFlatMinorNotASharpMinor() {
        assertEquals(-5, Keys.soundingKeySignature(0, 1))
        assertEquals(10, Keys.soundingTonalCentre(9, 1))
        assertEquals("B♭", Keys.soundingKeyName(keySignature = 0, tonalCentre = 9, transpose = 1))
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
        assertEquals("A♭", Keys.soundingKeyName(keySignature = 1, tonalCentre = 7, transpose = 1))
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
        assertEquals("B", Keys.soundingKeyName(keySignature = -2, tonalCentre = 10, transpose = 1))
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
        assertEquals("F♯", Keys.noteName(6, keySignature = 1))   // G major
        assertEquals("G♭", Keys.noteName(6, keySignature = -2))  // B-flat major
        assertEquals("D♭", Keys.noteName(1, keySignature = -5))
        assertEquals("B", Keys.noteName(11, keySignature = 5))
        assertEquals("E♭", Keys.noteName(3, keySignature = -3))
        assertEquals("C", Keys.noteName(0, keySignature = 0))
        assertEquals("A", Keys.noteName(9, keySignature = 0))
    }

    @Test
    fun spellingFallsBackToTheFixedTableWhenTheSignatureIsNull() {
        // Decision 40: flats for pitch classes 1, 3, 6, 8 and 10.
        val expected = listOf(
            "C", "D♭", "D", "E♭", "E", "F",
            "G♭", "G", "A♭", "A", "B♭", "B",
        )
        for (pitchClass in 0..11) {
            assertEquals(expected[pitchClass], Keys.noteName(pitchClass, keySignature = null))
        }
    }

    @Test
    fun everyPitchClassIsNameableForEveryKeySignature() {
        for (keySignature in -7..7) {
            for (pitchClass in 0..11) {
                val name = Keys.noteName(pitchClass, keySignature)
                assertTrue(name.isNotEmpty())
                assertTrue(name[0] in 'A'..'G', "bad letter in $name")
            }
        }
    }

    @Test
    fun keySignatureLabelsReadAsDecision32Describes() {
        assertEquals("3♯", Keys.keySignatureLabel(3))
        assertEquals("2♭", Keys.keySignatureLabel(-2))
        assertEquals("0", Keys.keySignatureLabel(0))
    }
}
