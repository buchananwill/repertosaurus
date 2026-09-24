package dev.repertosaurus.session

import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.data.ArtistResolution
import dev.repertosaurus.data.JunctionWrite
import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.data.Resolution
import dev.repertosaurus.data.SongCatalog
import dev.repertosaurus.data.SongResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one core [Messages] (style review F17 B2, F19) — the words, and above all **the channel**
 * each goes on. The channel ruling (session 09): an R23d "already removed" is the message
 * channel; a refusal is the error channel.
 */
class MessagesTest {

    private fun add(resolution: Resolution, title: String = "Jolene", artist: String = "Dolly Parton", titleDiffers: Boolean = false, artistDiffers: Boolean = false) =
        SongCatalog.SongAdd(
            song = SongResolution(resolution, "s-1", title, artist),
            artist = ArtistResolution(Resolution.EXISTING, "a-1", artist),
            titleDiffers = titleDiffers,
            artistDiffers = artistDiffers,
        )

    @Test
    fun alreadyRemovedIsTheMessageChannelEverywhere() {
        assertEquals(Notice.message("That was already removed."), Messages.junctionWrite(JunctionWrite.Removed("r", wrote = false)))
        assertEquals(Notice.Channel.MESSAGE, Messages.songRemoval("Jolene — Dolly Parton", removed = false).channel)
        assertEquals(Notice.Channel.MESSAGE, Messages.artistRemoval("Dolly", SongCatalog.ArtistRemoval.Gone).channel)
        assertEquals(Notice.Channel.MESSAGE, Messages.lookupRemoval("party", removed = false).channel)
        assertEquals(Notice.Channel.MESSAGE, Messages.viewAlreadyRemoved().channel)
    }

    @Test
    fun aRefusalIsTheErrorChannel() {
        assertEquals(Notice.error(Messages.SONG_GONE), Messages.junctionWrite(JunctionWrite.SongGone("r")))
        assertEquals(Notice.error(Messages.NOTHING_WRITTEN), Messages.junctionUpdate(wrote = false))
        assertEquals(Notice.error(Messages.ARTIST_PROTECTED), Messages.artistRemoval("Unknown Artist", SongCatalog.ArtistRemoval.Protected))
        assertEquals(Notice.Channel.ERROR, Messages.artistRemoval("Dolly", SongCatalog.ArtistRemoval.Refused(2)).channel)
        assertEquals(Notice.error(Messages.ARTIST_RENAME_GONE), Messages.artistRename("X", wrote = false))
    }

    /** A junction write that wrote says `done`; one that found the row as asked says `unchanged`; quiet by default. */
    @Test
    fun aJunctionWriteSaysDoneOrUnchanged() {
        val created = JunctionWrite.Added("r", Resolution.CREATED)
        val existing = JunctionWrite.Added("r", Resolution.EXISTING)
        val revivedParent = JunctionWrite.Added("r", Resolution.EXISTING, revivedParent = true)
        assertNull(Messages.junctionWrite(created))
        assertEquals(Notice.message("Added"), Messages.junctionWrite(created, done = "Added", unchanged = "Already"))
        assertEquals(Notice.message("Already"), Messages.junctionWrite(existing, done = "Added", unchanged = "Already"))
        assertEquals(Notice.message("Added"), Messages.junctionWrite(revivedParent, done = "Added", unchanged = "Already"))
        assertEquals(
            Notice.message("Coralie on that part was already removed."),
            Messages.junctionWrite(
                JunctionWrite.Removed("r", wrote = false),
                alreadyRemoved = Messages.capabilityAlreadyRemoved("Coralie"),
            ),
        )
    }

    /** rating-scale RS14: the feel in words, and no suffix for no feel. */
    @Test
    fun theLoggedLineNamesTheFeelInWords() {
        assertEquals("Logged Jolene · feel: certainly", Messages.logged("Jolene", RatingLevel.CERTAINLY))
        assertEquals("Logged Jolene · feel: not at all", Messages.logged("Jolene", RatingLevel.NOT_AT_ALL))
        assertEquals("Logged Jolene", Messages.logged("Jolene", null))
    }

    /** F19 N2: the singular/plural rule, once. */
    @Test
    fun songCountIsSingularForOne() {
        assertEquals("1 song", Messages.songCount(1))
        assertEquals("0 songs", Messages.songCount(0))
        assertEquals("12 songs", Messages.songCount(12))
        assertEquals(
            "The Zutons still has 1 song, so it cannot be removed. Give that song another artist first.",
            Messages.artistRefused("The Zutons", 1),
        )
        assertEquals(
            "Dolly still has 2 songs, so it cannot be removed. Give those songs another artist first.",
            Messages.artistRefused("Dolly", 2),
        )
    }

    /** F19 B1: R20's line is built here, with the removed-instrument fallback. */
    @Test
    fun thePracticeLineNamesARemovedInstrument() {
        val summary = RepertosaurusRepository.PracticeSummary("i-1", "backing vocal", 3, "2026-09-10")
        assertEquals("Backing Vocal: 3 logged, last on 2026-09-10", Messages.practiceLine(summary))
        assertEquals(
            "Removed instrument: 1 logged, last on 2026-09-01",
            Messages.practiceLine(RepertosaurusRepository.PracticeSummary("i-2", null, 1, "2026-09-01")),
        )
    }

    /** R23 / R23a: the add message, by resolution and by whether it differs. */
    @Test
    fun theSongAddMessage() {
        assertEquals("Added Jolene — Dolly Parton.", Messages.songAdd(add(Resolution.CREATED)))
        assertEquals(
            "Added Jolene — Dolly. The artist is already in the repertoire as Dolly.",
            Messages.songAdd(add(Resolution.CREATED, artist = "Dolly", artistDiffers = true)),
        )
        assertEquals("Restored Jolene — Dolly Parton. It had been removed.", Messages.songAdd(add(Resolution.REVIVED)))
        assertEquals(
            "Already in the repertoire as Jolene (live) — Dolly Parton.",
            Messages.songAdd(add(Resolution.EXISTING, title = "Jolene (live)", titleDiffers = true)),
        )
        assertEquals("Jolene — Dolly Parton is already in the repertoire.", Messages.songAdd(add(Resolution.EXISTING)))
        assertEquals(
            "Added Jolene — Dolly Parton. This view's filter hides it.",
            Messages.songAddInView(add(Resolution.CREATED), visibleInView = false),
        )
    }

    /** R14 / R23 for a save: a typed artist that landed on a renamed one says so; Gone is a refusal. */
    @Test
    fun theSaveMessage() {
        val fields = SongCatalog.SongFields(
            title = "Jolene", artist = SongCatalog.LookupChoice.Typed("Dolly Parton"), referenceRecording = null,
            keySignature = null, tonalCentre = null, tonalityNote = null, tempoBpm = null, durationSeconds = null,
            decade = null, loopLength = null, chordCount = null, chordPattern = null, groove = null,
            mashupNote = null, notes = null, chartUrl = null,
        )
        assertEquals(
            Notice.message("Saved. The artist is already in the repertoire as Dolly."),
            Messages.songSave(fields, SongCatalog.SongSave.Saved(ArtistResolution(Resolution.EXISTING, "a", "Dolly"))),
        )
        assertEquals(
            Notice.message(Messages.SAVED),
            Messages.songSave(fields, SongCatalog.SongSave.Saved(ArtistResolution(Resolution.EXISTING, "a", "Dolly Parton"))),
        )
        assertEquals(Notice.error(Messages.SAVE_SONG_GONE), Messages.songSave(fields, SongCatalog.SongSave.Gone))
    }

    /**
     * **F22 N5: the count and empty lines are shared, with the singular** — the Artists search
     * used to say "1 of 1 artists".
     */
    @Test
    fun theCountLineAgreesWithItsTotal() {
        assertEquals("1 of 1 artist", Messages.shownOf(1, Messages.artistCount(1)))
        assertEquals("52 of 282 artists", Messages.shownOf(52, Messages.artistCount(282)))
        assertEquals("0 of 1 song", Messages.shownOf(0, Messages.songCount(1)))
        assertEquals("3 of 12 songs", Messages.shownOf(3, Messages.songCount(12)))
        assertEquals("1 song", Messages.songCount(1))
        assertEquals("0 songs", Messages.songCount(0))
        assertEquals("2 geese", Messages.nounCount(2, "goose", "geese"))
        assertEquals("Nothing matches \"zzz\".", Messages.nothingMatches("zzz"))
    }

    /**
     * **F27 N1: one carried/dropped sentence.** The preview's counts, the confirmation and the
     * landed merge all say it this way — and none of them says "0 dropped".
     */
    @Test
    fun theCarriedDroppedSentenceNeverSaysZeroDropped() {
        assertEquals("1 practice event carried", Messages.eventsCarried(1, 0))
        assertEquals("3 practice events carried, 2 dropped", Messages.eventsCarried(3, 2))
        assertEquals("0 practice events carried, 1 dropped", Messages.eventsCarried(0, 1))
    }

    /** suggest SG13: the skip count is information, not a scolding: no exclamation mark. */
    @Test
    fun theSkipCountIsInformation() {
        assertEquals("Skipped 20 times since you last played it", Messages.suggestSkipCount(20))
        assertEquals("Skipped 1 time since you last played it", Messages.suggestSkipCount(1))
        assertTrue('!' !in Messages.suggestSkipCount(3))
    }
}
