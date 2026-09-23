package dev.repertosaurus.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.DatabaseState
import java.io.File
import kotlin.test.assertEquals

/**
 * Real database files, on the device, in the states this build has to survive
 * (schema-compatibility S13).
 *
 * Every fixture is built by creating the **current** schema through the production path and
 * then editing it down, rather than by transcribing an old `.sq` file. A transcription goes
 * stale silently; a downgrade cannot drift away from the real thing in any respect except the
 * ones it deliberately undoes.
 *
 * The version-1 shape here mirrors the user's real pre-Views file `songbook-2026-08-16b.db`:
 * `user_version = 1`, no `saved_view`, a `song_performer` with no `instrument_id`, and exactly
 * the indexes `song_performer_pair` and `song_performer_performer`.
 *
 * The version-1 shape is reached in two steps, as the schema history went up in two: the current
 * (version 3) schema with `2.sqm` undone ([THREE_TO_TWO]), then with `1.sqm` undone ([DOWNGRADE]).
 * A single "current minus version 2's deltas" stopped being version 1 when `2.sqm` landed.
 *
 * Both scripts have a second copy in `shared`'s unit tests (`SchemaV2Fixture.DOWNGRADE`,
 * `SchemaV1Fixture.DOWNGRADE`), which this source set cannot see; there, they are held to dumps of
 * real version-1 and version-2 databases. Change both.
 */
internal object DatabaseFixtures {

    /** Remove a database and every journal SQLite may have left beside it. */
    fun delete(context: Context, name: String) {
        val path = context.getDatabasePath(name).path
        for (suffix in listOf("", "-journal", "-wal", "-shm")) File(path + suffix).delete()
    }

    /** A current-schema database, exactly as a fresh install produces one. */
    fun writeCurrent(context: Context, name: String): File {
        delete(context, name)
        val holder = DatabaseHolder(context, TEST_DEVICE, name)
        assertEquals(DatabaseState.Ready, holder.load())
        holder.close()
        return context.getDatabasePath(name)
    }

    /**
     * A current-schema database with one table removed — a shape no version has ever had, so no
     * migration supplies it. It exercises the post-open verification: the pre-flight sees a
     * migration path, the driver runs the migration, and the schema is *still* short.
     */
    fun writeMissingATable(context: Context, name: String, table: String = "venue"): File {
        val file = writeVersionOne(context, name)
        edit(file) { db -> db.execSQL("DROP TABLE $table") }
        return file
    }

    /** The pre-Views shape, stamped with [userVersion]. */
    fun writeVersionOne(context: Context, name: String, userVersion: Long = 1): File {
        val file = writeCurrent(context, name)
        edit(file) { db ->
            for (statement in THREE_TO_TWO + DOWNGRADE) db.execSQL(statement)
            db.execSQL("PRAGMA user_version = $userVersion")
        }
        return file
    }

    /** A current-schema database claiming to come from the future. */
    fun writeFuture(context: Context, name: String): File {
        val file = writeCurrent(context, name)
        edit(file) { db -> db.execSQL("PRAGMA user_version = 99") }
        return file
    }

    /**
     * A valid SQLite file with no tables in it — what a `Schema.create` that failed and rolled
     * back leaves on disk.
     */
    fun writeEmptyDatabase(context: Context, name: String): File {
        delete(context, name)
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file.path, null).use { db ->
            db.execSQL("PRAGMA user_version = 0")
        }
        return file
    }

    /** Bytes that are not a database at all. */
    fun writeGarbage(context: Context, name: String): File {
        delete(context, name)
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(4096) { 0x41 })
        return file
    }

    /** Rewrite `user_version` without touching the content — the lie the crash rested on. */
    fun stamp(file: File, userVersion: Long) {
        edit(file) { db -> db.execSQL("PRAGMA user_version = $userVersion") }
    }

    /**
     * Counted out of the file on a second, read-only connection rather than through the
     * repository, so the number is what is actually on disk. [from] is a `FROM` clause, so a
     * `WHERE` can ride along. The one copy (style review F17 N7) — three test files held their own.
     */
    fun count(holder: DatabaseHolder, from: String): Long =
        SQLiteDatabase.openDatabase(holder.databaseFile().path, null, SQLiteDatabase.OPEN_READONLY)
            .use { db ->
                db.rawQuery("SELECT COUNT(*) FROM $from", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else -1L
                }
            }

    fun userVersion(file: File): Long =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else -1L
            }
        }

    fun tables(file: File): Set<String> =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
                null,
            ).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
        }

    private fun edit(file: File, block: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    }

    /** The inverse of `2.sqm` (schema 3). Mirrors `SchemaV2Fixture.DOWNGRADE` in `shared`. */
    private val THREE_TO_TWO: List<String> = listOf(
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

    /** The inverse of `1.sqm` (Views). Mirrors `SchemaV1Fixture.DOWNGRADE` in `shared`. */
    private val DOWNGRADE: List<String> = listOf(
        "DROP TABLE saved_view",
        """
        CREATE TABLE song_performer_v1 (
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
        """.trimIndent(),
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
