package dev.repertosaurus.data

import app.cash.sqldelight.db.SqlDriver

/**
 * Builds a **schema version 1** database — the shape that shipped before Views (schema-
 * compatibility S13) — two ways, held equal by `theTwoWaysOfBuildingVersionOneAgree`.
 *
 * [fromRealDump] replays `resources/schema-v1.sql`, dumped from the user's real pre-Views
 * database: evidence, not a transcription. [byDowngrade] undoes `1.sqm` on the version-2 dump.
 * [DOWNGRADE] is mirrored in the `androidApp` `DatabaseFixtures`, which cannot see this source set;
 * `downgradingTwiceFromTheCurrentSchemaReachesVersionOne` holds that route to the real dump.
 */
internal object SchemaV1Fixture {

    /** The seeded `vocal` instrument, which the 1 → 2 migration backfills with. */
    const val VOCAL_ID: String = SampleData.VOCAL

    /** Replay the dump taken from the user's real pre-Views database. */
    fun fromRealDump(driver: SqlDriver) {
        driver.replay("schema-v1.sql")
    }

    /** Build the version-2 shape, then undo the two things version 2 added. */
    fun byDowngrade(driver: SqlDriver) {
        SchemaV2Fixture.fromRealDump(driver)
        for (statement in DOWNGRADE) driver.execute(null, statement, 0)
    }

    /** The inverse of `1.sqm`. Mirrored in `androidApp`'s `DatabaseFixtures`. */
    val DOWNGRADE: List<String> = listOf(
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
