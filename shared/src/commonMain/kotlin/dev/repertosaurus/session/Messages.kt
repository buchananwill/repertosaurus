package dev.repertosaurus.session

import dev.repertosaurus.core.Keys
import dev.repertosaurus.core.NoteSpelling
import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.data.JunctionWrite
import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.data.Resolution
import dev.repertosaurus.data.SongCatalog
import dev.repertosaurus.data.SongChildKey
import dev.repertosaurus.data.SongMerge

/**
 * A sentence for the user **and the channel it belongs on** (E43, R29): a confirmation on the
 * message channel, or a refusal on the error channel, rendered in the error colour. Which channel
 * a sentence goes on is a rule, so it is decided here with the words rather than by each screen.
 */
public data class Notice(val text: String, val channel: Channel) {

    public enum class Channel {
        /** A confirmation, or a truthful no-op: the user's intent holds. */
        MESSAGE,

        /** A refusal or a failure: something the user asked for did not happen. */
        ERROR,
    }

    public companion object {
        public fun message(text: String): Notice = Notice(text, Channel.MESSAGE)
        public fun error(text: String): Notice = Notice(text, Channel.ERROR)
    }
}

/**
 * **Every refusal and confirmation the editing routes say, in one place** (style review F17 B2,
 * F19 B1/N1/N2, F16 #4). Before this, two `SONG_GONE` constants had already drifted apart, "already
 * removed" went to the message channel in one ViewModel and the error channel in two, the
 * practice line was assembled in a composable, and the singular/plural rule was written twice.
 * The desktop UI needs every one of these verbatim.
 *
 * **Channel ruling (session 09, F17 B2): an R23d no-op — "already removed" — is the message
 * channel.** It is truthful, and it is not a failure of the user's intent: the thing they wanted
 * gone is gone.
 */
public object Messages {

    // ---- The logger (rating-scale RS14) ---------------------------------------------------

    /** The undo snackbar: "Logged Jolene · feel: certainly". */
    public fun logged(title: String, feel: RatingLevel?): String =
        "Logged $title" + (feel?.let { " · feel: ${it.label}" } ?: "")

    // ---- Adding a song (R21-R23a) ---------------------------------------------------------

    /**
     * **R23, R23a: what an add says**, for both add paths — the logger (message only, per the
     * session 09 ruling) and the Songs route (message, and it opens the song).
     *
     * R22 means an add can land on a row whose current title or artist is not what was asked
     * for, and silently returning it is the failure R23 exists to prevent. Every label is
     * [songLabel]'s — no screen assembles its own dash (E46).
     *
     * - created: "Added *label*", plus the artist's current name when the typed artist landed on
     *   a renamed one;
     * - revived: "Restored *label*" (R21), and "already in the repertoire as" when it differs;
     * - existing: "Already in the repertoire as *label*".
     */
    public fun songAdd(add: SongCatalog.SongAdd): String {
        val label = songLabel(add.song.title, add.song.artistName)
        return when (add.song.resolution) {
            Resolution.CREATED ->
                if (add.artistDiffers) {
                    "Added $label. The artist is already in the repertoire as ${add.song.artistName}."
                } else {
                    "Added $label."
                }
            Resolution.REVIVED ->
                if (add.differs) {
                    "Already in the repertoire as $label. It had been removed, and is restored."
                } else {
                    "Restored $label. It had been removed."
                }
            Resolution.EXISTING ->
                if (add.differs) {
                    "Already in the repertoire as $label."
                } else {
                    "$label is already in the repertoire."
                }
        }
    }

    /**
     * The logger's add (session 09 ruling on R23: the message only). V9: a song with no
     * `song_performer` row is excluded by any filter, so a song added inside a filtered View is
     * saved and then invisible — and saying so beats looking broken.
     */
    public fun songAddInView(add: SongCatalog.SongAdd, visibleInView: Boolean): String =
        if (visibleInView) songAdd(add) else songAdd(add) + " " + HIDDEN_BY_VIEW

    public const val HIDDEN_BY_VIEW: String = "This view's filter hides it."

    // ---- Saving and removing a song (R14, R21) ---------------------------------------------

    /** R14: Save was pressed over a draft with errors. The fields say which. */
    public const val SAVE_INVALID: String = "Nothing was saved: fix the fields marked in red."

    /** E37: the song was removed before the save landed. */
    public const val SAVE_SONG_GONE: String = "This song has been removed, so nothing was saved."

    /** The detail was opened on a song that is no longer live. */
    public const val SONG_NOT_FOUND: String = "That song is no longer in the repertoire."

    /**
     * R14 and R23 for a save: "Saved.", and a typed artist that landed on a row under another name
     * says so. [SongCatalog.SongSave.Gone] is a refusal.
     */
    public fun songSave(fields: SongCatalog.SongFields, save: SongCatalog.SongSave): Notice = when (save) {
        is SongCatalog.SongSave.Saved -> {
            val typed = (fields.artist as? SongCatalog.LookupChoice.Typed)?.name
            if (typed != null && save.artist.differsFrom(typed)) {
                Notice.message("Saved. The artist is already in the repertoire as ${save.artist.name}.")
            } else {
                Notice.message(SAVED)
            }
        }
        SongCatalog.SongSave.Gone -> Notice.error(SAVE_SONG_GONE)
    }

    public const val SAVED: String = "Saved."

    /** R21 and R23d: a removal says what it removed; a removal that removed nothing says that. */
    public fun songRemoval(label: String, removed: Boolean): Notice =
        if (removed) Notice.message("Removed $label. Its practice history is kept.") else alreadyRemoved(label)

    // ---- Artists (R24, R25, R25a, R23d) ------------------------------------------------------

    public const val ARTIST_NEEDS_NAME: String = "An artist needs a name."

    public const val ARTIST_RENAME_GONE: String = "That artist has been removed. Nothing was written."

    /** R24: a rename that landed, or E37's refusal of one on a removed artist. */
    public fun artistRename(name: String, wrote: Boolean): Notice =
        if (wrote) Notice.message("Renamed to ${name.trim()}.") else Notice.error(ARTIST_RENAME_GONE)

    /** R25a, on the screen: why the seeded placeholder cannot go. */
    public const val ARTIST_PROTECTED: String =
        "Unknown Artist cannot be removed: a song added with no artist is filed under it. " +
            "It can be renamed."

    /** R25: the refusal names how many songs still point at the artist. */
    public fun artistRefused(name: String, liveSongs: Long): String =
        if (liveSongs == 1L) {
            "$name still has 1 song, so it cannot be removed. Give that song another artist first."
        } else {
            "$name still has ${songCount(liveSongs)}, so it cannot be removed. Give those songs " +
                "another artist first."
        }

    /** Each of [SongCatalog.ArtistRemoval]'s four answers, worded and on its channel. */
    public fun artistRemoval(name: String, removal: SongCatalog.ArtistRemoval): Notice = when (removal) {
        SongCatalog.ArtistRemoval.Removed -> Notice.message("Removed $name.")
        is SongCatalog.ArtistRemoval.Refused -> Notice.error(artistRefused(name, removal.liveSongs))
        SongCatalog.ArtistRemoval.Protected -> Notice.error(ARTIST_PROTECTED)
        SongCatalog.ArtistRemoval.Gone -> alreadyRemoved(name)
    }

    /**
     * **The singular/plural rule, once** (F19 N2, F22 N5): "1 song", "12 songs", "1 artist". Every
     * count line goes through here, so none can say "1 of 1 artists".
     */
    public fun nounCount(n: Long, singular: String, plural: String = singular + "s"): String =
        if (n == 1L) "1 $singular" else "$n $plural"

    /** "1 song", "12 songs". */
    public fun songCount(count: Long): String = nounCount(count, "song")

    /** "1 artist", "282 artists". */
    public fun artistCount(count: Long): String = nounCount(count, "artist")

    // ---- The list routes' count and empty lines (F22 N5) --------------------------------------

    /**
     * **The "N of M" line, once**: how many rows a search is showing out of a counted total —
     * "52 of 282 artists", "1 of 1 artist", "3 of 12 songs". [total] is already worded by
     * [nounCount], so the noun agrees with the total, which is what the line counts.
     */
    public fun shownOf(shown: Int, total: String): String = "$shown of $total"

    /** A search that matched nothing, on every list that has one. */
    public fun nothingMatches(query: String): String = "Nothing matches \"$query\"."

    /** A song list with no songs in it and no search typed. */
    public const val NO_SONGS_YET: String = "No songs yet."

    /** The merge picker with no search typed and no song but the one the merge started from. */
    public const val MERGE_NO_OTHER_SONGS: String = "There is no other song to merge with."

    // ---- The song detail's sections, shared with the merge preview (F27 N2) --------------------

    /** The heading over the song's own fields, where a screen shows one (the merge preview). */
    public const val SECTION_FIELDS: String = "Fields"

    /** R20's section, and the merge preview's events. */
    public const val SECTION_PRACTICE: String = "Practice"

    /** A child table's section: the detail's heading, and the merge preview's over the same rows. */
    public fun songSection(table: SongChildKey): String = when (table) {
        SongChildKey.TAG -> "Tags"
        SongChildKey.INSTRUMENT -> "Per instrument"
        SongChildKey.PERFORMER -> "Who plays this"
    }

    // ---- A song's junction rows (R17, R19, E1-E11, R23c, R23d) -------------------------------

    /**
     * R23c: a write to a child of a removed song wrote nothing. One sentence for every child
     * table — the capability editor and the song detail had drifted apart on it.
     */
    public const val SONG_GONE: String = "This song has been removed, so nothing was written."

    /** An update that wrote nothing: the row or its song is gone (E37, R23c). */
    public const val NOTHING_WRITTEN: String = "That row or its song has been removed. Nothing was written."

    /** R23d with no name to hand: the row was already removed. */
    public const val ALREADY_REMOVED: String = "That was already removed."

    /** R23d, named — and on the message channel, per the channel ruling. */
    public fun alreadyRemoved(label: String): Notice = Notice.message("$label was already removed.")

    /**
     * **The junction-write message, with its channel.** [JunctionWrite.SongGone] is a refusal;
     * a remove that removed nothing is an R23d no-op on the message channel; anything that wrote
     * says [done], and an add that found the row already there says [unchanged]. Null means the
     * screen stays quiet — the tag chips and the toggle list show the result themselves.
     */
    public fun junctionWrite(
        write: JunctionWrite,
        done: String? = null,
        unchanged: String? = done,
        alreadyRemoved: String = ALREADY_REMOVED,
    ): Notice? = when (write) {
        is JunctionWrite.SongGone -> Notice.error(SONG_GONE)
        is JunctionWrite.Removed ->
            if (write.wrote) done?.let(Notice::message) else Notice.message(alreadyRemoved)
        is JunctionWrite.Added ->
            (if (write.wrote) done else unchanged)?.let(Notice::message)
    }

    /** An update to a junction row's facts: [done] when it wrote, [NOTHING_WRITTEN] when not. */
    public fun junctionUpdate(wrote: Boolean, done: String? = null): Notice? =
        if (wrote) done?.let(Notice::message) else Notice.error(NOTHING_WRITTEN)

    // ---- The capability editor's words (E5-E7) ----------------------------------------------

    public fun capabilityAdded(performer: String, instrument: String): String = "Added $performer on $instrument"

    public fun capabilityPresent(performer: String, instrument: String): String =
        "$performer on $instrument was already in the line-up."

    public fun capabilitySaved(performer: String): String = "Saved $performer"

    public fun capabilityRemoved(performer: String): String = "Removed $performer"

    public fun capabilityAlreadyRemoved(performer: String): String = "$performer on that part was already removed."

    // ---- The Repertoire toggle list (R8, R23c) -----------------------------------------------

    /** R8 + R23c: the toggled song was removed elsewhere; the row rolls back and the list re-reads. */
    public fun toggleSongGone(label: String): String =
        "$label has been removed, so nothing was written. The list has been read again."

    /** R8: a toggle whose write failed, rolled back. */
    public fun toggleFailed(label: String, failure: Throwable): String = "Could not change $label: ${failure.message}"

    // ---- Lookups and Views (R23d on the manage screens) --------------------------------------

    /** A lookup removal: said when it removed, and said truthfully when it was already gone. */
    public fun lookupRemoval(name: String, removed: Boolean): Notice =
        if (removed) Notice.message("Removed $name") else alreadyRemoved(name)

    /** A View deletion that deleted nothing — it was already removed (R23d). */
    public fun viewAlreadyRemoved(): Notice = alreadyRemoved("That view")

    // ---- The practice summary (R20) -----------------------------------------------------------

    /** What the practice line calls an instrument whose row has since been removed. */
    public const val REMOVED_INSTRUMENT: String = "Removed instrument"

    /**
     * **R20's line, built here and not in a composable** (F19 B1): `Vocal: 3 logged, last on
     * 2026-09-10`. An instrument removed since is named as such rather than left blank.
     */
    public fun practiceLine(summary: RepertosaurusRepository.PracticeSummary): String =
        "${summary.instrumentName?.let(::titleCase) ?: REMOVED_INSTRUMENT}: " +
            "${summary.timesPractised} logged, last on ${summary.lastPractised}"

    // ---- Merging two songs (R31-R39, R38a) ----------------------------------------------------

    /** The picker's heading: which song the merge started from. */
    public fun mergeWith(label: String): String = "Merge $label with…"

    public const val MERGE_PICK_HINT: String = "Pick the other entry for this song."

    /** Why "Merge with…" is dead: the merge re-reads the song, so unsaved edits would be lost. */
    public const val MERGE_NEEDS_SAVE: String = "Save or discard your changes before merging."

    public fun mergeKeeps(label: String): String = "Keeps: $label"

    public fun mergeRemoves(label: String): String = "Merged away and removed: $label"

    /**
     * One side's value of a field in the preview (R34), or [MERGE_BLANK]. The two picked columns
     * are labelled by [Keys] as the detail labels them (R13); every other field is the draft's own
     * value ([SongDraft.value]). The tonal centre follows the user's [spelling] (R40-R42).
     */
    public fun mergeFieldValue(field: SongField, draft: SongDraft, spelling: NoteSpelling): String {
        val value = when (field) {
            SongField.KEY_SIGNATURE -> draft.keySignature?.let(Keys::keySignatureLabel)
            SongField.TONAL_CENTRE -> draft.tonalCentre?.let { Keys.noteName(it, draft.keySignature, spelling) }
            else -> draft.value(field)
        }
        return value ?: MERGE_BLANK
    }

    public const val MERGE_BLANK: String = "(blank)"

    public const val MERGE_FIELDS_SAME: String = "Every field already matches."

    /**
     * One child row: its parents by name ("Will on vocal", "keys", "party"), where it is held, and
     * — R38a / R23b — that carrying it restores a removed tag, instrument or performer.
     */
    public fun mergeChild(row: MergeChildRow): String {
        val names = row.parentNames.joinToString(" on ")
        val where = when {
            row.onSurvivor && row.onLoser -> "both"
            row.onSurvivor -> "kept song"
            else -> "merged song"
        }
        val restored = if (row.parentRemoved) "; keeping it restores a removed entry" else ""
        return "$names ($where$restored)"
    }

    /** One event in the preview (R33): its date and instrument. */
    public fun mergeEvent(event: SongMerge.Event): String =
        "${event.loggedOn} · ${event.instrumentName?.let(::titleCase) ?: REMOVED_INSTRUMENT}"

    /**
     * **R33's carried/dropped sentence, once** (style review F27 N1): "3 practice events carried,
     * 1 dropped" — and no "0 dropped", on every surface that says it (the preview's counts, the
     * confirmation, the landed merge).
     */
    public fun eventsCarried(carried: Int, dropped: Int): String =
        "${nounCount(carried.toLong(), "practice event")} carried" + (if (dropped > 0) ", $dropped dropped" else "")

    /** R33's counts: what is carried, what is dropped, and what the survivor already has. */
    public fun mergeEventCounts(plan: MergePlan): String {
        val kept = nounCount(plan.survivor.events.size.toLong(), "practice event")
        if (plan.loser.events.isEmpty()) return "The merged song has no practice history. The kept song has $kept."
        return "${eventsCarried(plan.eventsCarried, plan.eventsDropped)}. The kept song has $kept."
    }

    /** R37: the set-list items that move with the merge. */
    public fun mergeSetlistItems(count: Long): String =
        "${nounCount(count, "set-list entry", "set-list entries")} will point at the kept song."

    /** R38: the confirmation — what happens, and that it cannot be undone from the app. */
    public fun mergeConfirmation(plan: MergePlan): String {
        val survivor = songLabel(plan.survivor.record.title, plan.survivor.record.artistName)
        val loser = songLabel(plan.loser.record.title, plan.loser.record.artistName)
        return "$loser is removed and merged into $survivor. " +
            "${eventsCarried(plan.eventsCarried, plan.eventsDropped)}. " +
            MERGE_IRREVERSIBLE
    }

    public const val MERGE_IRREVERSIBLE: String = "This cannot be undone from the app."

    /**
     * What a merge says, with its channel: a landed merge on the message channel; a refusal or a
     * failure — which wrote nothing (R36, R38a) — on the error channel.
     */
    public fun mergeOutcome(outcome: SongMerge.Outcome, survivorLabel: String): Notice = when (outcome) {
        is SongMerge.Outcome.Merged -> Notice.message(
            "Merged into $survivorLabel: ${eventsCarried(outcome.eventsCarried, outcome.eventsDropped)}.",
        )
        is SongMerge.Outcome.Refused -> Notice.error(
            when (outcome.reason) {
                SongMerge.Refusal.SONG_GONE -> MERGE_SONG_GONE
                SongMerge.Refusal.NOT_WRITTEN -> MERGE_NOT_WRITTEN
            },
        )
        is SongMerge.Outcome.Failed -> Notice.error("Nothing was merged: ${outcome.cause.message}")
    }

    public const val MERGE_SONG_GONE: String = "One of the two songs has been removed, so nothing was merged."

    public const val MERGE_NOT_WRITTEN: String =
        "Something changed under the merge, so nothing was merged. Open the preview again."

    // ---- Failures -----------------------------------------------------------------------------

    /** A write that threw (S8): caught, and said on the error channel. */
    public fun failed(failure: Throwable): String = "That did not work: ${failure.message}"

    /** A read or write that threw, naming what it was doing: "Could not load the songs: …". */
    public fun couldNot(action: String, failure: Throwable): String = "Could not $action: ${failure.message}"

    /**
     * F18 N2: a write binds to the database that was current when it was tapped. An import that
     * replaced the database before the write reached the queue drops the write, and says so.
     */
    public const val DATABASE_REPLACED: String =
        "The database was replaced before this change was written, so nothing was written."
}
