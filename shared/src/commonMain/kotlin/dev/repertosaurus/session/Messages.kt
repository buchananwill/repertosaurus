package dev.repertosaurus.session

import dev.repertosaurus.core.DateLabels
import dev.repertosaurus.core.Keys
import dev.repertosaurus.core.NoteSpelling
import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.data.JunctionWrite
import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.data.Resolution
import dev.repertosaurus.data.SongCatalog
import dev.repertosaurus.data.SongChildKey
import dev.repertosaurus.data.SongMerge
import dev.repertosaurus.habit.HabitDay
import dev.repertosaurus.habit.HabitStats
import dev.repertosaurus.habit.PeriodTotal
import dev.repertosaurus.habit.WeekdayReliability
import kotlinx.datetime.LocalDate

/**
 * A sentence for the user **and the channel it belongs on** (E43, R29): a confirmation on the
 * message channel, or a refusal on the error channel, rendered as an error line (D97). Which channel
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

    /**
     * The undo snackbar: "Logged Jolene · feel: certainly". A timer's log (timer TM7, TM8) says its time,
     * "Logged Jolene · 24 min", or "Logged Jolene (too short to time)"; [time] is null for any other log.
     */
    public fun logged(title: String, feel: RatingLevel?, time: LoggedTime? = null): String =
        "Logged $title" + (feel?.let { " · feel: ${it.label}" } ?: "") + when (time) {
            is LoggedTime.Timed -> " · ${duration(time.seconds)}"
            LoggedTime.TooShort -> " (too short to time)"
            null -> ""
        }

    /** The one "Log it": the feel sheet's and the suggestion card's. */
    public const val LOG_IT: String = "Log it"

    /** The one "Undo": a log's snackbar, a cancelled timer's, and a staged skip's. */
    public const val UNDO: String = "Undo"

    /** The one "Cancel": every dialog's way out, and the running timer's (timer TM4). */
    public const val CANCEL: String = "Cancel"

    /** The one "Add song": the logger's foot, the Songs route's, and the add sheet's heading and button. */
    public const val ADD_SONG: String = "Add song"

    /** The button words more than one screen says (D95 B7). */
    public const val DONE: String = "Done"
    public const val REMOVE: String = "Remove"
    public const val SAVE: String = "Save"
    public const val RENAME: String = "Rename"

    /** triage T1: a Repertoire role's other pane, beside [DRAWER_SONGS]. */
    public const val RATINGS_PANE: String = "Ratings"

    /** An import's confirmation: what the picked file holds. */
    public fun importHolds(songs: Long, events: Long): String =
        "The file you picked holds $songs songs and $events practice events."

    /** R3: the toggle list's count, "3 of 12 songs held". */
    public fun heldOf(held: Int, total: Long): String = "${shownOf(held, songCount(total))} held"

    // ---- The practice timer (timer TM1-TM12) ----------------------------------------------------

    /** timer TM1: the feel sheet's timer button, and TM2's while another timer runs. */
    public const val TIMER_START: String = "Start timer"
    public const val TIMER_SWITCH: String = "Switch timer here"

    /** timer TM1: the suggestion card's. */
    public const val TIMER_TIME_IT: String = "Time it"

    /** timer TM4. */
    public const val TIMER_STOP: String = "Stop"

    /** timer TM4: the actions of the bar's clock and of the full-screen digits, read with the time. */
    public const val TIMER_OPEN_CLOCK: String = "Open the full-screen clock"
    public const val TIMER_CLOSE_CLOCK: String = "Back to the list"

    /** timer TM4: the drawer's line. */
    public fun timerRunning(title: String): String = "Timer running: $title"

    /** timer TM9: "Timer cancelled · Undo", with [UNDO]. */
    public const val TIMER_CANCELLED: String = "Timer cancelled"

    /** timer TM8: the one question, and its two buttons. */
    public fun timerQuestion(seconds: Long): String = "Log ${duration(seconds)}, or log without a time?"
    public fun timerLogWith(seconds: Long): String = "Log ${duration(seconds)}"
    public const val TIMER_LOG_WITHOUT: String = "Log without a time"

    /** timer TM10. */
    public const val TIMER_SONG_REMOVED: String = "A timer for a removed song was discarded"

    /** timer TM10: the stored timer could not be read or written (S11: said, never swallowed). */
    public fun timerReadFailed(failure: Throwable): String = couldNot("read the timer", failure)
    public fun timerWriteFailed(failure: Throwable): String = couldNot("remember the timer", failure)

    /** timer TM7, TM8: a duration in words: "45 s", "24 min", "5 h 12 min". Minutes are whole, rounded down. */
    public fun duration(seconds: Long): String {
        val minutes = seconds / 60L
        return when {
            minutes == 0L -> "$seconds s"
            minutes < 60L -> "$minutes min"
            else -> "${minutes / 60L} h ${minutes % 60L} min"
        }
    }

    /** timer TM4: the clock, `mm:ss`, and `h:mm:ss` from an hour. [seconds] is never negative (TM11). */
    public fun timerClock(seconds: Long): String {
        val two = { n: Long -> n.toString().padStart(2, '0') }
        return if (seconds < 3_600L) {
            "${two(seconds / 60L)}:${two(seconds % 60L)}"
        } else {
            "${seconds / 3_600L}:${two(seconds % 3_600L / 60L)}:${two(seconds % 60L)}"
        }
    }

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

    // ---- Paging and the ratings editor (triage T1-T9) -----------------------------------------

    /** triage T5: a page the filter has emptied, on either paged list. */
    public const val NOTHING_UNDER_THE_FILTER: String = "No songs here under this filter."

    /** triage T1: a Repertoire role's editor with no song enabled on the role. */
    public const val RATINGS_NO_ENABLED_PARTS: String = "This part is on for no songs yet. Turn some on under Songs."

    /** triage T1: the View entry's editor on an empty pool. */
    public const val RATINGS_EMPTY_VIEW: String = "This view has no songs."

    /** triage T9: the View menu's "Rate these songs" when no performer resolves. */
    public const val RATE_NEEDS_PERFORMER: String = "Set who you are from the menu (Who you are) to rate these songs"

    /** triage T9: the disabled Priority and Confidence sort modes (P9). */
    public const val SORT_NEEDS_PERFORMER: String = "Set who you are from the menu (Who you are) to sort by ratings"

    /** triage T6: the mode strip's three segments. */
    public fun sortMode(mode: SortMode): String = when (mode) {
        SortMode.STALENESS -> "Cold"
        SortMode.TRIAGE_PRIORITY -> "Priority"
        SortMode.TRIAGE_CONFIDENCE -> "Confidence"
    }

    /**
     * triage T6, T7: a resolved order read aloud, under the sort and in the View editor. T7's keys read as
     * a sentence, and this is that sentence.
     */
    public fun sortOrder(order: SessionOrder): String = when (order) {
        SessionOrder.COLDEST_FIRST -> "Coldest first"
        SessionOrder.HOTTEST_FIRST -> "Hottest first"
        SessionOrder.TRIAGE_PRIORITY -> "Top priority first, then least confident, then coldest"
        SessionOrder.TRIAGE_PRIORITY_REVERSED -> "Lowest priority first, then most confident, then hottest"
        SessionOrder.TRIAGE_CONFIDENCE -> "Least confident first, then top priority, then coldest"
        SessionOrder.TRIAGE_CONFIDENCE_REVERSED -> "Most confident first, then lowest priority, then hottest"
    }

    /** triage T6: the direction button's description: what a tap on it turns the list to. */
    public fun sortFlip(order: SessionOrder): String = "Reverse the order: " + sortOrder(order.flipped).lowercase()

    /** triage T1: what "Rate these songs" will open. [part] is `RatingsTarget.title`. */
    public fun rateThesePart(part: String): String = "Priority and confidence for $part"

    /** triage T1: the editor's read failed. */
    public fun ratingsReadFailed(failure: Throwable): String = couldNot("read the ratings", failure)

    /** RS9: one rating's write failed; the control shows the level last stored. */
    public fun ratingWriteFailed(failure: Throwable): String = couldNot("save that rating", failure)

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
        "${instrumentLabel(summary.instrumentName)}: ${summary.timesPractised} logged, last on ${summary.lastPractised}"

    private fun instrumentLabel(name: String?): String = name?.let(::titleCase) ?: REMOVED_INSTRUMENT

    /** One event by date and instrument: "2026-09-20 · Vocal". */
    private fun eventLabel(loggedOn: String, instrumentName: String?): String = "$loggedOn · ${instrumentLabel(instrumentName)}"

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
    public fun mergeEvent(event: SongMerge.Event): String = eventLabel(event.loggedOn, event.instrumentName)

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

    // ---- The scorecards (scorecards SC4-SC19) ----------------------------------------------------

    public const val HABIT_TITLE: String = "Scorecards"
    public const val HABIT_KICKER: String = "Your practice"
    public const val HABIT_ALL_INSTRUMENTS: String = "All instruments"

    /** SC4's second scope with no View to name an instrument: shown disabled. */
    public const val HABIT_NO_VIEW_INSTRUMENT: String = "This View's instrument"

    /** SC14. */
    public const val HABIT_EMPTY: String = "Your practice will fill this in"

    /** SC9's section. */
    public const val HABIT_WHICH_DAYS: String = "Which days"

    /** SC5, for accessibility: "Practice days, last 26 weeks". */
    public val habitGridDescription: String = "Practice days, last ${HabitStats.GRID_WEEKS} weeks"

    /** SC11's two headings. */
    public val habitRecentWeeks: String = "Last ${HabitStats.RECENT_WEEKS} weeks"
    public val habitRecentMonths: String = "Last ${HabitStats.RECENT_MONTHS} months"

    /** SC7: "Tue 9 Sep: 5 songs". A gap is stated plainly, never as a failure. */
    public fun habitDay(day: HabitDay): String {
        val label = DateLabels.day(LocalDate.parse(day.date))
        return if (day.count > 0L) "$label: ${songCount(day.count)}${timedFragment(day.timedSeconds)}" else "$label: nothing logged"
    }

    /** SC10: "This week: 4 days, 23 songs". */
    public fun habitThisWeek(total: PeriodTotal): String = habitPeriod("This week", total)
    public fun habitThisMonth(total: PeriodTotal): String = habitPeriod("This month", total)

    private fun habitPeriod(name: String, total: PeriodTotal): String =
        "$name: ${nounCount(total.days.toLong(), "day")}, ${songCount(total.events)}${timedFragment(total.timedSeconds)}"

    /** SC17. No zero guard: a real sum is at least 1 s (schema-3 M10), so a sum wrongly coerced to 0 shows up. */
    private fun timedFragment(timedSeconds: Long?): String =
        if (timedSeconds == null) "" else " · ${duration(timedSeconds)} timed"

    /** SC19: "2026-09-20 · Vocal: 24 min". */
    public fun timedEventLine(event: TimedEvent): String = "${eventLabel(event.loggedOn, event.instrumentName)}: ${duration(event.seconds)}"

    /** SC19: "Timed total: 1 h 10 min". */
    public fun timedTotalLine(seconds: Long): String = "Timed total: ${duration(seconds)}"

    /** SC19, journal session 11 D85 #1: the timed events beyond [TimedHistory.SHOWN], "and 3 more". */
    public fun timedMore(count: Long): String = "and $count more"

    /** SC8: "19 of 26", or "not yet" for a weekday the clipped window has not reached. */
    public fun habitFraction(weekday: WeekdayReliability): String =
        if (weekday.of == 0) "not yet" else "${weekday.practised} of ${weekday.of}"

    /** SC8, whole: "Tuesdays: 19 of 26". */
    public fun habitReliability(weekday: WeekdayReliability): String =
        "${DateLabels.weekdayPlural(weekday.isoDay)}: ${habitFraction(weekday)}"

    /** SC9: the strongest day may be named; the weakest never is. */
    public fun habitStrongest(isoDay: Int): String = "You show up most on ${DateLabels.weekdayPlural(isoDay)}"

    /** The card's read failed. */
    public fun habitReadFailed(failure: Throwable): String = couldNot("load your practice days", failure)

    // ---- Suggest (suggest SG1-SG11) ---------------------------------------------------------------

    /** SG1: the top-bar action's content description. */
    public const val SUGGEST: String = "Suggest a song"
    public const val SUGGEST_TITLE: String = "Suggestion"
    public const val SUGGEST_ANOTHER: String = "Another"
    public const val SUGGEST_TUNE: String = "Tune"
    public const val SUGGEST_SHUFFLE: String = "Shuffle"

    /** SG4. */
    public const val SUGGEST_EMPTY_POOL: String = "Everything here is logged today"
    public const val SUGGEST_EXHAUSTED: String = "That is every song here, once each. Another starts a fresh deck."

    /** SG11: under the open radar, at pure shuffle and otherwise. */
    public const val SUGGEST_SHUFFLE_HINT: String =
        "Pure shuffle: every song is equally likely. Drag a handle out to give that input more say."
    public const val SUGGEST_TUNED_HINT: String =
        "Drag a handle out to give that input more say. Shuffle puts every one back to zero."

    /** SG11, SG12, SG14: why a spoke is locked (journal session 11, D67 #8). */
    public const val SUGGEST_LOCKED_RATINGS: String = "Ratings not read yet"
    public const val SUGGEST_LOCKED_SKIPS: String = "Skips not counted"
    public const val SUGGEST_LOCKED_SKIPS_PENDING: String = "Skips not read yet"
    public const val SUGGEST_LOCKED_NO_PERFORMER: String = "No performer chosen"

    /** SG12, SG13: the two settings under "Tune". */
    public const val SUGGEST_COUNT_SKIPS: String = "Count skips"
    public const val SUGGEST_COUNT_SKIPS_HINT: String = "Another counts as a skip, with a moment to take it back."
    public const val SUGGEST_SHOW_SKIP_COUNT: String = "Show the count"

    /** SG12: the inline line while a skip can still be taken back. */
    public const val SUGGEST_SKIPPED: String = "Skipped ·"

    /** SG13: information, not a scolding. No exclamation mark. */
    public fun suggestSkipCount(skips: Long): String = "Skipped ${nounCount(skips, "time")} since you last played it"

    /** SG12: a skip's write or the skip counts' read threw (S11: said, never swallowed). */
    public fun suggestSkipsFailed(failure: Throwable): String = couldNot("count skips", failure)

    // ---- First-run onboarding (onboarding OB1-OB7) -----------------------------------------------

    /** OB1's welcome step, as the user approved it (journal session 11, D66). Set in display type. */
    public const val ONBOARDING_WELCOME_TITLE: String = "Every song was handed on."

    public const val ONBOARDING_WELCOME_BODY: String =
        "Birds sang first. People sang before they could write. “Tradition” comes from the Latin tradere: " +
            "to hand over. When you practise, you keep a song alive long enough to pass it on."

    public const val ONBOARDING_WELCOME_LATIN: String = "tradere"

    /** OB3: on every step. */
    public const val ONBOARDING_SKIP: String = "Skip setup"

    public const val ONBOARDING_PICK_COLOURS: String = "Pick your colours →"

    /** OB2 step 1. */
    public const val ONBOARDING_RAMP_QUESTION: String = "How should cold songs look?"

    /** OB2 step 2. */
    public const val ONBOARDING_WHO_QUESTION: String = "Which of these is you?"

    public const val ONBOARDING_NONE_OF_THESE: String = "None of these"

    /** OB7: the one line under each question. */
    public const val ONBOARDING_CHANGE_LATER: String = "You can change this any time from the menu."

    public const val ONBOARDING_NEXT: String = "Next"

    /** OB6: the drawer item and its sheet's heading (triage T10). */
    public const val WHO_YOU_ARE: String = "Who you are"
    public const val WHO_YOU_ARE_NONE: String = "None"

    /** OB6 with no live performers: adding one is the Performers route's (OB2). */
    public const val WHO_YOU_ARE_NO_PERFORMERS: String = "No performers yet. Add them under Performers."

    // ---- The drawer and its sheets (E26, R27, R40-R42, RS16, OB6) -------------------------------

    public const val DRAWER_APP_NAME: String = "Repertosaurus"
    public const val DRAWER_OFFLINE: String = "Offline. This phone holds the only copy."
    public const val DRAWER_EXPORT: String = "Export database"
    public const val DRAWER_REPERTOIRE: String = "Repertoire"
    public const val DRAWER_SONGS: String = "Songs"
    public const val DRAWER_ARTISTS: String = "Artists"
    public const val DRAWER_SIMPLIFY_SPELLING: String = "Simplify F♯♯ to G"
    public const val DRAWER_ADVANCED: String = "Advanced"
    public const val DRAWER_IMPORT: String = "Replace database from file"

    /** RS16: the drawer item and its sheet's heading. */
    public const val COLOUR_RAMP: String = "Colour ramp"

    /** RS16: said after the tick on the current ramp. */
    public const val CHOICE_CURRENT: String = "current"

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
