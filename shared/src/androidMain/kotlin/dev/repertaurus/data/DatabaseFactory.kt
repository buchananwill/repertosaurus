package dev.repertaurus.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.repertaurus.db.RepertaurusDatabase

/** The app-private database file name. One database, one file — that file is the backup. */
public const val DATABASE_NAME: String = "repertaurus.db"

/**
 * Opens the app-private database, ungated.
 *
 * **Nothing on the start-up path may call this.** `DatabaseHolder.load()` is the entry point:
 * it inspects the file before a driver touches it, verifies the schema afterwards, and
 * resolves to `DatabaseState` instead of throwing (schema-compatibility S8, S9). This function
 * is the bare construction underneath, kept for tests and tools.
 */
public fun createDatabase(context: Context, name: String = DATABASE_NAME): RepertaurusDatabase =
    RepertaurusDatabase(createDriver(context, name))

/**
 * The driver behind [createDatabase], exposed because import has to *close* the database
 * before replacing the file underneath it.
 *
 * Opening it runs `Schema.create` on a brand-new file and `Schema.migrate` on one whose
 * `user_version` is behind — the 1 → 2 migration of schema-compatibility S2 lands here. Both
 * can throw, which is why `DatabaseHolder` wraps every call.
 */
public fun createDriver(context: Context, name: String = DATABASE_NAME): AndroidSqliteDriver =
    AndroidSqliteDriver(
        schema = RepertaurusDatabase.Schema,
        context = context,
        name = name,
        callback = object : AndroidSqliteDriver.Callback(RepertaurusDatabase.Schema) {
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

            /**
             * **Do not delete the file.** The inherited implementation of this callback deletes a
             * database it finds corrupt, which on this app means destroying the user's only copy
             * of their repertoire — silently, and before they are told anything (schema
             * compatibility S8, S11). Leaving it alone makes the open fail, which is exactly what
             * `DatabaseHolder.load` turns into `DatabaseState.Unloadable`, which is what puts the
             * recovery screen in front of the user with their file still on disk behind it.
             *
             * Closing the handle is still right: a corrupt connection must not be handed out.
             */
            override fun onCorruption(db: SupportSQLiteDatabase) {
                if (db.isOpen) runCatching { db.close() }
            }
        },
    )
