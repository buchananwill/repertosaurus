package dev.repertosaurus.data

import dev.repertosaurus.core.Ids
import dev.repertosaurus.core.Timestamps
import dev.repertosaurus.db.RepertosaurusDatabase
import kotlinx.datetime.Clock

/**
 * The song aggregate — data-model E32, cut here by repertoire-editing R30.
 *
 * Songs (add, read, update, remove), artists (read, create-on-enter, rename, remove), song tags,
 * groove assignment and `song_instrument` rows. **The rest of E32 remains its own piece of
 * work**, and no nested row type of the repository moved (E31's precedent) —
 * [RepertosaurusRepository.Artist] stays where `androidApp` already names it. The capability
 * list with its held flag and the practice summary are *not* here (style review B5): they
 * belong to the capability and practice-log sections of the repository.
 *
 * The write rules, each applied everywhere it can arise:
 *
 * - **E38:** every read-then-write is one transaction; derived-id adds go through
 *   [insertOrRevive].
 * - **E37 / R23d:** no update clears a tombstone and no soft delete re-stamps one.
 * - **R23b:** a write that makes a live row reference a tombstoned parent revives the parent.
 * - **R23c:** a write to a child of a removed song writes nothing, and says so.
 *
 * The last three, for a song's junction rows, are [SongChildren]'s envelope — one copy for all
 * three tables (style review F14 B1).
 *
 * Reached through [RepertosaurusRepository.catalog], so a coordinator built per call against
 * `holder.repository` picks up a database swap on import (R28).
 */
public class SongCatalog internal constructor(
    private val database: RepertosaurusDatabase,
    private val deviceId: String,
    private val clock: Clock,
    private val lookups: LookupTables,
    private val children: SongChildren,
) {

    // ---- Row and result types -----------------------------------------------------------

    /** One row of the Songs route's list (R11). Unordered from here; `SongSearch` orders it. */
    public data class SongListEntry(
        val id: String,
        val title: String,
        val artistId: String,
        val artistName: String,
    )

    /**
     * One live `song` row, every column the detail shows (R12), plus the names of the two
     * lookups it points at. Numeric columns are the stored `INTEGER`s, unconverted — turning
     * them into form values is `SongDraft`'s job.
     */
    public data class SongRecord(
        val id: String,
        val title: String,
        val artistId: String,
        val artistName: String,
        val referenceRecording: String?,
        val keySignature: Long?,
        val tonalCentre: Long?,
        val tonalityNote: String?,
        val tempoBpm: Long?,
        val durationSeconds: Long?,
        val decade: Long?,
        val loopLength: Long?,
        val chordCount: Long?,
        val chordPattern: String?,
        val grooveId: String?,
        val grooveName: String?,
        val mashupNote: String?,
        val notes: String?,
        val chartUrl: String?,
    )

    /**
     * A lookup a song field points at, as the type-ahead produced it (R16, E5): a row the user
     * **picked** from the suggestions, by id, or a name they **typed**, created on save — or
     * resolved to an existing row by its derived id.
     *
     * Picked is by id, never by name: a renamed row's current name derives a *different* id
     * from the one it is stored under (R22), so re-resolving a picked row by name would fork it.
     */
    public sealed interface LookupChoice {
        public data class Picked(val id: String) : LookupChoice
        public data class Typed(val name: String) : LookupChoice

        public companion object {
            /**
             * **The one type-ahead rule** (style review F14 N5): a picked id wins; without one,
             * the text in the field is what was typed. Every type-ahead — the add sheet, the
             * detail's artist and groove, the logger — builds its choice here, so none can
             * forget that editing the text drops the pick (package 1 ruling).
             */
            public fun of(id: String?, name: String): LookupChoice = if (id != null) Picked(id) else Typed(name)
        }
    }

    /**
     * Every editable `song` column (R12, R14), already validated — `SongDraft.validate` is where
     * these come from. [groove] is null to clear it.
     */
    public data class SongFields(
        val title: String,
        val artist: LookupChoice,
        val referenceRecording: String?,
        val keySignature: Long?,
        val tonalCentre: Long?,
        val tonalityNote: String?,
        val tempoBpm: Long?,
        val durationSeconds: Long?,
        val decade: Long?,
        val loopLength: Long?,
        val chordCount: Long?,
        val chordPattern: String?,
        val groove: LookupChoice?,
        val mashupNote: String?,
        val notes: String?,
        val chartUrl: String?,
    )

    /**
     * What adding a song did (R21-R23a): how the song resolved **and** how the artist resolved.
     *
     * R23a: [differs] when either the title or the artist lands on a row whose current name is
     * not what was asked for — "already in the repertoire as *Jolene (live) — Dolly*". The
     * artist half compares the *song's* current artist against the asked-for name, which covers
     * both a song whose artist was since edited and a typed name that resolved to a renamed
     * artist. **A picked artist counts as asked for** (R23a clarified, safety review F15 B1): it
     * is the artist the user chose and can see in the field — and it differs **by id** (F23 N4),
     * so a song under a different artist that happens to carry the same name is still said. Only
     * a blank typed artist resolving to *Unknown Artist* is never counted: nothing was asked for
     * to differ from.
     *
     * [artist] is how the artist field resolved when the song landed under it. **When the song
     * landed on an existing row under a different artist** (R22: its artist was edited after it
     * was created), the asked-for artist was not written at all (R23a, F15 N5), and [artist] is
     * the song's own artist — [Resolution.REVIVED] if R23b brought it back, otherwise
     * [Resolution.EXISTING].
     */
    public data class SongAdd(
        val song: SongResolution,
        val artist: ArtistResolution,
        val titleDiffers: Boolean,
        val artistDiffers: Boolean,
    ) {
        public val differs: Boolean get() = titleDiffers || artistDiffers
    }

    /** What saving a song did (R14). */
    public sealed interface SongSave {
        /**
         * Written. [artist] is how the artist field resolved, so the detail can say "already
         * in the repertoire as *X*" when a typed name landed on a renamed artist (R23).
         */
        public data class Saved(val artist: ArtistResolution) : SongSave

        /** E37: the song was removed before the save landed. Nothing was written. */
        public data object Gone : SongSave
    }

    /** One row of the Artists route (R24). */
    public data class ArtistEntry(
        val id: String,
        val name: String,
        val sortName: String,
        val liveSongs: Long,
    )

    /** What a removal did (R25, R25a, R23d). */
    public sealed interface ArtistRemoval {
        public data object Removed : ArtistRemoval

        /** R25: refused, [liveSongs] live songs still point at this artist. */
        public data class Refused(val liveSongs: Long) : ArtistRemoval

        /** R25a: refused, this is the seeded *Unknown Artist*, whatever its song count. */
        public data object Protected : ArtistRemoval

        /** R23d: there was no live artist with this id — absent or already removed. Nothing written. */
        public data object Gone : ArtistRemoval
    }

    /** One live tag on a song (R17). */
    public data class SongTag(val id: String, val tagId: String, val tagName: String)

    /** One live `song_instrument` row (R19): per-song technical detail on one instrument. */
    public data class SongInstrument(
        val id: String,
        val instrumentId: String,
        val instrumentName: String,
        val difficulty: Long?,
        val patch: String?,
        val notes: String?,
    )

    // ---- Songs --------------------------------------------------------------------------

    /**
     * Every live song with its artist's name (R11). **Unordered**: the display order is
     * `SongSearch`'s, applied once in the session layer (style review N2).
     */
    public fun songs(): List<SongListEntry> =
        database.songQueries.selectAllLiveWithArtist().executeAsList()
            .map { SongListEntry(it.id, it.title, it.artist_id, it.artist_name) }

    /** One live song, every column (R12); null when there is none or it has been removed. */
    public fun song(id: String): SongRecord? =
        database.songQueries.selectDetailById(id).executeAsOneOrNull()?.let { row ->
            SongRecord(
                id = row.id,
                title = row.title,
                artistId = row.artist_id,
                artistName = row.artist_name,
                referenceRecording = row.reference_recording,
                keySignature = row.key_signature,
                tonalCentre = row.tonal_centre,
                tonalityNote = row.tonality_note,
                tempoBpm = row.tempo_bpm,
                durationSeconds = row.duration_seconds,
                decade = row.decade,
                loopLength = row.loop_length,
                chordCount = row.chord_count,
                chordPattern = row.chord_pattern,
                grooveId = row.groove_id,
                grooveName = row.groove_name,
                mashupNote = row.mashup_note,
                notes = row.notes,
                chartUrl = row.chart_url,
            )
        }

    /**
     * **The add-song path, once** (style review B2) — the logger's add sheet and the Songs
     * route's both land here. Title and artist, nothing else: every other column is left null,
     * which decisions 27 and 37 require.
     *
     * [artist] is resolved by [resolve] over the one [ArtistTarget] decision: null or a blank [LookupChoice.Typed]
     * is the seeded *Unknown Artist* (decision 28a) — a musician mid-practice should not have to
     * settle an attribution to log a song.
     *
     * The song id is `UUIDv5(namespace(song), artist_id + "/" + normalise(title))` (decision
     * 4d), so adding a song the repertoire already holds lands on that row:
     *
     * - no row → insert, [Resolution.CREATED];
     * - a tombstone → **revive it** (R21), keeping everything it carried,
     *   [Resolution.REVIVED];
     * - a live row → left exactly as it is, [Resolution.EXISTING].
     *
     * R23b: whatever the outcome, the song ends live with its artist and groove live — a
     * revived song whose artist was removed meanwhile brings its artist back too.
     *
     * **R22: the id is never re-derived on rename**, so after renaming *Jolene* to
     * *Jolene (live)*, adding *Jolene* lands on the renamed row and adding *Jolene (live)*
     * creates a second one. R23/R23a is the defence, through [SongAdd.differs].
     *
     * **R23a: the asked-for artist is written only if the song ends up under it** (safety review
     * F15 N5). The song id is derived from the artist's id before anything is written; if that
     * id already holds a song whose artist was edited since (R22), the add lands there and the
     * asked-for artist is neither created nor revived as a side effect. The same holds for a
     * picked artist, which R23a counts as typed.
     *
     * E38: the artist, the song and any parent revive are one transaction, so a failed add
     * leaves no orphan artist behind.
     */
    public fun addSong(title: String, artist: LookupChoice?): SongAdd {
        val display = title.trim()
        require(display.isNotEmpty()) { "a song needs a title" }
        val target = artistTarget(artist)
        return database.transactionWithResult {
            val askedArtistId = target.artistId()
            val id = Ids.song(askedArtistId, display)
            val existing = database.songQueries.selectById(id).executeAsOneOrNull()
            // F15 N5: resolve (and so write) the asked-for artist only when the song lands under it.
            val askedArtist = if (existing == null || existing.artist_id == askedArtistId) {
                resolve(target)
            } else {
                null
            }
            val resolution = database.insertOrRevive(
                read = { database.songQueries.selectById(id).executeAsOneOrNull() },
                deletedAt = { it.deleted_at },
                insert = Insert.Plain { insertSong(id, display, askedArtistId) },
                revive = {
                    database.songQueries.restore(
                        updated_at = Timestamps.now(clock),
                        device_id = deviceId,
                        id = id,
                    )
                },
            )
            val row = database.songQueries.selectById(id).executeAsOne()
            // R23b: whatever the song resolved to, its artist and groove end up live.
            val songArtistRevived = reviveArtistIfRemoved(row.artist_id)
            row.groove_id?.let { lookups.reviveIfRemoved(LookupTableKey.GROOVE, it) }
            val song = SongResolution(resolution, id, row.title, artistName(row.artist_id))
            SongAdd(
                song = song,
                artist = askedArtist ?: ArtistResolution(
                    resolution = if (songArtistRevived) Resolution.REVIVED else Resolution.EXISTING,
                    artistId = row.artist_id,
                    name = song.artistName,
                ),
                titleDiffers = namesDiffer(song.title, display),
                artistDiffers = target.differsFrom(row.artist_id, song.artistName),
            )
        }
    }

    /**
     * **F22 N1: what an artist field names — decided once.** The id derivation of [addSong], the
     * write of [resolve] and R23a's "differs" all branch on this, where each used to re-decide
     * from the raw [LookupChoice]:
     *
     * - [Named]: a typed, non-blank name — create-on-enter ([findOrCreateArtist]);
     * - [Picked]: a row picked out of the suggestions, by id;
     * - [Unknown]: null, or a blank typed name — the seeded *Unknown Artist* (decision 28a).
     */
    private sealed interface ArtistTarget {
        data class Named(val name: String) : ArtistTarget
        data class Picked(val id: String) : ArtistTarget
        data object Unknown : ArtistTarget
    }

    private fun artistTarget(choice: LookupChoice?): ArtistTarget = when {
        choice is LookupChoice.Typed && choice.name.isNotBlank() -> ArtistTarget.Named(choice.name.trim())
        choice is LookupChoice.Picked -> ArtistTarget.Picked(choice.id)
        else -> ArtistTarget.Unknown
    }

    /**
     * The artist id the target names, **without writing anything**: a picked id as it is, a typed
     * name's derived id, and *Unknown Artist*. [findOrCreateArtist] derives through
     * [artistIdForName] too, so the two cannot disagree.
     */
    private fun ArtistTarget.artistId(): String = when (this) {
        is ArtistTarget.Named -> artistIdForName(name)
        is ArtistTarget.Picked -> id
        ArtistTarget.Unknown -> UNKNOWN_ARTIST_ID
    }

    /**
     * R23a: whether the song's artist — [songArtistId], currently named [songArtistName] — differs
     * from what was asked for. A typed name is compared by name ([namesDiffer]); **a picked artist
     * is compared by id** (F23 N4), because two artists can carry one display name and the pick is
     * a row, not a spelling. *Unknown Artist* from a blank field never differs: nothing was asked.
     */
    private fun ArtistTarget.differsFrom(songArtistId: String, songArtistName: String): Boolean = when (this) {
        is ArtistTarget.Named -> namesDiffer(songArtistName, name)
        is ArtistTarget.Picked -> songArtistId != id
        ArtistTarget.Unknown -> false
    }

    /** The derived artist id for a name (decisions 2, 4): `UUIDv5(namespace(artist), normalise(name))`. */
    private fun artistIdForName(name: String): String = Ids.derived("artist", name.trim())

    private fun insertSong(id: String, title: String, artistId: String) {
        database.songQueries.insert(
            id = id,
            title = title,
            artist_id = artistId,
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
            updated_at = Timestamps.now(clock),
            deleted_at = null,
            device_id = deviceId,
        )
    }

    private fun artistName(artistId: String): String =
        database.artistQueries.selectById(artistId).executeAsOneOrNull()?.name.orEmpty()

    /**
     * Save the whole row (R14) — `song.update` writes every column and last-write-wins merges
     * per row anyway. The id is not touched (R22).
     *
     * A typed artist or groove is created on save, or resolved to an existing row by its
     * derived id (R16, E5); a picked one that has since been removed is revived (R23b). The
     * lookup writes and the song write are **one transaction** (E38).
     *
     * **E37: this never clears a tombstone.** A save that lands after the song was removed
     * writes nothing — no song, no artist, no groove — and returns [SongSave.Gone]. The
     * `WHERE deleted_at IS NULL` on `song.update` is the defence of record; the check here only
     * keeps the lookups from being written for a save that will not land.
     *
     * The values are expected to have passed `SongDraft.validate`; a `CHECK` violation that got
     * past it surfaces as the driver's exception, which the caller reports (R14, S8).
     */
    public fun updateSong(songId: String, fields: SongFields): SongSave =
        database.transactionWithResult {
            if (!songIsLive(songId)) return@transactionWithResult SongSave.Gone
            val artist = resolve(artistTarget(fields.artist))
            val grooveId = fields.groove?.let(::resolveGroove)
            database.songQueries.update(
                title = fields.title,
                artist_id = artist.artistId,
                reference_recording = fields.referenceRecording,
                key_signature = fields.keySignature,
                tonal_centre = fields.tonalCentre,
                tonality_note = fields.tonalityNote,
                tempo_bpm = fields.tempoBpm,
                duration_seconds = fields.durationSeconds,
                decade = fields.decade,
                loop_length = fields.loopLength,
                chord_count = fields.chordCount,
                chord_pattern = fields.chordPattern,
                groove_id = grooveId,
                mashup_note = fields.mashupNote,
                notes = fields.notes,
                chart_url = fields.chartUrl,
                updated_at = Timestamps.now(clock),
                device_id = deviceId,
                id = songId,
            )
            SongSave.Saved(artist)
        }

    /**
     * **The one artist rule** (style review B2), over the one [ArtistTarget] decision (F22 N1).
     * *Unknown Artist* and a picked id are taken as they are, and revived if removed since the
     * suggestions were read (R23b). A typed name is create-on-enter ([findOrCreateArtist]).
     */
    private fun resolve(target: ArtistTarget): ArtistResolution = when (target) {
        is ArtistTarget.Named -> findOrCreateArtist(target.name)
        is ArtistTarget.Picked -> pickedArtist(target.id)
        ArtistTarget.Unknown -> pickedArtist(UNKNOWN_ARTIST_ID)
    }

    private fun pickedArtist(artistId: String): ArtistResolution {
        val revived = reviveArtistIfRemoved(artistId)
        return ArtistResolution(
            resolution = if (revived) Resolution.REVIVED else Resolution.EXISTING,
            artistId = artistId,
            name = artistName(artistId),
        )
    }

    /** R23b for an artist, through the one [reviveIfRemoved]: untombstone, keeping spelling and sort name. */
    private fun reviveArtistIfRemoved(artistId: String): Boolean =
        reviveIfRemoved(database.artistQueries.selectById(artistId).executeAsOneOrNull(), { it.deleted_at }) {
            database.artistQueries.restore(
                updated_at = Timestamps.now(clock),
                device_id = deviceId,
                id = artistId,
            )
        }

    /**
     * R16: the groove field is a create-on-enter type-ahead through the lookup path the manage
     * screen uses (E13); a picked groove that has since been removed is revived (R23b).
     */
    private fun resolveGroove(choice: LookupChoice): String = when (choice) {
        is LookupChoice.Picked -> choice.id.also { lookups.reviveIfRemoved(LookupTableKey.GROOVE, it) }
        is LookupChoice.Typed -> lookups.add(LookupTableKey.GROOVE, choice.name)
    }

    /**
     * R21: remove a song — a soft delete. Its practice history is kept and hidden (decisions
     * 8, 9), and adding it again by the same title and artist revives this row ([addSong]).
     *
     * @return false when there was no live song to remove (R23d): nothing was written.
     */
    public fun removeSong(songId: String): Boolean =
        database.softDelete(clock) { now ->
            database.songQueries.softDelete(now, now, deviceId, songId)
        }

    /** R23c's test: a song that exists and is not tombstoned — the one definition, in `Writes.kt`. */
    internal fun songIsLive(songId: String): Boolean = database.songIsLive(songId)

    // ---- Artists ------------------------------------------------------------------------

    /**
     * Live artists, ordered by `sort_name` — the article at the end, per decision 21. The
     * artist type-ahead's list: with `ArtistSuggestions.search` it needs no `SessionPreferences`.
     */
    public fun artists(): List<RepertosaurusRepository.Artist> =
        database.artistQueries.selectAllLive().executeAsList()
            .map { RepertosaurusRepository.Artist(id = it.id, name = it.name, sortName = it.sort_name) }

    /** R24: every live artist with its live song count, by sort name. */
    public fun artistsWithSongCounts(): List<ArtistEntry> =
        database.artistQueries.selectAllLiveWithSongCount().executeAsList()
            .map { ArtistEntry(it.id, it.name, it.sort_name, it.live_songs) }

    /** R25: how many live songs point at this artist. */
    public fun liveSongCount(artistId: String): Long =
        database.songQueries.countLiveByArtist(artistId).executeAsOne()

    /**
     * The type-ahead's create-on-enter (decision 16), and the reason decision 17 exists.
     *
     * The id is `UUIDv5(namespace(artist), normalise(name))` (decisions 2, 4), so a name that
     * normalises to one already stored — `Fratellis` against `The Fratellis` — lands on the
     * *existing row*. The stored display name is left alone (decision 5). A tombstone at the id
     * is revived, spelling and sort name kept (R21); a live row comes back with its current
     * name, so a type-ahead that landed on a renamed artist can say so (R23).
     */
    public fun findOrCreateArtist(name: String): ArtistResolution {
        val display = name.trim()
        require(display.isNotEmpty()) { "an artist needs a name" }
        val id = artistIdForName(display)
        return database.transactionWithResult {
            val resolution = database.insertOrRevive(
                read = { database.artistQueries.selectById(id).executeAsOneOrNull() },
                deletedAt = { it.deleted_at },
                insert = Insert.Plain {
                    database.artistQueries.insert(
                        id = id,
                        name = display,
                        sort_name = sortName(display),
                        updated_at = Timestamps.now(clock),
                        deleted_at = null,
                        device_id = deviceId,
                    )
                },
                revive = {
                    database.artistQueries.restore(
                        updated_at = Timestamps.now(clock),
                        device_id = deviceId,
                        id = id,
                    )
                },
            )
            ArtistResolution(resolution, id, artistName(id))
        }
    }

    /**
     * R24: rename, and set the sort name. A blank [sortName] derives one from [name] as create
     * does (decision 21). The id is never re-derived (decision 5, E21, R22). R25a: the seeded
     * *Unknown Artist* can be renamed.
     *
     * @return false when there was no live artist to rename (E37): nothing was written.
     */
    public fun renameArtist(artistId: String, name: String, sortName: String? = null): Boolean {
        val display = name.trim()
        require(display.isNotEmpty()) { "an artist needs a name" }
        val sort = sortName?.trim()?.takeIf { it.isNotEmpty() } ?: sortName(display)
        return database.wrote {
            database.artistQueries.update(
                name = display,
                sort_name = sort,
                updated_at = Timestamps.now(clock),
                device_id = deviceId,
                id = artistId,
            )
        }
    }

    /**
     * Remove an artist (R24, R25, R25a, R23d), in this order:
     *
     * 1. R25a: the seeded *Unknown Artist* is never removed, whatever its song count — a blank
     *    artist on add-song resolves to its fixed id. Checked before the count is read.
     * 2. R25: an artist with live songs is refused, with the count.
     * 3. Otherwise a soft delete; [ArtistRemoval.Gone] when there was no live row to remove.
     *
     * E38: the count and the delete are one transaction, so a song added between them cannot
     * end up pointing at a removed artist. R25a needs no transaction — it is a fact about the id
     * — so it is answered before one is opened, and no write sits inside a `when` condition
     * (style review F14 N1).
     */
    public fun removeArtist(artistId: String): ArtistRemoval {
        if (artistId == UNKNOWN_ARTIST_ID) return ArtistRemoval.Protected
        return database.transactionWithResult {
            val live = liveSongCount(artistId)
            if (live > 0L) return@transactionWithResult ArtistRemoval.Refused(live)
            val removed = database.softDelete(clock) { now ->
                database.artistQueries.softDelete(now, now, deviceId, artistId)
            }
            if (removed) ArtistRemoval.Removed else ArtistRemoval.Gone
        }
    }

    /** Article moved to the end, per decision 21. */
    private fun sortName(name: String): String =
        if (name.startsWith("The ")) name.substring(4) + ", The" else name

    // ---- Tags (R17) ---------------------------------------------------------------------

    /** A song's live tags, by tag name. A removed tag is not listed. */
    public fun songTags(songId: String): List<SongTag> =
        database.song_tagQueries.selectBySong(songId).executeAsList()
            .map { SongTag(id = it.id, tagId = it.tag_id, tagName = it.tag_name) }

    /**
     * Tag a song: insert, or **revive** the tombstone at the derived id (R17, E6's rule), or
     * nothing when it is already live. R23b: a removed tag is revived. R23c: on a removed song,
     * nothing is written and the result says [JunctionWrite.SongGone]. The envelope is
     * [SongChildren]'s.
     */
    public fun addTag(songId: String, tagId: String): JunctionWrite =
        children.add(SongChildKey.TAG, songId, listOf(tagId))

    /**
     * The same from a typed tag name — the tag is created on enter (E5, E13), and the tag and
     * the junction are one transaction (E38). R23c: on a removed song, not even the tag is
     * created. [SongChildren.addNamed]'s, the one by-name add (F22 B1).
     */
    public fun addTagNamed(songId: String, tagName: String): JunctionWrite =
        children.addNamed(SongChildKey.TAG, songId, listOf(tagName))

    /**
     * Untag: a soft delete (R17, E7), by the `song_tag` row id like its siblings. R23c: on a
     * removed song nothing is written; R23d: [JunctionWrite.Removed.wrote] is false when the row
     * was not live.
     */
    public fun removeTag(id: String): JunctionWrite = children.remove(SongChildKey.TAG, id)

    // ---- song_instrument (R19) ----------------------------------------------------------

    /** A song's live per-instrument rows, by instrument name. */
    public fun songInstruments(songId: String): List<SongInstrument> =
        database.song_instrumentQueries.selectBySong(songId).executeAsList().map { row ->
            SongInstrument(
                id = row.id,
                instrumentId = row.instrument_id,
                instrumentName = row.instrument_name,
                difficulty = row.difficulty,
                patch = row.patch,
                notes = row.notes,
            )
        }

    /**
     * Add an instrument's row to a song: insert with no facts, or **revive** the tombstone at
     * the derived id keeping its difficulty, patch and notes (E6's rule), or nothing when it is
     * already live. Facts are set by [updateSongInstrument] afterwards, so a revive can never
     * be what resets them. R23b: a removed instrument is revived. R23c: on a removed song,
     * [JunctionWrite.SongGone]. The envelope is [SongChildren]'s.
     */
    public fun addSongInstrument(songId: String, instrumentId: String): JunctionWrite =
        children.add(SongChildKey.INSTRUMENT, songId, listOf(instrumentId))

    /**
     * The three facts on a `song_instrument` row. [difficulty] is 1-5 or null (decision 42) — a
     * picked value in the UI (R13), so anything else is a programming error and fails before it
     * reaches the `CHECK`. Blank text is stored as null.
     *
     * @return false when nothing was written: the row is not live (E37), or its song is removed
     *   (R23c).
     */
    public fun updateSongInstrument(id: String, difficulty: Long?, patch: String?, notes: String?): Boolean {
        require(difficulty == null || difficulty in DIFFICULTY_RANGE) {
            "difficulty is 1-5 or null, got $difficulty"
        }
        return children.update(SongChildKey.INSTRUMENT, id) { now ->
            database.song_instrumentQueries.update(
                difficulty = difficulty,
                patch = patch?.trim()?.takeIf { it.isNotEmpty() },
                notes = notes?.trim()?.takeIf { it.isNotEmpty() },
                updated_at = now,
                device_id = deviceId,
                id = id,
            )
        }
    }

    /** Remove: a soft delete (R19, E7), reported as [removeTag] reports. */
    public fun removeSongInstrument(id: String): JunctionWrite = children.remove(SongChildKey.INSTRUMENT, id)

    public companion object {
        /** Decision 42's range, the `song_instrument` `CHECK` — and R13's picker values. */
        public val DIFFICULTY_RANGE: LongRange = 1L..5L

        /**
         * The seeded placeholder of decision 28a — `UUIDv5(namespace(artist), 'unknown
         * artist')`, and the escape hatch when the user does not want to settle an attribution
         * to log a song. A placeholder is honest; a null FK is not an option because
         * `song.artist_id` is NOT NULL. R25a: never removed.
         */
        public const val UNKNOWN_ARTIST_ID: String = "cf06771d-4e8d-53fc-83fb-359be7dfaefc"
    }
}

/**
 * How a song add resolved (R21-R23, style review N1): [resolution], and the row's **current**
 * title and artist name — which, for [Resolution.REVIVED] and [Resolution.EXISTING], may not be
 * what was typed, because a rename never re-derives an id (R22).
 */
public data class SongResolution(
    val resolution: Resolution,
    val songId: String,
    val title: String,
    val artistName: String,
)

/**
 * How an artist field resolved (R23, style review N1): [resolution], and the row's **current**
 * name, so a typed name that landed on a renamed artist can be said out loud.
 */
public data class ArtistResolution(
    val resolution: Resolution,
    val artistId: String,
    val name: String,
) {
    /** R23: whether the row's current name differs from what was typed — [namesDiffer], the one rule. */
    public fun differsFrom(typedName: String): Boolean = namesDiffer(name, typedName)
}
