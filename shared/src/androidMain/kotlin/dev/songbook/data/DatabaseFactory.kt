package dev.songbook.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.songbook.db.SongbookDatabase

/** The app-private database file name. One database, one file — that file is the backup. */
public const val DATABASE_NAME: String = "songbook.db"

/**
 * Opens the app-private database.
 *
 * Phase 1 is standalone and app-private storage is wiped on uninstall; that risk is
 * accepted and there is deliberately no migration infrastructure here.
 */
public fun createDatabase(context: Context, name: String = DATABASE_NAME): SongbookDatabase =
    SongbookDatabase(createDriver(context, name))

/**
 * The driver behind [createDatabase], exposed because import has to *close* the database
 * before replacing the file underneath it.
 */
public fun createDriver(context: Context, name: String = DATABASE_NAME): AndroidSqliteDriver =
    AndroidSqliteDriver(
        schema = SongbookDatabase.Schema,
        context = context,
        name = name,
        callback = object : AndroidSqliteDriver.Callback(SongbookDatabase.Schema) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                super.onConfigure(db)
                // Every foreign key in the schema is real; enforce them.
                db.setForeignKeyConstraintsEnabled(true)

                // Rollback journalling, not WAL. Until phase 2 sync exists the exported
                // file is the user's only backup, and a WAL database is three files: a
                // single-file copy of one taken mid-session can be missing committed
                // transactions. In DELETE mode the main file is complete at every commit,
                // so export is a plain file copy that cannot silently lose a session. The
                // write volume here is a handful of rows a minute, so the cost is nil.
                //
                // A PRAGMA that returns a row cannot go through execSQL, hence query().
                db.query("PRAGMA journal_mode=DELETE").use { it.moveToFirst() }
            }
        },
    )
