package dev.repertosaurus.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/** The JVM tests' raw reads (style review F15 B8): every row of [sql], its first [columns] as text. */
internal fun SqlDriver.rows(sql: String, columns: Int): List<List<String?>> =
    executeQuery(
        null,
        sql,
        { cursor ->
            val values = mutableListOf<List<String?>>()
            while (cursor.next().value) values += (0 until columns).map { cursor.getString(it) }
            QueryResult.Value(values.toList())
        },
        0,
    ).value

/**
 * A `part_rating` row with a `kind` this build does not know — as a newer build might write —
 * inserted with the CHECK bypassed, for the decoder's skip-never-throw policy (S8).
 */
internal fun SqlDriver.insertRatingOfUnknownKind(songId: String, performerId: String, instrumentId: String) {
    execute(null, "PRAGMA ignore_check_constraints = ON", 0)
    execute(
        null,
        "INSERT INTO part_rating(id, song_id, performer_id, instrument_id, kind, level, updated_at, " +
            "deleted_at, device_id) VALUES ('future-kind-$songId', '$songId', '$performerId', " +
            "'$instrumentId', 'URGENCY', 2, '2026-09-01T00:00:00.000Z', NULL, 'newer-build')",
        0,
    )
    execute(null, "PRAGMA ignore_check_constraints = OFF", 0)
    check(long("SELECT count(*) FROM part_rating WHERE kind = 'URGENCY' AND song_id = '$songId'") == 1L)
}

/** The first column of the first row of [sql], as a number; a query with no row is an error. */
internal fun SqlDriver.long(sql: String): Long =
    executeQuery(null, sql, { QueryResult.Value(if (it.next().value) it.getLong(0) else null) }, 0)
        .value ?: error("no row for $sql")
