package dev.repertosaurus.data

import app.cash.sqldelight.db.SqlDriver
import dev.repertosaurus.db.RepertosaurusDatabase

/**
 * Builds a **schema version 1** database — the shape that shipped before Views — so the 1 → 2
 * migration is tested against the thing it actually has to upgrade (schema-compatibility S13).
 *
 * There are two ways to build one here, and having both is the point.
 *
 * [fromRealDump] replays `resources/schema-v1.sql`, which was dumped out of the user's real
 * pre-Views database with `SELECT sql FROM sqlite_master`. That is **evidence**: nobody typed it,
 * so it cannot quietly stop describing what version 1 was.
 *
 * [byDowngrade] creates the current schema and undoes the two things version 2 added. That is
 * **convenient** — it stays in step with everything versions 1 and 2 have in common — and it is
 * also the same script the `androidApp` instrumented tests use, which cannot see this source set
 * and cannot read this resource.
 *
 * `SchemaCompatibilityTest.theTwoWaysOfBuildingVersionOneAgree` asserts they produce an identical
 * `sqlite_master`. That assertion is what makes the convenient one trustworthy, and it is a
 * mechanism where the previous arrangement had only a comment saying "change both".
 *
 * **Caveat this fixture does not close**: [byDowngrade] starts from the *current* schema, so once
 * `2.sqm` lands it will produce "version 3 minus the version-2 deltas", not version 1. The
 * agreement test above is what will catch that — it will fail against the real dump — and the
 * answer then is to dump a version-2 fixture and downgrade from it, not to relax the assertion.
 */
internal object SchemaV1Fixture {

    /** The seeded `vocal` instrument, which the 1 → 2 migration backfills with. */
    const val VOCAL_ID: String = SampleData.VOCAL

    /** Replay the dump taken from the user's real pre-Views database. */
    fun fromRealDump(driver: SqlDriver) {
        for (statement in statements(readDump())) driver.execute(null, statement, 0)
    }

    /** Create the current schema, then undo the two things version 2 added. */
    fun byDowngrade(driver: SqlDriver) {
        RepertosaurusDatabase.Schema.create(driver)
        for (statement in DOWNGRADE) driver.execute(null, statement, 0)
    }

    /**
     * `\r\n` is normalised to `\n` after reading, so a checkout that converts line endings
     * (Git on Windows with `core.autocrlf`) still splits on the `--;` separator below. The
     * dump itself is not touched.
     */
    private fun readDump(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(DUMP)) {
            "$DUMP is not on the test classpath"
        }.bufferedReader().use { it.readText() }.replace("\r\n", "\n")

    /** The dump separates statements with a line containing only `--;`. */
    private fun statements(dump: String): List<String> = dump.split("\n--;\n")
        .map { chunk -> chunk.lines().filterNot { it.startsWith("--") }.joinToString("\n").trim() }
        .filter { it.isNotEmpty() }
        .map { it.removeSuffix(";") }

    private const val DUMP = "schema-v1.sql"

    /**
     * The inverse of `1.sqm`. There is a second copy in the `androidApp` instrumented tests,
     * which cannot see this source set; the agreement test keeps both honest against the real
     * dump, so if they ever diverge something fails rather than nothing.
     */
    private val DOWNGRADE: List<String> = listOf(
        // Views did not exist. DROP TABLE takes saved_view_position with it.
        "DROP TABLE saved_view",

        // song_performer had two keys, not three (views V1-V3).
        """
        CREATE TABLE song_performer (
            id            TEXT NOT NULL PRIMARY KEY,
            song_id       TEXT NOT NULL,
            performer_id  TEXT NOT NULL,
            is_lead       INTEGER NOT NULL DEFAULT 0,
            vocal_range   INTEGER,
            notes         TEXT,

            updated_at    TEXT NOT NULL,
            deleted_at    TEXT,
            device_id     TEXT NOT NULL,

            FOREIGN KEY (song_id) REFERENCES song(id),
            FOREIGN KEY (performer_id) REFERENCES performer(id),
            CHECK (is_lead IN (0, 1)),
            CHECK (vocal_range IS NULL OR vocal_range IN (0, 1)),
            CHECK (updated_at GLOB '????-??-??T??:??:??.???Z'),
            CHECK (deleted_at IS NULL OR deleted_at GLOB '????-??-??T??:??:??.???Z')
        )
        """.trimIndent().let { create ->
            // Built against a temporary name and renamed, so the stored DDL is byte-identical to
            // the real version-1 file's rather than carrying a `_v1` suffix the dump does not
            // have. Pre-3.25 SQLite does not rewrite the DDL text on rename, so the temporary
            // name is what would be stored; hence the placeholder swap.
            create.replaceFirst("song_performer", "song_performer_v1")
        },
        """
        INSERT INTO song_performer_v1(
            id, song_id, performer_id, is_lead, vocal_range, notes,
            updated_at, deleted_at, device_id
        )
        SELECT id, song_id, performer_id, is_lead, vocal_range, notes,
               updated_at, deleted_at, device_id
        FROM song_performer
        """.trimIndent(),
        "DROP TABLE song_performer",
        "ALTER TABLE song_performer_v1 RENAME TO song_performer",
        "CREATE UNIQUE INDEX song_performer_pair ON song_performer(song_id, performer_id)",
        "CREATE INDEX song_performer_performer ON song_performer(performer_id)",
    )
}
