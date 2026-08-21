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
 * There is a second copy of the downgrade in `shared`'s unit tests, which this source set
 * cannot see. Change both.
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
            for (statement in DOWNGRADE) db.execSQL(statement)
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
