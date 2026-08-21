package dev.repertosaurus.core

import kotlin.math.abs

/**
 * Key and transposition arithmetic (decisions 32-40, 55-57).
 *
 * `tonalCentre` is a pitch class 0-11 with 0 = C (decision 33). `keySignature` is a signed
 * sharp/flat count in -7..+7, negative for flats (decision 32). There is deliberately no
 * mode or quality anywhere here: major/minor is implied by the pair (decision 35).
 *
 * This arithmetic lives in the shared core with tests, never in a UI layer (decision 57).
 */
public object Keys {

    /** The legal range of a key signature (decision 32). */
    public val KEY_SIGNATURE_RANGE: IntRange = -7..7

    /**
     * Sounding pitch class after transposition: `(tonal_centre + transpose) mod 12`
     * (decision 55). The song's own key never changes.
     */
    public fun soundingTonalCentre(tonalCentre: Int, transpose: Int): Int =
        ((tonalCentre + transpose) % 12 + 12) % 12

    /**
     * Sounding key signature: `key_signature + 7 * transpose`, reduced into -7..+7
     * (decision 56).
     *
     * **The respelling fires only when [transpose] is non-zero** (decision 56a).
     * `key_signature` is already constrained to -7..+7, so at zero there is nothing out of
     * range to reduce and this must be the identity. Applying [reduce] uniformly would
     * silently re-spell a stored value the user chose deliberately — a song genuinely
     * notated in C-sharp major (+7) would display as D-flat major (-5) and never show what
     * was entered. Display stored data as stored; respell only what transposition moved.
     */
    public fun soundingKeySignature(keySignature: Int, transpose: Int): Int =
        if (transpose == 0) keySignature else reduce(keySignature + 7 * transpose)

    /**
     * Reduce a raw sharp/flat count into -7..+7 by +/-12, then pick the spelling.
     *
     * Where two spellings are legal — any result in {+/-5, +/-6, +/-7} — take the one with
     * the **smaller absolute value**, breaking a 6-versus--6 tie **toward flats**
     * (decision 56). Without this, C major transposed up a semitone renders as C-sharp
     * major with seven sharps rather than D-flat major with five flats.
     */
    public fun reduce(rawKeySignature: Int): Int {
        var signature = rawKeySignature
        while (signature > 7) signature -= 12
        while (signature < -7) signature += 12

        val alternative = if (signature > 0) signature - 12 else signature + 12
        return when {
            abs(alternative) < abs(signature) -> alternative
            abs(alternative) == abs(signature) -> -abs(signature) // tie -> flats
            else -> signature
        }
    }

    /**
     * Enharmonic spelling for display, derived from the key signature (decision 40).
     *
     * When [keySignature] is null there is no context to derive from, so fall back to the
     * fixed table — flats for pitch classes 1, 3, 6, 8 and 10 — so the UI is never unable
     * to name a key.
     */
    public fun noteName(pitchClass: Int, keySignature: Int?): String {
        val pc = ((pitchClass % 12) + 12) % 12
        if (keySignature == null) return FALLBACK_NAMES[pc]

        // Position on the line of fifths: index p has pitch class (7p) mod 12, and 7 is
        // its own inverse mod 12, so the candidates for pc are p == 7*pc (mod 12).
        val base = ((7 * pc) % 12 + 12) % 12
        // A key signature's diatonic notes span fifth indices [ks - 1, ks + 5], centred here.
        val centre = keySignature + 2

        var best = base
        var bestDistance = Int.MAX_VALUE
        var bestAccidentals = Int.MAX_VALUE
        for (k in -3..3) {
            val candidate = base + 12 * k
            val distance = abs(candidate - centre)
            val accidentals = abs(accidentalCount(candidate))
            val better = when {
                distance != bestDistance -> distance < bestDistance
                accidentals != bestAccidentals -> accidentals < bestAccidentals
                else -> candidate < best // tie -> flats
            }
            if (better) {
                best = candidate
                bestDistance = distance
                bestAccidentals = accidentals
            }
        }
        return fifthIndexToName(best)
    }

    /** The sounding key of a set list item, spelled for display (decisions 56, 60). */
    public fun soundingKeyName(keySignature: Int?, tonalCentre: Int, transpose: Int): String {
        val sounding = keySignature?.let { soundingKeySignature(it, transpose) }
        return noteName(soundingTonalCentre(tonalCentre, transpose), sounding)
    }

    /** `3#` / `2b` / `0` as decision 32 describes, using the real accidental glyphs. */
    public fun keySignatureLabel(keySignature: Int): String = when {
        keySignature > 0 -> "$keySignature♯"
        keySignature < 0 -> "${-keySignature}♭"
        else -> "0"
    }

    // Line of fifths: index 0 is C, +1 is G, -1 is F.
    private const val LETTERS = "FCGDAEB"

    private fun accidentalCount(fifthIndex: Int): Int = floorDiv(fifthIndex + 1, 7)

    private fun fifthIndexToName(fifthIndex: Int): String {
        val letter = LETTERS[(((fifthIndex + 1) % 7) + 7) % 7]
        val accidentals = accidentalCount(fifthIndex)
        val suffix = when {
            accidentals > 0 -> "♯".repeat(accidentals)
            accidentals < 0 -> "♭".repeat(-accidentals)
            else -> ""
        }
        return "$letter$suffix"
    }

    private fun floorDiv(dividend: Int, divisor: Int): Int {
        val quotient = dividend / divisor
        return if (dividend % divisor != 0 && (dividend xor divisor) < 0) quotient - 1 else quotient
    }

    /** Decision 40: flats for pitch classes 1, 3, 6, 8 and 10. */
    private val FALLBACK_NAMES = listOf(
        "C", "D♭", "D", "E♭", "E", "F",
        "G♭", "G", "A♭", "A", "B♭", "B",
    )
}
