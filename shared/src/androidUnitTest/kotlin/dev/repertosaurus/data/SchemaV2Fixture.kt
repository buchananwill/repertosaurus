package dev.repertosaurus.data

import app.cash.sqldelight.db.SqlDriver
import dev.repertosaurus.db.RepertosaurusDatabase

/**
 * Builds a **schema version 2** database (schema-compatibility S13, schema-3 §3).
 *
 * [fromRealDump] replays `resources/schema-v2.sql`, dumped from a database the schema-2 app
 * created on the emulator. [withRows] adds `schema-v2-rows.sql`: a voided event, an event with
 * feel, a saved View and a tombstoned one. [byDowngrade] undoes `2.sqm` on the current schema,
 * held to the dump by `theTwoWaysOfBuildingVersionTwoAgree`; its [DOWNGRADE] is mirrored in the
 * `androidApp` `DatabaseFixtures`.
 */
internal object SchemaV2Fixture {

    /** The event the fixture's one void hides. */
    const val VOIDED_EVENT: String = "ba947ad2-8759-4977-b713-bb3421ea05ef"

    /** Replay the shape dumped from a real schema-2 database. No rows. */
    fun fromRealDump(driver: SqlDriver) {
        driver.replay("schema-v2.sql")
    }

    /** [fromRealDump], then the fixture's rows. */
    fun withRows(driver: SqlDriver) {
        fromRealDump(driver)
        driver.replay("schema-v2-rows.sql")
    }

    /** Create the current schema, then undo `2.sqm`. */
    fun byDowngrade(driver: SqlDriver) {
        RepertosaurusDatabase.Schema.create(driver)
        for (statement in DOWNGRADE) driver.execute(null, statement, 0)
    }

    /** The inverse of `2.sqm`, voids held aside as `2.sqm` holds them (schema-3 M18). */
    val DOWNGRADE: List<String> = listOf(
        "DROP TABLE part_rating",
        "DROP TABLE suggestion_skip",
        "CREATE TABLE practice_event_void_hold AS SELECT * FROM practice_event_void",
        "DROP TABLE practice_event_void",
        """
        CREATE TABLE practice_event_v2 (
            id             TEXT NOT NULL PRIMARY KEY,
            song_id        TEXT NOT NULL,
            logged_on      TEXT NOT NULL,
            instrument_id  TEXT NOT NULL,
            context_id     TEXT,
            feel           INTEGER,
            note           TEXT,

            created_at     TEXT NOT NULL,
            device_id      TEXT NOT NULL,

            FOREIGN KEY (song_id) REFERENCES song(id),
            FOREIGN KEY (instrument_id) REFERENCES instrument(id),
            FOREIGN KEY (context_id) REFERENCES practice_context(id),
            CHECK (feel IS NULL OR feel BETWEEN 1 AND 3),
            CHECK (logged_on GLOB '????-??-??'),
            CHECK (created_at GLOB '????-??-??T??:??:??.???Z')
        )
        """.trimIndent(),
        """
        INSERT INTO practice_event_v2(
            id, song_id, logged_on, instrument_id, context_id, feel, note, created_at, device_id
        )
        SELECT id, song_id, logged_on, instrument_id, context_id, feel, note, created_at, device_id
        FROM practice_event
        """.trimIndent(),
        "DROP TABLE practice_event",
        "ALTER TABLE practice_event_v2 RENAME TO practice_event",
        "CREATE INDEX practice_event_song_instrument_logged ON practice_event(song_id, instrument_id, logged_on DESC)",
        "CREATE INDEX practice_event_instrument_logged ON practice_event(instrument_id, logged_on DESC)",
        "CREATE INDEX practice_event_logged ON practice_event(logged_on DESC)",
        """
        CREATE TABLE practice_event_void (
            id                 TEXT NOT NULL PRIMARY KEY,
            practice_event_id  TEXT NOT NULL,

            created_at         TEXT NOT NULL,
            device_id          TEXT NOT NULL,

            FOREIGN KEY (practice_event_id) REFERENCES practice_event(id),
            CHECK (created_at GLOB '????-??-??T??:??:??.???Z')
        )
        """.trimIndent(),
        "CREATE INDEX practice_event_void_event ON practice_event_void(practice_event_id)",
        """
        INSERT INTO practice_event_void(id, practice_event_id, created_at, device_id)
        SELECT id, practice_event_id, created_at, device_id FROM practice_event_void_hold
        """.trimIndent(),
        "DROP TABLE practice_event_void_hold",
        """
        CREATE TABLE saved_view_v2 (
            id                     TEXT NOT NULL PRIMARY KEY,
            name                   TEXT NOT NULL,
            filter_performer_id    TEXT,
            filter_instrument_id   TEXT,
            filter_lead_only       INTEGER NOT NULL DEFAULT 0,
            practice_instrument_id TEXT NOT NULL,
            sort_order             TEXT NOT NULL,
            position               INTEGER NOT NULL,
            notes                  TEXT,

            updated_at             TEXT NOT NULL,
            deleted_at             TEXT,
            device_id              TEXT NOT NULL,

            FOREIGN KEY (filter_performer_id)    REFERENCES performer(id),
            FOREIGN KEY (filter_instrument_id)   REFERENCES instrument(id),
            FOREIGN KEY (practice_instrument_id) REFERENCES instrument(id),
            CHECK (filter_lead_only IN (0, 1)),
            CHECK (sort_order IN ('COLDEST_FIRST', 'HOTTEST_FIRST')),
            CHECK (updated_at GLOB '????-??-??T??:??:??.???Z'),
            CHECK (deleted_at IS NULL OR deleted_at GLOB '????-??-??T??:??:??.???Z')
        )
        """.trimIndent(),
        "INSERT INTO saved_view_v2 SELECT * FROM saved_view",
        "DROP TABLE saved_view",
        "ALTER TABLE saved_view_v2 RENAME TO saved_view",
        "CREATE INDEX saved_view_position ON saved_view(position)",
    )
}
