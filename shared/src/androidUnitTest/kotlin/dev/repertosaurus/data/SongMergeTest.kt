package dev.repertosaurus.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.repertosaurus.TestClock
import dev.repertosaurus.core.Ids
import dev.repertosaurus.core.NoteSpelling
import dev.repertosaurus.data.SongCatalog.LookupChoice
import dev.repertosaurus.db.RepertosaurusDatabase
import dev.repertosaurus.session.MergeConfirmation
import dev.repertosaurus.session.MergePlan
import dev.repertosaurus.session.MergeSide
import dev.repertosaurus.session.MergeState
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.SongDetail
import dev.repertosaurus.session.SongDetailRead
import dev.repertosaurus.session.SongDraft
import dev.repertosaurus.session.SongDraftValidation
import dev.repertosaurus.session.SongField
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Song merge — repertoire-editing R31-R39 and R38a — over the in-memory driver.**
 *
 * The acceptance case is the user's own: *Shake If Off* (a workbook typo) merged into *Shake It
 * Off*. Every assertion reads the database back through the generated queries or the display
 * reads — never the merge's own report alone — because a merge that reported success while its
 * transaction rolled back (F25 N2) is exactly the defect under test.
 */
class SongMergeTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: RepertosaurusDatabase
    private val clock = TestClock(LOGGED_AT)

    /** The merging device. */
    private lateinit var repository: RepertosaurusRepository

    /** Another device that logged the history being merged, so a copy's `device_id` can be told apart. */
    private lateinit var oldPhone: RepertosaurusRepository

    private val vocal = Ids.derived("instrument", "vocal")
    private val guitar = Ids.derived("instrument", "guitar")
    private val keys = Ids.derived("instrument", "keys")

    private lateinit var itOff: String
    private lateinit var ifOff: String

    @BeforeTest
    fun open() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        RepertosaurusDatabase.Schema.create(driver)
        database = RepertosaurusDatabase(driver)
        repository = RepertosaurusRepository(database, DEVICE, clock, TimeZone.UTC)
        oldPhone = RepertosaurusRepository(database, OLD_DEVICE, clock, TimeZone.UTC)
        itOff = repository.catalog.addSong("Shake It Off", LookupChoice.Typed("Taylor Swift")).song.songId
        ifOff = repository.catalog.addSong("Shake If Off", LookupChoice.Typed("Taylor Swift")).song.songId
        // Guitar and keys are not seeded; vocal is.
        repository.lookups.add(LookupTableKey.INSTRUMENT, "guitar")
        repository.lookups.add(LookupTableKey.INSTRUMENT, "keys")
    }

    @AfterTest
    fun close() {
        driver.close()
    }

    // ---- Harness ------------------------------------------------------------------------------

    private fun side(songId: String): SongMerge.Side = assertNotNull(repository.merge.side(songId))

    /** The preview's defaults (R33-R35) for *Shake If Off* into *Shake It Off*, changed by [shape]. */
    private fun plan(
        survivor: String = itOff,
        loser: String = ifOff,
        shape: (MergePlan) -> MergePlan = { it },
    ): MergePlan = shape(MergePlan.of(side(survivor), side(loser)))

    private fun merge(plan: MergePlan): SongMerge.Outcome {
        clock.instant = Instant.parse(MERGED_AT)
        return repository.merge.merge(assertNotNull(plan.request(), "the default plan validates"))
    }

    private fun merged(plan: MergePlan = plan()): SongMerge.Outcome.Merged = assertIs(merge(plan))

    private fun log(songId: String, instrument: String, on: String, feel: Long? = null, note: String? = null, context: String? = null): String =
        oldPhone.logPractice(songId, instrument, contextId = context, feel = feel, note = note, loggedOn = on)

    private fun rows(table: SongChildKey, songId: String): List<SongChildRow> =
        songChildTable(database, DEVICE, table).bySong(songId)

    private fun voided(eventId: String): Boolean = database.practice_event_voidQueries.isVoided(eventId).executeAsOne()

    // ---- Events (R32, R33) --------------------------------------------------------------------

    /**
     * **The sum (R33's default):** the survivor's history is the union of both. Times practised
     * and last practised, per instrument, reflect the loser's events as well as its own (R20).
     */
    @Test
    fun theSurvivorsHistoryIsTheUnion() {
        log(itOff, vocal, "2026-09-01")
        log(itOff, vocal, "2026-09-10")
        log(ifOff, vocal, "2026-09-15")
        log(ifOff, guitar, "2026-08-20")
        log(ifOff, guitar, "2026-08-02")

        val outcome = merged()

        assertEquals(3, outcome.eventsCarried)
        assertEquals(0, outcome.eventsDropped)
        assertEquals(5L, repository.timesPractised(itOff))
        val summary = repository.practiceSummary(itOff).associateBy { it.instrumentId }
        assertEquals(3L to "2026-09-15", summary.getValue(vocal).let { it.timesPractised to it.lastPractised })
        assertEquals(2L to "2026-08-20", summary.getValue(guitar).let { it.timesPractised to it.lastPractised })
        assertEquals(0L, repository.timesPractised(ifOff), "the loser's history is voided, not duplicated")

        // The logger's staleness list sees the loser's more recent vocal log on the survivor.
        val stale = repository.songsByStaleness(vocal, null, null, 0L, today = "2026-09-23")
        assertEquals(listOf("Shake It Off"), stale.map { it.title }, "the loser is gone from the logger")
        assertEquals("2026-09-15" to 3L, stale.single().let { it.lastPractised to it.timesPractised })
    }

    /** **R33: a deselected event is voided and not copied — never hard-deleted.** */
    @Test
    fun aDeselectedEventIsVoidedAndNotCopied() {
        val kept = log(ifOff, vocal, "2026-09-15")
        val typo = log(ifOff, vocal, "2026-09-16", note = "logged on the wrong song")

        val outcome = merged(plan { it.toggleEvent(typo) })

        assertEquals(1 to 1, outcome.eventsCarried to outcome.eventsDropped)
        assertEquals(listOf("2026-09-15"), repository.practiceHistory(itOff).map { it.loggedOn })
        assertTrue(voided(kept) && voided(typo), "both originals are voided")
        val stored = database.practice_eventQueries.selectCreatedSince("").executeAsList()
        assertTrue(stored.any { it.id == typo }, "the dropped event's row is still there — voided, not deleted")
        assertEquals(3, stored.size, "two originals and one copy; nothing hard-deleted")
    }

    /**
     * **F28 B1: a voided loser event never comes back on the survivor.** An event undone on the
     * loser before the merge is already out of its history; the merge must neither copy it nor
     * offer it. Every `practice_event` row on the survivor is counted, live or voided, so a copy
     * cannot hide behind a second void either.
     */
    @Test
    fun aVoidedLoserEventNeverComesBack() {
        val kept = log(ifOff, vocal, "2026-09-15")
        val undone = log(ifOff, vocal, "2026-09-16", note = "undone on the old phone")
        oldPhone.voidPractice(undone)
        val preview = plan()

        val outcome = merged(preview)

        val onSurvivor = database.practice_eventQueries.selectCreatedSince("").executeAsList().filter { it.song_id == itOff }
        assertEquals(listOf("2026-09-15"), onSurvivor.map { it.logged_on }, "the voided event was copied to the survivor")
        assertEquals(listOf("2026-09-15"), repository.practiceHistory(itOff).map { it.loggedOn })
        assertEquals(1 to 0, outcome.eventsCarried to outcome.eventsDropped)
        assertEquals(listOf(kept), preview.loser.events.map { it.id }, "the preview offered the voided event")
    }

    /**
     * **R32: a copy is the same act of practice.** It keeps `logged_on`, `instrument_id`,
     * `context_id`, `feel`, `note` and `created_at`; only `id`, `song_id` and `device_id` change —
     * the last to the merging device.
     */
    @Test
    fun aCopyKeepsCreatedAtAndLoggedOn() {
        val context = repository.lookups.add(LookupTableKey.PRACTICE_CONTEXT, "rehearsal")
        val original = log(ifOff, guitar, "2026-07-04", feel = 2L, note = "capo 2", context = context)

        merged()

        val copy = database.practice_eventQueries.selectLiveRowsBySong(itOff).executeAsList().single()
        assertNotEquals(original, copy.id, "a new id")
        assertEquals(itOff, copy.song_id)
        assertEquals(DEVICE, copy.device_id, "the merging device")
        assertEquals(LOGGED_AT, copy.created_at, "R32: created_at is kept, not the merge's time")
        assertEquals("2026-07-04", copy.logged_on)
        assertEquals(listOf<Any?>(guitar, context, 2L, "capo 2"), listOf(copy.instrument_id, copy.context_id, copy.feel, copy.note))
        val voidRow = database.practice_event_voidQueries.selectCreatedSince("").executeAsList().single()
        assertEquals(original, voidRow.practice_event_id)
        assertEquals(MERGED_AT to DEVICE, voidRow.created_at to voidRow.device_id, "the void is the merge's own act")
        val stored = database.practice_eventQueries.selectCreatedSince("").executeAsList().single { it.id == original }
        assertEquals(ifOff, stored.song_id, "R32: the original is never re-pointed")
    }

    // ---- Children (R35, R38a) -----------------------------------------------------------------

    /** **R35's default: the union.** A tag, an instrument row and a capability only the loser holds are carried. */
    @Test
    fun childrenDefaultToTheUnion() {
        val will = repository.lookups.add(LookupTableKey.PERFORMER, "Will")
        val party = repository.lookups.add(LookupTableKey.TAG, "party")
        val slow = repository.lookups.add(LookupTableKey.TAG, "slow")
        repository.catalog.addTag(itOff, party)
        repository.catalog.addTag(ifOff, slow)
        repository.catalog.addSongInstrument(ifOff, keys)
        repository.addSongPerformer(ifOff, will, vocal)

        val outcome = merged()

        assertEquals(3, outcome.childrenCarried)
        assertEquals(setOf("party", "slow"), repository.catalog.songTags(itOff).map { it.tagName }.toSet())
        assertEquals(listOf(keys), repository.catalog.songInstruments(itOff).map { it.instrumentId })
        assertEquals(listOf(will to vocal), repository.songPerformers(itOff).map { it.performerId to it.instrumentId })
        for (table in SongChildKey.entries) {
            assertTrue(rows(table, ifOff).all { it.deletedAt != null }, "every loser $table row is removed (R38a)")
        }
    }

    /** **R35: both live — the survivor's row and its facts win**, and the loser's row is removed. */
    @Test
    fun whenBothRowsAreLiveTheSurvivorsFactsWin() {
        val kept = repository.catalog.addSongInstrument(itOff, keys).id
        val offered = repository.catalog.addSongInstrument(ifOff, keys).id
        assertTrue(repository.catalog.updateSongInstrument(kept, 2L, "Rhodes", "survivor"))
        assertTrue(repository.catalog.updateSongInstrument(offered, 5L, "Wurli", "loser"))
        val will = repository.lookups.add(LookupTableKey.PERFORMER, "Will")
        val lead = repository.addSongPerformer(itOff, will, vocal).id
        val backing = repository.addSongPerformer(ifOff, will, vocal).id
        assertTrue(repository.updateSongPerformer(lead, isLead = 1L, vocalRange = 1L, notes = "survivor"))
        assertTrue(repository.updateSongPerformer(backing, isLead = 0L, vocalRange = 0L, notes = "loser"))

        merged()

        val row = repository.catalog.songInstruments(itOff).single()
        assertEquals(listOf<Any?>(kept, 2L, "Rhodes", "survivor"), listOf(row.id, row.difficulty, row.patch, row.notes))
        val capability = repository.songPerformers(itOff).single()
        assertEquals(listOf<Any?>(lead, 1L, 1L, "survivor"), listOf(capability.id, capability.isLead, capability.vocalRange, capability.notes))
        assertNotNull(database.song_instrumentQueries.selectById(offered).executeAsOne().deleted_at)
        assertNotNull(database.song_performerQueries.selectById(backing).executeAsOne().deleted_at)
    }

    /**
     * **R38a: the survivor's row is tombstoned and the loser's is live — the loser's facts win.**
     * The survivor's row is revived in place (R4a: found by its key, the same id), and the facts the
     * user currently believes are copied onto it.
     */
    @Test
    fun whenOnlyTheLosersRowIsLiveTheLosersFactsWin() {
        val kept = repository.catalog.addSongInstrument(itOff, keys).id
        assertTrue(repository.catalog.updateSongInstrument(kept, 2L, "stale", "removed long ago"))
        assertTrue(repository.catalog.removeSongInstrument(kept).wrote)
        val offered = repository.catalog.addSongInstrument(ifOff, keys).id
        assertTrue(repository.catalog.updateSongInstrument(offered, 5L, "Wurli", "current"))
        val will = repository.lookups.add(LookupTableKey.PERFORMER, "Will")
        val lead = repository.addSongPerformer(itOff, will, vocal).id
        assertTrue(repository.updateSongPerformer(lead, isLead = 0L, vocalRange = 0L, notes = "stale"))
        assertTrue(repository.removeSongPerformer(lead).wrote)
        val current = repository.addSongPerformer(ifOff, will, vocal).id
        assertTrue(repository.updateSongPerformer(current, isLead = 1L, vocalRange = 1L, notes = "current"))

        merged()

        val row = repository.catalog.songInstruments(itOff).single()
        assertEquals(listOf<Any?>(kept, 5L, "Wurli", "current"), listOf(row.id, row.difficulty, row.patch, row.notes))
        val capability = repository.songPerformers(itOff).single()
        assertEquals(listOf<Any?>(lead, 1L, 1L, "current"), listOf(capability.id, capability.isLead, capability.vocalRange, capability.notes))
        assertEquals(1, rows(SongChildKey.INSTRUMENT, itOff).size, "revived, not a second row")
    }

    /**
     * **R38a: a row under a removed tag or instrument is still carried** — the display reads hide
     * it, the merge must not drop it. Carrying it makes a live row reference the parent, so R23b
     * revives the parent in the same transaction.
     */
    @Test
    fun aRowUnderARemovedParentIsStillCarried() {
        val slow = repository.lookups.add(LookupTableKey.TAG, "slow")
        repository.catalog.addTag(ifOff, slow)
        assertTrue(repository.lookups.remove(LookupTableKey.TAG, slow))
        val offered = repository.catalog.addSongInstrument(ifOff, keys).id
        assertTrue(repository.catalog.updateSongInstrument(offered, 3L, "organ", null))
        assertTrue(repository.lookups.remove(LookupTableKey.INSTRUMENT, keys))
        assertTrue(repository.catalog.songTags(ifOff).isEmpty(), "the display read hides it")

        val preview = plan()
        assertEquals(2, preview.children.count { it.parentRemoved && it.kept }, "offered, and flagged")
        val outcome = merged(preview)

        assertEquals(2, outcome.childrenCarried)
        assertEquals(listOf("slow"), repository.catalog.songTags(itOff).map { it.tagName })
        assertEquals(3L, repository.catalog.songInstruments(itOff).single().difficulty)
        assertNull(database.tagQueries.selectById(slow).executeAsOne().deleted_at, "R23b: the tag is back")
        assertNull(database.instrumentQueries.selectById(keys).executeAsOne().deleted_at, "R23b: the instrument is back")
    }

    /**
     * **R35: any row can be deselected.** A loser row deselected is not carried (and still removed
     * with the loser); a survivor row deselected ends removed from the survivor.
     */
    @Test
    fun aDeselectedRowIsNotCarried() {
        val party = repository.lookups.add(LookupTableKey.TAG, "party")
        val slow = repository.lookups.add(LookupTableKey.TAG, "slow")
        val keptTag = repository.catalog.addTag(itOff, party).id
        val offeredTag = repository.catalog.addTag(ifOff, slow).id

        val outcome = merged(
            plan { plan ->
                plan.toggleChild(SongMerge.ChildKey(SongChildKey.TAG, listOf(slow)))
                    .toggleChild(SongMerge.ChildKey(SongChildKey.TAG, listOf(party)))
            },
        )

        assertEquals(0, outcome.childrenCarried)
        assertTrue(repository.catalog.songTags(itOff).isEmpty())
        assertNotNull(database.song_tagQueries.selectById(keptTag).executeAsOne().deleted_at, "the survivor's deselected tag")
        assertNotNull(database.song_tagQueries.selectById(offeredTag).executeAsOne().deleted_at, "the loser's, removed with it")
        assertTrue(rows(SongChildKey.TAG, itOff).none { it.parentIds == listOf(slow) }, "nothing was inserted for it")
    }

    /**
     * **R38b: a row that appears after the preview was read is kept.** The request names what the
     * user dropped, so a tag added to the survivor (and one to the loser) between the preview and
     * the confirm — rows the user never saw, and so never deselected — both end live on the
     * survivor. A request that named the rows to keep would silently remove the survivor's.
     */
    @Test
    fun aRowAddedAfterThePreviewIsKept() {
        val party = repository.lookups.add(LookupTableKey.TAG, "party")
        val slow = repository.lookups.add(LookupTableKey.TAG, "slow")
        val preview = plan()
        assertTrue(preview.children.isEmpty(), "neither song had a tag when the preview was read")

        val added = repository.catalog.addTag(itOff, party).id
        repository.catalog.addTag(ifOff, slow)
        val outcome = merged(preview)

        assertEquals(setOf("party", "slow"), repository.catalog.songTags(itOff).map { it.tagName }.toSet())
        assertNull(database.song_tagQueries.selectById(added).executeAsOne().deleted_at, "the survivor's new tag was removed")
        assertEquals(1, outcome.childrenCarried, "the loser's new tag is carried")
    }

    // ---- Fields (R34) -------------------------------------------------------------------------

    /**
     * **R34: one pick per field**, through `SongDraft` — the default keeps the survivor's value and
     * fills its blanks from the loser; a field picked from the loser takes the loser's; title and
     * artist are picked like any other field.
     */
    @Test
    fun theMergedRowFollowsThePicks() {
        save(itOff) { it.copy(tempoBpm = "160", keySignature = 1) }
        save(ifOff) { it.copy(tempoBpm = "158", chordPattern = "I V vi IV", notes = "from the typo") }

        merged(plan { it.withPick(SongField.NOTES, MergeSide.SURVIVOR).withPick(SongField.KEY_SIGNATURE, MergeSide.LOSER) })

        val record = assertNotNull(repository.catalog.song(itOff))
        assertEquals("Shake It Off", record.title)
        assertEquals(160L, record.tempoBpm, "both set: the survivor's")
        assertEquals("I V vi IV", record.chordPattern, "blank on the survivor: the loser's")
        assertNull(record.notes, "picked from the survivor, blank")
        assertNull(record.keySignature, "picked from the loser, blank")
    }

    /**
     * **R34's defaults:** the survivor's value, or the loser's where the survivor's is blank; a
     * field blank on both sides stays the survivor's. The preview offers a choice only where the two
     * values differ, and a swap resets every pick for the new direction.
     */
    @Test
    fun theFieldPickDefaults() {
        save(itOff) { it.copy(tempoBpm = "160", keySignature = 1) }
        save(ifOff) { it.copy(tempoBpm = "158", chordPattern = "I V vi IV") }

        val preview = plan()

        assertEquals(MergeSide.SURVIVOR, preview.pick(SongField.TEMPO_BPM), "both set")
        assertEquals(MergeSide.LOSER, preview.pick(SongField.CHORD_PATTERN), "blank on the survivor")
        assertEquals(MergeSide.SURVIVOR, preview.pick(SongField.KEY_SIGNATURE), "blank on the loser")
        assertEquals(MergeSide.SURVIVOR, preview.pick(SongField.NOTES), "blank on both")
        assertEquals(MergeSide.SURVIVOR, preview.pick(SongField.TITLE), "title is picked like any field")
        assertEquals(
            listOf(SongField.TITLE, SongField.KEY_SIGNATURE, SongField.TEMPO_BPM, SongField.CHORD_PATTERN),
            preview.fields.map { it.field },
            "only the fields whose values differ are offered",
        )
        assertEquals(
            "Shake It Off" to "Shake If Off",
            preview.fields.first().field.let { field ->
                MergeSide.entries.map { Messages.mergeFieldValue(field, preview.draft(it), NoteSpelling.DEFAULT) }
                    .let { (kept, merged) -> kept to merged }
            },
        )
        val merged = preview.merged
        assertEquals(listOf("Shake It Off", "160", "I V vi IV"), listOf(merged.title, merged.tempoBpm, merged.chordPattern))
        assertEquals(1, merged.keySignature)

        val swapped = preview.swapped()
        assertEquals(ifOff, swapped.survivor.record.id)
        assertEquals(MergeSide.LOSER, swapped.pick(SongField.KEY_SIGNATURE), "reset for the new direction")
        assertEquals(MergeSide.SURVIVOR, swapped.pick(SongField.CHORD_PATTERN))
    }

    /**
     * **Session 09 ruling on 3b's #8: the artist differs by id, not by name.** Two artist rows can
     * carry one name (R22: a rename never re-derives the id) — the R22 case exactly — so the
     * preview offers the choice, and a pick from the loser lands on the loser's row.
     */
    @Test
    fun twoSameNamedArtistsAreOfferedAsAChoice() {
        val twin = repository.catalog.addSong("Shake It Of", LookupChoice.Typed("Tay Swift")).song.songId
        val twinArtist = assertNotNull(repository.catalog.song(twin)).artistId
        assertTrue(repository.catalog.renameArtist(twinArtist, "Taylor Swift"))
        val preview = plan(loser = twin)
        assertEquals(preview.survivor.record.artistName, preview.loser.record.artistName, "one name")
        assertNotEquals(preview.survivor.record.artistId, twinArtist, "two rows")

        assertTrue(SongField.ARTIST in preview.fields.map { it.field }, "two same-named artists were not offered as a choice")
        merged(preview.withPick(SongField.ARTIST, MergeSide.LOSER))

        assertEquals(twinArtist, repository.catalog.song(itOff)?.artistId, "the picked row, by id")
    }

    /**
     * **R40-R42 reach the merge preview.** A six-sharp song whose tonal centre is G reads F♯♯ as
     * written and G simplified, on whichever side holds it; the plan carries no preformatted text,
     * so the preview names the value with the setting in force at render time.
     */
    @Test
    fun theMergePreviewSpellsTheTonalCentreByTheSetting() {
        save(itOff) { it.copy(keySignature = 6, tonalCentre = 7) }
        save(ifOff) { it.copy(keySignature = 6) }

        val preview = plan()
        assertTrue(SongField.TONAL_CENTRE in preview.fields.map { it.field }, "the tonal centres differ")

        fun shown(side: MergeSide, spelling: NoteSpelling) =
            Messages.mergeFieldValue(SongField.TONAL_CENTRE, preview.draft(side), spelling)

        assertEquals("G", shown(MergeSide.SURVIVOR, NoteSpelling.SIMPLIFIED))
        assertEquals("F♯♯", shown(MergeSide.SURVIVOR, NoteSpelling.AS_WRITTEN))
        assertEquals(Messages.MERGE_BLANK, shown(MergeSide.LOSER, NoteSpelling.SIMPLIFIED))

        // Swapped, the same value is on the other side and still follows the setting.
        val swapped = preview.swapped()
        assertEquals("G", Messages.mergeFieldValue(SongField.TONAL_CENTRE, swapped.draft(MergeSide.LOSER), NoteSpelling.SIMPLIFIED))
        assertEquals("F♯♯", Messages.mergeFieldValue(SongField.TONAL_CENTRE, swapped.draft(MergeSide.LOSER), NoteSpelling.AS_WRITTEN))
    }

    /**
     * **F27 B2: the overlay's rules are the core's** — when a pick reads, what a read lands as,
     * what back and a closing detail do, and what a confirm sends, with the database generation
     * the preview was read from (F28 N4). Also [SongDetail.canMerge], the one rule the button and
     * the ViewModel's guard share.
     */
    @Test
    fun theMergeOverlaysRulesLiveInTheCore() {
        val picker = MergeState(songId = itOff)
        assertNull(picker.choosing(itOff), "the song the merge started from is not a candidate")
        val reading = assertNotNull(picker.choosing(ifOff))
        assertTrue(reading.loading)
        assertNull(reading.choosing(ifOff), "one read at a time")

        assertEquals(Messages.SONG_NOT_FOUND, reading.read(side(itOff), null, generation = 3L).error)
        val preview = reading.read(side(itOff), side(ifOff), generation = 3L)
        assertEquals(itOff to 3L, preview.plan?.survivor?.record?.id to preview.generation)

        val start = assertIs<MergeConfirmation.Start>(preview.confirm())
        assertEquals(3L, start.generation, "the confirm is bound to the database the preview was read from")
        assertTrue(start.state.merging)
        assertEquals(MergeConfirmation.Ignored, start.state.confirm(), "one merge at a time")
        assertEquals(MergeConfirmation.Ignored, picker.confirm(), "nothing to confirm without a preview")

        val back = assertNotNull(preview.back())
        assertEquals(null to null, back.plan to back.generation, "back closes the preview to the picker")
        assertNull(back.back(), "then the picker")
        assertEquals(start.state, start.state.back(), "nothing moves while the merge is in flight")
        assertNull(preview.cancelled())
        assertEquals(start.state, start.state.cancelled())

        val record = assertNotNull(repository.catalog.song(itOff))
        val detail = SongDetail(songId = itOff).saved(SongDetailRead.of(repository, itOff))
        assertTrue(detail.canMerge)
        assertTrue(!detail.copy(busy = true).canMerge, "not while a write is in flight")
        assertTrue(!detail.copy(draft = SongDraft.from(record).copy(title = "x")).canMerge, "not with unsaved changes")
        assertTrue(!SongDetail(songId = itOff).canMerge, "not before the song is read")
    }

    private fun save(songId: String, change: (SongDraft) -> SongDraft) {
        val record = assertNotNull(repository.catalog.song(songId))
        val fields = assertIs<SongDraftValidation.Valid>(change(SongDraft.from(record)).validate()).fields
        assertIs<SongCatalog.SongSave.Saved>(repository.catalog.updateSong(songId, fields))
    }

    // ---- Set lists (R37) and the loser (R31) --------------------------------------------------

    /** **R37: live set-list items on the loser point at the survivor**, stamped by the merging device. */
    @Test
    fun setListItemsArePointedAtTheSurvivor() {
        execute("INSERT INTO setlist(id, name, updated_at, device_id) VALUES ('gig', 'Blue Lion', '$LOGGED_AT', '$OLD_DEVICE')")
        execute("INSERT INTO setlist_set(id, setlist_id, set_no, updated_at, device_id) VALUES ('set1', 'gig', 1, '$LOGGED_AT', '$OLD_DEVICE')")
        setlistItem("live", ifOff, deletedAt = null)
        setlistItem("gone", ifOff, deletedAt = LOGGED_AT)
        setlistItem("other", itOff, deletedAt = null)
        assertEquals(1L, side(ifOff).setlistItems)

        val outcome = merged()

        assertEquals(1L, outcome.setlistItemsMoved)
        val items = database.setlist_itemQueries.selectChangedSince("").executeAsList().associateBy { it.id }
        assertEquals(itOff, items.getValue("live").song_id)
        assertEquals(MERGED_AT to DEVICE, items.getValue("live").let { it.updated_at to it.device_id })
        assertEquals("b", items.getValue("live").position, "the same entry, in the same place")
        assertEquals(ifOff to LOGGED_AT, items.getValue("gone").let { it.song_id to it.updated_at }, "a removed item is not re-stamped")
        assertEquals(listOf("Shake It Off", "Shake It Off"), database.setlist_itemQueries.selectBySetlist("gig").executeAsList().map { it.title })
    }

    private fun setlistItem(id: String, songId: String, deletedAt: String?) {
        val tombstone = deletedAt?.let { "'$it'" } ?: "NULL"
        execute(
            "INSERT INTO setlist_item(id, setlist_set_id, song_id, position, updated_at, deleted_at, device_id) " +
                "VALUES ('$id', 'set1', '$songId', '${if (id == "other") "a" else "b"}', '$LOGGED_AT', $tombstone, '$OLD_DEVICE')",
        )
    }

    /** **R31: the loser ends tombstoned** — and gone from every list — while the survivor keeps its id. */
    @Test
    fun theLoserEndsTombstoned() {
        val outcome = merged()

        assertEquals(itOff, outcome.survivorId)
        assertNull(repository.catalog.song(ifOff))
        val stored = database.songQueries.selectById(ifOff).executeAsOne()
        assertEquals(MERGED_AT, stored.deleted_at)
        assertEquals(listOf("Shake It Off"), repository.catalog.songs().map { it.title })
        assertNotNull(repository.catalog.song(itOff))
    }

    /**
     * **R38a's accepted case:** re-adding the loser's exact title and artist after a merge revives
     * the loser — as a song with **no history and no children**, rather than resolving to the
     * survivor. A visible, empty duplicate that can be merged again, not lost data.
     */
    @Test
    fun reAddingTheLosersTitleRevivesAnEmptyLoser() {
        log(ifOff, vocal, "2026-09-15")
        val party = repository.lookups.add(LookupTableKey.TAG, "party")
        repository.catalog.addTag(ifOff, party)
        merged()

        val again = repository.catalog.addSong("Shake If Off", LookupChoice.Typed("Taylor Swift"))

        assertEquals(Resolution.REVIVED to ifOff, again.song.resolution to again.song.songId)
        assertEquals(0L, repository.timesPractised(ifOff), "no history")
        assertTrue(repository.catalog.songTags(ifOff).isEmpty(), "no children")
        assertEquals(1L, repository.timesPractised(itOff), "the history stayed with the survivor")
    }

    // ---- R36 / R38a / F25 N2: one transaction, and a failure is a failure ---------------------

    /**
     * **A failure inside the merge leaves both songs, every event and every child exactly as they
     * were, and is reported as a failure.** A trigger injects `RAISE(ABORT)` at two points: the
     * first event copy (after the fields and children were written), and the loser's removal (the
     * very last write). Each time the whole database is compared, table by table, with its state
     * before the merge.
     */
    @Test
    fun aFailureInsideTheMergeRollsBackEverythingAndIsReported() {
        val will = repository.lookups.add(LookupTableKey.PERFORMER, "Will")
        val slow = repository.lookups.add(LookupTableKey.TAG, "slow")
        repository.catalog.addTag(ifOff, slow)
        assertTrue(repository.lookups.remove(LookupTableKey.TAG, slow))
        repository.catalog.addSongInstrument(ifOff, keys)
        repository.addSongPerformer(ifOff, will, vocal)
        log(ifOff, vocal, "2026-09-15")
        log(itOff, vocal, "2026-09-01")
        save(ifOff) { it.copy(notes = "typo") }
        val preview = plan()

        for ((name, trigger) in FAILURES) {
            val before = snapshot()
            execute(trigger)

            val outcome = merge(preview)

            val failed = assertIs<SongMerge.Outcome.Failed>(outcome, "$name: reported as a failure")
            assertTrue(failed.cause.message.orEmpty().contains("injected"), "$name: ${failed.cause.message}")
            assertEquals(before, snapshot(), "$name: the database is exactly as it was")
            execute("DROP TRIGGER $name")
        }

        // And with nothing injected, the same plan lands.
        assertIs<SongMerge.Outcome.Merged>(merge(preview))
    }

    /** A refusal is its own outcome, and writes nothing: the loser was removed before the merge landed. */
    @Test
    fun aRemovedSongIsRefused() {
        log(ifOff, vocal, "2026-09-15")
        val preview = plan()
        assertTrue(repository.catalog.removeSong(ifOff))
        val before = snapshot()

        assertEquals(SongMerge.Outcome.Refused(SongMerge.Refusal.SONG_GONE), merge(preview))
        assertEquals(before, snapshot())
    }

    /**
     * **F28 N1: a merge refuses to run inside another transaction, and says so loudly.** SQLDelight
     * 2.0.1 assigns an outer transaction's success from its latest child, so a merge nested in a
     * caller's transaction could have its refusal committed around. Nested, it throws to the
     * caller — no [SongMerge.Outcome] — and nothing is written; on its own, the same request lands.
     */
    @Test
    fun aMergeInsideAnotherTransactionFailsLoudly() {
        log(ifOff, vocal, "2026-09-15")
        val request = assertNotNull(plan().request())
        val before = snapshot()

        val thrown = assertFailsWith<IllegalStateException>("a nested merge ran instead of failing") {
            database.transaction { repository.merge.merge(request) }
        }

        assertTrue(thrown !is MergeRefused, "the nesting refusal, not a merge refusal: ${thrown.message}")
        assertEquals(before, snapshot(), "nothing was written")
        clock.instant = Instant.parse(MERGED_AT)
        assertIs<SongMerge.Outcome.Merged>(repository.merge.merge(request), "the database is usable afterwards")
    }

    /** Every row of every table the merge can touch, as text, in id order. */
    private fun snapshot(): Map<String, List<List<String?>>> = SNAPSHOT_TABLES.associateWith { table ->
        val width = driver.executeQuery(null, "SELECT COUNT(*) FROM pragma_table_info('$table')", { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0)!!.toInt())
        }, 0).value
        driver.executeQuery(null, "SELECT * FROM $table ORDER BY id", { cursor ->
            val out = mutableListOf<List<String?>>()
            while (cursor.next().value) out += (0 until width).map { cursor.getString(it) }
            QueryResult.Value(out)
        }, 0).value
    }

    private fun execute(sql: String) {
        driver.execute(null, sql, 0)
    }

    private companion object {
        const val DEVICE: String = "merging-device"
        const val OLD_DEVICE: String = "old-phone"
        const val LOGGED_AT: String = "2026-09-01T08:00:00.000Z"
        const val MERGED_AT: String = "2026-09-23T10:00:00.000Z"

        val SNAPSHOT_TABLES = listOf(
            "song", "artist", "song_tag", "song_instrument", "song_performer", "tag", "instrument",
            "performer", "practice_event", "practice_event_void", "setlist_item",
        )

        /** Trigger name to DDL: a `RAISE(ABORT)` partway through, and one at the very last write. */
        val FAILURES = listOf(
            "fail_on_copy" to
                "CREATE TRIGGER fail_on_copy BEFORE INSERT ON practice_event " +
                "BEGIN SELECT RAISE(ABORT, 'injected failure on the first copy'); END",
            "fail_on_loser" to
                "CREATE TRIGGER fail_on_loser BEFORE UPDATE OF deleted_at ON song WHEN NEW.deleted_at IS NOT NULL " +
                "BEGIN SELECT RAISE(ABORT, 'injected failure on the loser'); END",
        )
    }
}
