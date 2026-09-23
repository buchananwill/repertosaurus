package dev.repertosaurus.data

import app.cash.sqldelight.db.SqlDriver

/**
 * Replay a checked-in SQL dump from the test classpath (style review F15 B8). Statements are
 * separated by a line holding only `--;`, and comment lines are dropped. `\r\n` is normalised
 * first, so a checkout that converts line endings still splits.
 */
internal fun SqlDriver.replay(resource: String) {
    val dump = checkNotNull(SqlDumps::class.java.classLoader?.getResourceAsStream(resource)) {
        "$resource is not on the test classpath"
    }.bufferedReader().use { it.readText() }.replace("\r\n", "\n")
    dump.split("\n--;\n")
        .map { chunk -> chunk.lines().filterNot { it.startsWith("--") }.joinToString("\n").trim() }
        .filter { it.isNotEmpty() }
        .map { it.removeSuffix(";") }
        .forEach { statement -> execute(null, statement, 0) }
}

/** Anchors [replay]'s class loader. */
private object SqlDumps
