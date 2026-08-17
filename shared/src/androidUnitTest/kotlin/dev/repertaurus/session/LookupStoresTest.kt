package dev.repertaurus.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.repertaurus.core.Ids
import dev.repertaurus.data.LookupTableKey
import dev.repertaurus.data.RepertaurusRepository
import dev.repertaurus.db.RepertaurusDatabase
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every managed lookup kind, wired — editing E13-E22 against a real database.
 *
 * `InstrumentLookupTest` proves the abstraction on the one kind that was wired before this
 * arc. This proves E18's claim that adding a kind is a branch and nothing else, and it walks
 * `LookupKind.entries` rather than naming kinds one at a time, so a kind added later without
 * a store fails here instead of at runtime on a drawer tap.
 */
class LookupStoresTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-08-17T10:30:00.250Z")
    }

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: RepertaurusDatabase
    private lateinit var repository: RepertaurusRepository

    private val guitar = Ids.derived("instrument", "guitar")
    private val party = Ids.derived("tag", "party")

    @BeforeTest
    fun open() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        RepertaurusDatabase.Schema.create(driver)
        database = RepertaurusDatabase(driver)
        repository = RepertaurusRepository(database, "test-device", fixedClock, TimeZone.UTC)
    }

    @AfterTest
    fun close() {
        driver.close()
    }

    private fun store(kind: LookupKind) = LookupStores.of(kind, repository)

    // ---- E18: every kind is wired --------------------------------------------------------

    /**
     * E18: the extension the abstraction was built for. Every kind round-trips add → rename →
     * remove → re-add through the shared store, with a derived id throughout (decisions 2, 4).
     */
    @Test
    fun everyKindRoundTripsThroughTheSharedStore() {
        for (kind in LookupKind.entries) {
            val store = store(kind)
            val id = store.add("Test ${kind.singular}")
            assertEquals(
                Ids.derived(kind.table.table, "Test ${kind.singular}"),
                id,
                "${kind.table.table}: the id is derived from the normalised name",
            )
            assertTrue(store.items().any { it.id == id }, "${kind.table.table}: listed")

            store.rename(id, "Renamed ${kind.singular}")
            assertEquals(
                "Renamed ${kind.singular}",
                store.items().single { it.id == id }.name,
                "${kind.table.table}: renamed",
            )
            assertNotEquals(
                Ids.derived(kind.table.table, "Renamed ${kind.singular}"),
                id,
                "${kind.table.table}: E21 — the new name does not derive this id, which is the point",
            )

            store.remove(id)
            assertTrue(store.items().none { it.id == id }, "${kind.table.table}: removed")

            // E6's shape again, one table up: the derived id is the same, so this untombstones.
            assertEquals(id, store.add("Test ${kind.singular}"))
            assertEquals(
                "Renamed ${kind.singular}",
                store.items().single { it.id == id }.name,
                "${kind.table.table}: the revive keeps the stored spelling",
            )
        }
    }

    /** E18 again: the type-ahead of decision 16 works for every kind, not just instruments. */
    @Test
    fun everyKindSurfacesANearMatchWhileTyping() {
        for (kind in LookupKind.entries) {
            val store = store(kind)
            store.add("Ukulele")
            assertEquals(
                listOf("Ukulele"),
                store.suggest("Ukelele", store.items()).map { it.name },
                "${kind.table.table}: a typo must be seen before it is committed",
            )
        }
    }

    // ---- E16: the notes field ------------------------------------------------------------

    /**
     * E16, pinned in both directions. `LookupKind.hasNotes` and the data layer's non-null
     * `LookupTable.setNotes` are two statements of one vocabulary; the same discipline views
     * V17a applies to `SessionOrder` and the `saved_view` CHECK.
     *
     * **Note the deviation this test encodes:** E16 names `venue` alongside `performer` and
     * `band` as carrying a notes column. `venue.sq` has no such column and data-model
     * decision 50 never gave it one. Adding it is a schema change under S1 and was not made,
     * so `VENUE.hasNotes` is false here. Change both sides together, with a migration.
     */
    @Test
    fun everyKindAgreesWithItsTableAboutWhetherItHasNotes() {
        for (kind in LookupKind.entries) {
            assertEquals(
                kind.hasNotes,
                repository.lookups.hasNotes(kind.table),
                "${kind.table.table}: LookupKind.hasNotes and the table's notes column disagree",
            )
        }
        assertEquals(
            setOf(LookupKind.PERFORMER, LookupKind.BAND),
            LookupKind.entries.filter { it.hasNotes }.toSet(),
        )
    }

    @Test
    fun notesRoundTripOnTheKindsThatHaveThem() {
        for (kind in LookupKind.entries.filter { it.hasNotes }) {
            val store = store(kind)
            val id = store.add("Blue Lion")
            assertNull(store.items().single { it.id == id }.notes)

            store.setNotes(id, "  four-piece, no horns  ")
            assertEquals("four-piece, no horns", store.items().single { it.id == id }.notes)

            // A rename must not clobber the notes, nor the notes a rename (E21).
            store.rename(id, "Blue Lion Acoustic")
            assertEquals("four-piece, no horns", store.items().single { it.id == id }.notes)

            store.setNotes(id, "   ")
            assertNull(store.items().single { it.id == id }.notes, "a blank clears the field")
            assertEquals("Blue Lion Acoustic", store.items().single { it.id == id }.name)
        }
    }

    /** A kind with no notes column fails loudly rather than writing nothing and looking fine. */
    @Test
    fun settingNotesOnAKindWithoutThemIsRefused() {
        for (kind in LookupKind.entries.filterNot { it.hasNotes }) {
            val store = store(kind)
            val id = store.add("Anything")
            assertFailsWith<IllegalArgumentException>(kind.table.table) { store.setNotes(id, "nope") }
        }
    }

    // ---- E17: usageCount -------------------------------------------------------------------

    /**
     * **E17: `usageCount` means "live rows that removing this one would hide", and each store
     * defines what counts.** It used to mean practice events specifically, for the one kind
     * that was wired. Each assertion below names what that kind counts.
     */
    @Test
    fun eachKindCountsTheRowsItsRemovalWouldHide() {
        val valerie = song("Valerie", "The Zutons")
        val dakota = song("Dakota", "Stereophonics")

        // instrument → practice events.
        repository.logPractice(valerie, guitar, loggedOn = "2026-08-15")
        repository.logPractice(dakota, guitar, loggedOn = "2026-08-16")
        assertEquals(2L, usage(LookupKind.INSTRUMENT, guitar))

        // performer → capability rows.
        val coordinator = CapabilityCoordinator(repository)
        val coralie = repository.lookups.add(LookupTableKey.PERFORMER, "Coralie")
        coordinator.add(valerie, "Coralie", "vocal")
        coordinator.add(valerie, "Coralie", "backing vocal")
        coordinator.add(dakota, "Coralie", "vocal")
        assertEquals(3L, usage(LookupKind.PERFORMER, coralie))

        // tag → tagged songs.
        tag(valerie, party)
        assertEquals(1L, usage(LookupKind.TAG, party))

        // groove → songs set to it.
        val shuffle = store(LookupKind.GROOVE).add("shuffle")
        setGroove(valerie, shuffle)
        setGroove(dakota, shuffle)
        assertEquals(2L, usage(LookupKind.GROOVE, shuffle))

        // venue and band → set lists.
        val lion = store(LookupKind.BAND).add("Blue Lion")
        val crown = store(LookupKind.VENUE).add("The Crown")
        setlist("Saturday", lion, crown)
        setlist("Sunday", lion, crown)
        assertEquals(2L, usage(LookupKind.BAND, lion))
        assertEquals(2L, usage(LookupKind.VENUE, crown))

        // practice_context → logged events.
        val gig = Ids.derived("practice_context", "gig")
        repository.logPractice(valerie, guitar, contextId = gig, loggedOn = "2026-08-16")
        assertEquals(1L, usage(LookupKind.PRACTICE_CONTEXT, gig))
    }

    /** What the manage screen renders is the same number, for every kind and every row. */
    @Test
    fun everyLiveRowSurfacesItsUsageCount() {
        val valerie = song("Valerie", "The Zutons")
        repository.logPractice(valerie, guitar, loggedOn = "2026-08-15")
        tag(valerie, party)
        CapabilityCoordinator(repository).add(valerie, "Coralie", "vocal")

        for (kind in LookupKind.entries) {
            for (item in store(kind).items()) {
                assertEquals(
                    usage(kind, item.id),
                    item.usageCount,
                    "${kind.table.table}/${item.name}",
                )
            }
        }
        assertEquals(1L, store(LookupKind.TAG).items().single { it.id == party }.usageCount)
        assertEquals(1L, store(LookupKind.INSTRUMENT).items().single { it.id == guitar }.usageCount)
    }

    /** Decision 8: an undone tap is not history the user is about to lose sight of. */
    @Test
    fun usageCountIgnoresVoidedEventsForEveryEventCountingKind() {
        val valerie = song("Valerie", "The Zutons")
        val gig = Ids.derived("practice_context", "gig")
        repository.logPractice(valerie, guitar, contextId = gig, loggedOn = "2026-08-15")
        val undone = repository.logPractice(valerie, guitar, contextId = gig, loggedOn = "2026-08-16")

        repository.voidPractice(undone)

        assertEquals(1L, usage(LookupKind.INSTRUMENT, guitar))
        assertEquals(1L, usage(LookupKind.PRACTICE_CONTEXT, gig))
    }

    /** E17: a soft-deleted song's rows are already hidden, so they do not count. */
    @Test
    fun capabilityRowsOnARemovedSongDoNotCountTowardAPerformer() {
        val valerie = song("Valerie", "The Zutons")
        val coralie = repository.lookups.add(LookupTableKey.PERFORMER, "Coralie")
        CapabilityCoordinator(repository).add(valerie, "Coralie", "vocal")
        assertEquals(1L, usage(LookupKind.PERFORMER, coralie))

        database.songQueries.softDelete(
            deleted_at = "2026-08-17T10:30:00.250Z",
            updated_at = "2026-08-17T10:30:00.250Z",
            device_id = "test-device",
            id = valerie,
        )
        assertEquals(0L, usage(LookupKind.PERFORMER, coralie))
    }

    // ---- E39: the count and the subtitle must never disagree --------------------------------

    /**
     * **E39, and the exact case it was found on.** `countLiveByPerformer` checked the
     * capability row and the song but not the instrument, while `selectPerformerInstruments` —
     * which feeds the subtitle on the very same row of the very same screen — already excluded
     * a deleted instrument. So removing `keys` dropped it from Charlotte's subtitle and left it
     * in her count: two numbers describing the same data, disagreeing, side by side.
     */
    @Test
    fun aRemovedInstrumentLeavesThePerformerCountAndTheSubtitleAgreeing() {
        val valerie = song("Valerie", "The Zutons")
        val dakota = song("Dakota", "Stereophonics")
        val keys = Ids.derived("instrument", "keys")
        val coordinator = CapabilityCoordinator(repository)
        coordinator.add(valerie, "Charlotte", "keys")
        coordinator.add(valerie, "Charlotte", "vocal")
        coordinator.add(dakota, "Charlotte", "keys")

        val before = store(LookupKind.PERFORMER).items().single { it.name == "Charlotte" }
        assertEquals("vocal, keys", before.subtitle)
        assertEquals(3L, before.usageCount)

        store(LookupKind.INSTRUMENT).remove(keys)

        val after = store(LookupKind.PERFORMER).items().single { it.name == "Charlotte" }
        assertEquals("vocal", after.subtitle, "the subtitle already excluded a removed instrument")
        assertEquals(1L, after.usageCount, "E39: and now the count beside it does too")
        assertEquals(
            after.subtitle!!.split(", ").size.toLong(),
            after.usageCount,
            "one live row per instrument here, so the two numbers must be the same number",
        )
    }

    /**
     * **E39 for the two event-counting kinds.** A practice event on a soft-deleted song is
     * already out of sight, so counting it overstates what removing the instrument — or the
     * context — would hide. Each kind is pinned by its own assertion, per E39.
     */
    @Test
    fun eventCountsExcludeEventsOnARemovedSong() {
        val valerie = song("Valerie", "The Zutons")
        val dakota = song("Dakota", "Stereophonics")
        val gig = Ids.derived("practice_context", "gig")
        repository.logPractice(valerie, guitar, contextId = gig, loggedOn = "2026-08-15")
        repository.logPractice(dakota, guitar, contextId = gig, loggedOn = "2026-08-16")

        assertEquals(2L, usage(LookupKind.INSTRUMENT, guitar))
        assertEquals(2L, usage(LookupKind.PRACTICE_CONTEXT, gig))

        database.songQueries.softDelete(
            deleted_at = now,
            updated_at = now,
            device_id = "test-device",
            id = dakota,
        )

        assertEquals(1L, usage(LookupKind.INSTRUMENT, guitar), "E39: instrument")
        assertEquals(1L, usage(LookupKind.PRACTICE_CONTEXT, gig), "E39: practice_context")
        // The events themselves survive — a tombstone hides a row, it never deletes one.
        assertEquals(1L, repository.timesPractised(dakota))
    }

    // ---- E14, E15: the performer subtitle -------------------------------------------------

    /**
     * E14 and E15: the subtitle is the instruments the person is recorded on, **derived from
     * `song_performer` and never stored** — there is no `performer_instrument` table and none
     * is proposed. The rows are added in the wrong order so a passing result cannot be
     * insertion order: the subtitle reads in decision 18's chip order.
     */
    @Test
    fun aPerformersSubtitleIsTheInstrumentsTheyAreRecordedOn() {
        val valerie = song("Valerie", "The Zutons")
        val dakota = song("Dakota", "Stereophonics")
        val coordinator = CapabilityCoordinator(repository)
        coordinator.add(valerie, "Charlotte", "keys")
        coordinator.add(dakota, "Charlotte", "backing vocal")
        coordinator.add(valerie, "Charlotte", "vocal")

        val charlotte = store(LookupKind.PERFORMER).items().single { it.name == "Charlotte" }
        assertEquals("vocal, backing vocal, keys", charlotte.subtitle)
        assertEquals(3L, charlotte.usageCount)
    }

    /**
     * **E15's accepted gap, stated as a test so nobody "fixes" it.** A performer added before
     * any song is recorded for them shows no instruments — the roster still lists them, and
     * the subtitle fills in as capabilities are entered.
     */
    @Test
    fun aPerformerWithNoCapabilitiesIsListedWithNoSubtitle() {
        val id = store(LookupKind.PERFORMER).add("Kendra")

        val kendra = store(LookupKind.PERFORMER).items().single { it.id == id }
        assertNull(kendra.subtitle)
        assertEquals(0L, kendra.usageCount)
    }

    /** Only performers carry a subtitle; the shared screen asks the store and does not guess. */
    @Test
    fun noOtherKindSuppliesASubtitle() {
        for (kind in LookupKind.entries.filterNot { it == LookupKind.PERFORMER }) {
            val id = store(kind).add("Anything")
            assertNull(store(kind).items().single { it.id == id }.subtitle, kind.table.table)
        }
    }

    // ---- E22: a removed row stays referenced by history ------------------------------------

    /**
     * E22: soft delete hides the row from pickers and from View eligibility; the rows pointing
     * at it survive. That is also why the screen states the count first.
     */
    @Test
    fun removingAPerformerHidesThemButKeepsEveryCapabilityRow() {
        val valerie = song("Valerie", "The Zutons")
        val coordinator = CapabilityCoordinator(repository)
        val rowId = coordinator.add(valerie, "Coralie", "vocal")
        val coralie = Ids.derived("performer", "Coralie")
        assertEquals(1L, usage(LookupKind.PERFORMER, coralie))

        store(LookupKind.PERFORMER).remove(coralie)

        assertTrue(store(LookupKind.PERFORMER).items().none { it.id == coralie })
        val row = database.performerQueries.selectById(coralie).executeAsOneOrNull()
        assertNotNull(row, "a hard delete comes back on the next merge")
        assertNotNull(row.deleted_at)
        assertNotNull(database.song_performerQueries.selectById(rowId).executeAsOneOrNull())
        assertEquals(1L, usage(LookupKind.PERFORMER, coralie), "the rows are still there")
    }

    // ---- Ordering --------------------------------------------------------------------------

    /** The manage list orders by name for every kind but `instrument`, which uses chip order. */
    @Test
    fun theInstrumentListKeepsChipOrderAndTheOthersAreAlphabetical() {
        // Added in the wrong order deliberately.
        store(LookupKind.INSTRUMENT).add("Ukulele")
        store(LookupKind.INSTRUMENT).add("Accordion")
        assertEquals(
            listOf("vocal", "backing vocal", "guitar", "bass", "keys", "Accordion", "Ukulele"),
            store(LookupKind.INSTRUMENT).items().map { it.name },
        )

        store(LookupKind.VENUE).add("The Crown")
        store(LookupKind.VENUE).add("Anchor")
        assertEquals(
            listOf("Anchor", "The Crown"),
            store(LookupKind.VENUE).items().map { it.name },
        )
    }

    // ---- Fixtures ---------------------------------------------------------------------------

    /**
     * The count as the screen states it (E22), read straight through so it can also be asked
     * of a row that has already been removed — which is the whole point of E22.
     * [everyLiveRowSurfacesItsUsageCount] pins this against what `items()` carries.
     */
    private fun usage(kind: LookupKind, id: String): Long =
        repository.lookups.usage(kind.table, id)

    private val now = "2026-08-17T10:30:00.250Z"

    private fun song(title: String, artist: String): String =
        repository.createSong(title, repository.findOrCreateArtist(artist))

    private fun tag(songId: String, tagId: String) {
        database.song_tagQueries.insert(
            id = Ids.junction("song_tag", songId, tagId),
            song_id = songId,
            tag_id = tagId,
            updated_at = now,
            deleted_at = null,
            device_id = "test-device",
        )
    }

    private fun setGroove(songId: String, grooveId: String) {
        val row = database.songQueries.selectById(songId).executeAsOne()
        database.songQueries.update(
            title = row.title,
            artist_id = row.artist_id,
            reference_recording = row.reference_recording,
            key_signature = row.key_signature,
            tonal_centre = row.tonal_centre,
            tonality_note = row.tonality_note,
            tempo_bpm = row.tempo_bpm,
            duration_seconds = row.duration_seconds,
            decade = row.decade,
            loop_length = row.loop_length,
            chord_count = row.chord_count,
            chord_pattern = row.chord_pattern,
            groove_id = grooveId,
            mashup_note = row.mashup_note,
            notes = row.notes,
            chart_url = row.chart_url,
            updated_at = now,
            device_id = "test-device",
            id = songId,
        )
    }

    private fun setlist(name: String, bandId: String, venueId: String) {
        database.setlistQueries.insert(
            id = "setlist-$name",
            name = name,
            performed_on = null,
            band_id = bandId,
            venue_id = venueId,
            client = null,
            notes = null,
            updated_at = now,
            deleted_at = null,
            device_id = "test-device",
        )
    }
}
