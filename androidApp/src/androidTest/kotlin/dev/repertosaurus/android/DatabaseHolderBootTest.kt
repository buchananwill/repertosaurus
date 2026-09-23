package dev.repertosaurus.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.DatabaseState
import dev.repertosaurus.data.ImportRejected
import dev.repertosaurus.data.SchemaCompatibility
import dev.repertosaurus.data.SchemaVerdict
import dev.repertosaurus.db.RepertosaurusDatabase
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The boot gate, on a real device, against real files (schema-compatibility S8, S9, S13).
 *
 * The failure this replaces: the import validator accepted a pre-Views database, the app wrote
 * it over the live one, and the next launch died on the start-up path with
 * `no such table: saved_view` — before any screen was drawn, including the import screen that
 * would have fixed it. Every case below therefore asserts a *state*, never an exception
 * escaping.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseHolderBootTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()

    @After
    fun cleanUp() {
        for (name in names) DatabaseFixtures.delete(context, name)
    }

    private fun name(suffix: String): String =
        "boot-test-$suffix.db".also { names += it }

    // ---- The states a launch can find --------------------------------------------------

    @Test
    fun aFreshInstallLoads() {
        val name = name("fresh")
        DatabaseFixtures.delete(context, name)

        assertEquals(DatabaseState.Ready, DatabaseHolder(context, TEST_DEVICE, name).load())
    }

    /**
     * **The headline case.** A pre-Views database already on the device — the exact state of the
     * user's `songbook-2026-08-16b.db` — upgrades in place (S2) instead of killing the process.
     */
    @Test
    fun aPreViewsDatabaseUpgradesInPlaceAndLoads() {
        val name = name("pre-views")
        val file = DatabaseFixtures.writeVersionOne(context, name)
        assertTrue("saved_view" !in DatabaseFixtures.tables(file))

        val holder = DatabaseHolder(context, TEST_DEVICE, name)
        assertEquals(DatabaseState.Ready, holder.load())

        // The migration ran, the version moved with it, and the query that used to be fatal is
        // now answerable.
        assertEquals(emptyList(), holder.repository.savedViews())
        holder.close()
        assertEquals(CURRENT, DatabaseFixtures.userVersion(file))
        assertTrue("saved_view" in DatabaseFixtures.tables(file))
    }

    /**
     * A current-schema database whose stamp is behind — what the phase-0 Python import writes,
     * and what all three files in the user's delivery folder carried. Content is the authority:
     * it loads, and the stamp is brought into line rather than read as an instruction to replay
     * the 1 -> 2 migration over tables that are already there.
     */
    @Test
    fun aCurrentDatabaseStampedOneLoadsAndIsRestamped() {
        val name = name("stamped-one")
        val file = DatabaseFixtures.writeCurrent(context, name)
        DatabaseFixtures.stamp(file, 1)

        val holder = DatabaseHolder(context, TEST_DEVICE, name)
        assertEquals(DatabaseState.Ready, holder.load())
        assertEquals(emptyList(), holder.repository.savedViews())
        holder.close()

        assertEquals(CURRENT, DatabaseFixtures.userVersion(file))
    }

    /**
     * A `Schema.create` that failed rolls back and leaves a table-less SQLite file behind. The
     * driver must be allowed another go at it, or the user is stranded on the recovery screen
     * over an empty file with `Start fresh` as the only exit.
     */
    @Test
    fun aTableLessFileIsCreatedRatherThanRefused() {
        val name = name("half-created")
        DatabaseFixtures.writeEmptyDatabase(context, name)

        val holder = DatabaseHolder(context, TEST_DEVICE, name)
        assertEquals(DatabaseState.Ready, holder.load())
        assertEquals(emptyList(), holder.repository.savedViews())
        holder.close()
        assertEquals(CURRENT, DatabaseFixtures.userVersion(context.getDatabasePath(name)))
    }

    // ---- The states that must refuse rather than crash ---------------------------------

    @Test
    fun aDatabaseFromTheFutureIsRefused() {
        val name = name("future")
        DatabaseFixtures.writeFuture(context, name)

        val state = assertIs<DatabaseState.Unloadable>(DatabaseHolder(context, TEST_DEVICE, name).load())
        assertIs<SchemaVerdict.TooNew>(state.verdict)
        assertTrue("newer build" in state.reason, state.reason)
    }

    /**
     * Pre-Views content with `user_version = 0`, which is what a file built outside the app
     * carries. Android reads 0 as "brand new" and runs `CREATE TABLE` over tables that already
     * exist, so there is no migration path and the answer is a refusal that names the gap.
     */
    @Test
    fun aPreViewsDatabaseWithNoUsableStampIsRefused() {
        val name = name("pre-views-zero")
        DatabaseFixtures.writeVersionOne(context, name, userVersion = 0)

        val state = assertIs<DatabaseState.Unloadable>(DatabaseHolder(context, TEST_DEVICE, name).load())
        assertIs<SchemaVerdict.TooOld>(state.verdict)
        assertTrue("saved_view" in state.reason, state.reason)
    }

    @Test
    fun aFileThatIsNotADatabaseIsRefused() {
        val name = name("garbage")
        val file = DatabaseFixtures.writeGarbage(context, name)

        val state = assertIs<DatabaseState.Unloadable>(DatabaseHolder(context, TEST_DEVICE, name).load())
        assertTrue(state.reason.isNotBlank())
        assertTrue(state.file.endsWith(name), state.file)
        // The framework's default corruption handler DELETES the file it cannot read. On this app
        // that is the user's only copy of their repertoire, destroyed before they are told
        // anything. `DatabaseHolder.KEEP_CORRUPT_FILE` and the driver's `onCorruption` override
        // exist to prevent it, and this is the assertion that they do.
        assertTrue(file.exists(), "a refused database must still be on disk")
        assertTrue(file.length() > 0, "a refused database must still have its bytes")
    }

    /**
     * The post-open verification. A file the pre-flight has a migration path for, but which is
     * short of something no migration supplies: the driver runs `1.sqm`, the schema is still
     * incomplete, and the gate refuses before any query can hit the gap.
     */
    @Test
    fun aDatabaseStillMissingSomethingAfterMigratingIsRefused() {
        val name = name("still-missing")
        DatabaseFixtures.writeMissingATable(context, name, table = "venue")

        val state = assertIs<DatabaseState.Unloadable>(DatabaseHolder(context, TEST_DEVICE, name).load())
        assertTrue("venue" in state.reason, state.reason)
    }

    /**
     * S9's first action has to actually re-read the file. `load` caches its refusal, so a naive
     * "Try again" would hand back the cached verdict and the button would be inert — a dead
     * button on a recovery screen being the same class of defect as no recovery screen.
     */
    @Test
    fun retryRereadsTheFileRatherThanTheCachedRefusal() {
        val name = name("retry")
        DatabaseFixtures.writeFuture(context, name)
        val holder = DatabaseHolder(context, TEST_DEVICE, name)
        assertIs<DatabaseState.Unloadable>(holder.load())

        // The user puts a readable file in place from outside the app.
        DatabaseFixtures.stamp(context.getDatabasePath(name), CURRENT)

        assertIs<DatabaseState.Unloadable>(holder.load())
        assertEquals(DatabaseState.Ready, holder.retry())
        holder.close()
    }

    /** S9's second action: the unusable file goes and an empty current-schema one takes its place. */
    @Test
    fun startFreshRecoversFromAnUnloadableDatabase() {
        val name = name("start-fresh")
        DatabaseFixtures.writeFuture(context, name)
        val holder = DatabaseHolder(context, TEST_DEVICE, name)
        assertIs<DatabaseState.Unloadable>(holder.load())

        assertEquals(DatabaseState.Ready, holder.startFresh())
        assertEquals(emptyList(), holder.repository.savedViews())
        holder.close()
        assertEquals(CURRENT, DatabaseFixtures.userVersion(context.getDatabasePath(name)))
    }

    // ---- Import: one implementation, two callers (S6, S7) ------------------------------

    /**
     * S7: the file that broke the app is refused **at import**, with the difference named. This
     * is the case that used to be accepted and then fatal on the next launch, and it survived a
     * full uninstall–reinstall–reimport because the reimport accepted it again.
     */
    @Test
    fun importRefusesAPreViewsDatabase() {
        val source = name("import-source-old")
        val live = name("import-live-old")
        val file = DatabaseFixtures.writeVersionOne(context, source)
        val holder = DatabaseHolder(context, TEST_DEVICE, live)

        val rejected = assertFailsWith<ImportRejected> {
            file.inputStream().use { holder.stageImport(it) }
        }
        val message = rejected.message.orEmpty()
        assertTrue("saved_view" in message, message)
        assertTrue("Views" in message, message)
        assertTrue("Export a fresh copy" in message, message)
    }

    @Test
    fun importAcceptsACurrentDatabase() {
        val source = name("import-source-new")
        val live = name("import-live-new")
        val file = DatabaseFixtures.writeCurrent(context, source)
        DatabaseFixtures.stamp(file, 1)
        val holder = DatabaseHolder(context, TEST_DEVICE, live)

        val preview = file.inputStream().use { holder.stageImport(it) }
        assertEquals(0L, preview.songs)

        assertEquals(DatabaseState.Ready, holder.commitImport())
        assertEquals(emptyList(), holder.repository.savedViews())
        holder.close()
        assertEquals(CURRENT, DatabaseFixtures.userVersion(context.getDatabasePath(live)))
    }

    /**
     * S4: **a re-import is always the supported recovery**, so the route out of an `Unloadable`
     * has to work from an `Unloadable`. This is the path a user with a broken database actually
     * walks, and it is the one that exercises `commitImport`'s cache invalidation — without which
     * the app would import a good file and go on showing the recovery screen over it.
     */
    @Test
    fun importRecoversFromAnUnloadableDatabase() {
        val source = name("import-source-recovery")
        val live = name("import-live-recovery")
        val file = DatabaseFixtures.writeCurrent(context, source)
        DatabaseFixtures.writeFuture(context, live)

        val holder = DatabaseHolder(context, TEST_DEVICE, live)
        assertIs<DatabaseState.Unloadable>(holder.load())

        file.inputStream().use { holder.stageImport(it) }
        assertEquals(DatabaseState.Ready, holder.commitImport())
        assertEquals(emptyList(), holder.repository.savedViews())
        holder.close()
    }

    @Test
    fun importRefusesAFileThatIsNotADatabase() {
        val live = name("import-live-garbage")
        val file = DatabaseFixtures.writeGarbage(context, name("import-source-garbage"))
        val holder = DatabaseHolder(context, TEST_DEVICE, live)

        val rejected = assertFailsWith<ImportRejected> {
            file.inputStream().use { holder.stageImport(it) }
        }
        assertTrue("not a SQLite database" in rejected.message.orEmpty(), rejected.message.orEmpty())
    }

    // ---- S5: the requirement describes this build, not a phase-1 snapshot --------------

    @Test
    fun theRequirementCoversSavedViewAndTheThreeKeyJunction() {
        assertTrue("saved_view" in SchemaCompatibility.REQUIRED)
        assertTrue("instrument_id" in SchemaCompatibility.REQUIRED.getValue("song_performer"))
        assertTrue("part_rating" in SchemaCompatibility.REQUIRED)
        assertTrue("duration_seconds" in SchemaCompatibility.REQUIRED.getValue("practice_event"))
        assertEquals(CURRENT, SchemaCompatibility.VERSION)
    }

    private companion object {
        const val DEVICE = "instrumented-test-device"

        /**
         * The version the migration chain reaches, read from the schema rather than written as a
         * literal: every literal here went stale at once when `2.sqm` moved the version to 3.
         */
        val CURRENT: Long = RepertosaurusDatabase.Schema.version
    }
}
