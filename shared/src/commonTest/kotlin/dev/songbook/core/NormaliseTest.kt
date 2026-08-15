package dev.songbook.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** Decision 17, and the consequences decision 4c calls out by name. */
class NormaliseTest {

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
        val inputs = listOf("The Fratellis", "AC/DC", "Florence & the Machine", "cw-duet")
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
