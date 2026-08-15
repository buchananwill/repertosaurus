package dev.songbook.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one matcher every type-ahead uses (decision 16).
 *
 * The two failure modes it has to cover are different in kind. A spelling that *normalises*
 * to the same string also derives the same id, so it converges on its own; a genuine typo
 * derives a different id and converges never — which is why the edit-distance pass exists
 * and why it must not be so generous that it hides real distinctions.
 */
class NearMatchesTest {

    @Test
    fun rankPrefersExactThenPrefixThenContains() {
        assertEquals(0, NearMatches.rank("guitar", "guitar"))
        assertEquals(1, NearMatches.rank("gui", "guitar"))
        assertEquals(2, NearMatches.rank("tar", "guitar"))
        assertEquals(3, NearMatches.rank("chiefs kaiser", "kaiser chiefs"))
        assertEquals(4, NearMatches.rank("acdc", "ac dc"))
        assertEquals(5, NearMatches.rank("ukelele", "ukulele"))
        assertNull(NearMatches.rank("trombone", "guitar"))
        assertNull(NearMatches.rank("", "guitar"))
    }

    /** One edit for short values; two only once there is enough text for a slip to show. */
    @Test
    fun theTypoToleranceIsOneEditUntilTheValueIsLong() {
        assertEquals(5, NearMatches.rank("keyz", "keys"))
        // Two edits on a four-letter word would make "bass" and "boss" the same thing.
        assertNull(NearMatches.rank("bosz", "bass"))
        // Thirteen characters: two slips is still plainly the same word.
        assertEquals(5, NearMatches.rank("backin vokal", "backing vocal"))
    }

    @Test
    fun editDistanceCountsEditsAndGivesUpEarly() {
        assertEquals(0, NearMatches.editDistance("ukulele", "ukulele", 2))
        assertEquals(1, NearMatches.editDistance("ukelele", "ukulele", 2))
        assertEquals(2, NearMatches.editDistance("ukelela", "ukulele", 2))
        assertEquals(1, NearMatches.editDistance("guitr", "guitar", 2))
        assertEquals(6, NearMatches.editDistance("guitar", "keys", 8), "no letters in common")
        assertEquals(3, NearMatches.editDistance("a", "abcdefgh", 2), "gives up: the cap plus one")
        assertEquals(1, NearMatches.editDistance("", "a", 2))
        assertEquals(3, NearMatches.editDistance("abc", "", 5))
    }

    @Test
    fun searchRanksAndLimits() {
        val values = listOf("Ukulele", "Ukulele Bass", "Guitar", "Keys")

        assertEquals(listOf("Ukulele", "Ukulele Bass"), NearMatches.search("ukulele", values) { it })
        assertEquals(listOf("Ukulele"), NearMatches.search("ukelele", values) { it })
        assertEquals(listOf("Ukulele"), NearMatches.search("ukulele", values, limit = 1) { it })
        assertTrue(NearMatches.search("  ", values) { it }.isEmpty())
    }
}
