package dev.songbook.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.songbook.db.SongbookDatabase

/**
 * Opens the app-private database.
 *
 * Phase 1 is standalone and app-private storage is wiped on uninstall; that risk is
 * accepted and there is deliberately no migration infrastructure here.
 */
public fun createDatabase(context: Context, name: String = "songbook.db"): SongbookDatabase {
    val driver = AndroidSqliteDriver(
        schema = SongbookDatabase.Schema,
        context = context,
        name = name,
        callback = object : AndroidSqliteDriver.Callback(SongbookDatabase.Schema) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                super.onConfigure(db)
                // Every foreign key in the schema is real; enforce them.
                db.setForeignKeyConstraintsEnabled(true)
            }
        },
    )
    return SongbookDatabase(driver)
}
