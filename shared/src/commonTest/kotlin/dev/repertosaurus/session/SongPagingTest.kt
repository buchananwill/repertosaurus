package dev.repertosaurus.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** triage T3-T5a: the letter rule, the paging and the [Page], on the JVM. */
class SongPagingTest {

    // ---- T3: the letter rule -------------------------------------------------------------

    @Test
    fun aLeadingArticleIsDropped() {
        assertEquals('W', SongLetters.of("The Weight"))
        assertEquals('W', SongLetters.of("the weight"))
        assertEquals('D', SongLetters.of("A Day in the Life"))
        assertEquals('I', SongLetters.of("An Innocent Man"))
        assertEquals('W', SongLetters.of("  The   Weight"))
    }

    /** F20 N5: any whitespace after the article, of any kind. */
    @Test
    fun anyWhitespaceEndsTheArticle() {
        assertEquals('W', SongLetters.of("The\tWeight"))
        assertEquals('W', SongLetters.of("The Weight"), "a no-break space")
        assertEquals('D', SongLetters.of("A\n Day in the Life"))
        assertEquals("Weight", SongLetters.withoutArticle("The \t Weight"))
    }

    @Test
    fun anArticleIsOnlyAWholeLeadingWord() {
        assertEquals('T', SongLetters.of("Thunderstruck"))
        assertEquals('T', SongLetters.of("Theory of Everything"))
        assertEquals('A', SongLetters.of("Another Brick in the Wall"))
        assertEquals('A', SongLetters.of("Angie"))
        assertEquals('A', SongLetters.of("A"), "a title that is only an article keeps it")
        assertEquals('T', SongLetters.of("The"))
        assertEquals("The", SongLetters.withoutArticle("The "))
        assertEquals('V', SongLetters.of("valerie"), "upper case")
    }

    @Test
    fun digitsAndSymbolsFileUnderHash() {
        assertEquals('#', SongLetters.of("9 to 5"))
        assertEquals('#', SongLetters.of("'39"))
        assertEquals('#', SongLetters.of("\"Heroes\""))
        assertEquals('#', SongLetters.of("The 59th Street Bridge Song"))
        assertEquals('#', SongLetters.of(""))
        // Only A-Z are pages; an accented initial is not folded (journal session 11, D59 #7).
        assertEquals('#', SongLetters.of("Édith"))
    }

    @Test
    fun theStripIsHashThenAToZ() {
        assertEquals(27, SongLetters.STRIP.size)
        assertEquals('#', SongLetters.STRIP.first())
        assertEquals('A', SongLetters.STRIP[1])
        assertEquals('Z', SongLetters.STRIP.last())
    }

    // ---- T3: pages ----------------------------------------------------------------------

    private val songs = listOf(
        PagedSong("s-dakota", "Dakota", "Stereophonics", set = false),
        PagedSong("s-dog", "Dog Days Are Over", "Florence & the Machine", set = true),
        PagedSong("s-valerie", "Valerie", "The Zutons", set = false),
        PagedSong("s-weight", "The Weight", "The Band", set = true),
        PagedSong("s-mr", "Mr. Brightside", "The Killers", set = false),
    )

    private fun ids(of: List<PagedSong>) = of.map { it.songId }

    @Test
    fun thePageOpensOnTheFirstLetterWithSongs() {
        val paging = SongPaging()
        assertEquals('D', paging.openLetter(songs))
        assertEquals(listOf("s-dakota", "s-dog"), ids(paging.visible(songs)))
        assertEquals('D', paging.pinned(songs).letter)

        val withHash = songs + PagedSong("s-925", "9 to 5", "Dolly Parton", set = false)
        assertEquals('#', paging.openLetter(withHash), "# leads the strip")
    }

    @Test
    fun aLetterWithNoSongsIsNotAPage() {
        val paging = SongPaging()
        assertEquals(setOf('D', 'V', 'W', 'M'), paging.letters(songs))
        assertEquals('D', paging.withLetter('Q').openLetter(songs), "falls back to the first with songs")
        assertEquals(listOf("s-weight"), ids(paging.withLetter('W').visible(songs)), "The Weight is on W")
        assertTrue(paging.visible(emptyList()).isEmpty())
        assertNull(paging.openLetter(emptyList()))
    }

    // ---- T4: search -------------------------------------------------------------------------

    @Test
    fun aSearchMatchesAcrossLettersInTheGivenOrder() {
        val paging = SongPaging().withLetter('V')
        // normalise drops a leading "The ", so "the" matches only the Machine's. The Songs route's rule.
        val searched = paging.withQuery("the")
        assertTrue(searched.searching)
        assertEquals(listOf("s-dog"), ids(searched.visible(songs)))

        assertEquals(listOf("s-dakota", "s-dog", "s-valerie"), ids(paging.withQuery("o").visible(songs)), "three letters, in order")
        assertEquals(listOf("s-mr"), ids(paging.withQuery("brightside").visible(songs)), "by title")
        assertEquals(listOf("s-mr"), ids(paging.withQuery("killers").visible(songs)), "by artist")
        assertEquals(listOf("s-dog"), ids(paging.withQuery("florence and").visible(songs)), "& is and")
    }

    @Test
    fun clearingTheSearchReturnsToTheLetterThatWasOpen() {
        val onV = SongPaging().withLetter('V').withQuery("dakota")
        assertEquals(listOf("s-dakota"), ids(onV.visible(songs)))
        val cleared = onV.withQuery("")
        assertFalse(cleared.searching)
        assertEquals(listOf("s-valerie"), ids(cleared.visible(songs)))
        assertEquals("", onV.withLetter('M').query, "a letter tapped mid-search ends the search")
    }

    // ---- T5: the filter ---------------------------------------------------------------------

    @Test
    fun theFilterCombinesWithTheLetterAndTheSearch() {
        val all = SongPaging()
        assertEquals(PageFilter.ALL, all.filter, "T5: All is the default")

        val unset = all.withFilter(PageFilter.UNSET)
        assertEquals(listOf("s-dakota"), ids(unset.visible(songs)))
        assertEquals(setOf('D', 'V', 'M'), unset.letters(songs), "W has only a set song")

        val set = all.withFilter(PageFilter.SET)
        assertEquals(listOf("s-dog"), ids(set.visible(songs)))
        assertEquals(setOf('D', 'W'), set.letters(songs))
        assertEquals('W', set.withLetter('W').openLetter(songs))
        assertEquals('D', set.withLetter('V').openLetter(songs), "V has no set song, so the first that has")

        assertEquals(listOf("s-dakota", "s-valerie"), ids(unset.withQuery("o").visible(songs)), "with a search")
    }

    // ---- The Page (F21 B1) -------------------------------------------------------------------

    @Test
    fun aPageIsFixedOnlyWhenItsPagingChanges() {
        val page = Page().fixed(songs)
        assertEquals(listOf("s-dakota", "s-dog"), page.order)
        assertEquals('D', page.paging.letter)

        // Dakota is now set; the page does not move until it is fixed again.
        val flagged = songs.map { if (it.songId == "s-dakota") it.copy(set = true) else it }
        val unset = page.withFilter(PageFilter.UNSET, flagged)
        assertEquals('M', unset.paging.letter, "D has nothing unset now, so the first letter that has")
        assertEquals(listOf("s-mr"), unset.order)
        assertEquals(listOf("s-valerie"), unset.withLetter('V', flagged).order)
        assertEquals(listOf("s-valerie", "s-mr"), unset.withQuery("r", flagged).order, "a search, still unset only")
    }
}
