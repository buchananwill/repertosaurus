package dev.repertosaurus.session

import dev.repertosaurus.core.RatingKind
import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.data.PartRatings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** triage T3-T5a and T9: the ratings editor's paging, search, filter and performer, on the JVM. */
class RatingsEditorTest {

    // ---- T3: the letter rule -------------------------------------------------------------

    @Test
    fun aLeadingArticleIsDropped() {
        assertEquals('W', SongLetters.of("The Weight"))
        assertEquals('W', SongLetters.of("the weight"))
        assertEquals('D', SongLetters.of("A Day in the Life"))
        assertEquals('I', SongLetters.of("An Innocent Man"))
        assertEquals('W', SongLetters.of("  The   Weight"))
    }

    @Test
    fun anArticleIsOnlyAWholeLeadingWord() {
        assertEquals('T', SongLetters.of("Thunderstruck"))
        assertEquals('T', SongLetters.of("Theory of Everything"))
        assertEquals('A', SongLetters.of("Another Brick in the Wall"))
        assertEquals('A', SongLetters.of("Angie"))
        assertEquals('A', SongLetters.of("A"), "a title that is only an article keeps it")
        assertEquals('T', SongLetters.of("The"))
        assertEquals('V', SongLetters.of("valerie"), "upper case")
    }

    @Test
    fun digitsAndSymbolsFileUnderHash() {
        assertEquals('#', SongLetters.of("9 to 5"))
        assertEquals('#', SongLetters.of("'39"))
        assertEquals('#', SongLetters.of("\"Heroes\""))
        assertEquals('#', SongLetters.of("The 59th Street Bridge Song"))
        assertEquals('#', SongLetters.of(""))
        // Only A-Z are pages (T3); an accented initial is not folded. Flagged in the P8 report.
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
        val state = RatingsEditorState()
        assertEquals('D', state.openLetter(songs))
        assertEquals(listOf("s-dakota", "s-dog"), ids(state.visible(songs)))
        assertEquals('D', state.pinned(songs).letter)

        val withHash = songs + PagedSong("s-925", "9 to 5", "Dolly Parton", set = false)
        assertEquals('#', state.openLetter(withHash), "# leads the strip")
    }

    @Test
    fun aLetterWithNoSongsIsNotAPage() {
        val state = RatingsEditorState()
        assertEquals(setOf('D', 'V', 'W', 'M'), state.letters(songs))
        // A chosen letter with nothing on it falls back to the first that has songs.
        assertEquals('D', state.withLetter('Q').openLetter(songs))
        assertEquals(listOf("s-weight"), ids(state.withLetter('W').visible(songs)), "The Weight is on W")
        assertTrue(state.visible(emptyList()).isEmpty())
        assertNull(state.openLetter(emptyList()))
    }

    // ---- T4: search -------------------------------------------------------------------------

    @Test
    fun aSearchMatchesAcrossLettersInTheGivenOrder() {
        val state = RatingsEditorState().withLetter('V')
        // "the" is in The Zutons, The Band and The Killers — normalise drops a leading "The ", so it
        // matches the Machine's "the" only. The Songs route's rule, not a second one.
        val searched = state.withQuery("the")
        assertTrue(searched.searching)
        assertEquals(listOf("s-dog"), ids(searched.visible(songs)))

        val across = state.withQuery("o")
        assertEquals(listOf("s-dakota", "s-dog", "s-valerie"), ids(across.visible(songs)), "three letters, in order")
        assertEquals(listOf("s-mr"), ids(state.withQuery("brightside").visible(songs)), "by title")
        assertEquals(listOf("s-mr"), ids(state.withQuery("killers").visible(songs)), "by artist")
        assertEquals(listOf("s-dog"), ids(state.withQuery("florence and").visible(songs)), "& is and, as the Songs route has it")
    }

    @Test
    fun clearingTheSearchReturnsToTheLetterThatWasOpen() {
        val onV = RatingsEditorState().withLetter('V').withQuery("dakota")
        assertEquals(listOf("s-dakota"), ids(onV.visible(songs)))
        val cleared = onV.withQuery("")
        assertFalse(cleared.searching)
        assertEquals(listOf("s-valerie"), ids(cleared.visible(songs)))
        assertEquals("", onV.withLetter('M').query, "a letter tapped mid-search ends the search")
    }

    // ---- T5: the filter ---------------------------------------------------------------------

    @Test
    fun theFilterCombinesWithTheLetterAndTheSearch() {
        val all = RatingsEditorState()
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

    // ---- The editor's page -------------------------------------------------------------------

    private val target = RatingsTarget("p-will", "i-guitar", "Will", "Guitar", RatingsSource.EnabledParts)

    private val editorSongs = listOf(
        // Out of title order on purpose: the editor sorts them (T4's title order).
        RatingsSong("s-dog", "Dog Days Are Over", "Florence & the Machine"),
        RatingsSong("s-valerie", "Valerie", "The Zutons"),
        RatingsSong("s-dakota", "Dakota", "Stereophonics"),
    )

    @Test
    fun theTitleNamesThePart() {
        assertEquals("Will · guitar", target.title)
    }

    @Test
    fun aLoadSortsByTitleAndFixesTheFirstPage() {
        val editor = RatingsEditor(target, ticket = 1L).loaded(editorSongs, emptyMap())
        assertFalse(editor.loading)
        assertEquals(listOf("s-dakota", "s-dog", "s-valerie"), editor.songs.map { it.songId })
        assertEquals(listOf("s-dakota", "s-dog"), editor.order)
        assertEquals('D', editor.paging.letter)
    }

    @Test
    fun unratedMeansNeitherRatingIsSet() {
        val editor = RatingsEditor(target, ticket = 1L).loaded(
            editorSongs,
            mapOf(
                "s-dog" to PartRatings(priority = null, confidence = RatingLevel.NOT_AT_ALL),
                "s-valerie" to PartRatings.UNRATED,
            ),
        )
        assertTrue(editor.isRated("s-dog"), "a confidence of 0 is a rating")
        assertFalse(editor.isRated("s-valerie"))
        assertFalse(editor.isRated("s-dakota"), "absent is unrated")
        assertEquals(listOf("s-dakota"), editor.withFilter(PageFilter.UNSET).order)
        assertEquals(listOf("s-dog"), editor.withFilter(PageFilter.SET).order)
    }

    /** RS9 and R7's rule: a rating shows at once and does not take its row off the page. */
    @Test
    fun aRatingDoesNotRefixThePage() {
        val unrated = RatingsEditor(target, ticket = 1L).loaded(editorSongs, emptyMap()).withFilter(PageFilter.UNSET)
        assertEquals(listOf("s-dakota", "s-dog"), unrated.order)

        val rated = unrated.withLevel("s-dakota", RatingKind.PRIORITY, RatingLevel.CERTAINLY)
        assertEquals(RatingLevel.CERTAINLY, rated.levelOf("s-dakota", RatingKind.PRIORITY))
        assertNull(rated.levelOf("s-dakota", RatingKind.CONFIDENCE))
        assertEquals(unrated.order, rated.order, "the rated row stayed for its second rating")

        assertEquals(listOf("s-dog"), rated.withFilter(PageFilter.UNSET).order, "the next fix drops it")
        val cleared = rated.withLevel("s-dakota", RatingKind.PRIORITY, null)
        assertFalse(cleared.isRated("s-dakota"), "a tap on the selected level clears it")
    }

    // ---- T9: whose part ----------------------------------------------------------------------

    private val live = setOf("p-will", "p-coralie")

    @Test
    fun theViewsFilterPerformerComesFirst() {
        assertEquals("p-coralie", RatingsPerformer.resolve("p-coralie", "p-will", live))
    }

    @Test
    fun otherwiseTheOwnerPerformer() {
        assertEquals("p-will", RatingsPerformer.resolve(null, "p-will", live))
    }

    @Test
    fun otherwiseNone() {
        assertNull(RatingsPerformer.resolve(null, null, live))
        assertNull(RatingsPerformer.resolve(null, "p-gone", live), "T10: a soft-deleted owner resolves to none")
        assertNull(RatingsPerformer.resolve("p-gone", "p-will", live), "a removed filter performer is not replaced by the owner")
    }
}
