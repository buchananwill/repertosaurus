package dev.repertosaurus.session

import dev.repertosaurus.core.NearMatches
import dev.repertosaurus.data.SongCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `Search.kt` (F14 N3/N4) and the one exact-match rule (F17 B4), and `LookupChoice.of` (F14 N5). */
class SearchTest {

    private val artists = listOf(
        SongCatalog.ArtistEntry("a-1", "Dolly Parton", "Dolly Parton", 2),
        SongCatalog.ArtistEntry("a-2", "The Zutons", "Zutons, The", 1),
        SongCatalog.ArtistEntry("a-3", "AC/DC", "AC/DC", 0),
    )

    /** F16 #8: the Artists route's search, over name and sort name, in the list's own order. */
    @Test
    fun artistSearchFiltersInTheListsOrder() {
        assertEquals(artists, ArtistSearch.search(artists, ""))
        assertEquals(listOf("a-2"), ArtistSearch.search(artists, "zutons").map { it.id })
        assertEquals(listOf("a-3"), ArtistSearch.search(artists, "acdc").map { it.id }, "punctuation folded")
        assertEquals(listOf("a-1"), ArtistSearch.search(artists, "parton dolly").map { it.id }, "every word, any order")
        assertEquals(emptyList(), ArtistSearch.search(artists, "beyonce"))
    }

    @Test
    fun theSongLabelDropsABlankArtist() {
        assertEquals("Jolene — Dolly Parton", songLabel("Jolene", "Dolly Parton"))
        assertEquals("Jolene", songLabel("Jolene", null))
        assertEquals("Jolene", songLabel("Jolene", " "))
    }

    /** F17 B4: the row a create-on-enter would land on — by normalised name, never for a blank. */
    @Test
    fun exactFindsTheRowTheNameNormalisesTo() {
        val names = listOf("The Fratellis", "AC/DC")
        assertEquals("The Fratellis", NearMatches.exact("fratellis", names) { it })
        assertEquals("AC/DC", NearMatches.exact("AC DC", names) { it })
        assertNull(NearMatches.exact("Fratelli", names) { it }, "a near miss is not exact")
        assertNull(NearMatches.exact("  ", names) { it })
        assertNull(NearMatches.exact("", listOf("")) { it }, "a blank query names nothing, even a blank row")
    }

    /** F14 N5: a picked id wins; without one, the text is what was typed. */
    @Test
    fun aLookupChoiceIsPickedWhenThereIsAnId() {
        assertEquals(SongCatalog.LookupChoice.Picked("a-1"), SongCatalog.LookupChoice.of("a-1", "Dolly"))
        assertEquals(SongCatalog.LookupChoice.Typed("Dolly"), SongCatalog.LookupChoice.of(null, "Dolly"))
    }
}
