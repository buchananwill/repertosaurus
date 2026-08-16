package dev.repertaurus.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Decision 17, and the consequences decisions 4c, 4e and 17a call out by name. */
class NormaliseTest {

    // Decision 17a. `e`-acute is either one code point (U+00E9) or `e` followed by a
    // combining acute (U+0065 U+0301), and macOS and iOS input methods routinely emit the
    // decomposed form. The characters are built from their code points rather than typed
    // as literals: the two spellings look identical in an editor, so a literal would leave
    // this test unable to fail, and a file re-encoding could silently rewrite one into the
    // other.
    private val eAcute = Char(0x00E9)
    private val oAcute = Char(0x00F3)
    private val combiningAcute = Char(0x0301)

    private val bubleComposed = "Michael Bubl" + eAcute
    private val bubleDecomposed = "Michael Buble" + combiningAcute
    private val sandeComposed = "Emily Sand" + eAcute
    private val sandeDecomposed = "Emily Sande" + combiningAcute

    /** Decision 17a: NFC runs first, or the two spellings of an accent fork an id. */
    @Test
    fun composedAndDecomposedAccentsNormaliseIdentically() {
        // The inputs really are different strings — the test is worthless otherwise.
        assertNotEquals(bubleComposed, bubleDecomposed)
        assertNotEquals(sandeComposed, sandeDecomposed)

        assertEquals("michael bubl" + eAcute, normalise(bubleComposed))
        assertEquals("michael bubl" + eAcute, normalise(bubleDecomposed))
        assertEquals(normalise(bubleComposed), normalise(bubleDecomposed))

        assertEquals("emily sand" + eAcute, normalise(sandeComposed))
        assertEquals("emily sand" + eAcute, normalise(sandeDecomposed))
        assertEquals(normalise(sandeComposed), normalise(sandeDecomposed))
    }

    /**
     * Decision 4e: accented characters are letters and survive. NFC folds the combining
     * mark back into its letter; the failure mode this guards is the mark surviving to the
     * punctuation pass and leaving `beyonce ` with a stray space.
     */
    @Test
    fun accentsSurviveNormalisation() {
        assertEquals("beyonc" + eAcute, normalise("Beyonc" + eAcute))
        assertEquals("beyonc" + eAcute, normalise("Beyonce" + combiningAcute))
        assertEquals("sigur r" + oAcute + "s", normalise("Sigur R" + oAcute + "s"))
    }

    /**
     * NFC is a no-op on pure ASCII. Every ratified id in decisions 4a and 4b keys on an
     * ASCII name, so if this moves, they all move — see IdsTest.
     */
    @Test
    fun nfcLeavesAsciiAlone() {
        val ascii = listOf(
            "vocal", "backing vocal", "guitar", "bass", "keys",
            "party", "christmas", "cw-duet", "target", "need-to-learn",
            "practice", "twitch", "rehearsal", "gig",
            "The Fratellis", "AC/DC", "Florence & the Machine", "Unknown Artist",
        )
        for (value in ascii) {
            assertEquals(value, unicodeNormalise(value), "NFC must not touch '$value'")
        }
    }

    // The five cases decision 4c states explicitly. These are the contract.
    @Test
    fun decision4cCases() {
        assertEquals("cw duet", normalise("cw-duet"))
        assertEquals("need to learn", normalise("need-to-learn"))
        assertEquals("ac dc", normalise("AC/DC"))
        assertEquals("fratellis", normalise("The Fratellis"))
        assertEquals("florence and the machine", normalise("Florence & the Machine"))
    }

    @Test
    fun lowercasesAndTrims() {
        assertEquals("valerie", normalise("  Valerie  "))
        assertEquals("mr brightside", normalise("MR. BRIGHTSIDE"))
    }

    @Test
    fun stripsOnlyALeadingArticle() {
        assertEquals("killers", normalise("The Killers"))
        assertEquals("zutons", normalise("the zutons"))
        // Not a leading article, and not stripped mid-string.
        assertEquals("all the small things", normalise("All The Small Things"))
        assertEquals("theatre", normalise("Theatre"))
    }

    @Test
    fun foldsAmpersandToAnd() {
        assertEquals("hall and oates", normalise("Hall & Oates"))
        assertEquals("and", normalise("&"))
    }

    @Test
    fun replacesPunctuationWithSpaceRatherThanDeletingIt() {
        // The whole point of decision 4c: deletion would key `cw-duet` on `cwduet` and a
        // user typing "CW Duet" would create a second tag.
        assertEquals("cw duet", normalise("CW Duet"))
        assertEquals("rock n roll", normalise("Rock 'n' Roll"))
        assertEquals("don t stop", normalise("Don't Stop"))
    }

    @Test
    fun collapsesWhitespace() {
        assertEquals("backing vocal", normalise("Backing   Vocal"))
        assertEquals("a b c", normalise("a\t b \n c"))
    }

    @Test
    fun isIdempotent() {
        val inputs = listOf(
            "The Fratellis",
            "AC/DC",
            "Florence & the Machine",
            "cw-duet",
            bubleDecomposed,
        )
        for (input in inputs) {
            assertEquals(normalise(input), normalise(normalise(input)))
        }
    }

    @Test
    fun emptyAndPunctuationOnlyInputsAreEmpty() {
        assertEquals("", normalise(""))
        assertEquals("", normalise("   "))
        assertEquals("", normalise("---"))
    }
}
