package dev.repertosaurus.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.repertosaurus.db.RepertosaurusDatabase

/**
 * What a candidate database file is, relative to the build looking at it.
 *
 * Schema-compatibility decision S5: the answer is decided by **the tables and columns this
 * build actually needs**, never by a hardcoded count. The five-table `sqlite_master` count
 * that used to live in `DatabaseHolder` was a phase-1 snapshot, and it silently stopped
 * describing the schema the moment a sixth table mattered: three structurally different files
 * all passed it, all claimed `user_version = 1`, and importing the wrong one killed the app on
 * its next boot.
 */
public sealed interface SchemaVerdict {

    /**
     * Everything this build needs is present. The stored `user_version` may still be behind —
     * a file written by the phase-0 Python import, or by a build from before the version moved
     * — and the caller stamps it forward. Content is the authority, not the stamp: that is the
     * whole lesson of the crash this type exists to prevent.
     */
    public data object Current : SchemaVerdict

    /**
     * Structurally older, but the stored `user_version` names a version SQLDelight has a
     * migration path from, so opening it runs `Schema.migrate` and brings it forward in place
     * (S2).
     *
     * [missing] is what the migration is expected to supply, and is quoted to the user when the
     * caller refuses rather than migrates.
     */
    public data class Upgradable(
        val fromVersion: Long,
        val missing: List<String>,
    ) : SchemaVerdict

    /**
     * Structurally older with no migration path — the stamp says nothing usable (`0`, from a
     * file built outside the app) while the content is short of what this build needs. Opening
     * it would make Android run `CREATE TABLE` over tables that already exist.
     */
    public data class TooOld(
        val userVersion: Long,
        val missing: List<String>,
    ) : SchemaVerdict

    /** Written by a newer build. Nothing here can read it, and guessing would corrupt it. */
    public data class TooNew(val userVersion: Long) : SchemaVerdict

    /** A SQLite file, but not this application's. */
    public data object NotRepertosaurus : SchemaVerdict
}

/**
 * The one implementation of "can this build load that database" (S6).
 *
 * The import path and the boot gate both call [check] and neither has its own opinion. The bug
 * this replaces existed precisely because they disagreed: import counted five tables and said
 * yes, boot ran a query against `saved_view` and died. One function, two callers.
 *
 * [check] itself is pure — tables, columns and `user_version` in, a verdict out — so every
 * branch is testable on the JVM with no file and no device.
 */
public object SchemaCompatibility {

    /** What this build writes into `PRAGMA user_version`, and the ceiling for [SchemaVerdict.TooNew]. */
    public val VERSION: Long
        get() = RepertosaurusDatabase.Schema.version

    /**
     * Every table and column `RepertosaurusDatabase.Schema` creates.
     *
     * S5 asks for the requirement to be derived from the schema where possible and, where it
     * must be a list, for that list to live beside the schema with **its staleness a test
     * failure rather than a runtime crash**. Deriving it at runtime would mean building a
     * throwaway database on every launch, so it is a list — and `SchemaCompatibilityTest`
     * creates a database from `Schema.create` and asserts [inspect] of it equals this map
     * exactly. Add a column to a `.sq` file without adding it here and that test fails.
     *
     * It is the *whole* schema rather than the subset the current queries touch, because a
     * file short of any part of it was written by a different build, and the honest answer to
     * that is a refusal with the difference named — not a guess about which absences happen to
     * be survivable this week.
     *
     * **Schema-3 M2: this map cannot see a `CHECK`, so no change may widen one without also adding
     * something this map can see**, or two structurally different databases share a verdict.
     */
    public val REQUIRED: Map<String, Set<String>> = mapOf(
        "artist" to setOf(
            "id", "name", "sort_name", "updated_at", "deleted_at", "device_id",
        ),
        "artist_alias" to setOf(
            "id", "artist_id", "alias", "updated_at", "deleted_at", "device_id",
        ),
        "band" to setOf(
            "id", "name", "notes", "updated_at", "deleted_at", "device_id",
        ),
        "groove" to setOf(
            "id", "name", "updated_at", "deleted_at", "device_id",
        ),
        "instrument" to setOf(
            "id", "name", "updated_at", "deleted_at", "device_id",
        ),
        "part_rating" to setOf(
            "id", "song_id", "performer_id", "instrument_id", "kind", "level",
            "updated_at", "deleted_at", "device_id",
        ),
        "performer" to setOf(
            "id", "name", "notes", "updated_at", "deleted_at", "device_id",
        ),
        "practice_context" to setOf(
            "id", "name", "updated_at", "deleted_at", "device_id",
        ),
        "practice_event" to setOf(
            "id", "song_id", "logged_on", "instrument_id", "context_id", "feel", "note",
            "duration_seconds", "created_at", "device_id",
        ),
        "practice_event_void" to setOf(
            "id", "practice_event_id", "created_at", "device_id",
        ),
        "saved_view" to setOf(
            "id", "name", "filter_performer_id", "filter_instrument_id", "filter_lead_only",
            "practice_instrument_id", "sort_order", "position", "notes",
            "updated_at", "deleted_at", "device_id",
        ),
        "setlist" to setOf(
            "id", "name", "performed_on", "band_id", "venue_id", "client", "notes",
            "updated_at", "deleted_at", "device_id",
        ),
        "setlist_item" to setOf(
            "id", "setlist_set_id", "song_id", "position", "transpose", "tempo_override",
            "note", "updated_at", "deleted_at", "device_id",
        ),
        "setlist_item_performer" to setOf(
            "id", "setlist_item_id", "performer_id", "position",
            "updated_at", "deleted_at", "device_id",
        ),
        "setlist_set" to setOf(
            "id", "setlist_id", "set_no", "target_minutes", "updated_at", "deleted_at",
            "device_id",
        ),
        "song" to setOf(
            "id", "title", "artist_id", "reference_recording", "key_signature",
            "tonal_centre", "tonality_note", "tempo_bpm", "duration_seconds", "decade",
            "loop_length", "chord_count", "chord_pattern", "groove_id", "mashup_note",
            "notes", "chart_url", "updated_at", "deleted_at", "device_id",
        ),
        "song_instrument" to setOf(
            "id", "song_id", "instrument_id", "difficulty", "patch", "notes",
            "updated_at", "deleted_at", "device_id",
        ),
        "song_performer" to setOf(
            "id", "song_id", "performer_id", "instrument_id", "is_lead", "vocal_range",
            "notes", "updated_at", "deleted_at", "device_id",
        ),
        "song_tag" to setOf(
            "id", "song_id", "tag_id", "updated_at", "deleted_at", "device_id",
        ),
        "suggestion_skip" to setOf(
            "id", "song_id", "performer_id", "instrument_id", "created_at", "device_id",
        ),
        "tag" to setOf(
            "id", "name", "updated_at", "deleted_at", "device_id",
        ),
        "venue" to setOf(
            "id", "name", "updated_at", "deleted_at", "device_id",
        ),
    )

    /**
     * The tables whose absence means the file is somebody else's, not an old one of ours.
     *
     * Deliberately a handful of the oldest, most load-bearing names: the distinction being
     * drawn is "not a Repertosaurus database" versus "a Repertosaurus database from before X", and
     * a file that has `song`, `artist`, `instrument` and `practice_event` is ours whatever else
     * it is short of.
     */
    public val IDENTIFYING: Set<String> = setOf("song", "artist", "instrument", "practice_event")

    /**
     * What [present] lacks against [REQUIRED], as `table` for a missing table and
     * `table.column` for a missing column, in a stable order so the message a user reads is
     * the same every time.
     */
    public fun missingFrom(present: Map<String, Set<String>>): List<String> = buildList {
        for ((table, columns) in REQUIRED) {
            val found = present[table]
            if (found == null) {
                add(table)
            } else {
                for (column in columns) if (column !in found) add("$table.$column")
            }
        }
    }

    /**
     * The verdict, from the tables and columns a candidate database actually has and the
     * `user_version` it carries.
     *
     * **Content outranks the stamp.** A file whose content satisfies this build is [Current]
     * even when it claims `user_version = 1`, because that is exactly the state the phase-0
     * Python import and every build from before the version moved leave behind, and because
     * handing such a file to SQLDelight as "version 1" would run the 1 → 2 migration over
     * tables that are already there.
     *
     * The stamp is only consulted for the two questions content cannot answer: whether the file
     * came from the future, and whether there is a migration path out of the past.
     */
    public fun check(present: Map<String, Set<String>>, userVersion: Long): SchemaVerdict {
        if (!present.keys.containsAll(IDENTIFYING)) return SchemaVerdict.NotRepertosaurus
        if (userVersion > VERSION) return SchemaVerdict.TooNew(userVersion)
        val missing = missingFrom(present)
        return when {
            missing.isEmpty() -> SchemaVerdict.Current
            userVersion in 1 until VERSION -> SchemaVerdict.Upgradable(userVersion, missing)
            else -> SchemaVerdict.TooOld(userVersion, missing)
        }
    }

    /** `sqlite_master` names every table; the internal ones are not part of any schema. */
    public const val TABLE_NAMES: String =
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"

    /**
     * `PRAGMA table_info` for [table]. Column 1 of each row is the column name; a table name
     * cannot be bound as a parameter to a PRAGMA, so it is quoted rather than interpolated bare.
     */
    public fun columnsOf(table: String): String =
        "PRAGMA table_info(\"${table.replace("\"", "\"\"")}\")"

    /** The column index [columnsOf]'s rows carry the column name in. */
    public const val COLUMN_NAME_INDEX: Int = 1

    /**
     * Android's own bookkeeping table, created by the framework in any database it opens. It is
     * in no `.sq` file, it is present in an app-created database and absent from one the Python
     * import wrote, and it is therefore not evidence either way about what a file is.
     */
    public val PLATFORM_TABLES: Set<String> = setOf("android_metadata")

    /**
     * The tables and columns an open database actually has, read through [readColumn].
     *
     * **One reader, however the rows are fetched.** The boot gate has to look at a *closed* file
     * before a driver exists — a driver would run `Schema.create` or `Schema.migrate` as a side
     * effect of opening, which is the thing being decided — so it reads through the platform
     * cursor while everything else reads through a [SqlDriver]. Only the row fetch differs, and
     * only the row fetch is passed in: what counts as a table, how a table name is escaped and
     * which column carries the name are stated once, here. Two copies of that judgement is the
     * shape of the bug this whole file exists to fix.
     *
     * @param readColumn runs a query and returns one column of every row, dropping nulls.
     */
    public fun inspect(readColumn: (sql: String, column: Int) -> List<String>): Map<String, Set<String>> =
        readColumn(TABLE_NAMES, 0)
            .filterNot { it in PLATFORM_TABLES }
            .associateWith { table -> readColumn(columnsOf(table), COLUMN_NAME_INDEX).toSet() }

    /** [inspect] over an open SQLDelight driver. */
    public fun inspect(driver: SqlDriver): Map<String, Set<String>> =
        inspect { sql, column -> driver.strings(sql, column) }

    /**
     * The sentence the user reads. It lives here so the import refusal and the recovery screen
     * say the same thing about the same file (S6), and so every branch names **what is wrong
     * and what to do** — a refusal the user cannot act on is barely better than the crash.
     */
    public fun explain(verdict: SchemaVerdict): String = when (verdict) {
        SchemaVerdict.Current -> "That database matches this build."
        is SchemaVerdict.Upgradable ->
            "That database was written before " + summarise(verdict.missing) +
                " and is missing " + list(verdict.missing) + ". " + REEXPORT
        is SchemaVerdict.TooOld ->
            explainMissing(verdict.missing) + " " + REEXPORT
        is SchemaVerdict.TooNew ->
            "That database was written by a newer build of Repertosaurus (schema " +
                "${verdict.userVersion}, this build reads $VERSION). Update the app first."
        SchemaVerdict.NotRepertosaurus ->
            "That is a SQLite file, but not a Repertosaurus one."
    }

    /**
     * The sentence for a database that opened and then turned out to be short of something.
     *
     * Here rather than at the call site so that every phrasing of "this file is missing X" in the
     * app comes out of this object (S6).
     */
    public fun explainMissing(missing: List<String>): String =
        "That database is missing " + list(missing) + ", which this build needs."

    /**
     * The feature a missing table belongs to, so the message names something the user recognises
     * rather than only a table name.
     *
     * A lookup rather than an `if`, because the next migration adds the next entry here and a
     * hardcoded single case would silently stop naming anything.
     */
    private val FEATURES: Map<String, String> = mapOf("saved_view" to "Views")

    private fun summarise(missing: List<String>): String =
        missing.firstNotNullOfOrNull { entry -> FEATURES[entry.substringBefore('.')] }
            ?: "this build"

    private fun list(missing: List<String>): String {
        val shown = missing.take(MAX_LISTED)
        val rest = missing.size - shown.size
        return shown.joinToString(", ") + if (rest > 0) " and $rest more" else ""
    }

    private const val MAX_LISTED = 4

    /**
     * What to do about a database this build cannot read. Phase 1 has one answer and one place
     * fresh databases come from; when the desktop target ships and can itself be the thing
     * showing this message, this sentence needs to become platform-aware.
     */
    private const val REEXPORT =
        "Export a fresh copy from the desktop import and pick that instead."

    private fun SqlDriver.strings(sql: String, column: Int = 0): List<String> =
        executeQuery(
            identifier = null,
            sql = sql,
            parameters = 0,
            mapper = { cursor ->
                val values = mutableListOf<String>()
                while (cursor.next().value) {
                    cursor.getString(column)?.let(values::add)
                }
                QueryResult.Value(values.toList())
            },
        ).value
}
