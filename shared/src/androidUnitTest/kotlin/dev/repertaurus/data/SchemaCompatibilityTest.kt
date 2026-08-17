package dev.repertaurus.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.repertaurus.core.Ids
import dev.repertaurus.db.RepertaurusDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The tests the crash of 2026-08-16 did not have.
 *
 * Three failures stacked: a `Schema.version` that did not move when the schema did, an import
 * validator that counted five tables instead of checking what the build needs, and a boot path
 * whose only failure mode was killing the process. This file covers the first two — that the
 * version moved, that the migration produces exactly what `Schema.create` produces, and that
 * every verdict is the one the spec names. The third is covered on a device, in the `androidApp`
 * instrumented tests, because it is about `SessionViewModel` and Android's SQLite.
 */
class SchemaCompatibilityTest {

    private lateinit var driver: JdbcSqliteDriver

    @BeforeTest
    fun open() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
    }

    @AfterTest
    fun close() {
        driver.close()
    }

    /** The version the migration chain is expected to reach, never a literal. */
    private val current: Long get() = RepertaurusDatabase.Schema.version

    // ---- S1, S2: the version is the contract ---------------------------------------------

    /**
     * S1: the schema version is bumped in the same change as any `.sq` edit that adds, removes
     * or retypes a table or column. It stayed at 1 across a change that added a table and a
     * column, and that single fault made every other failure possible.
     *
     * **When you add `2.sqm`, this number becomes 3.** If you are here because this test failed
     * and you did not add a migration, the version moved without one and something is wrong.
     */
    @Test
    fun schemaVersionIsTwo() {
        assertEquals(2L, RepertaurusDatabase.Schema.version)
        assertEquals(RepertaurusDatabase.Schema.version, SchemaCompatibility.VERSION)
    }

    /**
     * S5: the requirement lives beside the schema and **its staleness is a test failure, not a
     * runtime crash**. This is the assertion that makes that true — add a column to a `.sq`
     * file and forget `SchemaCompatibility.REQUIRED` and you fail here rather than shipping a
     * validator that quietly stopped describing the schema.
     */
    @Test
    fun requiredMatchesWhatTheSchemaCreates() {
        RepertaurusDatabase.Schema.create(driver)
        assertEquals(SchemaCompatibility.REQUIRED, SchemaCompatibility.inspect(driver))
    }

    /**
     * S13: the fixture is anchored to the real thing.
     *
     * The convenient way to build a version-1 database — create the current schema, undo the two
     * things version 2 added — is only trustworthy while it still describes what version 1 was.
     * This asserts it against a dump lifted from the user's real pre-Views file, so the claim is
     * a mechanism rather than the comment it used to be. It is also the assertion that will fail
     * when `2.sqm` lands and the downgrade silently starts producing "version 3 minus two".
     */
    @Test
    fun theTwoWaysOfBuildingVersionOneAgree() {
        SchemaV1Fixture.fromRealDump(driver)
        val real = driver.structure()

        val downgraded = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            downgraded.execute(null, "PRAGMA foreign_keys = ON", 0)
            SchemaV1Fixture.byDowngrade(downgraded)
            assertEquals(real, downgraded.structure())
        } finally {
            downgraded.close()
        }
    }

    /**
     * S2: the whole point of `1.sqm`. The user's real version-1 database, migrated in place, must
     * end up structurally identical to one created from scratch — every table, every column with
     * its declared type, nullability and default, every foreign key and every index. Anything
     * less and the app is back to two structurally different databases claiming one version.
     *
     * Comparing structure and not just column *names* is deliberate: S1 counts a **retype** as a
     * schema change, and a name-only comparison cannot see one.
     */
    @Test
    fun migratingTheRealVersionOneDatabaseReproducesTheCreatedSchema() {
        SchemaV1Fixture.fromRealDump(driver)
        assertFalse("saved_view" in SchemaCompatibility.inspect(driver))

        RepertaurusDatabase.Schema.migrate(driver, 1, current)

        assertEquals(SchemaCompatibility.REQUIRED, SchemaCompatibility.inspect(driver))
        assertEquals(created().structure, driver.structure())
    }

    /** The CHECK constraints too, which no `PRAGMA` reports. */
    @Test
    fun migrationLeavesTheSameConstraintTextAsCreation() {
        SchemaV1Fixture.fromRealDump(driver)
        RepertaurusDatabase.Schema.migrate(driver, 1, current)

        assertEquals(created().ddl, driver.normalisedDdl())
    }

    /**
     * The migration's data half. Every existing row is a vocal row — views V25 maps every singer
     * column to instrument `vocal`, V26 does the same for the annotations, and V27a measured
     * that nothing else ever wrote this table — so the backfill is the seeded `vocal` id and not
     * a guess, and no row may be lost on the way through the rebuild.
     */
    @Test
    fun migrationBackfillsExistingRowsWithVocal() {
        SchemaV1Fixture.fromRealDump(driver)
        seedSongAndPerformer()
        driver.execute(
            null,
            """
            INSERT INTO song_performer(
                id, song_id, performer_id, is_lead, vocal_range, notes,
                updated_at, deleted_at, device_id
            ) VALUES (
                'row-1', '$SONG', '$PERFORMER', 1, NULL, NULL,
                '2026-08-15T00:00:00.000Z', NULL, 'test'
            )
            """.trimIndent(),
            0,
        )

        RepertaurusDatabase.Schema.migrate(driver, 1, current)

        assertEquals(1L, driver.long("SELECT count(*) FROM song_performer"))
        assertEquals(
            SchemaV1Fixture.VOCAL_ID,
            driver.string("SELECT instrument_id FROM song_performer WHERE id = 'row-1'"),
        )
        assertEquals(1L, driver.long("SELECT is_lead FROM song_performer WHERE id = 'row-1'"))
        assertEquals(0L, driver.long("SELECT count(*) FROM pragma_foreign_key_check"))
    }

    /**
     * Views V2: after the migration one person can hold a guitar row and a vocal row on the same
     * song. Under version 1's `UNIQUE(song_id, performer_id)` the second insert would fail, so
     * this is the assertion that the old index really went.
     */
    @Test
    fun migrationWidensTheUniqueKeyToTheTriple() {
        SchemaV1Fixture.fromRealDump(driver)
        seedSongAndPerformer()
        RepertaurusDatabase.Schema.migrate(driver, 1, current)

        insertPerformerRow("a", SchemaV1Fixture.VOCAL_ID)
        insertPerformerRow("b", Ids.derived("instrument", "guitar"))

        assertEquals(2L, driver.long("SELECT count(*) FROM song_performer"))
    }

    // ---- S5, S6, S7: what makes a file loadable ------------------------------------------

    @Test
    fun aCurrentSchemaIsLoadableWhateverTheStampSays() {
        val present = SchemaCompatibility.REQUIRED
        // The three files in the user's delivery folder all carried user_version = 1 and only
        // one of them had `saved_view`. Content is the authority; the stamp is not.
        assertEquals(SchemaVerdict.Current, SchemaCompatibility.check(present, 0))
        assertEquals(SchemaVerdict.Current, SchemaCompatibility.check(present, 1))
        assertEquals(SchemaVerdict.Current, SchemaCompatibility.check(present, current))
    }

    /** Android's own bookkeeping table is present in an app-made file and absent from a tool-made one. */
    @Test
    fun androidMetadataIsNotEvidenceEitherWay() {
        RepertaurusDatabase.Schema.create(driver)
        driver.execute(null, "CREATE TABLE android_metadata (locale TEXT)", 0)

        assertFalse("android_metadata" in SchemaCompatibility.inspect(driver))
        assertEquals(
            SchemaVerdict.Current,
            SchemaCompatibility.check(SchemaCompatibility.inspect(driver), current),
        )
    }

    /** S13: the previous version's database reports a verdict; it never throws. */
    @Test
    fun aVersionOneDatabaseIsUpgradable() {
        SchemaV1Fixture.fromRealDump(driver)
        val verdict = SchemaCompatibility.check(SchemaCompatibility.inspect(driver), 1)

        val upgradable = assertIs<SchemaVerdict.Upgradable>(verdict)
        assertEquals(1L, upgradable.fromVersion)
        assertEquals(
            listOf("saved_view", "song_performer.instrument_id"),
            upgradable.missing,
        )
    }

    /**
     * And what it says is missing is exactly what the migration supplies — not a coincidence to
     * be re-checked by eye each time a version lands.
     */
    @Test
    fun whatVersionOneIsMissingIsWhatTheMigrationSupplies() {
        SchemaV1Fixture.fromRealDump(driver)
        val before = SchemaCompatibility.check(SchemaCompatibility.inspect(driver), 1)
        val missing = assertIs<SchemaVerdict.Upgradable>(before).missing

        RepertaurusDatabase.Schema.migrate(driver, 1, current)

        val after = SchemaCompatibility.inspect(driver)
        assertTrue(missing.isNotEmpty())
        assertEquals(emptyList(), SchemaCompatibility.missingFrom(after))
    }

    /**
     * The same content with no usable stamp. `user_version = 0` is what a file built outside the
     * app carries, and Android reads 0 as "brand new" and runs `CREATE TABLE` over tables that
     * are already there — so there is no migration path and the answer is a refusal.
     */
    @Test
    fun aVersionOneDatabaseStampedZeroIsTooOld() {
        SchemaV1Fixture.fromRealDump(driver)
        val verdict = SchemaCompatibility.check(SchemaCompatibility.inspect(driver), 0)

        val old = assertIs<SchemaVerdict.TooOld>(verdict)
        assertEquals(listOf("saved_view", "song_performer.instrument_id"), old.missing)
    }

    @Test
    fun aNewerDatabaseIsTooNew() {
        assertEquals(
            SchemaVerdict.TooNew(99),
            SchemaCompatibility.check(SchemaCompatibility.REQUIRED, 99),
        )
    }

    @Test
    fun someoneElsesDatabaseIsNotRepertaurus() {
        assertEquals(
            SchemaVerdict.NotRepertaurus,
            SchemaCompatibility.check(mapOf("notes" to setOf("id", "body")), 1),
        )
    }

    /**
     * S7: the refusal names what is missing and what to do. A message the user cannot act on is
     * barely better than the crash it replaced.
     */
    @Test
    fun theRefusalNamesViewsAndTheMissingTable() {
        SchemaV1Fixture.fromRealDump(driver)
        val verdict = SchemaCompatibility.check(SchemaCompatibility.inspect(driver), 1)
        val explained = SchemaCompatibility.explain(verdict)

        assertTrue("Views" in explained, explained)
        assertTrue("saved_view" in explained, explained)
        assertTrue("Export a fresh copy" in explained, explained)
    }

    @Test
    fun missingColumnsAreReportedByName() {
        val present = SchemaCompatibility.REQUIRED.mapValues { (table, columns) ->
            if (table == "song") columns - "chart_url" else columns
        }
        assertEquals(listOf("song.chart_url"), SchemaCompatibility.missingFrom(present))
    }

    // ---- helpers --------------------------------------------------------------------------

    private class Created(val structure: Map<String, List<String>>, val ddl: List<String>)

    /** A database built by `Schema.create`, described the two ways this file compares. */
    private fun created(): Created {
        val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return try {
            fresh.execute(null, "PRAGMA foreign_keys = ON", 0)
            RepertaurusDatabase.Schema.create(fresh)
            Created(fresh.structure(), fresh.normalisedDdl())
        } finally {
            fresh.close()
        }
    }

    /**
     * Every table's columns *with their declared type, nullability, default and key position*,
     * its foreign keys and its indexes.
     *
     * Read through `PRAGMA` rather than compared as DDL text because `ALTER TABLE … RENAME TO`
     * rewrites the stored statement — it quotes the new name — so two structurally identical
     * tables can carry different text. [normalisedDdl] covers what the PRAGMAs cannot see.
     */
    private fun SqlDriver.structure(): Map<String, List<String>> =
        strings(SchemaCompatibility.TABLE_NAMES)
            .filterNot { it in SchemaCompatibility.PLATFORM_TABLES }
            .associateWith { table -> columnsOf(table) + foreignKeysOf(table) + indexesOf(table) }

    private fun SqlDriver.columnsOf(table: String): List<String> =
        rows("PRAGMA table_info(\"$table\")", 6).map { row ->
            "column ${row[1]} type=${row[2]} notnull=${row[3]} default=${row[4]} pk=${row[5]}"
        }

    private fun SqlDriver.foreignKeysOf(table: String): List<String> =
        rows("PRAGMA foreign_key_list(\"$table\")", 5)
            .map { row -> "fk ${row[3]} -> ${row[2]}(${row[4]})" }
            .sorted()

    private fun SqlDriver.indexesOf(table: String): List<String> =
        rows("PRAGMA index_list(\"$table\")", 4)
            .filter { row -> row[3] != "pk" }
            .map { row ->
                val columns = rows("PRAGMA index_info(\"${row[1]}\")", 3).map { it[2] }
                "index ${row[1]} unique=${row[2]} (${columns.joinToString(", ")})"
            }
            .sorted()

    /**
     * Every stored `CREATE` statement, with quoting and whitespace normalised away. This is what
     * catches a `CHECK` constraint drifting, which no `PRAGMA` reports.
     */
    private fun SqlDriver.normalisedDdl(): List<String> =
        strings(
            "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' " +
                "AND tbl_name NOT IN ('android_metadata')",
        )
            .map { sql -> sql.replace("\"", "").replace(Regex("\\s+"), " ").trim() }
            .sorted()

    /**
     * The dump is a *shape* and carries no seed rows, so the foreign keys these tests exercise
     * need their targets inserted by hand. `vocal` is deliberately left out — the migration's own
     * `INSERT OR IGNORE` is supposed to supply it, and a test that seeded it first would never
     * find out whether it does.
     */
    private fun seedSongAndPerformer() {
        driver.execute(
            null,
            """
            INSERT INTO instrument(id, name, updated_at, deleted_at, device_id)
            VALUES ('${Ids.derived("instrument", "guitar")}', 'guitar',
                    '2026-08-15T00:00:00.000Z', NULL, 'test')
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            INSERT INTO performer(id, name, notes, updated_at, deleted_at, device_id)
            VALUES ('$PERFORMER', 'Will', NULL, '2026-08-15T00:00:00.000Z', NULL, 'test')
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            INSERT INTO artist(id, name, sort_name, updated_at, deleted_at, device_id)
            VALUES ('$ARTIST', 'Unknown Artist', 'unknown artist',
                    '2026-08-15T00:00:00.000Z', NULL, 'test')
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            INSERT INTO song(id, title, artist_id, updated_at, deleted_at, device_id)
            VALUES ('$SONG', '9 to 5', '$ARTIST', '2026-08-15T00:00:00.000Z', NULL, 'test')
            """.trimIndent(),
            0,
        )
    }

    private fun insertPerformerRow(id: String, instrumentId: String) {
        driver.execute(
            null,
            """
            INSERT INTO song_performer(
                id, song_id, performer_id, instrument_id, is_lead, vocal_range, notes,
                updated_at, deleted_at, device_id
            ) VALUES (
                '$id', '$SONG', '$PERFORMER', '$instrumentId', 0, NULL, NULL,
                '2026-08-15T00:00:00.000Z', NULL, 'test'
            )
            """.trimIndent(),
            0,
        )
    }

    private fun SqlDriver.long(sql: String): Long =
        executeQuery(null, sql, { QueryResult.Value(if (it.next().value) it.getLong(0) else null) }, 0)
            .value ?: error("no row for $sql")

    private fun SqlDriver.string(sql: String): String =
        executeQuery(null, sql, { QueryResult.Value(if (it.next().value) it.getString(0) else null) }, 0)
            .value ?: error("no row for $sql")

    private fun SqlDriver.strings(sql: String): List<String> =
        rows(sql, 1).mapNotNull { it[0] }

    private fun SqlDriver.rows(sql: String, columns: Int): List<List<String?>> =
        executeQuery(
            null,
            sql,
            { cursor ->
                val values = mutableListOf<List<String?>>()
                while (cursor.next().value) {
                    values += (0 until columns).map { cursor.getString(it) }
                }
                QueryResult.Value(values.toList())
            },
            0,
        ).value

    private companion object {
        /** The seeded `Unknown Artist`, so a song row satisfies `song.artist_id NOT NULL`. */
        const val ARTIST = "cf06771d-4e8d-53fc-83fb-359be7dfaefc"
        const val SONG = "song-under-test"
        const val PERFORMER = "performer-under-test"
    }
}
