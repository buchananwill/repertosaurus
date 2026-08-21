package dev.repertosaurus.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.repertosaurus.core.Ids
import dev.repertosaurus.core.Timestamps
import dev.repertosaurus.data.LookupTableKey
import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.db.RepertosaurusDatabase
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Views (views spec V1-V31) against a real database on the host JVM — no device involved.
 *
 * The fixture is the spec's own worked example, widened until every one of V11's eight filter
 * combinations really does distinguish a different set of songs — a claim an earlier version
 * of this file made and did not meet, because every vocal row except Coralie's was
 * `is_lead = 1` and Coralie shared her only song with a lead singer, which left `leadOnly`
 * inert on the vocal axis and three of the eight lines identical to another three.
 *
 * Seven songs:
 *
 * - *9 to 5* — Will lead vocal, Coralie non-lead vocal.
 * - *Angels* — Will lead vocal, Will non-lead guitar. The only song with any practice history.
 * - *Dakota* — Will lead guitar.
 * - *Ain't No Sunshine* — Newton lead vocal.
 * - *Torn* — Coralie non-lead vocal, and she is the only performer on it.
 * - *Valerie* — Will non-lead vocal, and he is the only performer on it. This is the row that
 *   makes `leadOnly` bite on the performer axis.
 * - *Mr. Brightside* — no `song_performer` row at all, which is V9's case.
 */
class ViewsTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-08-15T10:30:00.250Z")
    }
    private val today = "2026-08-15"

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: RepertosaurusDatabase
    private lateinit var repository: RepertosaurusRepository
    private lateinit var preferences: InMemorySessionPreferences
    private lateinit var views: ViewCoordinator
    private lateinit var session: SessionCoordinator

    private val vocal = Ids.derived("instrument", "vocal")
    private val guitar = Ids.derived("instrument", "guitar")
    private val bass = Ids.derived("instrument", "bass")

    private val will = Ids.derived("performer", "Will")
    private val newton = Ids.derived("performer", "Newton")
    private val coralie = Ids.derived("performer", "Coralie")

    private lateinit var nineToFive: String
    private lateinit var angels: String
    private lateinit var sunshine: String
    private lateinit var dakota: String
    private lateinit var brightside: String
    private lateinit var torn: String
    private lateinit var valerie: String

    /** Every live song, coldest first — the base order the filter narrows. */
    private val everySong = listOf(
        "9 to 5", "Ain't No Sunshine", "Dakota", "Mr. Brightside", "Torn", "Valerie", "Angels",
    )

    @BeforeTest
    fun open() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        RepertosaurusDatabase.Schema.create(driver)
        database = RepertosaurusDatabase(driver)
        repository = RepertosaurusRepository(database, "test-device", fixedClock, TimeZone.UTC)
        preferences = InMemorySessionPreferences()
        views = ViewCoordinator(repository, preferences)
        session = SessionCoordinator(repository, preferences)

        nineToFive = insertSong("9 to 5", "Dolly Parton")
        angels = insertSong("Angels", "Robbie Williams")
        sunshine = insertSong("Ain't No Sunshine", "Bill Withers")
        dakota = insertSong("Dakota", "Stereophonics")
        brightside = insertSong("Mr. Brightside", "The Killers")
        torn = insertSong("Torn", "Natalie Imbruglia")
        valerie = insertSong("Valerie", "The Zutons")

        insertPerformer(will, "Will")
        insertPerformer(newton, "Newton")
        insertPerformer(coralie, "Coralie")

        capability(nineToFive, will, vocal, lead = true)
        capability(angels, will, vocal, lead = true)
        capability(angels, will, guitar, lead = false)
        capability(dakota, will, guitar, lead = true)
        capability(sunshine, newton, vocal, lead = true)
        capability(nineToFive, coralie, vocal, lead = false)
        capability(torn, coralie, vocal, lead = false)
        capability(valerie, will, vocal, lead = false)

        repository.logPractice(angels, guitar, loggedOn = "2026-06-01")
    }

    @AfterTest
    fun close() {
        driver.close()
    }

    // ---- V1-V5: the capability row ------------------------------------------------------

    /** V2: one person, one song, two instruments, two rows — and the ids do not collide. */
    @Test
    fun onePersonHoldsAGuitarRowAndAVocalRowOnTheSameSongWithoutColliding() {
        capability(angels, will, bass, lead = false)

        val rows = database.song_performerQueries.selectBySong(angels).executeAsList()
        assertEquals(3, rows.size)
        assertEquals(
            listOf("bass", "guitar", "vocal"),
            rows.map { it.instrument_name }.sorted(),
        )
        assertEquals(3, rows.map { it.id }.toSet().size, "three distinct derived ids")
        assertEquals(
            Ids.songPerformer(angels, will, guitar),
            rows.single { it.instrument_id == guitar }.id,
        )
    }

    // ---- V11: the eight filter combinations ---------------------------------------------

    /** V11 and V21: all three components absent constrains nothing — every live song. */
    @Test
    fun anEmptyFilterIsEveryLiveSong() {
        assertEquals(everySong, titles(ViewFilter.NONE))
        assertTrue(ViewFilter.NONE.unfiltered)
    }

    /**
     * V11: each component independently degrades to "no constraint" when absent, so all
     * eight combinations are one query.
     *
     * **Each of the eight lines below selects a different set of songs**, and the closing
     * assertion holds this file to that claim rather than leaving it to a reader to check —
     * an earlier fixture made the same claim with three of the eight duplicated.
     */
    @Test
    fun everyOneOfTheEightFilterCombinationsSelectsItsOwnSongs() {
        val selected = mutableListOf<List<String>>()
        fun combination(filter: ViewFilter, expected: List<String>, why: String) {
            assertEquals(expected, titles(filter), why)
            selected.add(expected)
        }

        combination(
            ViewFilter(null, null, false),
            everySong,
            "no constraint at all",
        )
        combination(
            ViewFilter(will, null, false),
            listOf("9 to 5", "Dakota", "Valerie", "Angels"),
            "performer only: anything Will does, lead or not",
        )
        combination(
            ViewFilter(null, vocal, false),
            listOf("9 to 5", "Ain't No Sunshine", "Torn", "Valerie", "Angels"),
            "instrument only: anything anybody sings",
        )
        combination(
            ViewFilter(null, null, true),
            listOf("9 to 5", "Ain't No Sunshine", "Dakota", "Angels"),
            "lead only: anything anybody leads on, on any instrument",
        )
        combination(
            ViewFilter(will, vocal, false),
            listOf("9 to 5", "Valerie", "Angels"),
            "performer and instrument: anything Will sings, lead or not",
        )
        combination(
            ViewFilter(will, null, true),
            listOf("9 to 5", "Dakota", "Angels"),
            "performer and lead: anything Will leads on — including lead guitar",
        )
        combination(
            ViewFilter(null, vocal, true),
            listOf("9 to 5", "Ain't No Sunshine", "Angels"),
            "instrument and lead: anything anybody sings lead on",
        )
        combination(
            ViewFilter(will, vocal, true),
            listOf("9 to 5", "Angels"),
            "all three: the motivating View",
        )

        assertEquals(8, selected.size)
        assertEquals(
            8,
            selected.map { it.toSet() }.toSet().size,
            "eight combinations, eight distinct sets — a fixture where two coincide proves " +
                "nothing about the component that differs between them",
        )
    }

    /** And the three-way filter discriminates by instrument, not merely by performer. */
    @Test
    fun theLeadFlagIsScopedToItsInstrument() {
        // V6: Will leads on guitar only on Dakota; his Angels guitar row is not lead.
        assertEquals(listOf("Dakota"), titles(ViewFilter(will, guitar, true)))
        assertEquals(listOf("Dakota", "Angels"), titles(ViewFilter(will, guitar, false)))
    }

    /**
     * V9: a song with no `song_performer` row asserts nothing, so any filter excludes it.
     * Treating absent data as a match would make the filter useless — 147 of the 479
     * migrated songs are unannotated.
     */
    @Test
    fun aSongWithNoCapabilityRowIsExcludedByEveryFilter() {
        assertTrue(titles(ViewFilter.NONE).contains("Mr. Brightside"))
        for (filter in listOf(
            ViewFilter(will, null, false),
            ViewFilter(null, vocal, false),
            ViewFilter(null, null, true),
            ViewFilter(will, vocal, true),
        )) {
            assertTrue(
                "Mr. Brightside" !in titles(filter),
                "an unannotated song must not pass $filter",
            )
        }
    }

    /** A soft-deleted capability row stops the song passing the filter (decision 9). */
    @Test
    fun aTombstonedCapabilityRowNoLongerQualifiesTheSong() {
        val now = Timestamps.now(fixedClock)
        database.song_performerQueries.softDelete(
            deleted_at = now,
            updated_at = now,
            device_id = "test-device",
            id = Ids.songPerformer(nineToFive, will, vocal),
        )
        assertEquals(listOf("Angels"), titles(ViewFilter(will, vocal, true)))
    }

    // ---- V12: the cross-instrument case -------------------------------------------------

    /**
     * V12, and the whole point of a View. The practice instrument appears only in the
     * `LEFT JOIN … ON`, so a song that passes the filter but has never been touched on that
     * instrument still appears, carrying a null staleness — and it leads, because
     * never-practised is the coldest thing there is (V14).
     */
    @Test
    fun aSongPassingTheFilterButNeverPractisedOnTheInstrumentStillAppearsWithNullStaleness() {
        val rows = stale(guitar, ViewFilter(will, vocal, true))

        assertEquals(listOf("9 to 5", "Angels"), rows.map { it.title })
        assertNull(rows[0].lastPractised, "9 to 5 has never been played on guitar")
        assertNull(rows[0].daysSince)
        assertEquals(0L, rows[0].timesPractised)
        assertEquals("2026-06-01", rows[1].lastPractised)
        assertEquals(75L, rows[1].daysSince)
    }

    /**
     * V12a: staleness is keyed on the practice instrument **alone**. Singing *9 to 5* today
     * says nothing about its guitar part, so the row stays "never" in a guitar View — which
     * is the behaviour a cross-instrument View exists to produce.
     */
    @Test
    fun practiceOnAnotherInstrumentDoesNotWarmTheRow() {
        repository.logPractice(nineToFive, vocal, loggedOn = today)

        val onGuitar = stale(guitar, ViewFilter(will, vocal, true))
        assertNull(onGuitar.single { it.title == "9 to 5" }.daysSince)

        val onVocal = stale(vocal, ViewFilter(will, vocal, true))
        assertEquals(0L, onVocal.single { it.title == "9 to 5" }.daysSince)
    }

    /** The filter instrument and the practice instrument are genuinely separate (V12, V13). */
    @Test
    fun theFilterInstrumentAndThePracticeInstrumentAreIndependent() {
        // Filter on what Will sings; measure staleness on guitar, bass, and vocal in turn.
        val filter = ViewFilter(will, vocal, true)
        assertEquals(listOf("9 to 5", "Angels"), stale(guitar, filter).map { it.title })
        assertEquals(listOf("9 to 5", "Angels"), stale(bass, filter).map { it.title })
        assertTrue(
            stale(bass, filter).all { it.lastPractised == null },
            "nothing has been practised on bass",
        )
    }

    // ---- V14: the direction --------------------------------------------------------------

    /** V14: SQL gives the stable base order, the Kotlin comparator flips it. Both stay. */
    @Test
    fun theDirectionToggleActsOnTheFilteredRows() {
        val view = SessionView(
            id = "v-1",
            name = "Will sings, guitar",
            filter = ViewFilter(will, vocal, true),
            practiceInstrumentId = guitar,
            order = SessionOrder.COLDEST_FIRST,
            position = 0L,
        )
        val loaded = SessionState().switchingTo(view).withRows(session.rows(view))

        assertEquals(listOf("9 to 5", "Angels"), loaded.pending.map { it.title })
        assertEquals(
            listOf("Angels", "9 to 5"),
            loaded.withOrder(SessionOrder.HOTTEST_FIRST).pending.map { it.title },
            "never-practised trails hottest first — it is not hot",
        )
    }

    // ---- V15-V18, V23: the saved_view row -------------------------------------------------

    @Test
    fun aCreatedViewRoundTripsEveryField() {
        val created = views.createView(
            name = "  Will sings, guitar  ",
            filter = ViewFilter(will, vocal, true),
            practiceInstrumentId = guitar,
            order = SessionOrder.HOTTEST_FIRST,
            notes = "the motivating case",
        )

        val loaded = views.views().single()
        assertEquals("Will sings, guitar", loaded.name, "the name is trimmed")
        assertEquals(created.id, loaded.id)
        assertEquals(ViewFilter(will, vocal, true), loaded.filter)
        assertEquals(guitar, loaded.practiceInstrumentId)
        assertEquals(SessionOrder.HOTTEST_FIRST, loaded.order)
        assertEquals("the motivating case", loaded.notes)
        assertTrue(loaded.saved)

        // V16: random, not derived — a second View of the same name is a second row.
        val twin = views.createView("Will sings, guitar", ViewFilter.NONE, guitar)
        assertTrue(twin.id != created.id)
        assertEquals(2, views.views().size)
    }

    /** V24: a View stores ids, so renaming the performer leaves it working. */
    @Test
    fun renamingThePerformerDoesNotDisturbTheView() {
        views.createView("Will sings", ViewFilter(will, vocal, true), guitar)
        database.performerQueries.update(
            name = "William",
            notes = null,
            updated_at = Timestamps.now(fixedClock),
            device_id = "test-device",
            id = will,
        )

        val view = views.views().single()
        assertEquals(will, view.filter.performerId)
        assertEquals(listOf("9 to 5", "Angels"), session.rows(view).map { it.title })
    }

    /**
     * V18: reads order by `(position, id)`. Rows are inserted here **in the wrong order and
     * with a colliding position** deliberately — SQLite 3.19 on minSdk 26 has no window
     * functions to lean on, and an ordering that only looks right because of insertion order
     * is not an ordering.
     */
    @Test
    fun viewsReadBackInPositionThenIdOrderHoweverTheyWereInserted() {
        insertViewRow(id = "zzz-third", name = "third", position = 2)
        insertViewRow(id = "bbb-collides", name = "collides B", position = 1)
        insertViewRow(id = "aaa-collides", name = "collides A", position = 1)
        insertViewRow(id = "mmm-first", name = "first", position = 0)

        assertEquals(
            listOf("first", "collides A", "collides B", "third"),
            views.views().map { it.name },
        )
        assertEquals(listOf(0L, 1L, 1L, 2L), views.views().map { it.position })
    }

    /** Created Views land at the end, in creation order (V18, phase 1). */
    @Test
    fun createdViewsAreAppendedInCreationOrder() {
        views.createView("one", ViewFilter.NONE, vocal)
        views.createView("two", ViewFilter.NONE, guitar)
        views.createView("three", ViewFilter.NONE, bass)

        assertEquals(listOf("one", "two", "three"), views.views().map { it.name })
        assertEquals(listOf(0L, 1L, 2L), views.views().map { it.position })
    }

    @Test
    fun updatingAViewRewritesEveryEditableField() {
        val created = views.createView("draft", ViewFilter.NONE, vocal)

        views.updateView(
            created.copy(
                name = "settled",
                filter = ViewFilter(newton, vocal, true),
                practiceInstrumentId = guitar,
                order = SessionOrder.HOTTEST_FIRST,
                notes = "changed my mind",
            ),
        )

        val loaded = views.views().single()
        assertEquals(created.id, loaded.id, "the id is never rewritten")
        assertEquals("settled", loaded.name)
        assertEquals(ViewFilter(newton, vocal, true), loaded.filter)
        assertEquals(guitar, loaded.practiceInstrumentId)
        assertEquals(SessionOrder.HOTTEST_FIRST, loaded.order)
        assertEquals("changed my mind", loaded.notes)
    }

    /** V23: a soft delete. The row survives, or a stale device reinserts it on merge. */
    @Test
    fun deletingAViewIsATombstoneNotADelete() {
        val created = views.createView("temporary", ViewFilter.NONE, vocal)

        views.deleteView(created.id)

        assertTrue(views.views().isEmpty())
        val row = database.saved_viewQueries.selectById(created.id).executeAsOneOrNull()
        assertNotNull(row, "a hard delete would come back on the next merge")
        assertNotNull(row.deleted_at)
        assertEquals("temporary", row.name)
    }

    // ---- V19, V20, V20a, V21: opening the screen --------------------------------------------

    /** V21: with no Views at all the app is exactly what it is today. */
    @Test
    fun withNoSavedViewsTheAppOpensOnTheRememberedChipAndDirection() {
        preferences.rememberInstrument(guitar)
        preferences.rememberOrder(SessionOrder.HOTTEST_FIRST.name)

        val start = views.start()
        val opened = start.view

        assertNotNull(opened)
        assertTrue(!opened.saved)
        assertEquals(guitar, opened.practiceInstrumentId)
        assertEquals(SessionOrder.HOTTEST_FIRST, opened.order)
        assertEquals(ViewFilter.NONE, opened.filter)
        assertEquals(7, session.rows(opened).size, "every song, as today")
        assertTrue(start.views.isEmpty())
        assertNull(start.homeViewId, "nothing to remember without a saved View")
    }

    /** And a fresh install with nothing remembered opens on the first chip (decision 18). */
    @Test
    fun aFreshInstallOpensOnTheFirstChipAndColdestFirst() {
        val opened = views.start().view

        assertNotNull(opened)
        assertEquals(vocal, opened.practiceInstrumentId, "the seed order leads with vocal")
        assertEquals(SessionOrder.COLDEST_FIRST, opened.order)
        assertEquals(vocal, preferences.lastInstrumentId(), "and it is written back")
    }

    @Test
    fun withNoInstrumentsThereIsNothingToOpen() {
        removeEveryInstrument()

        val start = views.start()

        assertTrue(start.instruments.isEmpty())
        assertNull(start.view)
    }

    /** V19: the home View is remembered locally, and survives a fresh process. */
    @Test
    fun theHomeViewIsRememberedAcrossLaunches() {
        views.createView("first", ViewFilter.NONE, vocal)
        val second = views.createView("second", ViewFilter(will, vocal, true), guitar)

        // First launch: nothing remembered, so the first View, written back.
        assertEquals("first", views.start().view?.name)
        assertEquals(views.views().first().id, preferences.homeViewId())

        views.rememberHomeView(second.id)

        val relaunched = ViewCoordinator(repository, preferences)
        assertEquals("second", relaunched.start().view?.name)
        assertEquals(guitar, relaunched.start().view?.practiceInstrumentId)
    }

    /**
     * V20: a home id that no longer resolves — a View deleted on another device — falls back
     * to the first by `(position, id)`, **and the resolution is written back**, so the app
     * cannot open on nothing twice.
     */
    @Test
    fun aStaleHomeIdFallsBackToTheFirstViewAndIsWrittenBack() {
        views.createView("first", ViewFilter.NONE, vocal)
        views.createView("second", ViewFilter.NONE, guitar)
        preferences.rememberHomeView("a-view-deleted-on-another-device")

        val opened = views.start().view

        assertEquals("first", opened?.name)
        assertEquals(opened?.id, preferences.homeViewId(), "the resolution is written back")
    }

    /** The same when the home View was deleted on *this* device: a tombstone is not live. */
    @Test
    fun aDeletedHomeViewFallsBackToTheFirstSurvivor() {
        val first = views.createView("first", ViewFilter.NONE, vocal)
        views.createView("second", ViewFilter.NONE, guitar)
        views.rememberHomeView(first.id)

        views.deleteView(first.id)

        val opened = views.start().view
        assertEquals("second", opened?.name)
        assertEquals(opened?.id, preferences.homeViewId())
    }

    /** And with every View deleted, V21's empty state comes back. */
    @Test
    fun deletingTheLastViewReturnsTheAppToItsCurrentBehaviour() {
        val only = views.createView("only", ViewFilter(will, vocal, true), guitar)
        views.rememberHomeView(only.id)

        views.deleteView(only.id)

        val opened = views.start().view
        assertNotNull(opened)
        assertTrue(!opened.saved)
        assertEquals(vocal, opened.practiceInstrumentId)
        assertEquals(7, session.rows(opened).size)
    }

    /**
     * V20a — the defect this test exists for. A View names an instrument that has since been
     * **soft-deleted**: removal is unguarded and can also arrive by sync, so this is not a
     * user error. Without resolution the app opens with no chip highlighted and every tap
     * writes `practice_event.instrument_id` pointing at a tombstoned row, silently, because
     * the foreign key is still satisfied by that row.
     *
     * The fallback is the first chip — the same rule [SessionInstruments.resolve] applies —
     * and **the repair is written back**, so the second launch does not have to redo it.
     */
    @Test
    fun aViewNamingARemovedInstrumentIsResolvedAgainstTheChipRowAndRepaired() {
        val view = views.createView("bass night", ViewFilter.NONE, bass)
        views.rememberHomeView(view.id)

        repository.lookups.remove(LookupTableKey.INSTRUMENT, bass)

        val start = views.start()
        val opened = start.view

        assertNotNull(opened)
        assertEquals(view.id, opened.id, "still the same View")
        assertEquals(vocal, opened.practiceInstrumentId, "fallen back to the first chip")
        assertTrue(
            start.instruments.any { it.id == opened.practiceInstrumentId },
            "SessionState's invariant: the selected instrument is always one of the chips",
        )
        assertEquals(
            vocal,
            views.views().single().practiceInstrumentId,
            "and the repair is written back, so a second launch does not redo it",
        )
        assertEquals(
            vocal,
            start.views.single().practiceInstrumentId,
            "the switcher's copy of the list is re-read after the repair",
        )
        // A tap in this View now writes a live instrument, which is the whole point.
        session.persist(session.newTap(nineToFive, opened.practiceInstrumentId))
        assertEquals(
            vocal,
            repository.practiceHistory(nineToFive).single().instrumentId,
        )
    }

    /** V21's stand-in gets the same treatment: it too can name a removed instrument. */
    @Test
    fun theUnsavedViewAlsoFallsBackWhenItsRememberedChipHasGone() {
        preferences.rememberInstrument(bass)
        repository.lookups.remove(LookupTableKey.INSTRUMENT, bass)

        val opened = views.start().view

        assertNotNull(opened)
        assertTrue(!opened.saved)
        assertEquals(vocal, opened.practiceInstrumentId)
    }

    /** A View still on screen survives a reload, and is re-read so an edit elsewhere lands. */
    @Test
    fun theViewOnScreenIsKeptAcrossAReloadButReReadFromTheTable() {
        val first = views.createView("first", ViewFilter.NONE, vocal)
        val second = views.createView("second", ViewFilter.NONE, guitar)
        views.rememberHomeView(first.id)

        // On screen: the second View, not the home one. A reload must not snap back.
        assertEquals(second.id, views.start(second).view?.id)

        views.updateView(second.copy(name = "renamed"))
        assertEquals("renamed", views.start(second).view?.name, "the edit is picked up")

        // Deleted elsewhere, so there is nothing to keep and V20 resolves instead.
        views.deleteView(second.id)
        assertEquals(first.id, views.start(second).view?.id)
    }

    /** V13b: the direction persists to the saved row, or to preferences when there is none. */
    @Test
    fun theDirectionPersistsToWhereverTheActiveViewCameFrom() {
        val saved = views.createView("saved", ViewFilter.NONE, guitar)

        val refreshed = views.rememberOrder(saved.copy(order = SessionOrder.HOTTEST_FIRST))

        assertEquals(SessionOrder.HOTTEST_FIRST, views.views().single().order)
        assertEquals(SessionOrder.HOTTEST_FIRST, refreshed?.single()?.order, "and re-read")
        assertNull(preferences.lastOrder(), "a saved View does not touch the preference")

        // V21's unsaved View has no row, so the preference is the only place it can go — and
        // under V13a a chip tap has already forked, so this is the common case.
        assertNull(views.rememberOrder(SessionView.unsaved(guitar, SessionOrder.HOTTEST_FIRST)))
        assertEquals(SessionOrder.HOTTEST_FIRST.name, preferences.lastOrder())
        assertEquals(
            SessionOrder.HOTTEST_FIRST,
            views.views().single().order,
            "and the saved row is untouched by the unsaved View's direction",
        )
    }

    // ---- V17a: the enum and the CHECK are one vocabulary -------------------------------------

    /**
     * V17a. `sort_order` is the [SessionOrder] name under a SQL `CHECK`, and the two are two
     * statements of one vocabulary. Adding a constant without widening the `CHECK` throws at
     * insert; this iterates the enum so that failure lands here rather than on a device.
     */
    @Test
    fun everySessionOrderConstantIsAcceptedByTheSortOrderCheck() {
        for ((index, order) in SessionOrder.entries.withIndex()) {
            insertViewRow(
                id = "v-$index",
                name = order.name,
                position = index.toLong(),
                sortOrder = order.name,
            )
        }

        assertEquals(SessionOrder.entries.size, views.views().size)
        assertEquals(
            SessionOrder.entries.map { it.name }.toSet(),
            views.views().map { it.name }.toSet(),
        )
        for (view in views.views()) {
            assertEquals(
                SessionOrder.valueOf(view.name),
                view.order,
                "the stored name reads back as the constant it was written from",
            )
        }
    }

    /**
     * V17's fallback, over a row that really is on disk. Today's `CHECK` refuses a third
     * direction, so the only way this row can exist is the way V17a describes — written by a
     * later build whose `CHECK` was widened and synced back to this one. `ignore_check_
     * constraints` reproduces that here; the read must not throw, and must not guess.
     *
     * Note what V17a warns about and this test demonstrates: the unknown value **silently
     * downgrades** and the next `updateView` writes `COLDEST_FIRST` over it. That is the
     * behaviour, not an accident — but it is why the constant and the `CHECK` move together.
     */
    @Test
    fun aStoredDirectionThisBuildCannotParseReadsAsColdestFirst() {
        driver.execute(null, "PRAGMA ignore_check_constraints = ON", 0)
        insertViewRow(
            id = "v-from-a-later-build",
            name = "most practised",
            position = 0L,
            sortOrder = "MOST_PRACTISED",
        )
        driver.execute(null, "PRAGMA ignore_check_constraints = OFF", 0)

        assertEquals(
            "MOST_PRACTISED",
            database.saved_viewQueries.selectById("v-from-a-later-build")
                .executeAsOne().sort_order,
            "the row really is on disk carrying a direction this build has no constant for",
        )
        assertEquals(SessionOrder.COLDEST_FIRST, views.views().single().order)
    }

    /** And the `CHECK` really is closed: a third direction is refused, loudly. */
    @Test
    fun aSortOrderOutsideTheEnumIsRefusedByTheDatabase() {
        assertFails {
            insertViewRow(
                id = "v-third",
                name = "third direction",
                position = 0L,
                sortOrder = "MOST_PRACTISED",
            )
        }
    }

    // ---- the merge path (phase 2's adapter writes through applyMerged) -----------------------

    /**
     * V18's tie-break, exercised the way sync will produce it: two devices that have not seen
     * each other both create a View and both write `position = 1`. Neither is wrong, and
     * `position` alone is not a total order — the `id` tie-break is what keeps the two phones
     * showing the same list.
     */
    @Test
    fun twoDevicesThatBothWrotePositionOneMergeIntoADeterministicOrder() {
        merge(id = "zzz-phone", name = "phone view", position = 1L, deviceId = "phone")
        merge(id = "aaa-laptop", name = "laptop view", position = 1L, deviceId = "laptop")
        merge(id = "mmm-first", name = "first", position = 0L, deviceId = "phone")

        assertEquals(
            listOf("first", "laptop view", "phone view"),
            views.views().map { it.name },
            "(position, id): the colliding pair orders by id, not by arrival",
        )
        assertEquals(listOf(0L, 1L, 1L), views.views().map { it.position })
    }

    /**
     * A tombstone merged in, then a later live edit from another device. Last write wins, so
     * the View comes back — and the reverse order buries it again. Nothing here is a `DELETE`,
     * so either direction is representable (V23).
     */
    @Test
    fun aSoftDeletedViewMergedAgainstALaterLiveEditComesBack() {
        val created = views.createView("shared", ViewFilter(will, vocal, true), guitar)

        merge(
            id = created.id,
            name = "shared",
            position = created.position,
            deviceId = "phone",
            filterPerformerId = will,
            filterInstrumentId = vocal,
            filterLeadOnly = 1L,
            updatedAt = "2026-08-16T10:00:00.000Z",
            deletedAt = "2026-08-16T10:00:00.000Z",
        )
        assertTrue(views.views().isEmpty(), "the tombstone hides it")

        merge(
            id = created.id,
            name = "shared, renamed on the laptop",
            position = created.position,
            deviceId = "laptop",
            filterPerformerId = will,
            filterInstrumentId = vocal,
            filterLeadOnly = 1L,
            updatedAt = "2026-08-16T11:00:00.000Z",
            deletedAt = null,
        )

        val revived = views.views().single()
        assertEquals("shared, renamed on the laptop", revived.name)
        assertEquals(ViewFilter(will, vocal, true), revived.filter, "every column came with it")
        assertNull(
            database.saved_viewQueries.selectById(created.id).executeAsOne().deleted_at,
        )
    }

    /**
     * The full column round trip. `applyMerged` binds twelve parameters, six of them adjacent
     * `TEXT` and two of those — `practice_instrument_id` and `sort_order` — `NOT NULL` and
     * semantically swappable with their neighbours. Every value below is distinct, so a
     * transposition lands somewhere visible instead of type-checking and passing.
     */
    @Test
    fun everyColumnSurvivesAMergeRoundTrip() {
        merge(
            id = "v-round-trip",
            name = "the motivating view",
            position = 4L,
            deviceId = "another-device",
            filterPerformerId = coralie,
            filterInstrumentId = vocal,
            filterLeadOnly = 1L,
            practiceInstrumentId = bass,
            sortOrder = SessionOrder.HOTTEST_FIRST.name,
            notes = "a note that is not the name",
            updatedAt = "2026-08-16T09:08:07.006Z",
        )

        val loaded = views.views().single()
        assertEquals("v-round-trip", loaded.id)
        assertEquals("the motivating view", loaded.name)
        assertEquals(coralie, loaded.filter.performerId)
        assertEquals(vocal, loaded.filter.instrumentId)
        assertTrue(loaded.filter.leadOnly)
        assertEquals(bass, loaded.practiceInstrumentId, "not the filter instrument")
        assertEquals(SessionOrder.HOTTEST_FIRST, loaded.order)
        assertEquals(4L, loaded.position)
        assertEquals("a note that is not the name", loaded.notes)

        val row = database.saved_viewQueries.selectById("v-round-trip").executeAsOne()
        assertEquals("2026-08-16T09:08:07.006Z", row.updated_at)
        assertEquals("another-device", row.device_id)
        assertNull(row.deleted_at)

        // And the View works: Coralie's non-lead vocal rows are excluded by leadOnly, so this
        // filter selects nothing — which is a real answer, not a mapping accident.
        assertTrue(session.rows(loaded).isEmpty())
        assertEquals(
            listOf("Torn", "9 to 5"),
            session.rows(loaded.copy(filter = loaded.filter.copy(leadOnly = false)))
                .map { it.title }
                .sortedDescending(),
        )
    }

    // ---- helpers --------------------------------------------------------------------------

    private fun titles(filter: ViewFilter): List<String> = stale(guitar, filter).map { it.title }

    /**
     * The repository takes the filter as primitives — `data` must not depend on `session` —
     * and `SessionCoordinator.rows` is the one place that destructures a [ViewFilter]. This
     * helper does the same thing for the tests that want the raw rows.
     */
    private fun stale(
        practiceInstrumentId: String,
        filter: ViewFilter,
    ): List<RepertosaurusRepository.StaleSong> = repository.songsByStaleness(
        practiceInstrumentId = practiceInstrumentId,
        filterPerformerId = filter.performerId,
        filterInstrumentId = filter.instrumentId,
        leadOnly = filter.leadOnlyFlag,
        today = today,
    )

    private fun insertSong(title: String, artist: String): String {
        val artistId = repository.findOrCreateArtist(artist)
        return repository.createSong(title, artistId)
    }

    private fun insertPerformer(id: String, name: String) {
        database.performerQueries.insert(
            id = id,
            name = name,
            notes = null,
            updated_at = Timestamps.now(fixedClock),
            deleted_at = null,
            device_id = "test-device",
        )
    }

    private fun capability(songId: String, performerId: String, instrumentId: String, lead: Boolean) {
        database.song_performerQueries.insert(
            id = Ids.songPerformer(songId, performerId, instrumentId),
            song_id = songId,
            performer_id = performerId,
            instrument_id = instrumentId,
            is_lead = if (lead) 1L else 0L,
            vocal_range = null,
            notes = null,
            updated_at = Timestamps.now(fixedClock),
            deleted_at = null,
            device_id = "test-device",
        )
    }

    private fun insertViewRow(
        id: String,
        name: String,
        position: Long,
        sortOrder: String = SessionOrder.COLDEST_FIRST.name,
    ) {
        database.saved_viewQueries.insert(
            id = id,
            name = name,
            filter_performer_id = null,
            filter_instrument_id = null,
            filter_lead_only = 0L,
            practice_instrument_id = guitar,
            sort_order = sortOrder,
            position = position,
            notes = null,
            updated_at = Timestamps.now(fixedClock),
            deleted_at = null,
            device_id = "test-device",
        )
    }

    /** An empty chip row — the one state in which there is no View to open at all. */
    private fun removeEveryInstrument() {
        for (instrument in repository.instruments()) {
            repository.lookups.remove(LookupTableKey.INSTRUMENT, instrument.id)
        }
    }

    /**
     * A row as another device would merge it in. `applyMerged` is an `INSERT OR REPLACE` over
     * twelve columns, six of them adjacent `TEXT`, so every parameter is named here — a
     * transposition of two of them is exactly what these tests exist to catch.
     */
    private fun merge(
        id: String,
        name: String,
        position: Long,
        deviceId: String,
        filterPerformerId: String? = null,
        filterInstrumentId: String? = null,
        filterLeadOnly: Long = 0L,
        practiceInstrumentId: String = guitar,
        sortOrder: String = SessionOrder.COLDEST_FIRST.name,
        notes: String? = null,
        updatedAt: String = Timestamps.now(fixedClock),
        deletedAt: String? = null,
    ) {
        database.saved_viewQueries.applyMerged(
            id = id,
            name = name,
            filter_performer_id = filterPerformerId,
            filter_instrument_id = filterInstrumentId,
            filter_lead_only = filterLeadOnly,
            practice_instrument_id = practiceInstrumentId,
            sort_order = sortOrder,
            position = position,
            notes = notes,
            updated_at = updatedAt,
            deleted_at = deletedAt,
            device_id = deviceId,
        )
    }
}
