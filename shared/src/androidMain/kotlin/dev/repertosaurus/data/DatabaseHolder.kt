package dev.repertosaurus.data

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.repertosaurus.db.RepertosaurusDatabase
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Owns the open database for the process, so import can close it, swap the file underneath
 * and reopen — which is the whole of "replace the app database with this one".
 *
 * A process singleton by construction (see the app's `AppGraph`): two live
 * [AndroidSqliteDriver]s over one file would each hold a connection and the swap could not
 * be made safely.
 *
 * **It is also the boot gate.** Schema-compatibility decisions S8 and S9: opening resolves to
 * [DatabaseState.Ready] or [DatabaseState.Unloadable] and never throws its way out of the
 * process, because the import UI that fixes a bad database lives inside the app and a crash on
 * this path takes it with it. Both the gate and the import validator ask
 * [SchemaCompatibility] — one implementation, two callers (S6) — after the two of them
 * disagreeing shipped a build that accepted a file at import and died on it at boot.
 */
public class DatabaseHolder(
    private val context: Context,
    private val deviceId: String,
    private val name: String = DATABASE_NAME,
) {

    @Volatile
    private var driver: AndroidSqliteDriver? = null

    @Volatile
    private var database: RepertosaurusDatabase? = null

    @Volatile
    private var unloadable: DatabaseState.Unloadable? = null

    /**
     * Rebuilt on every import, so callers must ask each time rather than caching it — the
     * old one points at a database file that no longer exists.
     *
     * @throws DatabaseUnloadable when the database cannot be loaded. Callers on the start-up
     *   path use [load] instead and never see this; the accessor keeps the throw so a path
     *   that forgot the gate fails with the reason attached rather than with `no such table`.
     */
    public val repository: RepertosaurusRepository
        get() = RepertosaurusRepository(open(), deviceId)

    /**
     * Open the database if it is not open, and say what happened. Idempotent, and the only
     * method the start-up path needs.
     *
     * Three things happen in order, and the order is the fix:
     *
     * 1. **The file is inspected before any driver touches it.** A file whose content already
     *    satisfies this build but whose `user_version` is behind is stamped forward, because
     *    handing it to SQLDelight as "version 1" would run the 1 → 2 migration over tables that
     *    are already there. A file with no migration path out of its state is refused here,
     *    before Android can run `CREATE TABLE` over a populated database.
     * 2. **The driver opens**, which is where `Schema.create` or `Schema.migrate` runs.
     * 3. **The result is verified.** The schema is read back and compared against
     *    [SchemaCompatibility.REQUIRED]. Nothing reaches a query until this passes, so
     *    `no such table: saved_view` cannot happen — the state that produced it is now a
     *    refusal with a screen behind it.
     */
    @Synchronized
    public fun load(): DatabaseState {
        if (database != null) return DatabaseState.Ready
        unloadable?.let { return it }

        val file = databaseFile()
        return try {
            // `refuse` has already cached this.
            inspectExistingFile(file)?.let { return it }

            val opened = createDriver(context, name)
            val missing = try {
                SchemaCompatibility.missingFrom(SchemaCompatibility.inspect(opened))
            } catch (failure: Exception) {
                opened.close()
                throw failure
            }
            if (missing.isNotEmpty()) {
                opened.close()
                return refuseAndRemember(
                    file,
                    null,
                    SchemaCompatibility.explainMissing(missing),
                )
            }

            driver = opened
            database = RepertosaurusDatabase(opened)
            DatabaseState.Ready
        } catch (failure: Exception) {
            // S8: every failure mode of opening a database ends here rather than in the
            // process's exception handler. S11: it is not swallowed — it becomes a state the
            // recovery screen renders, with the message attached and two actions offered.
            driver = null
            database = null
            DatabaseState.Unloadable.from(file.absolutePath, failure).also { unloadable = it }
        }
    }

    /**
     * Forget a previous refusal and look at the file again.
     *
     * [load] caches its refusal, because a boot gate that re-opened and re-inspected the file on
     * every `repository` access would pay for it on the tap path. The consequence is that
     * "Try again" on the recovery screen would otherwise be **inert** — it would be handed the
     * cached verdict rather than a fresh reading — and a dead button on a recovery screen is the
     * same class of defect as no recovery screen at all. Anything that can change the answer
     * from outside the app (a sideloaded file, a permission that has come back) needs this.
     */
    @Synchronized
    public fun retry(): DatabaseState {
        if (database != null) return DatabaseState.Ready
        unloadable = null
        return load()
    }

    // Synchronized because the Session screen reads on a background dispatcher while an
    // import closes and reopens from another: two drivers over one file would each hold a
    // connection, and one of them to a file that no longer exists.
    @Synchronized
    public fun open(): RepertosaurusDatabase {
        database?.let { return it }
        val state = load()
        database?.let { return it }
        throw DatabaseUnloadable(
            state as? DatabaseState.Unloadable ?: DatabaseState.Unloadable(
                verdict = null,
                reason = "The database closed while it was being opened.",
                file = databaseFile().absolutePath,
            ),
        )
    }

    /**
     * The fields are cleared **before** the driver is closed, so a failure to close cannot leave
     * this holder pointing at a driver it has already given up on. The failure itself is not
     * swallowed (S11) — it reaches the caller, all of whom are inside a `runCatching`.
     */
    @Synchronized
    public fun close() {
        val open = driver
        driver = null
        database = null
        open?.close()
    }

    /**
     * S9's second recovery action: throw the unusable file away and create an empty
     * current-schema database, so a user whose only copy is unreadable is not stuck staring at
     * a screen with one button they cannot use.
     *
     * Destructive and unrecoverable, which is why the screen asks first.
     */
    @Synchronized
    public fun startFresh(): DatabaseState {
        close()
        unloadable = null
        val target = databaseFile()
        for (suffix in SIDECARS) File(target.path + suffix).delete()
        return load()
    }

    public fun databaseFile(): File = context.getDatabasePath(name)

    /** A suggested backup file name: `repertosaurus-YYYY-MM-DD.db`. */
    public fun exportFileName(today: String): String = "repertosaurus-$today.db"

    /**
     * Export: copy the database file to [target] — a stream from the system save sheet.
     * Until phase 2 sync exists this is the user's only backup, so it is a byte copy of the
     * real file and nothing else. Journalling is DELETE mode (see [createDriver]), so the
     * file on disk is complete at every commit.
     *
     * @return bytes written.
     */
    public fun exportTo(target: OutputStream): Long {
        open()
        val file = databaseFile()
        require(file.exists()) { "no database file at ${file.absolutePath}" }
        return file.inputStream().use { it.copyTo(target) }
    }

    /**
     * Import, step one: copy the picked file somewhere private and check it really is a
     * database this build can load before anything destructive happens. Nothing is
     * overwritten here.
     *
     * S5 retires the old check, which counted five tables out of `sqlite_master` and asserted
     * that "a file holding all the phase 1 tables is a phase 1 database". It is not, and had
     * not been since `saved_view` landed: three structurally different files all passed it.
     *
     * S7: a database missing something this build needs is **refused, with the difference
     * named**, and not accepted and then fatal. Note the deliberate asymmetry with [load],
     * which migrates an older database in place: a migrated database keeps the two-key
     * `song_performer` ids of S3, so when the user is holding a file and choosing, a fresh
     * export is strictly the better answer and the refusal says so.
     *
     * @throws ImportRejected if the file is not a database this build can load.
     */
    public fun stageImport(input: InputStream): ImportPreview {
        val staged = File(context.cacheDir, STAGING_NAME)
        // Sidecars and all, not just the main file: a process killed mid-staging leaves a
        // journal behind, and the next `openDatabase` below would replay it over an unrelated
        // file. `commitImport` guards the live database against exactly this; staging needs the
        // same guard, because staging is where the file gets opened.
        discardStagedImport()
        staged.outputStream().use { input.copyTo(it) }

        if (staged.length() < SQLITE_HEADER.size) {
            staged.delete()
            throw ImportRejected("That file is empty.")
        }
        val header = ByteArray(SQLITE_HEADER.size)
        staged.inputStream().use { it.read(header) }
        if (!header.contentEquals(SQLITE_HEADER)) {
            staged.delete()
            throw ImportRejected("That file is not a SQLite database.")
        }

        return try {
            openRaw(staged).use { db ->
                val verdict = SchemaCompatibility.check(db.schema(), db.userVersion())
                if (verdict != SchemaVerdict.Current) {
                    throw ImportRejected(SchemaCompatibility.explain(verdict))
                }
                // Content is the authority (S5), so a file this build can read is stamped with
                // the version this build writes. The phase 0 Python import leaves 0 behind and
                // every build from before the version moved leaves 1; Android reads a stale
                // stamp as a migration instruction and would replay the 1 -> 2 migration over
                // tables that are already there.
                stampVersion(db)

                ImportPreview(
                    songs = db.count("SELECT count(*) FROM song WHERE deleted_at IS NULL"),
                    practiceEvents = db.count("SELECT count(*) FROM practice_event"),
                    bytes = staged.length(),
                )
            }
        } catch (rejected: ImportRejected) {
            staged.delete()
            throw rejected
        } catch (failure: Exception) {
            discardStagedImport()
            throw ImportRejected(
                DatabaseState.Unloadable.from(staged.path, failure).reason,
            )
        }
    }

    /**
     * Import, step two: the destructive half, run only after the user has confirmed. The
     * live database is closed, its files are removed — including any journal, or a stale
     * one would be replayed over the new database — and the staged file takes its place.
     *
     * @return the state the app is in afterwards. A staged file that passed [stageImport] is
     *   loadable by construction, but the answer is returned rather than assumed, because
     *   this is the path a user in recovery is standing on.
     */
    @Synchronized
    public fun commitImport(): DatabaseState {
        val staged = File(context.cacheDir, STAGING_NAME)
        require(staged.exists()) { "nothing staged to import" }

        close()
        unloadable = null
        val target = databaseFile()
        target.parentFile?.mkdirs()
        for (suffix in SIDECARS) File(target.path + suffix).delete()
        staged.copyTo(target, overwrite = true)
        discardStagedImport()
        return load()
    }

    /** Drops a staged import the user declined, and any sidecar it was opened with. */
    public fun discardStagedImport() {
        val staged = File(context.cacheDir, STAGING_NAME)
        for (suffix in SIDECARS) File(staged.path + suffix).delete()
    }

    /**
     * The pre-flight of [load]: what a file already on disk is, decided before any driver can
     * act on it.
     *
     * Returns null when the driver may proceed — no file at all (a fresh install, where
     * `Schema.create` is exactly right), a file whose content is current, or a file SQLDelight
     * has a migration path for (S2). Returns the refusal otherwise.
     */
    private fun inspectExistingFile(file: File): DatabaseState.Unloadable? {
        if (!file.exists() || file.length() == 0L) return null
        return openRaw(file).use { db ->
            val present = db.schema()
            // A SQLite file holding no schema at all is one this app started creating and did not
            // finish — `Schema.create` runs in a transaction and a failure rolls it back, leaving
            // the file (and the framework's own `android_metadata`) behind. Letting the driver
            // have another go is right. Calling it `NotRepertosaurus` would be technically true and
            // practically awful: it would strand the user on the recovery screen over an empty
            // file with `Start fresh` the only way out. Import has no such file to rescue and
            // keeps the strict answer.
            if (present.isEmpty()) return@use null
            when (val verdict = SchemaCompatibility.check(present, db.userVersion())) {
                SchemaVerdict.Current -> {
                    stampVersion(db)
                    null
                }
                // S2: a database already on the device upgrades in place rather than being
                // rejected. SQLDelight runs 1.sqm when the driver opens it.
                is SchemaVerdict.Upgradable -> null
                else -> refuseAndRemember(file, verdict, SchemaCompatibility.explain(verdict))
            }
        }
    }

    /**
     * Open a database file directly, **without the framework's default corruption handling**.
     *
     * `SQLiteDatabase.openDatabase` with no error handler installs `DefaultDatabaseErrorHandler`,
     * whose `onCorruption` **deletes the file**. On this app that means destroying the user's only
     * copy of their repertoire, silently, as the first thing the boot gate does to a database it
     * was opened to protect (S8, S11). [KEEP_CORRUPT_FILE] leaves it on disk, the open fails, and
     * the failure becomes a recovery screen with the file still behind it.
     */
    private fun openRaw(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(
        file.path,
        null,
        SQLiteDatabase.OPEN_READWRITE,
        KEEP_CORRUPT_FILE,
    )

    /** Builds the refusal **and caches it**; `retry()` is what clears the cache. */
    private fun refuseAndRemember(
        file: File,
        verdict: SchemaVerdict?,
        reason: String,
    ): DatabaseState.Unloadable = DatabaseState.Unloadable(
        verdict = verdict,
        reason = reason,
        file = file.absolutePath,
    ).also { unloadable = it }

    /** Content decided the file is current, so the stamp is brought into line with it. */
    private fun stampVersion(db: SQLiteDatabase) {
        val expected = SchemaCompatibility.VERSION
        if (db.userVersion() != expected) db.execSQL("PRAGMA user_version = $expected")
    }

    private fun SQLiteDatabase.userVersion(): Long = count("PRAGMA user_version")

    /**
     * The tables and columns this file has, in the shape [SchemaCompatibility.check] takes.
     *
     * Only the **row fetch** is here; which tables count, how a name is escaped and which column
     * carries it all stay in [SchemaCompatibility.inspect]. The framework cursor is used rather
     * than a SQLDelight driver because the whole point is to look at the file *before* a driver
     * exists to run migrations on it — and a second copy of the judgement, rather than a second
     * copy of the plumbing, is precisely the shape of the bug this class now guards against.
     */
    private fun SQLiteDatabase.schema(): Map<String, Set<String>> =
        SchemaCompatibility.inspect { sql, column ->
            rawQuery(sql, null).use { cursor ->
                buildList { while (cursor.moveToNext()) cursor.getString(column)?.let(::add) }
            }
        }

    private fun SQLiteDatabase.count(sql: String): Long =
        rawQuery(sql, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    private companion object {
        const val STAGING_NAME = "import-staging.db"

        /**
         * The main file (the leading empty suffix — that is deliberate, not a bug) and every
         * journal SQLite may have left beside it.
         */
        val SIDECARS = listOf("", "-journal", "-wal", "-shm")

        /**
         * A corruption handler that keeps the file. See [openRaw]; the framework's default
         * deletes it, and `DatabaseFactory.createDriver` overrides the same behaviour on the
         * driver's own open helper.
         */
        val KEEP_CORRUPT_FILE = DatabaseErrorHandler { db ->
            if (db.isOpen) runCatching { db.close() }
        }

        /** `SQLite format 3\u0000` — the sixteen-byte header every SQLite file starts with. */
        val SQLITE_HEADER: ByteArray = byteArrayOf(
            0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x20, 0x66,
            0x6F, 0x72, 0x6D, 0x61, 0x74, 0x20, 0x33, 0x00,
        )
    }
}

/** What the confirmation dialog states before it overwrites anything. */
public data class ImportPreview(
    val songs: Long,
    val practiceEvents: Long,
    val bytes: Long,
)

/** A picked file that is not a usable Repertosaurus database. Nothing has been overwritten. */
public class ImportRejected(message: String) : Exception(message)
