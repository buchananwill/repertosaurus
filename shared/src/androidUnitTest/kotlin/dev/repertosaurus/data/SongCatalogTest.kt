package dev.repertosaurus.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.repertosaurus.core.Ids
import dev.repertosaurus.data.SongCatalog.LookupChoice
import dev.repertosaurus.db.RepertosaurusDatabase
import dev.repertosaurus.session.SongSearch
import dev.repertosaurus.TestClock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The song aggregate (repertoire-editing R30) against a real database: R17, R19, R21-R25a, and
 * the write rules — E37/R23d (no update clears a tombstone, no soft delete re-stamps one), R23b
 * (a write revives a removed parent) and R23c (a write to a removed song's child is refused).
 */
class SongCatalogTest {

    /** Movable, so a revive's bumped `updated_at` is distinguishable from the original. */
    private val clock = TestClock("2026-09-23T10:00:00.000Z")

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: RepertosaurusDatabase
    private lateinit var repository: RepertosaurusRepository
    private lateinit var catalog: SongCatalog

    private val vocal = Ids.derived("instrument", "vocal")
    private val guitar = Ids.derived("instrument", "guitar")
    private val keys = Ids.derived("instrument", "keys")

    @BeforeTest
    fun open() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        RepertosaurusDatabase.Schema.create(driver)
        database = RepertosaurusDatabase(driver)
        repository = RepertosaurusRepository(database, "test-device", clock, TimeZone.UTC)
        catalog = repository.catalog
    }

    @AfterTest
    fun close() {
        driver.close()
    }

    private fun tick(to: String) {
        clock.instant = Instant.parse(to)
    }

    private fun artist(name: String): String = catalog.findOrCreateArtist(name).artistId

    private fun add(title: String, artist: String): SongCatalog.SongAdd =
        catalog.addSong(title, LookupChoice.Typed(artist))

    private fun song(title: String, artist: String): String = add(title, artist).song.songId

    private fun fieldsOf(record: SongCatalog.SongRecord): SongCatalog.SongFields =
        SongCatalog.SongFields(
            title = record.title,
            artist = LookupChoice.Picked(record.artistId),
            referenceRecording = record.referenceRecording,
            keySignature = record.keySignature,
            tonalCentre = record.tonalCentre,
            tonalityNote = record.tonalityNote,
            tempoBpm = record.tempoBpm,
            durationSeconds = record.durationSeconds,
            decade = record.decade,
            loopLength = record.loopLength,
            chordCount = record.chordCount,
            chordPattern = record.chordPattern,
            groove = record.grooveId?.let { LookupChoice.Picked(it) },
            mashupNote = record.mashupNote,
            notes = record.notes,
            chartUrl = record.chartUrl,
        )

    private fun artistRow(id: String) = database.artistQueries.selectById(id).executeAsOne()

    private fun songRow(id: String) = database.songQueries.selectById(id).executeAsOne()

    // ---- R21: revive on create ----------------------------------------------------------

    /**
     * **R21.** Remove a song, add it again by the same title and artist: the derived id is the
     * tombstone's, so the add must revive that row — live again, same id — rather than return
     * a hidden one.
     */
    @Test
    fun reAddingARemovedSongRevivesTheSameRow() {
        val created = add("Jolene", "Dolly Parton")
        assertEquals(Resolution.CREATED, created.song.resolution)
        assertEquals(Resolution.CREATED, created.artist.resolution)
        assertFalse(created.differs)
        val id = created.song.songId

        assertTrue(catalog.removeSong(id))
        assertNull(catalog.song(id), "a removed song has no detail")
        assertTrue(catalog.songs().none { it.id == id })

        tick("2026-09-23T11:00:00.000Z")
        val again = add("Jolene", "Dolly Parton")

        assertEquals(Resolution.REVIVED, again.song.resolution)
        assertEquals(id, again.song.songId, "same derived id")
        assertNull(songRow(id).deleted_at, "live again")
        assertEquals("2026-09-23T11:00:00.000Z", songRow(id).updated_at, "the revive is a write LWW can merge")
        assertEquals(listOf(id), catalog.songs().map { it.id })
        assertFalse(again.differs, "nothing was renamed")
    }

    /** R21 keeps what the row carried: a revive is an untombstone, not a reset. */
    @Test
    fun aRevivedSongKeepsItsFields() {
        val id = song("Jolene", "Dolly Parton")
        val record = assertNotNull(catalog.song(id))
        catalog.updateSong(id, fieldsOf(record).copy(keySignature = -4L, notes = "capo 4"))
        catalog.removeSong(id)

        add("Jolene", "Dolly Parton")

        val revived = assertNotNull(catalog.song(id))
        assertEquals(-4L, revived.keySignature)
        assertEquals("capo 4", revived.notes)
    }

    /** R21 for artists: create-on-enter of a removed artist's name revives it. */
    @Test
    fun reEnteringARemovedArtistRevivesTheSameRow() {
        val created = catalog.findOrCreateArtist("The Zutons")
        assertEquals(Resolution.CREATED, created.resolution)
        assertEquals(SongCatalog.ArtistRemoval.Removed, catalog.removeArtist(created.artistId))
        assertTrue(catalog.artists().none { it.id == created.artistId })

        val again = catalog.findOrCreateArtist("Zutons")

        assertEquals(Resolution.REVIVED, again.resolution)
        assertEquals(created.artistId, again.artistId)
        assertEquals("The Zutons", again.name, "the stored spelling survives the revive")
        assertNull(artistRow(created.artistId).deleted_at)
        assertEquals("Zutons, The", artistRow(created.artistId).sort_name)
        assertTrue(catalog.artists().any { it.id == created.artistId })
    }

    // ---- R22 / R23 / R23a ---------------------------------------------------------------

    /**
     * **R22 and R23, with the spec's own example.** Rename *Jolene* to *Jolene (live)*. The id
     * is not re-derived, so adding *Jolene* again lands on the renamed row — and says what it
     * is now called — while adding *Jolene (live)* derives a free id and creates a second row.
     */
    @Test
    fun aRenamedSongIsFoundByItsOldTitleAndItsNewTitleIsANewSong() {
        val jolene = song("Jolene", "Dolly Parton")
        val record = assertNotNull(catalog.song(jolene))
        catalog.updateSong(jolene, fieldsOf(record).copy(title = "Jolene (live)"))

        val again = add("Jolene", "Dolly Parton")
        assertEquals(Resolution.EXISTING, again.song.resolution)
        assertEquals(jolene, again.song.songId, "R22: the id was never re-derived")
        assertEquals("Jolene (live)", again.song.title, "R23: the current title, for the message")
        assertTrue(again.titleDiffers)
        assertFalse(again.artistDiffers)
        assertTrue(again.differs)

        val live = add("Jolene (live)", "Dolly Parton")
        assertEquals(Resolution.CREATED, live.song.resolution)
        assertNotEquals(jolene, live.song.songId, "R22: the new title's derived id was free")
        assertEquals(2, catalog.songs().size)
    }

    /**
     * **R23a, reviewer case 1.** Edit *Jolene*'s artist to someone else, then add *Jolene*
     * typing the old artist. The song id derives from the old artist, so the add lands on the
     * edited row — whose artist is no longer what was typed. The title matches; the artist
     * does not, and that alone must make the add differ.
     */
    @Test
    fun reAddingASongWhoseArtistWasEditedDiffersOnTheArtist() {
        val jolene = song("Jolene", "Dolly Parton")
        val record = assertNotNull(catalog.song(jolene))
        catalog.updateSong(jolene, fieldsOf(record).copy(artist = LookupChoice.Typed("The Dolly Band")))

        val again = add("Jolene", "Dolly Parton")

        assertEquals(Resolution.EXISTING, again.song.resolution)
        assertEquals(jolene, again.song.songId)
        assertEquals("The Dolly Band", again.song.artistName)
        assertFalse(again.titleDiffers)
        assertTrue(again.artistDiffers, "R23a: the song's current artist is not what was typed")
        assertTrue(again.differs)
    }

    /**
     * **R23a, reviewer case 2.** Rename an artist, then add a new song typing the artist's old
     * name. The name derives the renamed artist's id, so the new song lands under the renamed
     * artist — and the add must say so, although the song itself is new.
     */
    @Test
    fun addingUnderARenamedArtistDiffersOnTheArtist() {
        val dolly = artist("Dolly Parton")
        assertTrue(catalog.renameArtist(dolly, "Dolly"))

        val added = add("9 to 5", "Dolly Parton")

        assertEquals(Resolution.CREATED, added.song.resolution)
        assertEquals(Resolution.EXISTING, added.artist.resolution)
        assertEquals(dolly, added.artist.artistId)
        assertEquals("Dolly", added.song.artistName)
        assertFalse(added.titleDiffers)
        assertTrue(added.artistDiffers)
        assertTrue(added.artist.differsFrom("Dolly Parton"))
    }

    /**
     * An exact re-add never differs — typed, or picked under the artist the song is already
     * under (R23a clarified: a picked artist counts as what was typed, and here it matches).
     */
    @Test
    fun anExactOrPickedReAddDoesNotDiffer() {
        val dolly = artist("Dolly Parton")
        add("Jolene", "Dolly Parton")

        assertFalse(add("  Jolene ", "Dolly Parton").differs)
        val picked = catalog.addSong("Jolene", LookupChoice.Picked(dolly))
        assertEquals(Resolution.EXISTING, picked.song.resolution)
        assertFalse(picked.differs)
    }

    /**
     * **F15 B1 / R23a clarified: a picked artist counts as typed.** *Jolene*'s artist was edited
     * from Dolly to *The Dolly Band*. Adding *Jolene* with Dolly **picked** from the suggestions
     * derives Dolly's song id, lands on the edited row — and must say so, exactly as typing
     * "Dolly Parton" would. Before the clarification a pick could never differ, which left the
     * everyday add path silent.
     */
    @Test
    fun aPickedArtistDiffersWhenTheSongLandsUnderAnother() {
        val dolly = artist("Dolly Parton")
        val jolene = song("Jolene", "Dolly Parton")
        val record = assertNotNull(catalog.song(jolene))
        catalog.updateSong(jolene, fieldsOf(record).copy(artist = LookupChoice.Typed("The Dolly Band")))

        val picked = catalog.addSong("Jolene", LookupChoice.Picked(dolly))

        assertEquals(Resolution.EXISTING, picked.song.resolution)
        assertEquals(jolene, picked.song.songId)
        assertEquals("The Dolly Band", picked.song.artistName)
        assertFalse(picked.titleDiffers)
        assertTrue(picked.artistDiffers, "R23a: the song's current artist is not the picked one")
        assertTrue(picked.differs)
    }

    /**
     * **F23 N4: a picked artist differs by id, not by name.** Two artists carry the one display
     * name *Dolly Parton* — the second was renamed to it, which R22 allows and never re-derives.
     * *Jolene* was created under the first and moved to the second. Adding *Jolene* with the
     * **first** picked lands on the moved row: the song is under a different artist, so it must
     * say so, even though the two names read the same.
     */
    @Test
    fun aPickedArtistDiffersByIdEvenWhenTheNamesAgree() {
        val dolly = artist("Dolly Parton")
        val band = artist("The Dolly Band")
        assertTrue(catalog.renameArtist(band, "Dolly Parton"))
        val jolene = song("Jolene", "Dolly Parton")
        val record = assertNotNull(catalog.song(jolene))
        catalog.updateSong(jolene, fieldsOf(record).copy(artist = LookupChoice.Picked(band)))

        val picked = catalog.addSong("Jolene", LookupChoice.Picked(dolly))

        assertEquals(Resolution.EXISTING, picked.song.resolution)
        assertEquals(jolene, picked.song.songId)
        assertEquals("Dolly Parton", picked.song.artistName, "the names agree")
        assertTrue(picked.artistDiffers, "F23 N4: the song is under another artist row")

        // The same pick under the artist the song IS under does not differ.
        val other = song("9 to 5", "Dolly Parton")
        val same = catalog.addSong("9 to 5", LookupChoice.Picked(dolly))
        assertEquals(other, same.song.songId)
        assertFalse(same.artistDiffers)
    }

    /**
     * **F15 N5 / R23a: a typed artist is written only if the song ends up under it.** *Jolene*
     * moved from Dolly to X, and Dolly — left with no songs — was removed. Re-adding *Jolene*
     * typing "Dolly Parton" derives Dolly's song id and lands on the row now under X. Dolly must
     * **stay removed**: reviving her would be a side effect of an add that did not use her.
     */
    @Test
    fun aTypedArtistIsNotRevivedWhenTheSongLandsUnderAnother() {
        val dolly = artist("Dolly Parton")
        val jolene = song("Jolene", "Dolly Parton")
        val record = assertNotNull(catalog.song(jolene))
        catalog.updateSong(jolene, fieldsOf(record).copy(artist = LookupChoice.Typed("X")))
        assertEquals(SongCatalog.ArtistRemoval.Removed, catalog.removeArtist(dolly))
        val removed = artistRow(dolly)
        tick("2026-09-23T12:00:00.000Z")

        val again = add("Jolene", "Dolly Parton")

        assertEquals(Resolution.EXISTING, again.song.resolution)
        assertEquals(jolene, again.song.songId)
        assertEquals(removed, artistRow(dolly), "the typed artist was neither revived nor re-stamped")
        assertEquals(Ids.derived("artist", "X"), again.artist.artistId, "the artist the song is under")
        assertEquals(Resolution.EXISTING, again.artist.resolution)
        assertTrue(again.artistDiffers)
    }

    /** F15 N5, the same for a pick: a picked artist the song does not land under is not revived either. */
    @Test
    fun aPickedArtistIsNotRevivedWhenTheSongLandsUnderAnother() {
        val dolly = artist("Dolly Parton")
        val jolene = song("Jolene", "Dolly Parton")
        val record = assertNotNull(catalog.song(jolene))
        catalog.updateSong(jolene, fieldsOf(record).copy(artist = LookupChoice.Typed("X")))
        assertEquals(SongCatalog.ArtistRemoval.Removed, catalog.removeArtist(dolly))

        catalog.addSong("Jolene", LookupChoice.Picked(dolly))

        assertNotNull(artistRow(dolly).deleted_at, "a stale pick the song did not land under stays removed")
    }

    /** The one "differs" rule (F14 N7): exact over the trimmed text asked for. */
    @Test
    fun namesDifferIsExactOverTheTrimmedName() {
        assertFalse(namesDiffer("Jolene", "  Jolene "))
        assertTrue(namesDiffer("Jolene", "jolene"), "a case change is said (package 1 ruling)")
        assertTrue(namesDiffer("Jolene (live)", "Jolene"))
    }

    // ---- R23b: a write revives a removed parent -----------------------------------------

    /**
     * **F15 N3: R23b for the groove on a song revive.** The song carried a groove; the song was
     * removed and then the groove. Re-adding the song revives it with its groove — which must
     * come back too, or a live song points at a hidden groove.
     */
    @Test
    fun revivingASongRevivesItsRemovedGroove() {
        val jolene = song("Jolene", "Dolly Parton")
        val record = assertNotNull(catalog.song(jolene))
        catalog.updateSong(jolene, fieldsOf(record).copy(groove = LookupChoice.Typed("shuffle")))
        val shuffle = Ids.derived("groove", "shuffle")
        assertTrue(catalog.removeSong(jolene))
        assertTrue(repository.lookups.remove(LookupTableKey.GROOVE, shuffle))

        val again = add("Jolene", "Dolly Parton")

        assertEquals(Resolution.REVIVED, again.song.resolution)
        assertNull(database.grooveQueries.selectById(shuffle).executeAsOne().deleted_at, "R23b: the groove is live again")
        assertEquals("shuffle", assertNotNull(catalog.song(jolene)).grooveName)
    }

    /**
     * **F15 N4: `wrote` includes a revived parent.** The tag row on the song is live, but the tag
     * itself was removed underneath it. Tagging again finds the row [Resolution.EXISTING] — and
     * revives the tag, which is a write. By id and by typed name alike.
     */
    @Test
    fun aTagAddThatRevivesTheTagReportsAWrite() {
        val jolene = song("Jolene", "Dolly Parton")
        val tagId = repository.lookups.add(LookupTableKey.TAG, "country")
        val rowId = catalog.addTag(jolene, tagId).id
        assertTrue(repository.lookups.remove(LookupTableKey.TAG, tagId))

        val again = catalog.addTag(jolene, tagId)
        assertEquals(JunctionWrite.Added(rowId, Resolution.EXISTING, revivedParent = true), again)
        assertTrue(again.wrote)
        assertNull(database.tagQueries.selectById(tagId).executeAsOne().deleted_at)

        assertTrue(repository.lookups.remove(LookupTableKey.TAG, tagId))
        val named = catalog.addTagNamed(jolene, "Country")
        assertEquals(JunctionWrite.Added(rowId, Resolution.EXISTING, revivedParent = true), named)
        assertTrue(named.wrote)

        val quiet = catalog.addTagNamed(jolene, "country")
        assertFalse(quiet.wrote, "nothing left to revive and the row is live: nothing written")
    }

    /** F15 N3: `updateSongInstrument` says true when it wrote — the Boolean's other half. */
    @Test
    fun updatingALiveSongInstrumentSaysItWrote() {
        val id = song("Jolene", "Dolly Parton")
        val rowId = catalog.addSongInstrument(id, keys).id
        assertTrue(catalog.updateSongInstrument(rowId, difficulty = 2L, patch = null, notes = null))
        assertFalse(catalog.updateSongInstrument("no-such-row", difficulty = 2L, patch = null, notes = null))
    }

    /**
     * **R23b, the reviewer's scenario.** Edit the song's artist to X, remove the song, remove X
     * (it has no live songs now), then re-add the song by its original title and artist. The
     * song revives (R21) — and it still points at X, which must come back with it, or a live
     * song renders under a hidden artist.
     */
    @Test
    fun revivingASongRevivesItsRemovedArtist() {
        val jolene = song("Jolene", "Dolly Parton")
        val record = assertNotNull(catalog.song(jolene))
        catalog.updateSong(jolene, fieldsOf(record).copy(artist = LookupChoice.Typed("X")))
        val x = Ids.derived("artist", "X")
        assertTrue(catalog.removeSong(jolene))
        assertEquals(SongCatalog.ArtistRemoval.Removed, catalog.removeArtist(x))

        val again = add("Jolene", "Dolly Parton")

        assertEquals(Resolution.REVIVED, again.song.resolution)
        assertNull(songRow(jolene).deleted_at)
        assertNull(artistRow(x).deleted_at, "R23b: the song's artist is live again")
        assertTrue(catalog.artists().any { it.id == x })
        assertEquals("X", assertNotNull(catalog.song(jolene)).artistName)
        assertTrue(again.artistDiffers)
    }

    /**
     * **R23b on a stale pick.** The detail's artist list was read before the artist was
     * removed; saving with that picked id revives the artist in the same transaction.
     */
    @Test
    fun savingWithAStalePickedArtistRevivesIt() {
        val jolene = song("Jolene", "Dolly Parton")
        val zutons = artist("The Zutons")
        assertEquals(SongCatalog.ArtistRemoval.Removed, catalog.removeArtist(zutons))
        val record = assertNotNull(catalog.song(jolene))

        val saved = catalog.updateSong(jolene, fieldsOf(record).copy(artist = LookupChoice.Picked(zutons)))

        val artist = assertIs<SongCatalog.SongSave.Saved>(saved).artist
        assertEquals(Resolution.REVIVED, artist.resolution)
        assertNull(artistRow(zutons).deleted_at)
        assertEquals("The Zutons", assertNotNull(catalog.song(jolene)).artistName)
    }

    /** R23b on a stale picked groove, and on a junction's tag and instrument. */
    @Test
    fun junctionAndGrooveWritesReviveTheirRemovedParents() {
        val jolene = song("Jolene", "Dolly Parton")
        val shuffle = repository.lookups.add(LookupTableKey.GROOVE, "shuffle")
        val party = repository.lookups.add(LookupTableKey.TAG, "Party Time")
        repository.lookups.remove(LookupTableKey.GROOVE, shuffle)
        repository.lookups.remove(LookupTableKey.TAG, party)
        repository.lookups.remove(LookupTableKey.INSTRUMENT, keys)

        val record = assertNotNull(catalog.song(jolene))
        catalog.updateSong(jolene, fieldsOf(record).copy(groove = LookupChoice.Picked(shuffle)))
        catalog.addTag(jolene, party)
        catalog.addSongInstrument(jolene, keys)

        assertNull(database.grooveQueries.selectById(shuffle).executeAsOne().deleted_at)
        assertNull(database.tagQueries.selectById(party).executeAsOne().deleted_at)
        assertNull(database.instrumentQueries.selectById(keys).executeAsOne().deleted_at)
        assertEquals(listOf("Party Time"), catalog.songTags(jolene).map { it.tagName })
        assertEquals(1, catalog.songInstruments(jolene).size)
    }

    // ---- R23c: writes to a removed song's children --------------------------------------

    /**
     * **R23c.** Tag and `song_instrument` writes on a removed song write nothing, and the
     * caller can tell — including `addTagNamed`, which must not even create the tag.
     */
    @Test
    fun childWritesOnARemovedSongWriteNothingAndSaySo() {
        val jolene = song("Jolene", "Dolly Parton")
        val tagId = repository.lookups.add(LookupTableKey.TAG, "country")
        val otherTag = repository.lookups.add(LookupTableKey.TAG, "rock")
        val tagRow = assertIs<JunctionWrite.Added>(catalog.addTag(jolene, tagId)).id
        val instrumentRow = catalog.addSongInstrument(jolene, keys).id
        assertTrue(catalog.removeSong(jolene))
        val tagBefore = database.song_tagQueries.selectById(tagRow).executeAsOne()
        val instrumentBefore = database.song_instrumentQueries.selectById(instrumentRow).executeAsOne()

        assertEquals(JunctionWrite.SongGone(Ids.songTag(jolene, otherTag)), catalog.addTag(jolene, otherTag))
        assertEquals(1L, countRows("song_tag"), "no rock row inserted")
        val named = catalog.addTagNamed(jolene, "Brand New Tag")
        assertEquals(JunctionWrite.SongGone(Ids.songTag(jolene, Ids.derived("tag", "Brand New Tag"))), named)
        assertFalse(named.wrote)
        assertNull(
            database.tagQueries.selectById(Ids.derived("tag", "Brand New Tag")).executeAsOneOrNull(),
            "no tag created for a refused write",
        )
        assertEquals(JunctionWrite.SongGone(tagRow), catalog.removeTag(tagRow))
        assertEquals(JunctionWrite.SongGone(Ids.songInstrument(jolene, vocal)), catalog.addSongInstrument(jolene, vocal))
        assertFalse(catalog.updateSongInstrument(instrumentRow, 3L, "patch", null))
        assertEquals(JunctionWrite.SongGone(instrumentRow), catalog.removeSongInstrument(instrumentRow))

        assertEquals(tagBefore, database.song_tagQueries.selectById(tagRow).executeAsOne())
        assertEquals(instrumentBefore, database.song_instrumentQueries.selectById(instrumentRow).executeAsOne())
        assertEquals(1L, countRows("song_instrument"), "no vocal row inserted")
    }

    // ---- R25 / R25a ---------------------------------------------------------------------

    /** **R25.** An artist with live songs cannot be removed, and the refusal says how many. */
    @Test
    fun anArtistWithLiveSongsCannotBeRemoved() {
        val dolly = artist("Dolly Parton")
        val jolene = song("Jolene", "Dolly Parton")
        val nineToFive = song("9 to 5", "Dolly Parton")

        assertEquals(SongCatalog.ArtistRemoval.Refused(2), catalog.removeArtist(dolly))
        assertNull(artistRow(dolly).deleted_at, "not removed")
        assertEquals(2L, catalog.artistsWithSongCounts().single { it.id == dolly }.liveSongs)

        catalog.removeSong(jolene)
        assertEquals(SongCatalog.ArtistRemoval.Refused(1), catalog.removeArtist(dolly))

        catalog.removeSong(nineToFive)
        assertEquals(
            SongCatalog.ArtistRemoval.Removed,
            catalog.removeArtist(dolly),
            "only soft-deleted songs point at it, so it can go",
        )
        assertTrue(catalog.artistsWithSongCounts().none { it.id == dolly })
    }

    /**
     * **R25a.** The seeded *Unknown Artist* is never removed — not even with zero live songs,
     * which is exactly the case R25's count would allow — and the refusal says why. It can
     * still be renamed.
     */
    @Test
    fun theUnknownArtistCannotBeRemovedButCanBeRenamed() {
        val unknown = SongCatalog.UNKNOWN_ARTIST_ID
        assertEquals(0L, catalog.liveSongCount(unknown), "the case R25 alone would allow")

        assertEquals(SongCatalog.ArtistRemoval.Protected, catalog.removeArtist(unknown))
        assertNull(artistRow(unknown).deleted_at, "still live")
        assertTrue(catalog.artists().any { it.id == unknown })

        assertTrue(catalog.renameArtist(unknown, "Artist Unknown"))

        assertEquals("Artist Unknown", artistRow(unknown).name)
        assertNull(artistRow(unknown).deleted_at)
    }

    /**
     * **S5 / R23d.** Removing an artist that is already removed, or that never existed, writes
     * nothing and says so — it is not re-stamped.
     */
    @Test
    fun removingAnAlreadyRemovedOrMissingArtistIsGone() {
        val zutons = artist("The Zutons")
        assertEquals(SongCatalog.ArtistRemoval.Removed, catalog.removeArtist(zutons))
        val removed = artistRow(zutons)
        tick("2026-09-23T12:00:00.000Z")

        assertEquals(SongCatalog.ArtistRemoval.Gone, catalog.removeArtist(zutons))
        assertEquals(removed, artistRow(zutons), "not re-stamped")

        assertEquals(SongCatalog.ArtistRemoval.Gone, catalog.removeArtist("no-such-artist"))
    }

    /** R24: the count is of live songs, and an artist with none still lists with zero. */
    @Test
    fun artistSongCountsCountLiveSongsOnly() {
        val dolly = artist("Dolly Parton")
        val zutons = artist("The Zutons")
        song("Jolene", "Dolly Parton")
        catalog.removeSong(song("9 to 5", "Dolly Parton"))

        val counts = catalog.artistsWithSongCounts().associate { it.name to it.liveSongs }

        assertEquals(1L, counts["Dolly Parton"])
        assertEquals(0L, counts["The Zutons"])
        assertEquals(0L, counts["Unknown Artist"], "the seeded placeholder lists too")
        assertEquals(1L, catalog.liveSongCount(dolly))
        assertEquals(0L, catalog.liveSongCount(zutons))
    }

    // ---- E37 / R23d: no update clears a tombstone, no soft delete re-stamps one ----------

    /**
     * **E37 on `song.update`.** An edit that lands after a remove writes nothing. Exercises the
     * statement directly, because the `WHERE deleted_at IS NULL` is the defence of record.
     */
    @Test
    fun updatingARemovedSongWritesNothing() {
        val id = song("Jolene", "Dolly Parton")
        catalog.removeSong(id)
        val removed = songRow(id)
        tick("2026-09-23T12:00:00.000Z")

        database.songQueries.update(
            title = "Jolene (live)",
            artist_id = removed.artist_id,
            reference_recording = null,
            key_signature = null,
            tonal_centre = null,
            tonality_note = null,
            tempo_bpm = null,
            duration_seconds = null,
            decade = null,
            loop_length = null,
            chord_count = null,
            chord_pattern = null,
            groove_id = null,
            mashup_note = null,
            notes = null,
            chart_url = null,
            updated_at = "2026-09-23T12:00:00.000Z",
            device_id = "test-device",
            id = id,
        )

        assertEquals(removed, songRow(id), "untouched")
    }

    /** N4: the same through the catalog is [SongCatalog.SongSave.Gone], and creates no lookup. */
    @Test
    fun savingARemovedSongIsGone() {
        val id = song("Jolene", "Dolly Parton")
        val record = assertNotNull(catalog.song(id))
        catalog.removeSong(id)

        val saved = catalog.updateSong(
            id,
            fieldsOf(record).copy(
                title = "Jolene (live)",
                artist = LookupChoice.Typed("Someone New"),
                groove = LookupChoice.Typed("shuffle"),
            ),
        )

        assertEquals(SongCatalog.SongSave.Gone, saved)
        assertNotNull(songRow(id).deleted_at)
        assertEquals("Jolene", songRow(id).title)
        assertTrue(catalog.artists().none { it.name == "Someone New" }, "no orphan artist")
        assertTrue(database.grooveQueries.selectAllLive().executeAsList().isEmpty(), "no orphan groove")
    }

    /** **E37 on `artist.update`.** A rename after a remove writes nothing, and says so. */
    @Test
    fun renamingARemovedArtistWritesNothing() {
        val id = artist("The Zutons")
        catalog.removeArtist(id)
        val removed = artistRow(id)
        tick("2026-09-23T12:00:00.000Z")

        assertFalse(catalog.renameArtist(id, "Zutons Revival"))

        assertEquals(removed, artistRow(id))
    }

    /** **E37 on `song_instrument.update`.** An edit after a remove writes nothing, and says so. */
    @Test
    fun updatingARemovedSongInstrumentWritesNothing() {
        val id = song("Jolene", "Dolly Parton")
        val rowId = catalog.addSongInstrument(id, keys).id
        assertTrue(catalog.updateSongInstrument(rowId, difficulty = 3L, patch = "Wavestate 12", notes = "intro"))
        assertTrue(catalog.removeSongInstrument(rowId).wrote)
        val removed = database.song_instrumentQueries.selectById(rowId).executeAsOne()
        tick("2026-09-23T12:00:00.000Z")

        assertFalse(catalog.updateSongInstrument(rowId, difficulty = 5L, patch = null, notes = null))

        assertEquals(removed, database.song_instrumentQueries.selectById(rowId).executeAsOne())
        assertTrue(catalog.songInstruments(id).isEmpty())
    }

    /**
     * **R23d.** Removing a song, a tag or a `song_instrument` row that is already removed
     * writes nothing — the tombstone keeps its original stamp — and reports it.
     */
    @Test
    fun removingSomethingAlreadyRemovedDoesNotReStampIt() {
        val id = song("Jolene", "Dolly Parton")
        val tagRow = catalog.addTagNamed(id, "country").id
        val instrumentRow = catalog.addSongInstrument(id, keys).id

        assertTrue(catalog.removeTag(tagRow).wrote)
        assertTrue(catalog.removeSongInstrument(instrumentRow).wrote)
        val tagRemoved = database.song_tagQueries.selectById(tagRow).executeAsOne()
        val instrumentRemoved = database.song_instrumentQueries.selectById(instrumentRow).executeAsOne()
        tick("2026-09-23T12:00:00.000Z")

        assertEquals(JunctionWrite.Removed(tagRow, wrote = false), catalog.removeTag(tagRow))
        assertEquals(JunctionWrite.Removed(instrumentRow, wrote = false), catalog.removeSongInstrument(instrumentRow))
        assertEquals(tagRemoved, database.song_tagQueries.selectById(tagRow).executeAsOne())
        assertEquals(instrumentRemoved, database.song_instrumentQueries.selectById(instrumentRow).executeAsOne())

        tick("2026-09-23T13:00:00.000Z")
        assertTrue(catalog.removeSong(id))
        val songRemoved = songRow(id)
        tick("2026-09-23T14:00:00.000Z")
        assertFalse(catalog.removeSong(id), "R23d: nothing written")
        assertEquals(songRemoved, songRow(id), "not re-stamped")
        assertFalse(catalog.removeSong("no-such-song"))
    }

    // ---- R19: song_instrument add / remove / re-add -------------------------------------

    @Test
    fun reAddingARemovedSongInstrumentRevivesItWithItsFacts() {
        val id = song("Jolene", "Dolly Parton")
        val first = catalog.addSongInstrument(id, keys)
        assertEquals(JunctionWrite.Added(Ids.songInstrument(id, keys), Resolution.CREATED), first)
        val rowId = first.id
        catalog.updateSongInstrument(rowId, difficulty = 4L, patch = "  Wavestate 12 ", notes = " ")

        val stored = catalog.songInstruments(id).single()
        assertEquals(4L, stored.difficulty)
        assertEquals("Wavestate 12", stored.patch, "trimmed")
        assertNull(stored.notes, "blank is null")

        catalog.removeSongInstrument(rowId)
        assertTrue(catalog.songInstruments(id).isEmpty())

        assertEquals(JunctionWrite.Added(rowId, Resolution.REVIVED), catalog.addSongInstrument(id, keys))
        assertEquals(stored, catalog.songInstruments(id).single(), "same row, facts intact")
        assertEquals(1L, countRows("song_instrument"))

        val again = catalog.addSongInstrument(id, keys)
        assertEquals(JunctionWrite.Added(rowId, Resolution.EXISTING), again, "a live row is a no-op")
        assertFalse(again.wrote)
    }

    // ---- R17: tags ----------------------------------------------------------------------

    @Test
    fun reAddingARemovedTagRevivesTheSameRow() {
        val id = song("Jolene", "Dolly Parton")
        val first = catalog.addTagNamed(id, "Country")
        val tagId = Ids.derived("tag", "Country")
        assertEquals(JunctionWrite.Added(Ids.songTag(id, tagId), Resolution.CREATED), first)
        val rowId = first.id
        assertEquals(listOf("Country"), catalog.songTags(id).map { it.tagName })

        assertEquals(JunctionWrite.Removed(rowId, wrote = true), catalog.removeTag(rowId))
        assertTrue(catalog.songTags(id).isEmpty())

        tick("2026-09-23T11:00:00.000Z")
        assertEquals(JunctionWrite.Added(rowId, Resolution.REVIVED), catalog.addTag(id, tagId))

        val row = database.song_tagQueries.selectById(rowId).executeAsOne()
        assertNull(row.deleted_at)
        assertEquals("2026-09-23T11:00:00.000Z", row.updated_at)
        assertEquals(1L, countRows("song_tag"))
        assertEquals(JunctionWrite.Added(rowId, Resolution.EXISTING), catalog.addTag(id, tagId))
        assertEquals(1, catalog.songTags(id).size)
    }

    // ---- S5 / E38: a failed add leaves nothing behind -----------------------------------

    /**
     * **E38.** The add path creates the typed artist and then the song in one transaction. If
     * the song insert fails, the artist it just created must not survive. The failure is
     * injected with a trigger that aborts every `song` insert.
     */
    @Test
    fun aFailedAddRollsBackTheArtistItCreated() {
        driver.execute(
            null,
            "CREATE TRIGGER refuse_song BEFORE INSERT ON song BEGIN SELECT RAISE(ABORT, 'refused'); END",
            0,
        )

        assertFailsWith<Exception> { add("Jolene", "Brand New Artist") }

        assertNull(
            database.artistQueries.selectById(Ids.derived("artist", "Brand New Artist")).executeAsOneOrNull(),
            "the artist created inside the failed add was rolled back",
        )
    }

    // ---- R11, R12, R14, R16: list, detail, save -----------------------------------------

    /** N2: the list is ordered once, in the session layer, by the one base comparator. */
    @Test
    fun theSongListIsLiveSongsOrderedBySongSearch() {
        song("zulu", "Dolly Parton")
        song("Mango", "The Zutons")
        song("apple", "Dolly Parton")
        catalog.removeSong(song("Banana", "Dolly Parton"))

        val listed = SongSearch.search(catalog.songs(), "")

        assertEquals(listOf("apple", "Mango", "zulu"), listed.map { it.title })
        assertEquals("The Zutons", listed.single { it.title == "Mango" }.artistName)
    }

    /** R14/R16: the whole row is written, and a typed groove and artist are created on save. */
    @Test
    fun savingWritesEveryColumnAndCreatesTypedLookups() {
        val id = song("Jolene", "Dolly Parton")
        val record = assertNotNull(catalog.song(id))

        val saved = catalog.updateSong(
            id,
            SongCatalog.SongFields(
                title = "Jolene (live)",
                artist = LookupChoice.Typed("Dolly"),
                referenceRecording = "1973 single",
                keySignature = -4L,
                tonalCentre = 1L,
                tonalityNote = "C# minor, spelled flat",
                tempoBpm = 110L,
                durationSeconds = 161L,
                decade = 1970L,
                loopLength = 4L,
                chordCount = 3L,
                chordPattern = "i-III-VII",
                groove = LookupChoice.Typed("Train beat"),
                mashupNote = "into Valerie",
                notes = "capo 4",
                chartUrl = "https://example.invalid/jolene",
            ),
        )

        assertEquals(Resolution.CREATED, assertIs<SongCatalog.SongSave.Saved>(saved).artist.resolution)
        val after = assertNotNull(catalog.song(id))
        assertEquals(id, after.id, "R22: never re-derived")
        assertEquals("Jolene (live)", after.title)
        assertEquals("Dolly", after.artistName)
        assertNotEquals(record.artistId, after.artistId)
        assertEquals(-4L, after.keySignature)
        assertEquals(1L, after.tonalCentre)
        assertEquals(110L, after.tempoBpm)
        assertEquals(161L, after.durationSeconds)
        assertEquals(1970L, after.decade)
        assertEquals(4L, after.loopLength)
        assertEquals(3L, after.chordCount)
        assertEquals("i-III-VII", after.chordPattern)
        assertEquals(Ids.derived("groove", "Train beat"), after.grooveId)
        assertEquals("Train beat", after.grooveName)
        assertEquals("into Valerie", after.mashupNote)
        assertEquals("capo 4", after.notes)
        assertEquals("https://example.invalid/jolene", after.chartUrl)
        assertEquals("1973 single", after.referenceRecording)
        assertEquals("C# minor, spelled flat", after.tonalityNote)

        // Clearing the groove writes NULL.
        catalog.updateSong(id, fieldsOf(after).copy(groove = null))
        assertNull(assertNotNull(catalog.song(id)).grooveId)
    }

    // ---- R20: practice summary (B5: a pure summary over practiceHistory) ----------------

    @Test
    fun practiceSummaryIsPerInstrumentAndExcludesVoidedEvents() {
        val id = song("Jolene", "Dolly Parton")
        repository.logPractice(id, vocal, loggedOn = "2026-09-01")
        repository.logPractice(id, vocal, loggedOn = "2026-09-10")
        repository.logPractice(id, guitar, loggedOn = "2026-09-05")
        repository.voidPractice(repository.logPractice(id, guitar, loggedOn = "2026-09-20"))

        val summary = repository.practiceSummary(id).associateBy { it.instrumentId }

        assertEquals(2, summary.size)
        assertEquals(2L, summary.getValue(vocal).timesPractised)
        assertEquals("2026-09-10", summary.getValue(vocal).lastPractised)
        assertEquals(1L, summary.getValue(guitar).timesPractised)
        assertEquals("2026-09-05", summary.getValue(guitar).lastPractised, "the voided log is gone")
        assertEquals("vocal", summary.getValue(vocal).instrumentName)
    }

    private fun countRows(table: String): Long =
        driver.executeQuery(null, "SELECT COUNT(*) FROM $table", { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0)!!)
        }, 0).value
}
