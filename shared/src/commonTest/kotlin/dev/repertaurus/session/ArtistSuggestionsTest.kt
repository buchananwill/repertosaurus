package dev.repertaurus.session

import dev.repertaurus.core.Ids
import dev.repertaurus.data.RepertaurusRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The artist type-ahead's near-match pass — decisions 16 and 17, and the mechanism the
 * whole artist-normalisation design rests on.
 *
 * The two cases named in the design are here explicitly, because a type-ahead that only
 * prefix-matches the raw string fails both and lets the user create the second `Fratellis`
 * that no merge rule can reconcile.
 */
class ArtistSuggestionsTest {

    private fun artist(name: String) =
        RepertaurusRepository.Artist(Ids.derived("artist", name), name, name)

    private val library = listOf(
        artist("The Fratellis"),
        artist("AC/DC"),
        artist("Florence & the Machine"),
        artist("The Kaiser Chiefs"),
        artist("Taylor Swift"),
        artist("Stereophonics"),
    )

    private fun search(query: String) = ArtistSuggestions.search(query, library).map { it.name }

    /** The leading article is stripped by normalise, so the short form finds the long one. */
    @Test
    fun typingFratellisSurfacesTheFratellis() {
        assertEquals("The Fratellis", search("Fratellis").first())
        assertEquals("The Fratellis", search("fratellis").first())
        assertEquals("The Fratellis", search("frat").first())
        assertEquals("The Fratellis", search("The Fratellis").first())
    }

    /** Punctuation becomes a space, so the slash is not an obstacle in either direction. */
    @Test
    fun typingAcDcSurfacesAcSlashDc() {
        assertEquals("AC/DC", search("AC DC").first())
        assertEquals("AC/DC", search("ac dc").first())
        assertEquals("AC/DC", search("AC/DC").first())
        // Even run together: matching may be lossy, derivation may not (decision 17c).
        assertEquals("AC/DC", search("acdc").first())
    }

    /** `&` folds to `and`, so either spelling finds the stored one. */
    @Test
    fun ampersandAndTheWordAndAreTheSameArtist() {
        assertEquals("Florence & the Machine", search("Florence and the Machine").first())
        assertEquals("Florence & the Machine", search("florence & the machine").first())
        assertEquals("Florence & the Machine", search("machine").first())
    }

    @Test
    fun everyWordTypedHasToAppearSomewhere() {
        assertEquals(listOf("The Kaiser Chiefs"), search("kaiser chiefs"))
        assertEquals(listOf("The Kaiser Chiefs"), search("chiefs kaiser"))
        assertTrue(search("kaiser swift").isEmpty())
    }

    @Test
    fun anExactNormalisedMatchOutranksAContainsMatch() {
        val extended = library + artist("The Fratellis Live")
        val ranked = ArtistSuggestions.search("fratellis", extended).map { it.name }
        assertEquals("The Fratellis", ranked.first())
        assertTrue(ranked.contains("The Fratellis Live"))
    }

    @Test
    fun anEmptyQuerySuggestsNothing() {
        assertTrue(search("").isEmpty())
        assertTrue(search("   ").isEmpty())
        assertTrue(search("!!!").isEmpty(), "punctuation alone normalises to nothing")
    }

    @Test
    fun theSuggestionLimitIsRespected() {
        val many = (1..20).map { artist("Artist $it") }
        assertEquals(6, ArtistSuggestions.search("artist", many).size)
        assertEquals(3, ArtistSuggestions.search("artist", many, limit = 3).size)
    }

    /**
     * The suggestion is a courtesy; the id is the guarantee. A name that normalises to an
     * existing one derives that artist's id whether or not the user noticed the chip.
     */
    @Test
    fun aNearMatchDerivesTheSameIdAsTheStoredArtist() {
        assertEquals(Ids.derived("artist", "The Fratellis"), Ids.derived("artist", "Fratellis"))
        assertEquals(Ids.derived("artist", "AC/DC"), Ids.derived("artist", "AC DC"))
        assertEquals(
            Ids.derived("artist", "Florence & the Machine"),
            Ids.derived("artist", "florence and the machine"),
        )
    }
}
