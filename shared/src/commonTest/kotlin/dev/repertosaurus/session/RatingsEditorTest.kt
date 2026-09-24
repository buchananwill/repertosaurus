package dev.repertosaurus.session

import dev.repertosaurus.core.RatingKind
import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.data.PartRatings
import dev.repertosaurus.data.RepertosaurusRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** triage T1-T5 and T9: the ratings editor's state and whose part it rates, on the JVM. */
class RatingsEditorTest {

    private val target = RatingsTarget("p-will", "i-guitar", "Will", "Guitar", RatingsSource.EnabledParts)

    private val songs = listOf(
        // Out of title order on purpose: the editor sorts them (T4's title order).
        RatingsSong("s-dog", "Dog Days Are Over", "Florence & the Machine"),
        RatingsSong("s-valerie", "Valerie", "The Zutons"),
        RatingsSong("s-dakota", "Dakota", "Stereophonics"),
    )

    private fun loaded(ratings: Map<String, PartRatings> = emptyMap()) =
        RatingsEditor(target, ticket = 1L).loaded(songs, ratings)

    @Test
    fun theTitleNamesThePart() {
        assertEquals("Will · guitar", target.title)
    }

    @Test
    fun aLoadSortsByTitleAndFixesTheFirstPage() {
        val editor = loaded()
        assertFalse(editor.loading)
        assertEquals(listOf("s-dakota", "s-dog", "s-valerie"), editor.songs.map { it.songId })
        assertEquals(listOf("s-dakota", "s-dog"), editor.page.order)
        assertEquals('D', editor.page.paging.letter)
    }

    @Test
    fun unratedMeansNeitherRatingIsSet() {
        val editor = loaded(
            mapOf(
                "s-dog" to PartRatings(priority = null, confidence = RatingLevel.NOT_AT_ALL),
                "s-valerie" to PartRatings.UNRATED,
            ),
        )
        assertTrue(editor.isRated("s-dog"), "a confidence of 0 is a rating")
        assertFalse(editor.isRated("s-valerie"))
        assertFalse(editor.isRated("s-dakota"), "absent is unrated")
        assertEquals(listOf("s-dakota"), editor.withFilter(PageFilter.UNSET).page.order)
        assertEquals(listOf("s-dog"), editor.withFilter(PageFilter.SET).page.order)
    }

    /** RS9 and R7's rule: a tap shows at once and does not take its row off the page. */
    @Test
    fun aTapDoesNotRefixThePage() {
        val unrated = loaded().withFilter(PageFilter.UNSET)
        assertEquals(listOf("s-dakota", "s-dog"), unrated.page.order)

        val rated = unrated.tapped("s-dakota", RatingKind.PRIORITY, RatingLevel.CERTAINLY)
        assertEquals(RatingLevel.CERTAINLY, rated.levelOf("s-dakota", RatingKind.PRIORITY))
        assertNull(rated.levelOf("s-dakota", RatingKind.CONFIDENCE))
        assertEquals(unrated.page, rated.page, "the rated row stayed for its second rating")
        assertEquals(listOf("s-dog"), rated.withFilter(PageFilter.UNSET).page.order, "the next fix drops it")
    }

    // ---- F20 B1: only a key's latest tap decides what it shows ------------------------------

    @Test
    fun anOlderLandingDoesNotOverwriteANewerTap() {
        val first = loaded().tapped("s-dakota", RatingKind.PRIORITY, RatingLevel.SOMEWHAT)
        val second = first.tapped("s-dakota", RatingKind.PRIORITY, RatingLevel.EXCEPTIONALLY)
        assertEquals(1L, first.lastTap)
        assertEquals(2L, second.lastTap)

        val landedFirst = second.landed("s-dakota", RatingKind.PRIORITY, tap = 1L, RatingLevel.SOMEWHAT, wrote = true)
        assertEquals(RatingLevel.EXCEPTIONALLY, landedFirst.levelOf("s-dakota", RatingKind.PRIORITY), "the newer tap still shows")
        assertEquals(RatingLevel.SOMEWHAT, landedFirst.stored["s-dakota"]?.priority)

        val landedSecond = landedFirst.landed("s-dakota", RatingKind.PRIORITY, tap = 2L, RatingLevel.EXCEPTIONALLY, wrote = true)
        assertEquals(RatingLevel.EXCEPTIONALLY, landedSecond.levelOf("s-dakota", RatingKind.PRIORITY))
        assertEquals(RatingLevel.EXCEPTIONALLY, landedSecond.stored["s-dakota"]?.priority)
    }

    @Test
    fun aFailedLatestTapShowsWhatWasLastStored() {
        val start = loaded(mapOf("s-dakota" to PartRatings(priority = RatingLevel.CERTAINLY, confidence = null)))
        // The first write lands; the second, the latest, fails: the display is the first's level.
        val tapped = start
            .tapped("s-dakota", RatingKind.PRIORITY, RatingLevel.SOMEWHAT)
            .tapped("s-dakota", RatingKind.PRIORITY, null)
        val afterFirst = tapped.landed("s-dakota", RatingKind.PRIORITY, tap = 1L, RatingLevel.SOMEWHAT, wrote = true)
        assertNull(afterFirst.levelOf("s-dakota", RatingKind.PRIORITY), "the clear still shows while it is in flight")
        val afterSecond = afterFirst.landed("s-dakota", RatingKind.PRIORITY, tap = 2L, null, wrote = false)
        assertEquals(RatingLevel.SOMEWHAT, afterSecond.levelOf("s-dakota", RatingKind.PRIORITY))
    }

    @Test
    fun twoFailuresShowTheLoadedLevel() {
        val tapped = loaded(mapOf("s-dakota" to PartRatings(priority = RatingLevel.CERTAINLY, confidence = null)))
            .tapped("s-dakota", RatingKind.PRIORITY, RatingLevel.SOMEWHAT)
            .tapped("s-dakota", RatingKind.PRIORITY, RatingLevel.NOT_AT_ALL)
        val landed = tapped
            .landed("s-dakota", RatingKind.PRIORITY, tap = 1L, RatingLevel.SOMEWHAT, wrote = false)
            .landed("s-dakota", RatingKind.PRIORITY, tap = 2L, RatingLevel.NOT_AT_ALL, wrote = false)
        assertEquals(RatingLevel.CERTAINLY, landed.levelOf("s-dakota", RatingKind.PRIORITY))
    }

    @Test
    fun theTwoKindsAreSeparateKeys() {
        val tapped = loaded()
            .tapped("s-dakota", RatingKind.PRIORITY, RatingLevel.SOMEWHAT)
            .tapped("s-dakota", RatingKind.CONFIDENCE, RatingLevel.CERTAINLY)
        val landed = tapped.landed("s-dakota", RatingKind.PRIORITY, tap = 1L, RatingLevel.SOMEWHAT, wrote = false)
        assertNull(landed.levelOf("s-dakota", RatingKind.PRIORITY), "priority's own latest tap failed")
        assertEquals(RatingLevel.CERTAINLY, landed.levelOf("s-dakota", RatingKind.CONFIDENCE), "confidence untouched")
    }

    // ---- T9: whose part ----------------------------------------------------------------------

    private val performers = listOf(
        RepertosaurusRepository.Performer("p-will", "Will"),
        RepertosaurusRepository.Performer("p-coralie", "Coralie"),
    )

    @Test
    fun theViewsFilterPerformerComesFirst() {
        assertEquals(
            PartResolution.Resolved(ResolvedPart("p-coralie", "Coralie", "i-vocal")),
            RatingsPerformer.resolve("p-coralie", "p-will", "i-vocal", performers),
        )
    }

    @Test
    fun otherwiseTheOwnerPerformer() {
        assertEquals(
            PartResolution.Resolved(ResolvedPart("p-will", "Will", "i-vocal")),
            RatingsPerformer.resolve(null, "p-will", "i-vocal", performers),
        )
    }

    @Test
    fun otherwiseNone() {
        assertEquals(PartResolution.None, RatingsPerformer.resolve(null, null, "i-vocal", performers))
        assertEquals(PartResolution.None, RatingsPerformer.resolve(null, "p-gone", "i-vocal", performers), "T10: a soft-deleted owner")
        assertEquals(
            PartResolution.None,
            RatingsPerformer.resolve("p-gone", "p-will", "i-vocal", performers),
            "a removed filter performer is not replaced by the owner",
        )
    }

    /** F20 N2: performers not yet read is not "nobody". */
    @Test
    fun unreadPerformersArePending() {
        assertEquals(PartResolution.Pending, RatingsPerformer.resolve(null, "p-will", "i-vocal", null))
        assertEquals(PartResolution.None, RatingsPerformer.resolve(null, "p-will", "i-vocal", emptyList()))
    }
}
