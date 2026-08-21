package dev.repertosaurus.session

import dev.repertosaurus.core.Ids
import dev.repertosaurus.core.NearMatches
import dev.repertosaurus.data.RepertosaurusRepository
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
        RepertosaurusRepository.Artist(Ids.derived("artist", name), name, name)

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

    // ---- The performer picker (E46) ---------------------------------------------------------

    private val roster = listOf(
        performer("Will"),
        performer("Coralie"),
        performer("Charlotte"),
        performer("Newton"),
        performer("Alex"),
        performer("Bea"),
        performer("Cass"),
        performer("Dev"),
        performer("Eve"),
        performer("Flo"),
    )

    private fun performer(name: String) =
        RepertosaurusRepository.Performer(Ids.derived("performer", name), name)

    /**
     * **E46: one matcher, one limit.** The capability sheet and the View editor each held a
     * copy of this rule, and their two limit constants disagreed on the search cap for the
     * *same* picker — the identical field offered eight names in one sheet and six in the other.
     */
    @Test
    fun thePerformerPickerBrowsesWhenBlankAndSearchesWhenTyped() {
        assertEquals(
            NearMatches.BROWSE_LIMIT,
            PerformerSuggestions.search("", roster).size,
            "a blank field browses: eight rows, not nothing and not all ten",
        )
        assertEquals(
            NearMatches.BROWSE_LIMIT,
            PerformerSuggestions.search("   ", roster).size,
            "whitespace is blank, not a query that matches nothing",
        )
        assertEquals(listOf("Will"), PerformerSuggestions.search("wil", roster).map { it.name })
        assertEquals(
            listOf("Charlotte"),
            PerformerSuggestions.search("charlote", roster).map { it.name },
            "a typo still surfaces the row it meant, which is the whole point",
        )
        assertTrue(PerformerSuggestions.search("zzz", roster).isEmpty())
    }

    /** The browse limit and the search limit are the same number, and it is the core's. */
    @Test
    fun theOneLimitCapsBothHalvesOfThePicker() {
        val many = (1..20).map { performer("Performer $it") }
        assertEquals(NearMatches.BROWSE_LIMIT, PerformerSuggestions.search("", many).size)
        assertEquals(NearMatches.BROWSE_LIMIT, PerformerSuggestions.search("performer", many).size)
        assertEquals(3, PerformerSuggestions.search("", many, limit = 3).size)
        assertEquals(3, PerformerSuggestions.search("performer", many, limit = 3).size)
    }

    /** The browse rule is generic, because the instrument picker needs the identical branch. */
    @Test
    fun browsingWhenBlankIsOneRuleForEveryPicker() {
        val chips = listOf(
            InstrumentChip("1", "vocal"),
            InstrumentChip("2", "backing vocal"),
            InstrumentChip("3", "guitar"),
        )
        assertEquals(
            listOf("vocal", "backing vocal", "guitar"),
            NearMatches.browseOrSearch("", chips) { it.name }.map { it.name },
            "fewer rows than the limit means all of them",
        )
        assertEquals(
            listOf("vocal"),
            NearMatches.browseOrSearch("", chips, limit = 1) { it.name }.map { it.name },
        )
        assertEquals(
            listOf("backing vocal"),
            NearMatches.browseOrSearch("backing", chips) { it.name }.map { it.name },
        )
    }
}
